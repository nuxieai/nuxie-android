package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.StableEventCaptureResult
import ai.nuxie.sdk.events.StableEventCommitAdmission
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.experiences.AuthenticatedDeviceLegRelease
import ai.nuxie.sdk.experiences.DeviceLegArtifactManager
import ai.nuxie.sdk.experiences.DeviceLegProfileCatalog
import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import ai.nuxie.sdk.experiences.PreparedDeviceLegArtifacts
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.identity.IdentityScope
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.presentation.DeviceLegPresentationRequest
import ai.nuxie.sdk.presentation.DeviceLegPresentationReservation
import ai.nuxie.sdk.presentation.DeviceLegPresentationResult
import ai.nuxie.sdk.presentation.DeviceLegPresenting
import ai.nuxie.sdk.presentation.DeviceLegScreenEmissionBatch
import ai.nuxie.sdk.presentation.DeviceLegSurfaceOutcome
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal interface DeviceLegProfileConsumer {
    suspend fun profileDidCommit(
        snapshot: DeviceLegProfileCatalog.Snapshot,
        authority: ProfileDeliveryAuthority,
        distinctId: String,
        admissionGeneration: Long,
        artifacts: PreparedDeviceLegArtifacts? = null,
    )

    suspend fun profileDidClear(distinctId: String, admissionGeneration: Long)
    suspend fun profileDidClearAll()
}

internal data class DeviceLegDispatchRequest(
    val run: DeviceLegRun,
    val release: AuthenticatedDeviceLegRelease,
    val stepId: String,
    val action: JsonObject,
    val effectId: String,
    val distinctId: String,
    val identityScope: IdentityScope,
    val executionFence: DeviceLegExecutionFence,
    val executionFenceToken: DeviceLegExecutionFenceToken,
)

internal data class DeviceLegExecutionFenceToken(val generation: Long)

/** Linearizes execution revocation with final local publications. */
internal class DeviceLegExecutionFence {
    private val lock = Any()
    private var generation = 0L

    fun advance(): DeviceLegExecutionFenceToken = synchronized(lock) {
        generation += 1
        DeviceLegExecutionFenceToken(generation)
    }

    fun token(): DeviceLegExecutionFenceToken = synchronized(lock) {
        DeviceLegExecutionFenceToken(generation)
    }

    fun token(
        expected: DeviceLegExecutionFenceToken,
    ): DeviceLegExecutionFenceToken? = synchronized(lock) {
        expected.takeIf { generation == it.generation }
    }

    fun isCurrent(token: DeviceLegExecutionFenceToken): Boolean = synchronized(lock) {
        generation == token.generation
    }

    fun <T> performIfCurrent(
        token: DeviceLegExecutionFenceToken,
        operation: () -> T,
    ): T? = synchronized(lock) {
        if (generation == token.generation) operation() else null
    }
}

internal sealed interface DeviceLegDispatchResult {
    data class Outlet(val name: String) : DeviceLegDispatchResult
    data class Complete(val outcome: String) : DeviceLegDispatchResult
    data object Unsupported : DeviceLegDispatchResult
    data object Failed : DeviceLegDispatchResult
}

internal fun interface DeviceLegDispatching {
    suspend fun dispatch(request: DeviceLegDispatchRequest): DeviceLegDispatchResult
}

/**
 * Owns authenticated device-leg execution. A private FIFO gives profile,
 * event, lifecycle, and identity changes one deterministic order while the
 * capture pipeline samples [eventAdmissionGeneration] synchronously.
 */
