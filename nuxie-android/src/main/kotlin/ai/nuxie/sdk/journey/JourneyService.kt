package ai.nuxie.sdk.journey

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.JourneyUpdate
import ai.nuxie.sdk.SuppressReason
import ai.nuxie.sdk.TriggerError
import ai.nuxie.sdk.TriggerErrorCode
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.events.TriggerService
import ai.nuxie.sdk.events.TriggerBroker
import ai.nuxie.sdk.TriggerUpdate
import ai.nuxie.sdk.util.IsoDates
import ai.nuxie.sdk.presentation.CloseReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val triggerBroker: TriggerBroker = TriggerBroker(),
) : TriggerService.JourneyRouter, JourneyDownFactRouter {
    private val admissions = mutableSetOf<AdmissionKey>()
    private val restoredRunIds = mutableSetOf<String>()
    private val hostDismissalReservations = mutableSetOf<HostDismissalKey>()
    // E1 has few concurrent run operations; one lock keeps each run's
    // load-mutate-save sequence coherent until per-run locking is warranted.
    private val runLock = Mutex()

    init {
        // Load the current user's persisted live runs on startup. Direct
        // store lookups below keep cross-user state scoped even after identity
        // transitions; action restoration belongs to the execution slice.
        initialDistinctId?.let { distinctId ->
            restoredRunIds += store.loadActive(distinctId).map(JourneyRun::id)
        }
    }

    override suspend fun handleEventForTrigger(event: StoredEvent): List<TriggerService.JourneyTriggerResult> {
        val results = mutableListOf<TriggerService.JourneyTriggerResult>()
        for (release in releases.releasesFor(event.distinctId, event.name)) {
            results += enroll(event, release)
        }
        return results
    }

    /** Resolves the typed forwarding view without changing the persisted event contract. */
    internal fun forwardingExperienceRef(distinctId: String, journeyId: String): ExperienceRef? =
        store.load(distinctId, journeyId)?.let { run ->
            ExperienceRef(run.experienceId, run.experienceVersion, run.id)
        }

    suspend fun transition(
        distinctId: String,
        journeyId: String,
        fromNode: String?,
        toNode: String,
        region: String = "device-main",
    ) {
        runLock.withLock {
            val run = store.load(distinctId, journeyId) ?: return
            if (run.state == JourneyRunState.ACTIVE && !run.isGhost) ledger.transition(run, fromNode, toNode, region)
        }
    }

    suspend fun milestone(distinctId: String, journeyId: String, milestoneId: String) {
        runLock.withLock {
            val run = store.load(distinctId, journeyId) ?: return
            if (run.state == JourneyRunState.ACTIVE && !run.isGhost) ledger.milestone(run, milestoneId)
        }
    }

    suspend fun requestEffect(
        distinctId: String,
        journeyId: String,
        nodeId: String,
        attempt: Long,
        effect: String,
        payload: JsonObject,
    ): String? {
        return runLock.withLock {
            val run = store.load(distinctId, journeyId) ?: return null
            if (run.state == JourneyRunState.ACTIVE && !run.isGhost) {
                ledger.effectRequested(run, nodeId, attempt, effect, payload)
            } else {
                null
            }
        }
    }

    suspend fun exit(distinctId: String, journeyId: String, reason: String) {
        runLock.withLock {
            val run = store.load(distinctId, journeyId) ?: return
            if (run.state != JourneyRunState.ACTIVE ||
                HostDismissalKey(distinctId, journeyId) in hostDismissalReservations
            ) return
            val terminal = run.copy(state = JourneyRunState.TERMINAL, terminalReason = reason)
            store.save(terminal)
            if (!run.isGhost) {
                ledger.exited(run, reason, nowMillis())
                if (reason != "cancelled" && reason != "error") {
                    store.recordCompletion(
                        distinctId,
                        JourneyCompletion(run.experienceId, run.id, nowMillis()),
                    )
                }
            }
        }
    }

    /** Receives the presentation-scoped close outcome for a linked Journey. */
    suspend fun presentationEnded(distinctId: String, journeyId: String, reason: CloseReason): Boolean {
        if (reason == CloseReason.HostDismissed) {
            return hostDismiss(distinctId, journeyId)
        }
        if (reason == CloseReason.UserDismissed) {
            return userDismiss(distinctId, journeyId)
        }
        val exitReason = when (reason) {
            CloseReason.UserDismissed -> error("handled above")
            CloseReason.HostDismissed -> error("handled above")
            CloseReason.IdentityChanged -> return true
            CloseReason.GoalMet -> "goal_met"
            CloseReason.PurchaseCompleted -> "completed"
            CloseReason.Timeout -> "completed"
            is CloseReason.Error -> "error"
        }
        exit(distinctId, journeyId, exitReason)
        return true
    }

    /**
     * Reserves a live Journey before host dismissal may terminalize it. A call
     * admitted under the old customer may then finish even if identity changes,
     * while a call made after identity changed cannot terminalize or tear down
     * the old customer's presentation.
     */
    suspend fun reserveHostDismissal(
        ownerDistinctId: String,
        journeyId: String,
        initiatingDistinctId: String,
    ): Boolean = runLock.withLock {
        if (ownerDistinctId != initiatingDistinctId) return@withLock false
        val run = withContext(Dispatchers.IO) { store.load(ownerDistinctId, journeyId) }
            ?: return@withLock false
        if (run.state != JourneyRunState.ACTIVE) return@withLock false
        hostDismissalReservations += HostDismissalKey(ownerDistinctId, journeyId)
        true
    }

    suspend fun releaseHostDismissalReservation(ownerDistinctId: String, journeyId: String) {
        runLock.withLock {
            hostDismissalReservations -= HostDismissalKey(ownerDistinctId, journeyId)
        }
    }

    private suspend fun userDismiss(distinctId: String, journeyId: String): Boolean = runLock.withLock {
        val run = withContext(Dispatchers.IO) { store.load(distinctId, journeyId) }
            ?: return@withLock true
        if (run.state != JourneyRunState.ACTIVE ||
            HostDismissalKey(distinctId, journeyId) in hostDismissalReservations
        ) return@withLock false
        val completedAtMillis = nowMillis()
        val terminal = run.copy(
            state = JourneyRunState.TERMINAL,
            terminalReason = "dismissed",
            completedAtMillis = completedAtMillis,
        )
        withContext(Dispatchers.IO) { store.save(terminal) }
        if (run.isGhost) return@withLock true
        ledger.userExited(run, completedAtMillis)
        withContext(Dispatchers.IO) {
            store.recordCompletion(
                distinctId,
                JourneyCompletion(run.experienceId, run.id, completedAtMillis),
            )
        }
        emitDismissedTrigger(run)
        true
    }

    private suspend fun hostDismiss(distinctId: String, journeyId: String): Boolean = runLock.withLock {
        val run = withContext(Dispatchers.IO) { store.load(distinctId, journeyId) }
            ?: return@withLock true
        var terminal = when {
            run.state == JourneyRunState.ACTIVE &&
                HostDismissalKey(distinctId, journeyId) in hostDismissalReservations -> run.copy(
                state = JourneyRunState.TERMINAL,
                terminalReason = "dismissed",
                completedAtMillis = nowMillis(),
                pendingHostExitCapture = true,
                pendingHostCompletion = true,
                pendingHostTriggerCompletion = true,
            )
            run.state == JourneyRunState.TERMINAL &&
                run.terminalReason == "dismissed" &&
                (run.pendingHostExitCapture ||
                    run.pendingHostCompletion ||
                    run.pendingHostTriggerCompletion) -> run
            else -> return@withLock false
        }
        withContext(Dispatchers.IO) { store.save(terminal) }
        hostDismissalReservations -= HostDismissalKey(distinctId, journeyId)
        val completedAtMillis = terminal.completedAtMillis ?: return@withLock true
        if (terminal.pendingHostExitCapture) {
            val captured = runCatching {
                ledger.hostExited(terminal, completedAtMillis)
            }.getOrDefault(false)
            if (captured) {
                val advanced = terminal.copy(pendingHostExitCapture = false)
                if (runCatching { withContext(Dispatchers.IO) { store.save(advanced) } }.isSuccess) {
                    terminal = advanced
                }
            }
        }
        if (terminal.pendingHostCompletion) {
            val recorded = runCatching {
                withContext(Dispatchers.IO) {
                    store.recordCompletion(
                        distinctId,
                        JourneyCompletion(terminal.experienceId, terminal.id, completedAtMillis),
                    )
                }
            }.isSuccess
            if (recorded) {
                val advanced = terminal.copy(pendingHostCompletion = false)
                if (runCatching { withContext(Dispatchers.IO) { store.save(advanced) } }.isSuccess) {
                    terminal = advanced
                }
            }
        }
        if (terminal.pendingHostTriggerCompletion) {
            if (runCatching { emitDismissedTrigger(terminal) }.isSuccess) {
                val advanced = terminal.copy(pendingHostTriggerCompletion = false)
                if (runCatching { withContext(Dispatchers.IO) { store.save(advanced) } }.isSuccess) {
                    terminal = advanced
                }
            }
        }
        if (!terminal.pendingHostExitCapture &&
            !terminal.pendingHostCompletion &&
            !terminal.pendingHostTriggerCompletion
        ) {
            withContext(Dispatchers.IO) { store.delete(terminal) }
        }
        true
    }

    suspend fun recoverPendingHostDismissals() {
        val pending = withContext(Dispatchers.IO) { store.loadPendingHostDismissals() }
        pending.forEach { run -> hostDismiss(run.distinctId, run.id) }
    }

    private suspend fun emitDismissedTrigger(run: JourneyRun) {
        run.triggerRef?.let { triggerRef ->
            triggerBroker.emit(
                triggerRef,
                TriggerUpdate.Journey(
                    JourneyUpdate(
                        ref = ExperienceRef(run.experienceId, run.experienceVersion, run.id),
                        exitReason = JourneyExitReason.DISMISSED,
                        goalMet = run.convertedAtMillis != null,
                    ),
                ),
            )
        }
    }

    override suspend fun applyDownFacts(body: JsonObject, distinctId: String) {
        val facts = body["facts"] as? JsonArray ?: return
        val receivedAtMillis = nowMillis()
        facts.forEach { element ->
            val fact = element as? JsonObject ?: return@forEach
            val id = fact.string("id") ?: return@forEach
            val name = fact.string("event") ?: return@forEach
            val properties = fact["properties"] as? JsonObject ?: return@forEach
            if (name !in DOWN_FACT_NAMES) return@forEach
            // A conversion payload carries its authoritative conversion time;
            // other server facts use their envelope time. Both accept epoch
            // millis or ISO-8601, and only fall back to local receipt time.
            val timestamp = if (name == JourneyEventNames.CONVERTED) {
                properties.long("at") ?: properties.string("at")?.let(IsoDates::parseMillis)
            } else {
                null
            } ?: fact.long("timestamp")
                ?: fact.string("timestamp")?.let(IsoDates::parseMillis)
                ?: nowMillis()
            val serverProperties = buildJsonObject {
                properties.forEach { (key, value) -> put(key, value) }
                put("\$server_fact_id", JsonPrimitive(id))
                put("\$nuxie_event_origin", JsonPrimitive("server"))
            }
            val event = StoredEvent(id, name, serverProperties, timestamp, distinctId)
            if (name == JourneyEventNames.SUPERSEDED || name == JourneyEventNames.CONVERTED) {
                runLock.withLock {
                    if (ledger.serverFact(event, receivedAtMillis)) {
                        routeDownFact(distinctId, name, serverProperties)
                    }
                }
            } else if (ledger.serverFact(event, receivedAtMillis)) {
                routeDownFact(distinctId, name, serverProperties)
            }
        }
    }

    private suspend fun enroll(event: StoredEvent, release: AdmittedJourneyRelease): TriggerService.JourneyTriggerResult {
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
                triggerRef = event.id,
            )
            // The enrollment fact is a synchronous durability boundary. Do
            // not admit or persist a run if committing it failed.
            if (ledger.enrolled(run, event.id) == null) {
                return TriggerService.JourneyTriggerResult.Failed(
                    TriggerError(TriggerErrorCode.TRIGGER_FAILED, "Journey enrollment capture failed"),
                )
            }
            // Persist after the synchronous enrollment fact so a crash cannot
            // leave server admission without its local run snapshot.
            runLock.withLock { store.save(run) }
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
        when (name) {
            JourneyEventNames.SUPERSEDED -> {
                val journeyId = properties.string("journey_id") ?: return
                val run = store.load(distinctId, journeyId) ?: return
                // iOS parity: only a live run enters ghost play-out. A
                // supersede arriving after the run already ended terminally
                // is a late fact and a no-op; the server reconciles the
                // already-committed exit on its side.
                if (run.state == JourneyRunState.ACTIVE && !run.isGhost) {
                    store.save(run.copy(isGhost = true))
                }
            }
            JourneyEventNames.CONVERTED -> {
                val journeyId = properties.string("journey_id") ?: return
                val convertedAt = properties.long("at") ?: properties.string("at")?.let(IsoDates::parseMillis) ?: return
                val run = store.load(distinctId, journeyId) ?: return
                if (run.convertedAtMillis == null || convertedAt < run.convertedAtMillis) {
                    store.save(run.copy(convertedAtMillis = convertedAt))
                }
            }
            JourneyEventNames.EFFECT_COMPLETED -> {
                // Effect-completed routing belongs to the deferred execution slice.
            }
        }
    }

    private fun JsonObject.withAnchor(now: Long): JsonObject = buildJsonObject {
        this@withAnchor.forEach { (key, value) -> if (key != "goal_window_ms") put(key, value) }
        put("conversion_anchor_at", JsonPrimitive(now))
        val window = (this@withAnchor["goal_window_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
        put("goal_window_ends_at", window?.let { JsonPrimitive(now + it) } ?: kotlinx.serialization.json.JsonNull)
    }

    private data class AdmissionKey(val distinctId: String, val experienceId: String)
    private data class HostDismissalKey(val distinctId: String, val journeyId: String)

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
