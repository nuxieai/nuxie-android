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
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val hostDismissRetrySleep: suspend (Long) -> Unit = { delay(it) },
) : TriggerService.JourneyRouter, JourneyDownFactRouter {
    private val admissions = mutableSetOf<AdmissionKey>()
    private val runs = mutableMapOf<RunKey, JourneyRun>()
    // E1 has few concurrent run operations; one lock keeps each run's
    // load-mutate-save sequence coherent until per-run locking is warranted.
    private val runLock = Mutex()
    private val hostDismissWriteLock = Mutex()

    init {
        // Load the current user's persisted live runs on startup. Direct
        // store lookups below keep cross-user state scoped even after identity
        // transitions; action restoration belongs to the execution slice.
        initialDistinctId?.let { distinctId ->
            store.loadActive(distinctId).forEach(::rememberRun)
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
        loadRun(distinctId, journeyId)?.let { run ->
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
            val run = loadRun(distinctId, journeyId) ?: return
            if (run.state == JourneyRunState.ACTIVE && !run.isGhost) {
                ledger.transition(run, fromNode, toNode, region)
            }
        }
    }

    suspend fun milestone(distinctId: String, journeyId: String, milestoneId: String) {
        runLock.withLock {
            val run = loadRun(distinctId, journeyId) ?: return
            if (run.state == JourneyRunState.ACTIVE && !run.isGhost) {
                ledger.milestone(run, milestoneId)
            }
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
            val run = loadRun(distinctId, journeyId) ?: return null
            if (run.state == JourneyRunState.ACTIVE && !run.isGhost) {
                ledger.effectRequested(run, nodeId, attempt, effect, payload)
            } else {
                null
            }
        }
    }

    suspend fun exit(distinctId: String, journeyId: String, reason: String) {
        runLock.withLock {
            val run = loadRun(distinctId, journeyId) ?: return
            if (run.state != JourneyRunState.ACTIVE) return
            val terminal = run.copy(state = JourneyRunState.TERMINAL, terminalReason = reason)
            store.save(terminal)
            rememberRun(terminal)
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

    /**
     * The sole presentation-outcome arbiter. Every path attempts this same
     * lock-held ACTIVE -> TERMINAL transition; only the winner is recorded.
     */
    suspend fun transitionPresentationOutcome(
        ownerDistinctId: String,
        journeyId: String,
        reason: CloseReason,
        initiatingDistinctId: String? = null,
    ): Boolean = runLock.withLock {
        val run = loadRun(ownerDistinctId, journeyId) ?: return@withLock false
        if (run.state != JourneyRunState.ACTIVE) return@withLock false

        val outcome = reason.toRunPresentationOutcome()
        val completedAtMillis = when (outcome) {
            JourneyRunPresentationOutcome.USER_DISMISSED,
            JourneyRunPresentationOutcome.HOST_DISMISSED,
            JourneyRunPresentationOutcome.GOAL_MET,
            JourneyRunPresentationOutcome.PURCHASE_COMPLETED,
            JourneyRunPresentationOutcome.TIMEOUT,
            -> nowMillis()
            JourneyRunPresentationOutcome.IDENTITY_CHANGED,
            JourneyRunPresentationOutcome.ERROR,
            -> null
        }
        rememberRun(
            run.copy(
                state = JourneyRunState.TERMINAL,
                terminalReason = outcome.terminalReason,
                terminalPresentationOutcome = outcome,
                terminalInitiatingDistinctId = initiatingDistinctId,
                completedAtMillis = completedAtMillis,
                pendingHostExitCapture = outcome == JourneyRunPresentationOutcome.HOST_DISMISSED,
                pendingHostCompletion = outcome == JourneyRunPresentationOutcome.HOST_DISMISSED,
                pendingHostTriggerCompletion = outcome == JourneyRunPresentationOutcome.HOST_DISMISSED,
            ),
        )
        true
    }

    /** Performs bookkeeping only for the outcome already selected on the run. */
    suspend fun completePresentationOutcome(distinctId: String, journeyId: String): Boolean {
        val selected = runLock.withLock {
            inMemoryRun(distinctId, journeyId)?.terminalPresentationOutcome
        } ?: return false
        if (selected == JourneyRunPresentationOutcome.HOST_DISMISSED) {
            hostDismissWithRetry(distinctId, journeyId)
            return true
        }
        runLock.withLock {
            val terminal = inMemoryRun(distinctId, journeyId)
                ?.takeIf { it.state == JourneyRunState.TERMINAL }
                ?: return@withLock
            withContext(Dispatchers.IO) { store.save(terminal) }
            if (selected == JourneyRunPresentationOutcome.IDENTITY_CHANGED || terminal.isGhost) {
                return@withLock
            }
            val completedAtMillis = terminal.completedAtMillis
            if (selected == JourneyRunPresentationOutcome.USER_DISMISSED) {
                if (completedAtMillis == null) return@withLock
                ledger.userExited(terminal, completedAtMillis)
            } else {
                ledger.exited(terminal, requireNotNull(terminal.terminalReason), nowMillis())
            }
            completedAtMillis?.let {
                withContext(Dispatchers.IO) {
                    store.recordCompletion(
                        distinctId,
                        JourneyCompletion(terminal.experienceId, terminal.id, it),
                    )
                }
            }
            if (selected == JourneyRunPresentationOutcome.USER_DISMISSED) {
                emitDismissedTrigger(terminal)
            }
        }
        return true
    }

    private suspend fun hostDismissWithRetry(distinctId: String, journeyId: String): Boolean {
        var retryDelayMillis = INITIAL_HOST_DISMISS_RETRY_DELAY_MILLIS
        while (true) {
            try {
                return hostDismiss(distinctId, journeyId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!hasPendingHostDismissalInMemory(distinctId, journeyId)) throw failure
                hostDismissRetrySleep(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2)
                    .coerceAtMost(MAX_HOST_DISMISS_RETRY_DELAY_MILLIS)
            }
        }
    }

    private suspend fun hostDismiss(distinctId: String, journeyId: String): Boolean =
        hostDismissWriteLock.withLock {
        var terminal = runLock.withLock { inMemoryRun(distinctId, journeyId) }
            ?: return@withLock true
        if (terminal.state != JourneyRunState.TERMINAL ||
            terminal.terminalReason != "dismissed" ||
            (!terminal.pendingHostExitCapture &&
                !terminal.pendingHostCompletion &&
                !terminal.pendingHostTriggerCompletion)
        ) {
            return@withLock false
        }
        withContext(Dispatchers.IO) { store.save(terminal) }
        val completedAtMillis = terminal.completedAtMillis ?: return@withLock true
        if (terminal.pendingHostExitCapture) {
            val captured = runCatching {
                ledger.hostExited(terminal, completedAtMillis)
            }.getOrDefault(false)
            if (captured) {
                val advanced = terminal.copy(pendingHostExitCapture = false)
                if (persistAdvancedHostDismissal(terminal, advanced)) {
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
                if (persistAdvancedHostDismissal(terminal, advanced)) {
                    terminal = advanced
                }
            }
        }
        if (terminal.pendingHostTriggerCompletion) {
            if (runCatching { emitDismissedTrigger(terminal) }.isSuccess) {
                val advanced = terminal.copy(pendingHostTriggerCompletion = false)
                if (persistAdvancedHostDismissal(terminal, advanced)) {
                    terminal = advanced
                }
            }
        }
        if (!terminal.pendingHostExitCapture &&
            !terminal.pendingHostCompletion &&
            !terminal.pendingHostTriggerCompletion
        ) {
            withContext(Dispatchers.IO) { store.delete(terminal) }
            runLock.withLock { forgetRun(terminal) }
        }
        true
    }

    private suspend fun persistAdvancedHostDismissal(
        expected: JourneyRun,
        advanced: JourneyRun,
    ): Boolean {
        if (runCatching { withContext(Dispatchers.IO) { store.save(advanced) } }.isFailure) {
            return false
        }
        runLock.withLock {
            if (inMemoryRun(expected.distinctId, expected.id) == expected) {
                rememberRun(advanced)
            }
        }
        return true
    }

    suspend fun recoverPendingHostDismissals() {
        val pending = withContext(Dispatchers.IO) { store.loadPendingHostDismissals() }
        runLock.withLock { pending.forEach(::rememberPendingRecoveryRun) }
        (inMemoryPendingHostDismissals() + pending)
            .distinctBy { it.distinctId to it.id }
            .forEach { run -> recoverHostDismissal(run.distinctId, run.id) }
    }

    private fun hasPendingHostDismissalInMemory(distinctId: String, journeyId: String): Boolean =
        inMemoryRun(distinctId, journeyId)?.let { run ->
            run.state == JourneyRunState.TERMINAL &&
                run.terminalReason == "dismissed" &&
                (run.pendingHostExitCapture ||
                    run.pendingHostCompletion ||
                    run.pendingHostTriggerCompletion)
        } == true

    private suspend fun recoverHostDismissal(distinctId: String, journeyId: String) {
        try {
            hostDismiss(distinctId, journeyId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(
                LOG_TAG,
                "Pending host-dismissal recovery failed for Journey '$journeyId'; " +
                    "will retry on the next recovery scan.",
                failure,
            )
        }
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
            val runSnapshot = runLock.withLock {
                runsForAdmissionSnapshot(event.distinctId)
            }
            if (isReentryLimited(event.distinctId, release, runSnapshot)) {
                return TriggerService.JourneyTriggerResult.Suppressed(SuppressReason.REENTRY_LIMITED)
            }
            if (runSnapshot.any {
                    it.state == JourneyRunState.ACTIVE &&
                        it.experienceId == release.experienceId
                }
            ) {
                return TriggerService.JourneyTriggerResult.Suppressed(SuppressReason.ALREADY_ACTIVE)
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
            runLock.withLock {
                store.save(run)
                rememberRun(run)
            }
            return TriggerService.JourneyTriggerResult.Started(
                ExperienceRef(run.experienceId, run.experienceVersion, run.id),
            )
        } finally {
            synchronized(admissions) { admissions.remove(key) }
        }
    }

    private fun isReentryLimited(
        distinctId: String,
        release: AdmittedJourneyRelease,
        runSnapshot: List<JourneyRun>,
    ): Boolean = when (val policy = release.reentry) {
        JourneyReentry.EveryTime -> false
        JourneyReentry.OneTime ->
            terminalCompletionTimes(runSnapshot, release.experienceId).isNotEmpty() ||
                pendingHostCompletionTombstones(runSnapshot, release.experienceId).isNotEmpty() ||
                store.hasCompleted(distinctId, release.experienceId)
        is JourneyReentry.OncePerWindow -> {
            val now = nowMillis()
            val pendingCompletionAtMillis = pendingHostCompletionTombstones(
                runSnapshot,
                release.experienceId,
            ).maxOfOrNull { it.completedAtMillis ?: now }
            val lastCompletionAtMillis = listOfNotNull(
                terminalCompletionTimes(runSnapshot, release.experienceId).maxOrNull(),
                pendingCompletionAtMillis,
                store.lastCompletionAtMillis(distinctId, release.experienceId),
            ).maxOrNull()
            lastCompletionAtMillis?.let { now - it < policy.windowMillis } ?: false
        }
    }

    private fun pendingHostCompletionTombstones(
        runSnapshot: List<JourneyRun>,
        experienceId: String,
    ): List<JourneyRun> = runSnapshot.filter { run ->
        run.experienceId == experienceId && run.pendingHostCompletion
    }

    private fun terminalCompletionTimes(
        runSnapshot: List<JourneyRun>,
        experienceId: String,
    ): List<Long> =
        runSnapshot.mapNotNull { run ->
            run.completedAtMillis?.takeIf {
                run.state == JourneyRunState.TERMINAL && run.experienceId == experienceId
            }
        }

    /** Called under [runLock]; both admission gates consume the returned state snapshot. */
    private fun runsForAdmissionSnapshot(distinctId: String): List<JourneyRun> {
        store.loadActive(distinctId).forEach(::rememberRunIfAbsent)
        store.loadPendingHostDismissals(distinctId).forEach(::rememberPendingRecoveryRun)
        return inMemoryRuns(distinctId)
    }

    private fun routeDownFact(distinctId: String, name: String, properties: JsonObject) {
        when (name) {
            JourneyEventNames.SUPERSEDED -> {
                val journeyId = properties.string("journey_id") ?: return
                val run = loadRun(distinctId, journeyId) ?: return
                // iOS parity: only a live run enters ghost play-out. A
                // supersede arriving after the run already ended terminally
                // is a late fact and a no-op; the server reconciles the
                // already-committed exit on its side.
                if (run.state == JourneyRunState.ACTIVE && !run.isGhost) {
                    val ghost = run.copy(isGhost = true)
                    store.save(ghost)
                    rememberRun(ghost)
                }
            }
            JourneyEventNames.CONVERTED -> {
                val journeyId = properties.string("journey_id") ?: return
                val convertedAt = properties.long("at") ?: properties.string("at")?.let(IsoDates::parseMillis) ?: return
                val run = loadRun(distinctId, journeyId) ?: return
                if (run.state == JourneyRunState.ACTIVE &&
                    (run.convertedAtMillis == null || convertedAt < run.convertedAtMillis)
                ) {
                    val converted = run.copy(convertedAtMillis = convertedAt)
                    store.save(converted)
                    rememberRun(converted)
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

    private fun loadRun(distinctId: String, journeyId: String): JourneyRun? {
        val key = RunKey(distinctId, journeyId)
        synchronized(runs) { runs[key]?.let { return it } }
        val durable = store.load(distinctId, journeyId) ?: return null
        return synchronized(runs) { runs[key] ?: durable.also { runs[key] = it } }
    }

    private fun inMemoryRun(distinctId: String, journeyId: String): JourneyRun? =
        synchronized(runs) { runs[RunKey(distinctId, journeyId)] }

    private fun inMemoryRuns(distinctId: String? = null): List<JourneyRun> = synchronized(runs) {
        runs.values.filter { distinctId == null || it.distinctId == distinctId }
    }

    private fun inMemoryPendingHostDismissals(): List<JourneyRun> = inMemoryRuns().filter { run ->
        run.pendingHostExitCapture || run.pendingHostCompletion || run.pendingHostTriggerCompletion
    }

    private fun rememberRun(run: JourneyRun) {
        synchronized(runs) { runs[run.key()] = run }
    }

    private fun rememberRunIfAbsent(run: JourneyRun) {
        synchronized(runs) { if (run.key() !in runs) runs[run.key()] = run }
    }

    private fun rememberPendingRecoveryRun(run: JourneyRun) {
        synchronized(runs) {
            val current = runs[run.key()]
            if (current == null || current.state == JourneyRunState.ACTIVE) runs[run.key()] = run
        }
    }

    private fun forgetRun(run: JourneyRun) {
        synchronized(runs) { if (runs[run.key()] == run) runs.remove(run.key()) }
    }

    private fun JourneyRun.key(): RunKey = RunKey(distinctId, id)

    private fun CloseReason.toRunPresentationOutcome(): JourneyRunPresentationOutcome = when (this) {
        CloseReason.UserDismissed -> JourneyRunPresentationOutcome.USER_DISMISSED
        CloseReason.HostDismissed -> JourneyRunPresentationOutcome.HOST_DISMISSED
        CloseReason.IdentityChanged -> JourneyRunPresentationOutcome.IDENTITY_CHANGED
        CloseReason.GoalMet -> JourneyRunPresentationOutcome.GOAL_MET
        CloseReason.PurchaseCompleted -> JourneyRunPresentationOutcome.PURCHASE_COMPLETED
        CloseReason.Timeout -> JourneyRunPresentationOutcome.TIMEOUT
        is CloseReason.Error -> JourneyRunPresentationOutcome.ERROR
    }

    private val JourneyRunPresentationOutcome.terminalReason: String
        get() = when (this) {
            JourneyRunPresentationOutcome.USER_DISMISSED,
            JourneyRunPresentationOutcome.HOST_DISMISSED,
            -> "dismissed"
            JourneyRunPresentationOutcome.IDENTITY_CHANGED -> "identity_changed"
            JourneyRunPresentationOutcome.GOAL_MET -> "goal_met"
            JourneyRunPresentationOutcome.PURCHASE_COMPLETED,
            JourneyRunPresentationOutcome.TIMEOUT,
            -> "completed"
            JourneyRunPresentationOutcome.ERROR -> "error"
        }

    private data class AdmissionKey(val distinctId: String, val experienceId: String)
    private data class RunKey(val distinctId: String, val journeyId: String)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val INITIAL_HOST_DISMISS_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_HOST_DISMISS_RETRY_DELAY_MILLIS = 60_000L

        val DOWN_FACT_NAMES = setOf(
            JourneyEventNames.CONVERTED,
            JourneyEventNames.EFFECT_COMPLETED,
            JourneyEventNames.SUPERSEDED,
        )
    }
}