internal class DeviceLegService(
    private val identity: IdentityProvider,
    private val events: EventStore,
    private val catalog: DeviceLegProfileCatalog,
    private val journalDirectory: File,
    private val scope: CoroutineScope,
    private val capture: suspend (String, Map<String, Any?>, String, String) -> Boolean,
    private val captureScreenEvent: suspend (
        String,
        Map<String, Any?>,
        String,
        String,
        Long,
        StableEventCommitAdmission?,
    ) -> StableEventCaptureResult = { name, properties, eventId, distinctId, occurredAt, admission ->
        val admitted = admission == null || admission.commitIfCurrent { true } != null
        val settled = admitted && capture(name, properties, eventId, distinctId)
        StableEventCaptureResult(
            settled = settled,
            event = if (settled) {
                StoredEvent(
                    eventId,
                    name,
                    JsonValueConverter.fromMap(properties),
                    occurredAt,
                    distinctId,
                )
            } else {
                null
            },
        )
    },
    private val featureAccess: suspend (String) -> FeatureAccess? = { null },
    private val dispatcher: DeviceLegDispatching = DeviceLegDispatching {
        DeviceLegDispatchResult.Unsupported
    },
    private val presenter: DeviceLegPresenting? = null,
    private val pinnedReleaseAuthenticator: (
        JsonObject,
        JsonObject,
    ) -> AuthenticatedDeviceLegRelease = catalog::authenticatePinnedRelease,
    private val timezones: SignedTimezoneBundle = SignedTimezoneBundle.load(),
    private val currentDeviceTimezoneIdentifier: () -> String = {
        java.util.TimeZone.getDefault().id
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val beforeAdmission: suspend () -> Unit = {},
    private val beforeParkedResume: suspend () -> Unit = {},
    private val replayPendingLocalRoutes: suspend (String) -> Boolean = { true },
    private val artifactManager: DeviceLegArtifactManager? = null,
    fixedStorageScope: DeviceLegStorageScope? = null,
) : DeviceLegProfileConsumer {
    private class WorkerContext(
        val owner: DeviceLegService,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<WorkerContext>
    }
    private data class ProfileState(
        val distinctId: String,
        val snapshot: DeviceLegProfileCatalog.Snapshot,
        val generation: Long,
        val profileFenceToken: DeviceLegExecutionFenceToken,
        val executionFenceToken: DeviceLegExecutionFenceToken,
        val artifacts: PreparedDeviceLegArtifacts?,
    )

    private data class ReentryCandidate(
        val enrollment: Boolean,
        val release: AuthenticatedDeviceLegRelease,
        val reentry: JourneyReentry,
    )

    private sealed interface Command {
        val done: CompletableDeferred<Unit>?

        data class Initialize(
            override val done: CompletableDeferred<Unit>? = null,
        ) : Command
        data class ProfileCommit(
            val snapshot: DeviceLegProfileCatalog.Snapshot,
            val authority: ProfileDeliveryAuthority,
            val distinctId: String,
            val profileFenceToken: DeviceLegExecutionFenceToken,
            val executionFenceToken: DeviceLegExecutionFenceToken,
            val artifacts: PreparedDeviceLegArtifacts?,
            override val done: CompletableDeferred<Unit>,
        ) : Command
        data class ProfileClear(
            val distinctId: String?,
            override val done: CompletableDeferred<Unit>,
        ) : Command
        data class Event(
            val event: StoredEvent,
            val admittedGeneration: Long,
            val accepted: CompletableDeferred<Boolean>? = null,
        ) : Command {
            override val done: CompletableDeferred<Unit>? = null
        }
        data class PresentationBatch(
            val runId: String,
            val expectedStepId: String,
            val expectedScreenId: String,
            val release: AuthenticatedDeviceLegRelease,
            val executionFenceToken: DeviceLegExecutionFenceToken,
            val batch: DeviceLegScreenEmissionBatch,
            val accepted: CompletableDeferred<Boolean>,
        ) : Command {
            override val done: CompletableDeferred<Unit>? = null
        }
        data class ContinueExecution(
            val runId: String,
            val release: AuthenticatedDeviceLegRelease,
            val executionFenceToken: DeviceLegExecutionFenceToken,
            val signal: DeviceLegControlExecutor.Signal,
            val checkpoint: DeviceLegControlExecutor.Checkpoint?,
        ) : Command {
            override val done: CompletableDeferred<Unit>? = null
        }
        data class FinalizePresentationAbandonment(
            val runId: String,
            val executionFenceToken: DeviceLegExecutionFenceToken,
        ) : Command {
            override val done: CompletableDeferred<Unit>? = null
        }
        data class Background(override val done: CompletableDeferred<Unit>) : Command
        data class Foreground(override val done: CompletableDeferred<Unit>) : Command
        data class UserChange(
            val from: String,
            val to: String,
            override val done: CompletableDeferred<Unit>,
        ) : Command
        data class Wake(
            val generation: Long,
            val deadlineMillis: Long,
            override val done: CompletableDeferred<Unit>? = null,
        ) : Command
        data class PresentationEnded(
            val runId: String,
            val stepId: String,
            val screenId: String,
            val release: AuthenticatedDeviceLegRelease,
            val executionFenceToken: DeviceLegExecutionFenceToken,
            val outcome: DeviceLegSurfaceOutcome,
            override val done: CompletableDeferred<Unit>,
        ) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val profileGeneration = AtomicLong(0)
    private val profileFence = DeviceLegExecutionFence()
    private val executionFence = DeviceLegExecutionFence()
    /**
     * ProfileService admission generation of the newest runtime publication.
     * Admission and channel publication share this lock so delayed older
     * callbacks cannot overtake a newer profile command.
     */
    private val profilePublicationLock = Any()
    private var latestProfileAdmissionGeneration = 0L
    private var storageScope: DeviceLegStorageScope? = fixedStorageScope
    private val acceptsAuthorityScope = fixedStorageScope == null
    private var initialized = false
    private var currentProfilePublished = false
    // Setup can run from a receiver, worker, or service before any Activity is
    // visible. Keep screen-bearing admission closed until the lifecycle
    // coordinator observes the first Activity entering the foreground.
    private val foreground = AtomicBoolean(false)
    private var profileState: ProfileState? = null
    private var journal: DeviceLegRunJournal? = null
    private val retainedReleasesByDigest = linkedMapOf<String, AuthenticatedDeviceLegRelease>()
    private val retainedReleaseOrder = mutableListOf<String>()
    private var retainedReleaseBytes = 0
    private val revokingCustomers = mutableSetOf<String>()
    private val foregroundReceiptResetCustomers = mutableSetOf<String>()
    private val directlyRoutedRunByEventId = mutableMapOf<String, String>()
    private var wakeJob: Job? = null
    private var wakeGeneration = 0L

    private val worker = scope.launch(WorkerContext(this)) {
        for (command in commands) {
            val result = runCatching {
                when (command) {
                    is Command.Initialize -> initializeNow()
                    is Command.ProfileCommit -> profileDidCommitNow(
                        command.snapshot,
                        command.authority,
                        command.distinctId,
                        command.profileFenceToken,
                        command.executionFenceToken,
                        command.artifacts,
                    )
                    is Command.ProfileClear -> profileDidClearNow(
                        command.distinctId,
                    )
                    is Command.Event -> handleEventNow(
                        command.event,
                        command.admittedGeneration,
                    )
                    is Command.PresentationBatch -> handlePresentationBatchNow(command)
                    is Command.ContinueExecution -> continueExecutionNow(command)
                    is Command.FinalizePresentationAbandonment ->
                        finalizePresentationAbandonmentNow(command)
                    is Command.Background -> backgroundNow()
                    is Command.Foreground -> foregroundNow()
                    is Command.UserChange -> userDidChangeNow(command.from, command.to)
                    is Command.Wake -> wakeNow(command.generation, command.deadlineMillis)
                    is Command.PresentationEnded -> presentationEndedNow(command)
                }
            }.onFailure {
                Log.w(LOG_TAG, "Device-leg command failed", it)
            }
            if (command is Command.Event) {
                command.accepted?.complete(result.getOrNull() == true)
            }
            if (command is Command.PresentationBatch) {
                command.accepted.complete(result.getOrNull() == true)
            }
            command.done?.complete(Unit)
        }
    }

    fun eventAdmissionGeneration(): Long = profileGeneration.get()

    suspend fun initialize() = submit(Command::Initialize)

    /** Queue startup synchronously so every later event command follows it. */
    fun enqueueInitialization() {
        commands.trySend(Command.Initialize())
    }

    override suspend fun profileDidCommit(
        snapshot: DeviceLegProfileCatalog.Snapshot,
        authority: ProfileDeliveryAuthority,
        distinctId: String,
        admissionGeneration: Long,
        artifacts: PreparedDeviceLegArtifacts?,
    ) {
        val accepted = submitProfilePublication(admissionGeneration) {
                done, profileToken, executionToken,
            ->
            Command.ProfileCommit(
                snapshot,
                authority,
                distinctId,
                profileToken,
                executionToken,
                artifacts,
                done,
            )
        }
        if (!accepted) artifacts?.close()
    }

    override suspend fun profileDidClear(distinctId: String, admissionGeneration: Long) {
        var shouldShutdownPresentation = false
        submitProfilePublication(
            admissionGeneration,
            revokeExecution = {
                // Identity changes have their own synchronous fence. Only a
                // clear for the current customer may revoke this shared lane.
                if (identity.distinctId() == distinctId) {
                    executionFence.advance()
                    shouldShutdownPresentation = true
                }
            },
            afterPublication = {
                if (shouldShutdownPresentation) presenter?.shutdownOwnedBy(distinctId)
            },
        ) { done, _, _ -> Command.ProfileClear(distinctId, done) }
    }

    override suspend fun profileDidClearAll() {
        val done = CompletableDeferred<Unit>()
        val sent = synchronized(profilePublicationLock) {
            profileFence.advance()
            executionFence.advance()
            commands.trySend(Command.ProfileClear(null, done)).isSuccess
        }
        if (sent) {
            presenter?.shutdownOwnedBy(identity.distinctId())
            done.await()
        }
    }

    fun enqueueEvent(event: StoredEvent, admittedGeneration: Long) {
        commands.trySend(Command.Event(event, admittedGeneration))
    }

    suspend fun handleEvent(event: StoredEvent, admittedGeneration: Long): Boolean {
        if (coroutineContext[WorkerContext]?.owner === this) {
            return handleEventNow(event, admittedGeneration)
        }
        val accepted = CompletableDeferred<Boolean>()
        if (commands.trySend(Command.Event(event, admittedGeneration, accepted)).isFailure) {
            return false
        }
        return accepted.await()
    }

    private suspend fun handlePresentationBatch(
        runId: String,
        expectedStepId: String,
        expectedScreenId: String,
        release: AuthenticatedDeviceLegRelease,
        executionFenceToken: DeviceLegExecutionFenceToken,
        batch: DeviceLegScreenEmissionBatch,
    ): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        val command = Command.PresentationBatch(
            runId = runId,
            expectedStepId = expectedStepId,
            expectedScreenId = expectedScreenId,
            release = release,
            executionFenceToken = executionFenceToken,
            batch = batch,
            accepted = accepted,
        )
        if (coroutineContext[WorkerContext]?.owner === this) {
            return handlePresentationBatchNow(command)
        }
        if (commands.trySend(command).isFailure) return false
        return accepted.await()
    }

    suspend fun onAppDidEnterBackground() {
        foreground.set(false)
        submit(Command::Background)
    }

    suspend fun onAppWillEnterForeground() {
        foreground.set(true)
        submit(Command::Foreground)
    }

    suspend fun handleUserChange(from: String, to: String) =
        submit { done -> Command.UserChange(from, to, done) }

    private suspend fun submit(factory: (CompletableDeferred<Unit>) -> Command) {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(factory(done)).isSuccess) done.await()
    }

    private suspend fun submitProfilePublication(
        admissionGeneration: Long,
        revokeExecution: () -> Unit = {},
        afterPublication: suspend () -> Unit = {},
        factory: (
            CompletableDeferred<Unit>,
            DeviceLegExecutionFenceToken,
            DeviceLegExecutionFenceToken,
        ) -> Command,
    ): Boolean {
        val done = CompletableDeferred<Unit>()
        val sent = synchronized(profilePublicationLock) {
            if (admissionGeneration < latestProfileAdmissionGeneration) {
                return@synchronized false
            }
            latestProfileAdmissionGeneration = admissionGeneration
            val profileToken = profileFence.advance()
            // This must run before the command can wait behind a suspended
            // effect. Final effect publications hold the same fence lock.
            revokeExecution()
            val executionToken = executionFence.token()
            commands.trySend(factory(done, profileToken, executionToken)).isSuccess
        }
        if (sent) {
            afterPublication()
            done.await()
        }
        return sent
    }

    private suspend fun initializeNow() {
        if (initialized) return
        initialized = true
        if (storageScope != null && !ensureJournal(identity.distinctId())) return
        resetForegroundReceiptsIfNeeded()
        val state = currentState() ?: return
        reconcileNow(state.generation)
    }

    private suspend fun profileDidCommitNow(
        snapshot: DeviceLegProfileCatalog.Snapshot,
        authority: ProfileDeliveryAuthority,
        distinctId: String,
        profileFenceToken: DeviceLegExecutionFenceToken,
        executionFenceToken: DeviceLegExecutionFenceToken,
        artifacts: PreparedDeviceLegArtifacts?,
    ) {
        var adoptedArtifacts = false
        try {
            if (identity.distinctId() != distinctId) return
            if (acceptsAuthorityScope) {
                val authenticated = DeviceLegStorageScope(authority)
                if (storageScope != null && storageScope != authenticated) {
                    Log.e(LOG_TAG, "Authenticated device-leg authority changed")
                    return
                }
                storageScope = authenticated
            }
            val generation = profileGeneration.incrementAndGet()
            val state = ProfileState(
                distinctId,
                snapshot,
                generation,
                profileFenceToken,
                executionFenceToken,
                artifacts,
            )
            val previousArtifacts = profileState?.artifacts
            profileState = state
            adoptedArtifacts = true
            previousArtifacts?.close()
            currentProfilePublished = true
            if (!initialized) return
            if (!ensureJournal(distinctId)) return
            resetForegroundReceiptsIfNeeded()
            journal?.takeIf { it.distinctId == distinctId }?.let { current ->
                val receipts = snapshot.profile.armedLegs.asSequence()
                    .filter { it.entryCondition.text("type") != "event" }
                    .map(::deviceLegStateArmReceipt)
                    .toSet()
                current.retainStateArmReceipts(receipts)
                current.retainCheckmarks(liveReentryPolicies(snapshot), nowMillis())
            }
            reconcileNow(state.generation)
        } finally {
            if (!adoptedArtifacts) artifacts?.close()
        }
    }

    private suspend fun profileDidClearNow(distinctId: String?) {
        val currentJournal = journal
        if (distinctId != null &&
            profileState?.distinctId != distinctId &&
            currentJournal?.distinctId != distinctId
        ) return
        cancelWake()
        profileGeneration.incrementAndGet()
        profileState?.artifacts?.close()
        profileState = null
        currentProfilePublished = distinctId != null && identity.distinctId() == distinctId
        directlyRoutedRunByEventId.clear()
        clearRetainedReleaseCache()
        foregroundReceiptResetCustomers.clear()
        currentJournal?.takeIf { distinctId == null || it.distinctId == distinctId }
            ?.let { abandonJournal(it) }
    }

    private suspend fun handleEventNow(
        event: StoredEvent,
        admittedGeneration: Long,
    ): Boolean {
        val directlyRoutedRunId = directlyRoutedRunByEventId.remove(event.id)
        if (event.distinctId != identity.distinctId()) return false
        if (event.name == JourneyEventNames.LEG_STARTED ||
            event.name == JourneyEventNames.LEG_COMPLETED
        ) return true
        if (!initialized) return false
        val state = currentState() ?: return currentProfilePublished
        resumeParkedRuns(state, event, excludingRunId = directlyRoutedRunId)
        if (!isCurrent(state) || admittedGeneration != state.generation) {
            scheduleNextWake()
            return false
        }
        state.snapshot.profile.armedLegs.asSequence()
            .filter {
                it.entryCondition.text("type") == "event" &&
                    it.entryCondition.text("eventName") == event.name
            }
            .forEach { arm -> attemptStart(arm, state, event) }
        scheduleNextWake()
        return true
    }

    private fun backgroundNow() {
        foreground.set(false)
        cancelWake()
    }

    private suspend fun foregroundNow() {
        foreground.set(true)
        foregroundReceiptResetCustomers.remove(identity.distinctId())
        resetForegroundReceiptsIfNeeded()
        val state = currentState() ?: return
        reconcileNow(state.generation)
    }

    private suspend fun userDidChangeNow(from: String, to: String) {
        cancelWake()
        val departing = journal?.takeIf { it.distinctId == from }
        var departingRevoked = true
        if (departing != null) {
            departingRevoked = abandonJournal(departing)
            if (departingRevoked && journal === departing) journal = null
        }
        if (profileState?.distinctId == from) {
            profileGeneration.incrementAndGet()
            profileState?.artifacts?.close()
            profileState = null
        }
        currentProfilePublished = false
        directlyRoutedRunByEventId.clear()
        clearRetainedReleaseCache()
        foregroundReceiptResetCustomers.clear()
        if (!departingRevoked) return
        if (!initialized || identity.distinctId() != to) return
        if (!ensureJournal(to)) return
        resetForegroundReceiptsIfNeeded()
        scheduleNextWake()
    }

    private fun currentState(): ProfileState? = profileState?.takeIf {
        it.distinctId == identity.distinctId()
    }

    private fun isCurrent(state: ProfileState): Boolean =
        profileState?.generation == state.generation &&
            profileState?.distinctId == state.distinctId &&
            profileGeneration.get() == state.generation &&
            identity.distinctId() == state.distinctId

    private suspend fun resetForegroundReceiptsIfNeeded() {
        if (!foreground.get()) return
        val distinctId = identity.distinctId()
        if (distinctId in foregroundReceiptResetCustomers) return
        val current = journal?.takeIf { it.distinctId == distinctId } ?: return
        current.clearStateArmReceipts("app_foregrounded")
        foregroundReceiptResetCustomers += distinctId
    }

    private suspend fun ensureJournal(distinctId: String): Boolean {
        if (storageScope == null) return false
        val displaced = journal?.takeIf { it.distinctId != distinctId }
        if (displaced != null) {
            if (!abandonJournal(displaced)) return false
            if (journal === displaced) journal = null
        }
        val existing = journal?.takeIf { it.distinctId == distinctId }
        if (existing != null && distinctId in revokingCustomers) {
            if (!abandonJournal(existing) || distinctId in revokingCustomers) {
                return false
            }
        }
        if (identity.distinctId() == distinctId && journal?.distinctId != distinctId) {
            openJournal(distinctId)
        }
        return journal?.distinctId == distinctId && distinctId !in revokingCustomers
    }

    private suspend fun openJournal(distinctId: String) {
        val scope = storageScope ?: return
        runCatching {
            val opened = DeviceLegRunJournal(journalDirectory, distinctId, scope)
            clearRetainedReleaseCache()
            journal = opened
            opened.recover(nowMillis()) { run ->
                val manager = artifactManager
                manager == null || manager.retainedRunDigests(
                    deviceLegArtifactRunKey(run),
                ) == run.artifactDigests
            }
            publishTerminalPresentationPublications(opened)
            reporter(opened).flushPending()
            if (opened.finalizeRevocation()) revokingCustomers.remove(distinctId)
            else revokingCustomers += distinctId
        }.onFailure {
            Log.e(LOG_TAG, "Device-leg journal recovery failed", it)
            journal = null
        }
    }

    /** True once the durable marker prevents this journal from reopening. */
    private suspend fun abandonJournal(target: DeviceLegRunJournal): Boolean {
        revokingCustomers += target.distinctId
        val revoked = runCatching {
            publishAllPresentationObservability(target)
            target.abandonAll(nowMillis())
            true
        }.onFailure {
            Log.w(LOG_TAG, "Device-leg journal revocation failed", it)
        }.getOrDefault(false)
        if (!revoked) return false
        runCatching {
            reporter(target).flushPending()
            if (target.finalizeRevocation()) revokingCustomers.remove(target.distinctId)
        }.onFailure {
            Log.w(LOG_TAG, "Device-leg revocation reporting remains pending", it)
        }
        return true
    }

    private fun reporter(target: DeviceLegRunJournal) = DeviceLegReporter(
        target,
        capture,
    ) { run ->
            if (run.artifactDigests.isNotEmpty()) {
                runCatching {
                    artifactManager?.releaseRun(deviceLegArtifactRunKey(run))
                }.onFailure {
                    Log.w(LOG_TAG, "Device-leg artifact pin release failed", it)
                }
            }
        }

    private suspend fun reconcileNow(generation: Long) {
        val state = currentState()?.takeIf { it.generation == generation } ?: return
        if (!ensureJournal(state.distinctId)) return
        settleLivePresentationPublications(state)
        if (!isCurrent(state)) return
        // A pending live route may still own its receipt while this command
        // runs. It is already queued behind us and will acknowledge itself;
        // every unowned receipt is replayed before fresh state-arm admission.
        replayPendingLocalRoutes(state.distinctId)
        if (!isCurrent(state)) return
        resumeParkedRuns(state, null)
        evaluateStateArms(state)
        scheduleNextWake()
    }

    private suspend fun settleLivePresentationPublications(state: ProfileState) {
        val target = journal?.takeIf { it.distinctId == state.distinctId } ?: return
        val executionToken = executionFence.token(state.executionFenceToken) ?: return
        for (run in target.runs().filter {
            it.completion == null && it.pendingPresentationPublication != null
        }) {
            if (!isCurrent(state) || !isExecutionCurrent(executionToken, target)) return
            val release = releaseFor(run, state, executionToken, target) ?: continue
            val identityScope = identity.captureScope()
            if (identityScope.distinctId != target.distinctId) return
            settlePresentationPublication(
                run,
                release,
                target,
                executionToken,
                identityScope,
            )
        }
        publishTerminalPresentationPublications(target)
    }

    private suspend fun publishAllPresentationObservability(
        target: DeviceLegRunJournal,
    ) {
        for (run in target.runs().filter {
            it.pendingPresentationPublication != null
        }) {
            publishPresentationObservability(run, target)
        }
    }

    private suspend fun publishTerminalPresentationPublications(
        target: DeviceLegRunJournal,
    ) {
        for (run in target.runs().filter {
            it.completion != null && it.pendingPresentationPublication != null
        }) {
            publishPresentationObservability(run, target)
        }
    }

    private suspend fun publishPresentationObservability(
        run: DeviceLegRun,
        target: DeviceLegRunJournal,
    ): Boolean {
        val publication = run.pendingPresentationPublication ?: return true
        for (item in publication.items) {
            val captured = runCatching {
                captureScreenEvent(
                    item.name,
                    JsonValueConverter.toNativeMap(item.properties),
                    item.eventId,
                    target.distinctId,
                    item.occurredAtMillis,
                    null,
                )
            }.getOrNull() ?: return false
            if (!captured.settled) return false
        }
        return runCatching {
            target.clearPresentationPublication(run.id, publication.invocationId) != null
        }.getOrDefault(false)
    }

    private suspend fun evaluateStateArms(state: ProfileState) {
        if (!isCurrent(state)) return
        state.snapshot.profile.armedLegs.asSequence()
            .filter { it.entryCondition.text("type") != "event" }
            .forEach { attemptStart(it, state, null) }
    }

    private suspend fun attemptStart(
        arm: JourneyPlaneProfile.Arm,
        state: ProfileState,
        event: StoredEvent?,
    ) {
        val currentJournal = journal?.takeIf {
            it.distinctId == state.distinctId && it.distinctId !in revokingCustomers
        } ?: return
        val profileToken = profileFence.token(state.profileFenceToken) ?: return
        val executionToken = executionFence.token(state.executionFenceToken) ?: return
        val digest = arm.reference.text("descriptorSha256") ?: return
        val release = state.snapshot.releasesByDigest[digest] ?: return
        val releasePin = state.snapshot.profile.releases.firstOrNull {
            it.envelope.text("descriptorSha256") == digest
        } ?: return
        // Android cannot safely launch the presentation Activity while the
        // host app is backgrounded. Keep the arm and its reentry history
        // untouched; state arms are reconsidered by foregroundNow and event
        // arms remain eligible for a later matching event.
        if (release.hasScreens() && !foreground.get()) return
        if (!entryMatches(arm, release, state, event)) return
        if (entitlementGateSuppresses(release.leg)) return
        if (!entryMatches(arm, release, state, event) || !isCurrent(state)) return
        val context = DeviceLegBoundaryProjector.inputContext(
            arm,
            event,
            release.leg.getValue("inputs").jsonObject,
        ) ?: return
        val admittedArm = arm.copy(context = context)
        val reentry = parseReentry(release.leg.getValue("reentry").jsonObject) ?: return
        val entryStepId = release.leg.text("entryStepId") ?: return
        val stateReceipt = arm.entryCondition.text("type")
            ?.takeIf { it != "event" }
            ?.let { deviceLegStateArmReceipt(arm) }
        val artifactDigests = if (release.hasScreens() && artifactManager != null) {
            state.artifacts?.digestsForRelease(digest)?.takeIf { it.isNotEmpty() }
                ?: return
        } else {
            emptySet()
        }
        val presentationReservation = if (presenter != null && release.hasScreens()) {
            presenter.reserve(state.distinctId) ?: return
        } else {
            null
        }
        var reservationHandedOff = false
        try {
            val identityScope = identity.captureScope()
            if (identityScope.distinctId != state.distinctId || !isCurrent(state)) return
            beforeAdmission()
            var retainedCandidate: DeviceLegRun? = null
            val run = try {
                profileFence.performIfCurrent(profileToken) {
                    identity.withCurrentScope(identityScope) {
                        if (!isCurrent(state) ||
                            (release.hasScreens() && !foreground.get())
                        ) return@withCurrentScope null
                        currentJournal.admit(
                            admittedArm,
                            reentry,
                            entryStepId,
                            nowMillis(),
                            releasePin,
                            stateReceipt,
                            artifactDigests,
                            retainArtifacts = { candidate ->
                                artifactManager?.retainForRun(
                                    deviceLegArtifactRunKey(candidate),
                                    candidate.artifactDigests,
                                )
                                retainedCandidate = candidate
                            },
                        )
                    }
                }
            } catch (failure: Throwable) {
                retainedCandidate?.let { candidate ->
                    val published = runCatching {
                        currentJournal.runs().any {
                            it.id == candidate.id &&
                                it.artifactDigests == candidate.artifactDigests
                        }
                    }.getOrNull()
                    if (published == false) {
                        runCatching {
                            artifactManager?.releaseRun(deviceLegArtifactRunKey(candidate))
                        }.onFailure {
                            Log.w(LOG_TAG, "Device-leg artifact pin rollback failed", it)
                        }
                    }
                }
                throw failure
            } ?: return
            if (!isExecutionCurrent(executionToken, currentJournal)) {
                finish(run, "abandoned", release.leg, currentJournal)
                return
            }
            reporter(currentJournal).flushPending()
            val queued = currentJournal.runs().firstOrNull {
                it.id == run.id && it.startedQueued
            } ?: return
            reservationHandedOff = true
            execute(
                queued,
                release,
                state,
                executionToken,
                executorSignal(event),
                null,
                currentJournal,
                presentationReservation,
            )
        } finally {
            if (!reservationHandedOff) presentationReservation?.close()
        }
    }

    private suspend fun entryMatches(
        arm: JourneyPlaneProfile.Arm,
        release: AuthenticatedDeviceLegRelease,
        state: ProfileState,
        event: StoredEvent?,
    ): Boolean {
        if (!isCurrent(state)) return false
        return DeviceLegEntryEvaluator.matches(
            entry = arm.entryCondition,
            facts = state.snapshot.profile.facts,
            references = release.leg.getValue("facts").jsonObject,
            foreground = foreground.get(),
            event = event?.let(::entryEvent),
            nowMillis = nowMillis(),
            events = events,
            distinctId = state.distinctId,
            featureAccess = featureAccess,
        )
    }

    private suspend fun entitlementGateSuppresses(leg: JsonObject): Boolean {
        val gate = leg.getValue("entitlementGate").jsonObject
        if (gate["enabled"]?.jsonPrimitive?.booleanOrNull != true) return false
        for (value in gate.getValue("products").jsonArray) {
            val product = value.jsonObject
            val featureIds = product.getValue("featureIds").jsonArray.map {
                it.jsonPrimitive.content
            }
            if (featureIds.isNotEmpty() && featureIds.all { featureAccess(it)?.allowed == true }) {
                return true
            }
        }
        return false
    }

    private suspend fun resumeParkedRuns(
        state: ProfileState,
        event: StoredEvent?,
        excludingRunId: String? = null,
    ) {
        if (!isCurrent(state) || (event == null && !foreground.get())) return
        val currentJournal = journal?.takeIf { it.distinctId == state.distinctId } ?: return
        val executionToken = executionFence.token(state.executionFenceToken) ?: return
        for (parked in currentJournal.runs().filter {
            it.id != excludingRunId && it.park != null && it.completion == null &&
                it.pendingPresentationPublication == null
        }) {
            if (!isCurrent(state)) return
            val park = parked.park ?: continue
            if (event == null && !park.pendingResponsesChanged &&
                (park.wakeAtMillis == null || park.wakeAtMillis > nowMillis())
            ) {
                continue
            }
            val release = releaseFor(
                parked,
                state,
                executionToken,
                currentJournal,
            ) ?: continue
            if (!isExecutionCurrent(executionToken, currentJournal)) return
            val presentationReservation = if (presenter != null && release.hasScreens()) {
                presenter.reserve(state.distinctId) ?: continue
            } else {
                null
            }
            val checkpoint = park.wakeAtMillis?.let { wake ->
                DeviceLegControlExecutor.Checkpoint(park.anchorAtMillis ?: wake, wake)
            }
            var reservationHandedOff = false
            try {
                beforeParkedResume()
                val resumed = profileFence.performIfCurrent(state.profileFenceToken) {
                    if (release.hasScreens() && !foreground.get()) {
                        null
                    } else {
                        currentJournal.resumeParked(parked.id)
                    }
                } ?: continue
                reservationHandedOff = true
                execute(
                    resumed,
                    release,
                    state,
                    executionToken,
                    executorSignal(event).copy(
                        responsesChanged = park.pendingResponsesChanged,
                    ),
                    checkpoint,
                    currentJournal,
                    presentationReservation,
                )
            } finally {
                if (!reservationHandedOff) presentationReservation?.close()
            }
        }
    }

    private suspend fun presentationEndedNow(command: Command.PresentationEnded) {
        val target = journal ?: return
        if (!isExecutionCurrent(command.executionFenceToken, target)) return
        val run = target.runs().firstOrNull { it.id == command.runId }
            ?.takeIf { it.completion == null && it.stepId == command.stepId }
            ?: return
        if (!matches(command.release, run.reference)) {
            finish(run, "abandoned", command.release.leg, target)
            return
        }
        if (command.outcome == DeviceLegSurfaceOutcome.ABANDONED) {
            finish(run, "abandoned", command.release.leg, target)
            return
        }
        val routeStepId = hostDismissRoute(command.release.leg, command.screenId)
        if (routeStepId == null) {
            finish(run, HOST_DISMISSED_OUTCOME, command.release.leg, target)
            return
        }
        val state = currentState()
        if (state == null || !isExecutionCurrent(command.executionFenceToken, target)) {
            finish(run, "abandoned", command.release.leg, target)
            return
        }
        target.transition(run.id, routeStepId, run.context)
        execute(
            initial = run.copy(stepId = routeStepId, park = null),
            release = command.release,
            state = state,
            executionToken = command.executionFenceToken,
            signal = DeviceLegControlExecutor.Signal(
                DeviceLegControlExecutor.Event(
                    HOST_DISMISSED_EVENT,
                    nowMillis(),
                    JsonObject(emptyMap()),
                ),
            ),
            initialCheckpoint = null,
            target = target,
        )
    }

    private suspend fun handlePresentationBatchNow(
        command: Command.PresentationBatch,
    ): Boolean {
        val target = journal ?: return false
        if (!isExecutionCurrent(command.executionFenceToken, target)) return false
        val run = target.runs().firstOrNull { it.id == command.runId }
            ?.takeIf {
                it.completion == null &&
                    it.stepId == command.expectedStepId &&
                    matches(command.release, it.reference)
            }
            ?: return false
        val batch = command.batch
        if (batch.journeyId != run.journeyId ||
            batch.invocationId.isEmpty() ||
            batch.source.screenId != command.expectedScreenId ||
            batch.source.actionId.isEmpty() ||
            batch.batchSequence < 0
        ) return false
        if (batch.batchSequence < run.nextPresentationBatchSequence) return true
        if (batch.batchSequence != run.nextPresentationBatchSequence ||
            batch.batchSequence == Long.MAX_VALUE
        ) return false

        val screen = command.release.leg.getValue("screens").jsonArray
            .map(JsonElement::jsonObject)
            .firstOrNull { it.text("id") == command.expectedScreenId }
            ?: return false
        val responseCaptures = (screen["responseCaptures"] as? JsonArray).orEmpty()
            .mapNotNull { value ->
                (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            }
            .toSet()
        if (responseCaptures.size != (screen["responseCaptures"] as? JsonArray).orEmpty().size) {
            return false
        }

        val responses = run.context.getValue("responses").jsonObject.toMutableMap()
        var responsesChanged = false
        val items = mutableListOf<DeviceLegRun.PendingPresentationPublication.Item>()
        val eventIds = mutableSetOf<String>()
        var nextEmissionSequence = run.nextPresentationEmissionSequence
        for (emission in batch.emissions) {
            if (emission.id.isEmpty() || !eventIds.add(emission.id) ||
                emission.sequence != nextEmissionSequence ||
                emission.occurredAtMillis < 0
            ) return false
            nextEmissionSequence = try {
                Math.addExact(nextEmissionSequence, 1L)
            } catch (_: ArithmeticException) {
                return false
            }
            when (emission.name) {
                RESPONSE_SET_EVENT -> {
                    val field = emission.payload.text("field") ?: return false
                    val value = emission.payload["value"] ?: return false
                    if (field !in responseCaptures) return false
                    responses[field] = value
                    responsesChanged = true
                }
                RESPONSE_UNSET_EVENT -> {
                    val field = emission.payload.text("field") ?: return false
                    if (field !in responseCaptures) return false
                    responses.remove(field)
                    responsesChanged = true
                }
                else -> {
                    if (emission.name.isEmpty() || emission.name.startsWith('$')) return false
                    val properties = attributedPresentationProperties(
                        emission.payload,
                        command.expectedScreenId,
                        run,
                    ) ?: return false
                    items += DeviceLegRun.PendingPresentationPublication.Item(
                        name = emission.name,
                        properties = properties,
                        eventId = emission.id,
                        occurredAtMillis = emission.occurredAtMillis,
                    )
                }
            }
        }
        val context = JsonObject(
            run.context + ("responses" to JsonObject(responses)),
        )
        val publication = DeviceLegRun.PendingPresentationPublication(
            invocationId = batch.invocationId,
            batchSequence = batch.batchSequence,
            nextEmissionSequence = nextEmissionSequence,
            sourceScreenId = batch.source.screenId,
            sourceActionId = batch.source.actionId,
            sourceComponentId = batch.source.componentId,
            sourceInstanceId = batch.source.instanceId,
            responsesChanged = responsesChanged,
            items = items,
        )
        val identityScope = identity.captureScope()
        if (identityScope.distinctId != target.distinctId) return false
        val staged = publishJournalIfCurrent(command.executionFenceToken, identityScope) {
            target.stagePresentationPublication(
                run.id,
                expectedStepId = command.expectedStepId,
                context = context,
                publication = publication,
            )
        } ?: return false
        return settlePresentationPublication(
            staged,
            command.release,
            target,
            command.executionFenceToken,
            identityScope,
        )
    }

    private suspend fun settlePresentationPublication(
        stagedRun: DeviceLegRun,
        release: AuthenticatedDeviceLegRelease,
        target: DeviceLegRunJournal,
        executionToken: DeviceLegExecutionFenceToken,
        identityScope: IdentityScope,
    ): Boolean {
        val publication = stagedRun.pendingPresentationPublication ?: return true
        val admission = StableEventCommitAdmission { commit ->
            executionFence.performIfCurrent(executionToken) {
                identity.withCurrentScope(identityScope, commit)
            }
        }
        var routedEvent: StoredEvent? = null
        var routeStepId: String? = null
        var publishedOrdinaryEvents = false
        for (item in publication.items) {
            directlyRoutedRunByEventId[item.eventId] = stagedRun.id
            val captureResult = runCatching {
                captureScreenEvent(
                    item.name,
                    JsonValueConverter.toNativeMap(item.properties),
                    item.eventId,
                    target.distinctId,
                    item.occurredAtMillis,
                    admission,
                )
            }.getOrElse {
                directlyRoutedRunByEventId.remove(item.eventId, stagedRun.id)
                return acknowledgePublishedPresentationBatchFailure(
                    publishedOrdinaryEvents,
                    stagedRun,
                    publication,
                    target,
                    executionToken,
                    identityScope,
                )
            }
            if (!captureResult.settled) {
                directlyRoutedRunByEventId.remove(item.eventId, stagedRun.id)
                return acknowledgePublishedPresentationBatchFailure(
                    publishedOrdinaryEvents,
                    stagedRun,
                    publication,
                    target,
                    executionToken,
                    identityScope,
                )
            }
            publishedOrdinaryEvents = true
            if (captureResult.event == null || !captureResult.localRoutePending) {
                directlyRoutedRunByEventId.remove(item.eventId, stagedRun.id)
            }
            val event = captureResult.event
            if (routedEvent == null && event != null) {
                screenEventRoute(
                    release.leg,
                    event.name,
                    publication.sourceScreenId,
                )?.let { route ->
                    routedEvent = event
                    routeStepId = route
                }
            }
        }

        // The batch is already durable and its ordinary events are stable.
        // Revocation recovery owns the remaining outbox if authority changed
        // while EventLog was publishing it.
        if (!isExecutionCurrent(executionToken, target) ||
            !identity.isCurrentScope(identityScope)
        ) return true
        val current = target.runs().firstOrNull {
            it.id == stagedRun.id &&
                it.completion == null &&
                it.stepId == stagedRun.stepId &&
                it.pendingPresentationPublication?.invocationId == publication.invocationId
        } ?: return acknowledgePublishedPresentationBatchFailure(
            publishedOrdinaryEvents,
            stagedRun,
            publication,
            target,
            executionToken,
            identityScope,
        )

        val event = routedEvent
        val route = routeStepId
        if (event != null && route != null) {
            val context = JsonObject(
                mapOf(
                    "event" to event.properties,
                    "responses" to current.context.getValue("responses").jsonObject,
                ),
            )
            val transitioned = runCatching {
                publishJournalIfCurrent(executionToken, identityScope) {
                    target.transition(
                        current.id,
                        route,
                        context,
                        clearingPresentationPublication = publication.invocationId,
                    )
                    target.runs().firstOrNull { it.id == current.id }
                }
            }.getOrNull() ?: return acknowledgePublishedPresentationBatchFailure(
                publishedOrdinaryEvents,
                current,
                publication,
                target,
                executionToken,
                identityScope,
            )
            commands.trySend(
                Command.ContinueExecution(
                    transitioned.id,
                    release,
                    executionToken,
                    executorSignal(event),
                    null,
                ),
            )
            return true
        }

        val checkpoint = controlCheckpoint(current.park)
        val resumesForResponses = publication.responsesChanged &&
            current.park != null &&
            stepAcceptsResponseChange(current.stepId, release.leg)
        val cleared = runCatching {
            publishJournalIfCurrent(executionToken, identityScope) {
                target.clearPresentationPublication(
                    current.id,
                    publication.invocationId,
                    retainResponsesChanged = resumesForResponses,
                )
            }
        }.getOrNull() ?: return acknowledgePublishedPresentationBatchFailure(
            publishedOrdinaryEvents,
            current,
            publication,
            target,
            executionToken,
            identityScope,
        )
        if (resumesForResponses) {
            commands.trySend(
                Command.ContinueExecution(
                    cleared.id,
                    release,
                    executionToken,
                    DeviceLegControlExecutor.Signal(responsesChanged = true),
                    checkpoint,
                ),
            )
        }
        return true
    }

    private fun acknowledgePublishedPresentationBatchFailure(
        publishedOrdinaryEvents: Boolean,
        run: DeviceLegRun,
        publication: DeviceLegRun.PendingPresentationPublication,
        target: DeviceLegRunJournal,
        executionToken: DeviceLegExecutionFenceToken,
        identityScope: IdentityScope,
    ): Boolean {
        if (!publishedOrdinaryEvents) return false
        val abandoned = runCatching {
            publishJournalIfCurrent(executionToken, identityScope) {
                target.abandonPendingPresentationPublication(
                    run.id,
                    publication.invocationId,
                    nowMillis(),
                )
            }
        }.getOrNull() ?: return false
        commands.trySend(
            Command.FinalizePresentationAbandonment(
                runId = abandoned.id,
                executionFenceToken = executionToken,
            ),
        )
        return true
    }

    private suspend fun finalizePresentationAbandonmentNow(
        command: Command.FinalizePresentationAbandonment,
    ) {
        val target = journal ?: return
        if (!isExecutionCurrent(command.executionFenceToken, target)) return
        val run = target.runs().firstOrNull {
            it.id == command.runId &&
                it.completion?.outcome == "abandoned" &&
                it.pendingPresentationPublication != null
        } ?: return
        if (!publishPresentationObservability(run, target)) return
        reporter(target).flushPending()
        scheduleNextWake()
    }

    private suspend fun continueExecutionNow(command: Command.ContinueExecution) {
        val target = journal ?: return
        if (!isExecutionCurrent(command.executionFenceToken, target)) return
        val run = target.runs().firstOrNull { it.id == command.runId }
            ?.takeIf { it.completion == null && it.pendingPresentationPublication == null }
            ?: return
        if (!matches(command.release, run.reference)) {
            finish(run, "abandoned", command.release.leg, target)
            return
        }
        val state = currentState() ?: run {
            finish(run, "abandoned", command.release.leg, target)
            return
        }
        execute(
            initial = run,
            release = command.release,
            state = state,
            executionToken = command.executionFenceToken,
            signal = command.signal,
            initialCheckpoint = command.checkpoint,
            target = target,
        )
    }

    private fun <T> publishJournalIfCurrent(
        executionToken: DeviceLegExecutionFenceToken,
        identityScope: IdentityScope,
        publication: () -> T,
    ): T? = executionFence.performIfCurrent(executionToken) {
        identity.withCurrentScope(identityScope, publication)
    }

    private fun attributedPresentationProperties(
        payload: JsonObject,
        screenId: String,
        run: DeviceLegRun,
    ): JsonObject? {
        val experienceId = run.reference.text("experienceId") ?: return null
        val versionId = run.reference.text("versionId") ?: return null
        val legId = run.reference.text("legId") ?: return null
        return JsonObject(
            payload + mapOf(
                "journey_id" to JsonPrimitive(run.journeyId),
                "experience_id" to JsonPrimitive(experienceId),
                "experience_version" to JsonPrimitive(versionId),
                "leg_id" to JsonPrimitive(legId),
                "leg_generation" to JsonPrimitive(run.generation),
                "screen_id" to JsonPrimitive(screenId),
            ),
        )
    }

    private fun screenEventRoute(
        leg: JsonObject,
        eventName: String,
        screenId: String,
    ): String? {
        val routes = leg.getValue("routes").jsonArray.map(JsonElement::jsonObject)
        fun route(kind: String, expectedScreenId: String? = null): String? = routes
            .firstOrNull { candidate ->
                if (candidate.text("eventName") != eventName) return@firstOrNull false
                val host = candidate.getValue("host").jsonObject
                host.text("kind") == kind &&
                    (expectedScreenId == null || host.text("screenId") == expectedScreenId)
            }
            ?.text("entryStepId")
        return route("screen", screenId) ?: route("journey")
    }

    private fun stepAcceptsResponseChange(stepId: String, leg: JsonObject): Boolean {
        val step = leg.getValue("steps").jsonArray.map(JsonElement::jsonObject)
            .firstOrNull { it.text("id") == stepId }
            ?: return false
        val action = step["action"] as? JsonObject ?: return false
        if (action.text("type") != "wait_until") return false
        return when ((action["trigger"] as? JsonObject)?.text("kind")) {
            "response_change", "event_or_response_change" -> true
            else -> false
        }
    }

    private fun controlCheckpoint(
        park: DeviceLegRun.Park?,
    ): DeviceLegControlExecutor.Checkpoint? {
        val wakeAt = park?.wakeAtMillis ?: return null
        return DeviceLegControlExecutor.Checkpoint(
            park.anchorAtMillis ?: wakeAt,
            wakeAt,
        )
    }

    private fun hostDismissRoute(leg: JsonObject, screenId: String): String? {
        val routes = leg.getValue("routes").jsonArray.map(JsonElement::jsonObject)
        fun routeFor(kind: String, expectedScreenId: String? = null): String? = routes
            .firstOrNull { route ->
                if (route.text("eventName") != HOST_DISMISSED_EVENT) return@firstOrNull false
                val host = route.getValue("host").jsonObject
                host.text("kind") == kind &&
                    (expectedScreenId == null || host.text("screenId") == expectedScreenId)
            }
            ?.text("entryStepId")
        return routeFor("screen", screenId) ?: routeFor("journey")
    }

    private suspend fun releaseFor(
        run: DeviceLegRun,
        state: ProfileState,
        executionToken: DeviceLegExecutionFenceToken,
        target: DeviceLegRunJournal,
    ): AuthenticatedDeviceLegRelease? {
        val digest = run.reference.text("descriptorSha256") ?: return null
        state.snapshot.releasesByDigest[digest]?.takeIf {
            matches(it, run.reference)
        }?.let { return it }
        cachedRetainedRelease(digest)?.let { cached ->
            if (matches(cached, run.reference)) return cached
            removeCachedRetainedRelease(digest)
            if (isExecutionCurrent(executionToken, target)) abandonRun(run, target)
            return null
        }
        return runCatching {
            check(isExecutionCurrent(executionToken, target))
            val pin = target.releasePin(digest) ?: error("missing retained release")
            check(isExecutionCurrent(executionToken, target))
            pinnedReleaseAuthenticator(pin, run.reference).takeIf {
                isExecutionCurrent(executionToken, target) &&
                    matches(it, run.reference)
            }?.also(::cacheRetainedRelease)
                ?: error("retained release identity mismatch")
        }.onFailure {
            Log.w(LOG_TAG, "Retained device-leg release rejected", it)
            if (isExecutionCurrent(executionToken, target)) abandonRun(run, target)
        }.getOrNull()
    }

    private fun cachedRetainedRelease(
        descriptorSha256: String,
    ): AuthenticatedDeviceLegRelease? {
        val release = retainedReleasesByDigest[descriptorSha256] ?: return null
        retainedReleaseOrder.removeAll { it == descriptorSha256 }
        retainedReleaseOrder += descriptorSha256
        return release
    }

    private fun cacheRetainedRelease(release: AuthenticatedDeviceLegRelease) {
        val descriptorSha256 = release.descriptorSha256
        val bytes = release.descriptorBytes.size
        if (bytes > RETAINED_RELEASE_CACHE_BYTE_LIMIT) return
        removeCachedRetainedRelease(descriptorSha256)
        while (retainedReleasesByDigest.size >= RETAINED_RELEASE_CACHE_COUNT_LIMIT ||
            retainedReleaseBytes + bytes > RETAINED_RELEASE_CACHE_BYTE_LIMIT
        ) {
            val oldest = retainedReleaseOrder.firstOrNull() ?: break
            removeCachedRetainedRelease(oldest)
        }
        retainedReleasesByDigest[descriptorSha256] = release
        retainedReleaseOrder += descriptorSha256
        retainedReleaseBytes += bytes
    }

    private fun removeCachedRetainedRelease(descriptorSha256: String) {
        retainedReleasesByDigest.remove(descriptorSha256)?.let {
            retainedReleaseBytes -= it.descriptorBytes.size
        }
        retainedReleaseOrder.removeAll { it == descriptorSha256 }
    }

    private fun clearRetainedReleaseCache() {
        retainedReleasesByDigest.clear()
        retainedReleaseOrder.clear()
        retainedReleaseBytes = 0
    }

    private fun matches(
        release: AuthenticatedDeviceLegRelease,
        reference: JsonObject,
    ): Boolean = release.descriptorSha256 == reference.text("descriptorSha256") &&
        release.identity.experienceId == reference.text("experienceId") &&
        release.identity.experienceVersionId == reference.text("versionId") &&
        release.leg.text("id") == reference.text("legId")

    private fun AuthenticatedDeviceLegRelease.hasScreens(): Boolean =
        leg.getValue("screens").jsonArray.isNotEmpty()

    private suspend fun execute(
        initial: DeviceLegRun,
        release: AuthenticatedDeviceLegRelease,
        state: ProfileState,
        executionToken: DeviceLegExecutionFenceToken,
        signal: DeviceLegControlExecutor.Signal,
        initialCheckpoint: DeviceLegControlExecutor.Checkpoint?,
        target: DeviceLegRunJournal,
        initialPresentationReservation: DeviceLegPresentationReservation? = null,
    ) {
        val leg = release.leg
        val steps = leg.getValue("steps").jsonArray.associate {
            val step = it.jsonObject
            step.getValue("id").jsonPrimitive.content to step
        }
        val appDefaultTimezone = (release.descriptor["metadata"] as? JsonObject)
            ?.text("appDefaultTimezone")
        val executor = DeviceLegControlExecutor(
            timezones,
            currentDeviceTimezoneIdentifier(),
            appDefaultTimezone,
        )
        var run = initial
        var checkpoint = initialCheckpoint
        var presentationReservation = initialPresentationReservation
        try {
            repeat(MAX_TRANSITIONS) {
                if (!isExecutionCurrent(executionToken, target)) {
                    finish(run, "abandoned", leg, target)
                    return
                }
                val step = steps[run.stepId] ?: run {
                    finish(run, "abandoned", leg, target)
                    return
                }
                when (val result = executor.evaluate(
                    step,
                    run.context,
                    state.snapshot.profile.facts.getValue("assignments").jsonObject,
                    nowMillis(),
                    checkpoint,
                    signal,
                )) {
                    is DeviceLegControlExecutor.Result.Advance -> {
                        target.transition(run.id, result.stepId, result.context)
                        run = run.copy(
                            stepId = result.stepId,
                            context = result.context,
                            park = null,
                        )
                        checkpoint = null
                    }
                    is DeviceLegControlExecutor.Result.Park -> {
                        target.transition(run.id, result.stepId, run.context, result.checkpoint)
                        scheduleNextWake()
                        return
                    }
                    is DeviceLegControlExecutor.Result.Complete -> {
                        finish(run, result.outcome, leg, target)
                        return
                    }
                    is DeviceLegControlExecutor.Result.Dispatch -> {
                        val identityScope = identity.captureScope()
                        if (identityScope.distinctId != target.distinctId) {
                            finish(run, "abandoned", leg, target)
                            return
                        }
                        val effectId = target.claimEffect(run.id, result.stepId)
                        val current: () -> Boolean = {
                            isExecutionCurrent(executionToken, target) &&
                                identity.isCurrentScope(identityScope)
                        }
                        if (!current()) {
                            finish(run, "abandoned", leg, target)
                            return
                        }
                        val presentation = presenter
                        if (presentation != null && result.action.text("type") == "navigate") {
                            val screenId = result.action.text("screenId") ?: run {
                                finish(run, "abandoned", leg, target)
                                return
                            }
                            // A null fresh reservation may still be valid when
                            // this same Journey already owns the visible surface;
                            // the presentation choke point decides that atomically.
                            val reserved = presentationReservation
                                ?: presentation.reserve(target.distinctId)
                            presentationReservation = null
                            val runId = run.id
                            val presentationResult = try {
                                presentation.present(
                                    DeviceLegPresentationRequest(
                                        release = release,
                                        delivery = state.snapshot.profile.delivery,
                                        screenId = screenId,
                                        journeyId = run.journeyId,
                                        ownerDistinctId = target.distinctId,
                                        reservation = reserved,
                                        canPresent = {
                                            foreground.get() && current()
                                        },
                                        nextBatchSequence = run.nextPresentationBatchSequence,
                                        nextEmissionSequence = run.nextPresentationEmissionSequence,
                                        onEmissionBatch = { batch ->
                                            handlePresentationBatch(
                                                runId = runId,
                                                expectedStepId = result.stepId,
                                                expectedScreenId = screenId,
                                                release = release,
                                                executionFenceToken = executionToken,
                                                batch = batch,
                                            )
                                        },
                                        onOutcome = { outcome ->
                                            submit { done ->
                                                Command.PresentationEnded(
                                                    runId = runId,
                                                    stepId = result.stepId,
                                                    screenId = screenId,
                                                    release = release,
                                                    executionFenceToken = executionToken,
                                                    outcome = outcome,
                                                    done = done,
                                                )
                                            }
                                        },
                                    ),
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                DeviceLegPresentationResult.Failed
                            } finally {
                                reserved?.close()
                            }
                            when (presentationResult) {
                                DeviceLegPresentationResult.Shown -> return
                                DeviceLegPresentationResult.Declined -> {
                                    target.park(run.id, result.stepId, nowMillis())
                                    scheduleNextWake()
                                    return
                                }
                                DeviceLegPresentationResult.Failed -> {
                                    finish(run, "abandoned", leg, target)
                                    return
                                }
                            }
                        }
                        val dispatched = dispatcher.dispatch(
                            DeviceLegDispatchRequest(
                                run,
                                release,
                                result.stepId,
                                result.action,
                                effectId,
                                target.distinctId,
                                identityScope,
                                executionFence,
                                executionToken,
                            ),
                        )
                        if (!current()) {
                            finish(run, "abandoned", leg, target)
                            return
                        }
                        when (dispatched) {
                            is DeviceLegDispatchResult.Outlet -> {
                                val advanced = executor.selectOutlet(
                                    step,
                                    dispatched.name,
                                    run.context,
                                ) as? DeviceLegControlExecutor.Result.Advance
                                if (advanced == null) {
                                    finish(run, "abandoned", leg, target)
                                    return
                                }
                                target.transition(run.id, advanced.stepId, advanced.context)
                                run = run.copy(
                                    stepId = advanced.stepId,
                                    context = advanced.context,
                                    park = null,
                                )
                                checkpoint = null
                            }
                            is DeviceLegDispatchResult.Complete -> {
                                finish(run, dispatched.outcome, leg, target)
                                return
                            }
                            DeviceLegDispatchResult.Unsupported -> {
                                finish(run, "abandoned", leg, target)
                                return
                            }
                            DeviceLegDispatchResult.Failed -> {
                                finish(run, "abandoned", leg, target)
                                return
                            }
                        }
                    }
                    DeviceLegControlExecutor.Result.Invalid -> {
                        finish(run, "abandoned", leg, target)
                        return
                    }
                }
            }
            finish(run, "abandoned", leg, target)
        } finally {
            presentationReservation?.close()
        }
    }

    private fun isExecutionCurrent(
        token: DeviceLegExecutionFenceToken,
        target: DeviceLegRunJournal,
    ): Boolean =
        executionFence.isCurrent(token) &&
            journal === target &&
            target.distinctId == identity.distinctId()

    private suspend fun finish(
        run: DeviceLegRun,
        outcome: String,
        leg: JsonObject,
        target: DeviceLegRunJournal,
    ) {
        val boundary = (leg.getValue("completionOutputs") as JsonObject)[outcome]
            as? JsonObject
        val projected = boundary?.let {
            DeviceLegBoundaryProjector.project(run.context, it)
        }
        val finalOutcome = if (boundary != null && projected == null) "abandoned" else outcome
        val responseOutputs = if (finalOutcome == "abandoned") {
            run.context.getValue("responses").jsonObject
        } else {
            projected?.getValue("responses")?.jsonObject ?: JsonObject(emptyMap())
        }
        target.complete(
            run.id,
            finalOutcome,
            nowMillis(),
            projected?.getValue("event")?.jsonObject ?: JsonObject(emptyMap()),
            responseOutputs,
        )
        reporter(target).flushPending()
        scheduleNextWake()
    }

    private suspend fun abandonRun(run: DeviceLegRun, target: DeviceLegRunJournal) {
        runCatching {
            target.complete(
                run.id,
                "abandoned",
                nowMillis(),
                responseOutputs = run.context.getValue("responses").jsonObject,
            )
            reporter(target).flushPending()
        }.onFailure { Log.w(LOG_TAG, "Device-leg abandonment failed", it) }
    }

    private fun executorSignal(event: StoredEvent?): DeviceLegControlExecutor.Signal =
        event?.let {
            DeviceLegControlExecutor.Signal(
                DeviceLegControlExecutor.Event(
                    it.name,
                    it.timestampMillis,
                    it.properties,
                ),
            )
        } ?: DeviceLegControlExecutor.Signal()

    private fun entryEvent(event: StoredEvent): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(event.name))
        put("timestamp", JsonPrimitive(event.timestampMillis / 1_000.0))
        put("distinctId", JsonPrimitive(event.distinctId))
        put("properties", event.properties)
    }

    private fun parseReentry(value: JsonObject): JourneyReentry? = when (value.text("type")) {
        "one_time" -> JourneyReentry.OneTime
        "every_time" -> JourneyReentry.EveryTime
        "once_per_window" -> JourneyReentry.OncePerWindow(
            Math.multiplyExact(value.getValue("windowSeconds").jsonPrimitive.long, 1_000L),
        )
        else -> null
    }

    private fun liveReentryPolicies(
        snapshot: DeviceLegProfileCatalog.Snapshot,
    ): Map<String, JourneyReentry> {
        val selected = linkedMapOf<String, ReentryCandidate>()
        for (arm in snapshot.profile.armedLegs) {
            val release = snapshot.releasesByDigest[arm.reference.text("descriptorSha256")]
                ?: continue
            val reentry = parseReentry(release.leg.getValue("reentry").jsonObject)
                ?: continue
            val candidate = ReentryCandidate(
                enrollment = arm.binding.text("type") == "new",
                release = release,
                reentry = reentry,
            )
            val experienceId = release.identity.experienceId
            val current = selected[experienceId]
            if (current == null || candidate.outranks(current)) {
                selected[experienceId] = candidate
            }
        }
        return selected.mapValues { it.value.reentry }
    }

    private fun ReentryCandidate.outranks(other: ReentryCandidate): Boolean {
        if (enrollment != other.enrollment) return enrollment
        val identity = release.identity
        val otherIdentity = other.release.identity
        return compareValuesBy(
            this,
            other,
            { it.release.identity.publishedAtSeq },
            { it.release.identity.versionNumber },
            { it.release.identity.publishedAt },
            { it.release.identity.experienceVersionId },
            { it.release.identity.buildId },
            { it.release.descriptorSha256 },
        ) > 0 && identity.experienceId == otherIdentity.experienceId
    }

    private suspend fun scheduleNextWake() {
        cancelWake()
        if (!foreground.get() || currentState() == null) return
        val next = journal?.runs()?.asSequence()
            ?.filter { it.completion == null }
            ?.mapNotNull { it.park?.wakeAtMillis }
            ?.filter { it > nowMillis() }
            ?.minOrNull() ?: return
        wakeGeneration += 1
        val generation = wakeGeneration
        wakeJob = scope.launch {
            delay((next - nowMillis()).coerceAtLeast(0L))
            commands.trySend(Command.Wake(generation, next))
        }
    }

    private suspend fun wakeNow(generation: Long, deadlineMillis: Long) {
        if (generation != wakeGeneration || !foreground.get() ||
            nowMillis() < deadlineMillis
        ) return
        wakeJob = null
        val state = currentState() ?: return
        resumeParkedRuns(state, null)
        scheduleNextWake()
    }

    private fun cancelWake() {
        wakeGeneration += 1
        wakeJob?.cancel()
        wakeJob = null
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val HOST_DISMISSED_EVENT = "host_dismissed"
        const val HOST_DISMISSED_OUTCOME = "host_dismissed"
        const val RESPONSE_SET_EVENT = "\$response_set"
        const val RESPONSE_UNSET_EVENT = "\$response_unset"
        const val MAX_TRANSITIONS = 10_000
        const val RETAINED_RELEASE_CACHE_BYTE_LIMIT = 64 * 1024 * 1024
        const val RETAINED_RELEASE_CACHE_COUNT_LIMIT = 256
    }
}

