package ai.nuxie.sdk.events

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.FeatureAccessUpdate
import ai.nuxie.sdk.SuppressReason
import ai.nuxie.sdk.TriggerDecision
import ai.nuxie.sdk.TriggerError
import ai.nuxie.sdk.TriggerErrorCode
import ai.nuxie.sdk.TriggerUpdate
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.journey.JourneyDownFactRouter
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The decision lane, ported from the iOS `TriggerService`: capture the
 * trigger event durably, post it synchronously to /event, then route the
 * gate plan and journey admissions into per-call updates with iOS-identical
 * terminal semantics.
 *
 * Journeys, Features, and presentation are injectable seams; their default
 * implementations (no journeys, no feature authority, presentation
 * unavailable) hold until those subsystems land, keeping the terminal
 * semantics honest rather than optimistic.
 */
internal class TriggerService(
    private val eventLog: EventLog,
    private val api: NuxieApi,
    private val broker: TriggerBroker,
    private val journeys: JourneyRouter,
    private val features: FeatureGate,
    private val presenter: ExperiencePresenter,
    private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    internal sealed interface JourneyTriggerResult {
        class Started(val ref: ExperienceRef) : JourneyTriggerResult
        class Suppressed(val reason: SuppressReason) : JourneyTriggerResult
    }

    internal fun interface JourneyRouter {
        suspend fun handleEventForTrigger(event: StoredEvent): List<JourneyTriggerResult>
    }

    internal interface FeatureGate {
        /** Cached access decision; null = no cached knowledge. */
        suspend fun cachedAccess(featureId: String, requiredBalance: Double?, entityId: String?): Boolean?

        /** Cache-first check that may hit the network. */
        suspend fun checkAccess(featureId: String, requiredBalance: Double?, entityId: String?): Boolean
    }

    internal fun interface ExperiencePresenter {
        /** Present the Experience; throws when presentation is unavailable/fails. */
        suspend fun present(experienceVersionId: String): ExperienceRef
    }

    object NoJourneys : JourneyRouter {
        override suspend fun handleEventForTrigger(event: StoredEvent): List<JourneyTriggerResult> = emptyList()
    }

    object NoFeatureAuthority : FeatureGate {
        override suspend fun cachedAccess(featureId: String, requiredBalance: Double?, entityId: String?): Boolean? = null
        override suspend fun checkAccess(featureId: String, requiredBalance: Double?, entityId: String?): Boolean = false
    }

    object PresentationUnavailable : ExperiencePresenter {
        override suspend fun present(experienceVersionId: String): ExperienceRef =
            throw IllegalStateException("Experience presentation is not available yet (runtime host pending)")
    }

    private enum class Mode { IMMEDIATE, EXPERIENCE, REQUIRE_FEATURE }

    suspend fun trigger(
        event: String,
        properties: Map<String, Any?>?,
        handler: (TriggerUpdate) -> Unit,
    ) {
        // 1. Durable capture (persist + announce; delivery pending).
        val stored = eventLog.captureForTrigger(event, properties)
        if (stored == null) {
            handler(TriggerUpdate.Error(TriggerError(TriggerErrorCode.TRIGGER_FAILED, "Event capture failed")))
            return
        }
        val eventId = stored.id

        // 2. Synchronous decision lane. A failed round trip is a trigger
        //    error, but the captured event stays durable for batch delivery.
        val gatePlan = runCatching {
            val responseText = api.postEvent(BatchItemWireEncoder.encode(stored))
            eventLog.markDeliveredViaDecisionLane(eventId)
            val response = Json.parseToJsonElement(responseText).jsonObject
            (journeys as? JourneyDownFactRouter)?.applyDownFacts(response, stored.distinctId)
            GatePlan.fromEventResponse(response)
        }.getOrElse { failure ->
            Log.w(LOG_TAG, "Decision lane request failed", failure)
            handler(
                TriggerUpdate.Error(
                    TriggerError(TriggerErrorCode.TRIGGER_FAILED, failure.message ?: "decision lane failed"),
                ),
            )
            return
        }

        val mode = when (gatePlan?.decision) {
            null -> Mode.EXPERIENCE
            GatePlan.Decision.ALLOW, GatePlan.Decision.DENY -> Mode.IMMEDIATE
            GatePlan.Decision.SHOW_EXPERIENCE -> Mode.EXPERIENCE
            GatePlan.Decision.REQUIRE_FEATURE -> Mode.REQUIRE_FEATURE
        }
        val terminalGateExperienceId = gatePlan
            ?.takeIf { it.decision == GatePlan.Decision.SHOW_EXPERIENCE }
            ?.experienceVersionId
            ?.let { "experience:$it" }

        // 3. Terminal semantics (iOS shouldCompleteUpdate, post-cut names).
        var journeyStarted = false
        broker.register(eventId) { update ->
            handler(update)
            val terminal = when (update) {
                is TriggerUpdate.Error -> true
                is TriggerUpdate.Decision -> when (val decision = update.decision) {
                    is TriggerDecision.AllowedImmediate,
                    is TriggerDecision.DeniedImmediate,
                    is TriggerDecision.NoMatch,
                    -> true
                    is TriggerDecision.Suppressed -> gatePlan == null && !journeyStarted
                    is TriggerDecision.ExperienceShown ->
                        decision.ref.experienceId == terminalGateExperienceId
                    else -> false
                }
                is TriggerUpdate.FeatureAccess -> when (update.update) {
                    is FeatureAccessUpdate.Allowed, is FeatureAccessUpdate.Denied -> true
                    is FeatureAccessUpdate.Pending -> false
                }
                is TriggerUpdate.Journey -> mode == Mode.EXPERIENCE
            }
            if (terminal) broker.complete(eventId)
        }

        // 4. Journey admissions.
        val journeyResults = runCatching { journeys.handleEventForTrigger(stored) }
            .getOrElse { emptyList() }
        journeyStarted = journeyResults.any { it is JourneyTriggerResult.Started }
        var emittedJourneyDecision = false
        journeyResults.forEach { result ->
            when (result) {
                is JourneyTriggerResult.Started ->
                    broker.emit(eventId, TriggerUpdate.Decision(TriggerDecision.JourneyStarted(result.ref)))
                is JourneyTriggerResult.Suppressed ->
                    broker.emit(eventId, TriggerUpdate.Decision(TriggerDecision.Suppressed(result.reason)))
            }
            emittedJourneyDecision = true
        }

        if (gatePlan == null && emittedJourneyDecision) return
        if (journeyStarted && mode == Mode.EXPERIENCE) return

        if (gatePlan == null) {
            broker.emit(eventId, TriggerUpdate.Decision(TriggerDecision.NoMatch))
            return
        }

        when (gatePlan.decision) {
            GatePlan.Decision.ALLOW ->
                broker.emit(eventId, TriggerUpdate.Decision(TriggerDecision.AllowedImmediate))
            GatePlan.Decision.DENY ->
                broker.emit(eventId, TriggerUpdate.Decision(TriggerDecision.DeniedImmediate))
            GatePlan.Decision.SHOW_EXPERIENCE -> handleShowExperience(gatePlan, eventId)
            GatePlan.Decision.REQUIRE_FEATURE -> handleRequireFeature(gatePlan, eventId)
        }
    }

    private suspend fun handleShowExperience(plan: GatePlan, eventId: String) {
        val experienceVersionId = plan.experienceVersionId
        if (experienceVersionId == null) {
            broker.emit(
                eventId,
                TriggerUpdate.Error(
                    TriggerError(TriggerErrorCode.EXPERIENCE_MISSING, "Missing experience for show decision"),
                ),
            )
            return
        }
        presentExperience(experienceVersionId, eventId)
    }

    private suspend fun handleRequireFeature(plan: GatePlan, eventId: String) {
        val featureId = plan.featureId
        if (featureId == null) {
            broker.emit(
                eventId,
                TriggerUpdate.Error(
                    TriggerError(TriggerErrorCode.FEATURE_MISSING, "Missing featureId for require_feature decision"),
                ),
            )
            return
        }

        if (plan.policy == GatePlan.Policy.CACHE_ONLY) {
            val cached = features.cachedAccess(featureId, plan.requiredBalance, plan.entityId)
            broker.emit(
                eventId,
                TriggerUpdate.FeatureAccess(
                    if (cached == true) FeatureAccessUpdate.Allowed else FeatureAccessUpdate.Denied,
                ),
            )
            return
        }

        val checked = runCatching {
            features.checkAccess(featureId, plan.requiredBalance, plan.entityId)
        }.getOrDefault(false)
        if (checked) {
            broker.emit(eventId, TriggerUpdate.FeatureAccess(FeatureAccessUpdate.Allowed))
            return
        }

        broker.emit(eventId, TriggerUpdate.FeatureAccess(FeatureAccessUpdate.Pending))

        plan.experienceVersionId?.let { presentExperience(it, eventId) }

        val timeoutMillis = (plan.timeoutMs ?: DEFAULT_FEATURE_TIMEOUT_MS).toLong()
        if (waitForFeatureAccess(featureId, plan.requiredBalance, plan.entityId, timeoutMillis)) {
            broker.emit(eventId, TriggerUpdate.FeatureAccess(FeatureAccessUpdate.Allowed))
        } else {
            broker.emit(
                eventId,
                TriggerUpdate.Error(
                    TriggerError(TriggerErrorCode.FEATURE_ACCESS_TIMEOUT, "Timed out waiting for Feature access"),
                ),
            )
        }
    }

    private suspend fun waitForFeatureAccess(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = nowMillis() + timeoutMillis.coerceAtLeast(100)
        while (nowMillis() < deadline) {
            if (features.cachedAccess(featureId, requiredBalance, entityId) == true) return true
            sleepMillis(POLL_INTERVAL_MS)
        }
        return false
    }

    private suspend fun presentExperience(experienceVersionId: String, eventId: String) {
        runCatching { presenter.present(experienceVersionId) }
            .onSuccess { ref ->
                broker.emit(eventId, TriggerUpdate.Decision(TriggerDecision.ExperienceShown(ref)))
            }
            .onFailure { failure ->
                broker.emit(
                    eventId,
                    TriggerUpdate.Error(
                        TriggerError(
                            TriggerErrorCode.EXPERIENCE_PRESENT_FAILED,
                            failure.message ?: "presentation failed",
                        ),
                    ),
                )
            }
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val DEFAULT_FEATURE_TIMEOUT_MS = 30_000
        const val POLL_INTERVAL_MS = 350L
    }
}
