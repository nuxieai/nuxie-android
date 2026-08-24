package ai.nuxie.sdk.journey

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.SuppressReason
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.events.TriggerService
import ai.nuxie.sdk.util.IsoDates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Optional companion seam for taking server down-facts from response bodies. */
internal interface JourneyDownFactRouter {
    suspend fun applyDownFacts(body: JsonObject, distinctId: String)
}

/** Device-owned enrollment, run persistence, fact emission, and ghost suppression. */
internal class JourneyService(
    private val store: JourneyStore,
    private val ledger: JourneyLedger,
    private val releases: JourneyReleaseProvider,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ids: TimeBasedEpochGenerator = TimeBasedEpochGenerator.shared,
    initialDistinctId: String? = null,
) : TriggerService.JourneyRouter, JourneyDownFactRouter {
    private val admissions = mutableSetOf<AdmissionKey>()
    private val restoredRunIds = mutableSetOf<String>()

    init {
        // Load the current user's persisted live runs on startup. Direct
        // store lookups below keep cross-user state scoped even after identity
        // transitions; action restoration belongs to the execution slice.
        initialDistinctId?.let { distinctId ->
            restoredRunIds += store.loadActive(distinctId).map(JourneyRun::id)
        }
    }

    override suspend fun handleEventForTrigger(event: StoredEvent): List<TriggerService.JourneyTriggerResult> =
        releases.releasesFor(event.name).map { release -> enroll(event, release) }

    fun transition(distinctId: String, journeyId: String, fromNode: String?, toNode: String, region: String = "device-main") {
        val run = store.load(distinctId, journeyId) ?: return
        if (run.state == JourneyRunState.ACTIVE && !run.isGhost) ledger.transition(run, fromNode, toNode, region)
    }

    fun milestone(distinctId: String, journeyId: String, milestoneId: String) {
        val run = store.load(distinctId, journeyId) ?: return
        if (run.state == JourneyRunState.ACTIVE && !run.isGhost) ledger.milestone(run, milestoneId)
    }

    fun requestEffect(distinctId: String, journeyId: String, nodeId: String, attempt: Long, effect: String, payload: JsonObject): String? {
        val run = store.load(distinctId, journeyId) ?: return null
        return if (run.state == JourneyRunState.ACTIVE && !run.isGhost) {
            ledger.effectRequested(run, nodeId, attempt, effect, payload)
        } else {
            null
        }
    }

    fun exit(distinctId: String, journeyId: String, reason: String) {
        val run = store.load(distinctId, journeyId) ?: return
        if (run.state != JourneyRunState.ACTIVE) return
        val terminal = run.copy(state = JourneyRunState.TERMINAL, terminalReason = reason)
        store.save(terminal)
        if (!run.isGhost) {
            ledger.exited(run, reason, nowMillis())
            if (reason != "cancelled" && reason != "error") {
                store.recordCompletion(distinctId, JourneyCompletion(run.experienceId, nowMillis()))
            }
        }
    }

    override suspend fun applyDownFacts(body: JsonObject, distinctId: String) {
        val facts = body["facts"] as? JsonArray ?: return
        facts.forEach { element ->
            val fact = element as? JsonObject ?: return@forEach
            val id = fact.string("id") ?: return@forEach
            val name = fact.string("event") ?: return@forEach
            val properties = fact["properties"] as? JsonObject ?: return@forEach
            if (name !in DOWN_FACT_NAMES || store.hasDownFact(distinctId, id)) return@forEach
            // Server facts carry their server-authored time, as epoch millis
            // or ISO-8601; substituting receipt time would skew the ledger.
            val timestamp = fact.long("timestamp")
                ?: fact.string("timestamp")?.let(IsoDates::parseMillis)
                ?: nowMillis()
            val event = StoredEvent(id, name, properties, timestamp, distinctId)
            if (ledger.serverFact(event)) {
                store.recordDownFactIfNew(distinctId, id)
                routeDownFact(distinctId, name, properties)
            }
        }
    }

    private fun enroll(event: StoredEvent, release: AdmittedJourneyRelease): TriggerService.JourneyTriggerResult {
        val key = AdmissionKey(event.distinctId, release.experienceId)
        synchronized(admissions) {
            if (!admissions.add(key)) return TriggerService.JourneyTriggerResult.Suppressed(SuppressReason.ALREADY_ACTIVE)
        }
        try {
            if (store.loadActive(event.distinctId).any { it.experienceId == release.experienceId }) {
                return TriggerService.JourneyTriggerResult.Suppressed(SuppressReason.ALREADY_ACTIVE)
            }
            if (isReentryLimited(event.distinctId, release)) {
                return TriggerService.JourneyTriggerResult.Suppressed(SuppressReason.REENTRY_LIMITED)
            }
            val now = nowMillis()
            val run = JourneyRun(
                id = ids.next(),
                distinctId = event.distinctId,
                experienceId = release.experienceId,
                experienceVersion = release.experienceVersion,
                epoch = 0,
                plane = JourneyPlane.DEVICE,
                settingsSnapshot = release.settingsTemplate.withAnchor(now),
                state = JourneyRunState.ACTIVE,
            )
            // Persist before emitting so restart recovery has a run for every
            // locally visible enrollment fact.
            store.save(run)
            ledger.enrolled(run, event.id)
            return TriggerService.JourneyTriggerResult.Started(
                ExperienceRef(run.experienceId, run.experienceVersion, run.id),
            )
        } finally {
            synchronized(admissions) { admissions.remove(key) }
        }
    }

    private fun isReentryLimited(distinctId: String, release: AdmittedJourneyRelease): Boolean = when (val policy = release.reentry) {
        JourneyReentry.EveryTime -> false
        JourneyReentry.OneTime -> store.hasCompleted(distinctId, release.experienceId)
        is JourneyReentry.OncePerWindow -> store.lastCompletionAtMillis(distinctId, release.experienceId)
            ?.let { nowMillis() - it < policy.windowMillis } ?: false
    }

    private fun routeDownFact(distinctId: String, name: String, properties: JsonObject) {
        if (name != JourneyEventNames.SUPERSEDED) return
        val journeyId = properties.string("journey_id") ?: return
        val run = store.load(distinctId, journeyId) ?: return
        if (!run.isGhost) store.save(run.copy(isGhost = true))
    }

    private fun JsonObject.withAnchor(now: Long): JsonObject = buildJsonObject {
        this@withAnchor.forEach { (key, value) -> if (key != "goal_window_ms") put(key, value) }
        put("conversion_anchor_at", JsonPrimitive(now))
        val window = (this@withAnchor["goal_window_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
        put("goal_window_ends_at", window?.let { JsonPrimitive(now + it) } ?: kotlinx.serialization.json.JsonNull)
    }

    private data class AdmissionKey(val distinctId: String, val experienceId: String)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private companion object {
        val DOWN_FACT_NAMES = setOf(
            JourneyEventNames.CONVERTED,
            JourneyEventNames.EFFECT_COMPLETED,
            JourneyEventNames.SUPERSEDED,
        )
    }
}