/** Exact boundary projection for signed leg inputs and completion outputs. */
private object DeviceLegBoundaryProjector {
    fun inputContext(
        arm: JourneyPlaneProfile.Arm,
        event: StoredEvent?,
        boundary: JsonObject,
    ): JsonObject? {
        val values = arm.context.getValue("event").jsonObject.toMutableMap()
        if (event != null) {
            for (field in boundary.getValue("eventFields").jsonArray) {
                val key = field.jsonObject.text("key") ?: return null
                event.properties[key]?.let { values[key] = it }
            }
        }
        val projectedEvent = project(
            JsonObject(values),
            boundary.getValue("eventFields").jsonArray,
            response = false,
        ) ?: return null
        val projectedResponses = project(
            arm.context.getValue("responses").jsonObject,
            boundary.getValue("responseFields").jsonArray,
            response = true,
        ) ?: return null
        return JsonObject(
            mapOf("event" to projectedEvent, "responses" to projectedResponses),
        )
    }

    fun project(context: JsonObject, boundary: JsonObject): JsonObject? {
        val event = project(
            context.getValue("event").jsonObject,
            boundary.getValue("eventFields").jsonArray,
            response = false,
        ) ?: return null
        val responses = project(
            context.getValue("responses").jsonObject,
            boundary.getValue("responseFields").jsonArray,
            response = true,
        ) ?: return null
        return JsonObject(mapOf("event" to event, "responses" to responses))
    }

    private fun project(
        values: JsonObject,
        fields: JsonArray,
        response: Boolean,
    ): JsonObject? {
        val result = linkedMapOf<String, JsonElement>()
        for (value in fields) {
            val field = value as? JsonObject ?: return null
            val key = field.text("key") ?: return null
            val type = field.text("type") ?: return null
            val required = field["required"]?.jsonPrimitive?.booleanOrNull ?: return null
            val current = values[key]
            if (current == null) {
                if (required) return null
                continue
            }
            if (!valid(current, type, field, response)) return null
            result[key] = current
        }
        return JsonObject(result)
    }

    private fun valid(
        value: JsonElement,
        type: String,
        field: JsonObject,
        response: Boolean,
    ): Boolean = when {
        type == "number" && value is JsonPrimitive && !value.isString -> {
            val number = value.doubleOrNull ?: return false
            val minimum = field["min"]?.jsonPrimitive?.doubleOrNull
            val maximum = field["max"]?.jsonPrimitive?.doubleOrNull
            number.isFinite() && (minimum == null || number >= minimum) &&
                (maximum == null || number <= maximum)
        }
        type == "string" && !response && value is JsonPrimitive && value.isString ->
            options(field["enum"])?.contains(value.content) ?: true
        type in setOf("text", "date") && response && value is JsonPrimitive && value.isString -> true
        type == "enum" && response && value is JsonPrimitive && value.isString ->
            options(field["options"])?.contains(value.content) == true
        type == "multi_enum" && response && value is JsonArray -> {
            val selected = value.mapNotNull {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            }
            val allowed = options(field["options"]) ?: return false
            selected.size == value.size && selected.size == selected.toSet().size &&
                selected.all(allowed::contains)
        }
        type == "boolean" && value is JsonPrimitive && value.booleanOrNull != null -> true
        type == "null" && !response && value == JsonNull -> true
        type == "json" && !response -> true
        else -> false
    }

    private fun options(value: JsonElement?): Set<String>? = (value as? JsonArray)?.map {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null
    }?.toSet()
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
