package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.AuthenticatedDeviceLegRelease
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.Delivery
import ai.nuxie.sdk.experiences.ReleaseArtifactAcquirer
import ai.nuxie.sdk.billing.ExperiencePurchasePreparer
import ai.nuxie.sdk.billing.ExperiencePurchaseSession
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieViewModelListProjection
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The authenticated catalog entry consumed by presentation. */
internal data class PresentationRelease(
    val release: AuthenticatedRelease,
    val delivery: Delivery,
)

/** Resolves only releases that passed profile authentication and admission. */
internal fun interface PresentationReleaseProvider {
    fun releaseFor(experienceVersionId: String): PresentationRelease?
}

/** Kotlin analog of the iOS presentation close-reason set. */
internal sealed interface CloseReason {
    data object UserDismissed : CloseReason
    data object HostDismissed : CloseReason
    /** Physical replacement while the same Journey retains surface ownership. */
    data object JourneyNavigation : CloseReason
    data object IdentityChanged : CloseReason
    data object GoalMet : CloseReason
    data object PurchaseCompleted : CloseReason
    data object Timeout : CloseReason
    data class AuthenticatedExit(val exitReason: JourneyExitReason) : CloseReason
    data class Error(val cause: Throwable) : CloseReason
}

internal class ExperiencePresentationException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        RUNTIME_UNAVAILABLE,
        RELEASE_NOT_FOUND,
        ACQUISITION_FAILED,
        PREPARATION_FAILED,
        HOST_FAILED,
        JOURNEY_COMPLETED,
        FIRST_FRAME_TIMEOUT,
        SUPERSEDED,
        DECLINED,
    }
}

internal data class PresentationOutcome(
    val ref: ExperienceRef,
    val reason: CloseReason,
    val ownerDistinctId: String? = null,
    val initiatingDistinctId: String? = null,
)

/** Prepared content remains service-owned; only its opaque id crosses the Activity boundary. */
internal data class PreparedPresentation(
    val rivFile: File,
    val artboardName: String?,
    val clearColor: Int,
    val shell: PresentationShell,
    val screenId: String? = null,
    val descriptor: JsonObject? = null,
    val artifactsByKey: Map<String, File> = emptyMap(),
    val artboardSize: ExperienceArtboardSize? = null,
    val commerce: ExperiencePurchaseSession? = null,
    val viewModelProjection: NuxieViewModelListProjection? = null,
) {
    private val screenControlDispatcher = ExperienceScreenControlDispatcher(descriptor, screenId)

    /** Deliver only runtime events emitted by this authenticated presentation. */
    fun handleRuntimeEvent(
        activity: Activity,
        event: NuxieRuntimeEvent,
        viewModelSnapshot: NuxieViewModelSnapshot? = null,
    ) {
        val activeScreenId = screenId ?: return
        screenControlDispatcher.dispatch(event).forEach { routedEvent ->
            commerce?.handle(activity, activeScreenId, routedEvent, viewModelSnapshot)
        }
    }
}

internal sealed interface PresentationShell {
    val dismissible: Boolean

    data object FullScreen : PresentationShell {
        override val dismissible: Boolean = true
    }

    data class Sheet(
        val detent: Detent,
        override val dismissible: Boolean,
    ) : PresentationShell {
        enum class Detent { MEDIUM, LARGE }
    }

    data class Drawer(
        val edge: Edge,
        val extentRatio: Float,
        val cornerRadiusDp: Float,
        override val dismissible: Boolean,
    ) : PresentationShell {
        enum class Edge { TOP, BOTTOM, LEADING, TRAILING }
    }
}

/** Physical close operations coordinated with the attached host Activity. */
internal interface PresentationActivityHandle {
    fun requestCloseFromService(reason: CloseReason): Boolean

    fun screenCloseReason(): CloseReason?

    fun finishAfterServiceClose()
}

/**
 * Process-local handoff between [ExperiencePresentationService] and the one
 * engine-owned Activity. Absence after process death is deliberate: content
 * is never reconstructed from Intent extras.
 */
internal object PresentationRegistry {
    private class Entry(
        val content: PreparedPresentation,
        val onFirstFrame: () -> Unit,
        val onFailure: (Throwable) -> Unit,
        val onDismissed: (CloseReason) -> Unit,
        val onOutcome: (CloseReason) -> Unit,
        val onRuntimeStep: (NuxiePlayerStepOutcome, ULong) -> Unit,
    ) {
        val terminal = AtomicBoolean(false)
        val firstFrame = AtomicBoolean(false)
        var latestActivity = WeakReference<PresentationActivityHandle>(null)
        // Configuration recreation changes the dismissal target, but every
        // instance remains pending until its runtime lane calls detach.
        val attachedActivities: MutableSet<PresentationActivityHandle> =
            Collections.newSetFromMap(IdentityHashMap())
        var dismissalReason: CloseReason? = null
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun register(
        id: String,
        content: PreparedPresentation,
        onFirstFrame: () -> Unit,
        onFailure: (Throwable) -> Unit,
        onDismissed: (CloseReason) -> Unit,
        onOutcome: (CloseReason) -> Unit,
        onRuntimeStep: (NuxiePlayerStepOutcome, ULong) -> Unit = { _, _ -> },
    ) {
        synchronized(lock) {
            check(id !in entries) { "duplicate presentation id" }
            entries[id] = Entry(
                content,
                onFirstFrame,
                onFailure,
                onDismissed,
                onOutcome,
                onRuntimeStep,
            )
        }
    }

    fun resolve(id: String): PreparedPresentation? = synchronized(lock) { entries[id]?.content }

    fun attach(id: String, activity: PresentationActivityHandle): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        if (entry.dismissalReason != null) return@synchronized false
        entry.attachedActivities += activity
        entry.latestActivity = WeakReference(activity)
        true
    }

    fun detach(id: String, activity: PresentationActivityHandle) {
        val completion = synchronized(lock) {
            val entry = entries[id] ?: return
            entry.attachedActivities.remove(activity)
            if (entry.latestActivity.get() === activity) {
                entry.latestActivity = WeakReference(null)
            }
            if (entry.dismissalReason == null) {
                entry.dismissalReason = activity.screenCloseReason()
            }
            completionIfReady(id, entry)
        }
        completion?.invoke()
    }

    fun reportFirstFrame(id: String) {
        val callback = synchronized(lock) {
            val entry = entries[id] ?: return
            if (entry.terminal.get() || !entry.firstFrame.compareAndSet(false, true)) return
            entry.onFirstFrame
        }
        callback()
    }

    fun reportFailure(id: String, error: Throwable) {
        val outcome = synchronized(lock) { entries[id]?.onOutcome }
        dismiss(id, CloseReason.Error(error))
        outcome?.invoke(CloseReason.Error(error))
    }

    fun reportDismissed(id: String, reason: CloseReason) {
        var outcome: ((CloseReason) -> Unit)? = null
        val completion = synchronized(lock) {
            val entry = entries[id] ?: return
            outcome = entry.onOutcome
            if (entry.dismissalReason == null) entry.dismissalReason = reason
            completionIfReady(id, entry)
        }
        completion?.invoke()
        outcome?.invoke(reason)
    }

    fun reportOutcome(id: String, reason: CloseReason) {
        val callback = synchronized(lock) { entries[id]?.onOutcome } ?: return
        callback(reason)
    }

    fun reportRuntimeStep(id: String, outcome: NuxiePlayerStepOutcome, correlationId: ULong) {
        val callback = synchronized(lock) {
            entries[id]?.takeUnless { it.terminal.get() }?.onRuntimeStep
        } ?: return
        callback(outcome, correlationId)
    }

    fun dismiss(id: String, reason: CloseReason) {
        var completion: (() -> Unit)? = null
        val activityToFinish = synchronized(lock) {
            val entry = entries[id] ?: return
            val attached = entry.latestActivity.get()
            val selected = entry.dismissalReason
            when {
                selected != null -> {
                    if (attached?.screenCloseReason() == selected) attached else null
                }
                attached == null -> {
                    entry.dismissalReason = reason
                    completion = completionIfReady(id, entry)
                    null
                }
                attached.requestCloseFromService(reason) || attached.screenCloseReason() == reason -> {
                    entry.dismissalReason = reason
                    attached
                }
                else -> null
            }
        }
        completion?.invoke()
        activityToFinish?.finishAfterServiceClose()
    }

    private fun completionIfReady(id: String, entry: Entry): (() -> Unit)? {
        val reason = entry.dismissalReason ?: return null
        if (entry.attachedActivities.isNotEmpty()) return null
        entries.remove(id)
        if (!entry.terminal.compareAndSet(false, true)) return null
        return when (reason) {
            is CloseReason.Error -> ({ entry.onFailure(reason.cause) })
            else -> ({ entry.onDismissed(reason) })
        }
    }

    internal fun clearForTesting() {
        synchronized(lock) { entries.clear() }
    }
}

/** Trigger-to-first-frame presentation authority. */
internal class ExperiencePresentationService(
    private val releases: PresentationReleaseProvider,
    private val acquire: suspend (PresentationRelease) -> AcquiredRelease,
    private val emit: (String, Map<String, Any?>, String?) -> Unit,
    private val scope: CoroutineScope,
    private val runtimeAvailable: () -> Boolean,
    private val launch: (String) -> Unit,
    private val commerce: ExperiencePurchasePreparer = ExperiencePurchasePreparer.NONE,
    private val openLink: (String, String?) -> Unit = { _, _ -> },
    private val transitionOutcome: suspend (PresentationOutcome) -> Boolean = { true },
    private val reportOutcome: suspend (PresentationOutcome) -> Unit = {},
    private val firstFrameTimeoutMillis: Long = FIRST_FRAME_TIMEOUT_MILLIS,
    private val beforeHostTeardownForTesting: () -> Unit = {},
) {
    constructor(
        context: Context,
        releases: PresentationReleaseProvider,
        acquirer: ReleaseArtifactAcquirer,
        emit: (String, Map<String, Any?>, String?) -> Unit,
        scope: CoroutineScope,
        runtimeAvailable: () -> Boolean,
        commerce: ExperiencePurchasePreparer = ExperiencePurchasePreparer.NONE,
        transitionOutcome: suspend (PresentationOutcome) -> Boolean = { true },
        reportOutcome: suspend (PresentationOutcome) -> Unit = {},
    ) : this(
        releases = releases,
        acquire = { admitted -> acquirer.acquire(admitted.release, admitted.delivery) },
        emit = emit,
        scope = scope,
        runtimeAvailable = runtimeAvailable,
        launch = AndroidPresentationLauncher(context.applicationContext ?: context),
        commerce = commerce,
        openLink = { url, _ ->
            (context.applicationContext ?: context).startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
        transitionOutcome = transitionOutcome,
        reportOutcome = reportOutcome,
        firstFrameTimeoutMillis = FIRST_FRAME_TIMEOUT_MILLIS,
    )

    private data class ActivePresentation(
        val id: String,
        val ref: ExperienceRef,
        val acquired: AcquiredRelease,
        val commerce: ExperiencePurchaseSession?,
        val ownerDistinctId: String?,
        val outcomeSink: OutcomeSink,
        val firstFrame: CompletableDeferred<ExperienceRef>,
        val closed: AtomicBoolean = AtomicBoolean(false),
        val shown: AtomicBoolean = AtomicBoolean(false),
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
        // Shown and the run-terminal observation are one fact-pairing decision.
        val factLock: Any = Any(),
        // Every close path observes the same first-terminal run transition.
        val runTransitionFinished: CompletableDeferred<Unit> = CompletableDeferred(),
        val outcomeStarted: AtomicBoolean = AtomicBoolean(false),
    )

    private sealed interface OutcomeSink {
        data object Legacy : OutcomeSink
        class DeviceLeg(
            val screenId: String,
            val onOutcome: suspend (DeviceLegSurfaceOutcome) -> Unit,
            val onScreenDismissed: suspend (
                String,
                String?,
                String,
            ) -> DeviceLegScreenDismissalResult,
            val emissions: DeviceLegRuntimeEmissionCoordinator,
            val screenDismissed: AtomicBoolean = AtomicBoolean(false),
        ) : OutcomeSink
    }

    private data class PreparedSource(
        val identity: ai.nuxie.sdk.experiences.ExperienceReleaseIdentity,
        val descriptor: JsonObject,
        val acquired: AcquiredRelease,
        val artboardName: String?,
        val screenId: String? = null,
        val artboardSize: ExperienceArtboardSize? = null,
        val commerce: ExperiencePurchaseSession? = null,
        val viewModelProjection: NuxieViewModelListProjection? = null,
    )

    private data class PendingReservation(
        val id: String,
        val request: PresentationRequest,
    )

    private inner class DeviceLegReservation(
        val id: String,
        val request: PresentationRequest,
    ) : DeviceLegPresentationReservation {
        override fun close() {
            synchronized(stateLock) {
                if (pendingReservation?.id == id) pendingReservation = null
            }
        }
    }

    private val presentationMutex = Mutex()
    private val stateLock = Any()
    private var current: ActivePresentation? = null
    private var pendingReservation: PendingReservation? = null
    private var transitionInProgress = false
    private val identityEpochByOwner = mutableMapOf<String, Long>()

    private data class PresentationRequest(
        val ownerDistinctId: String?,
        val identityEpoch: Long,
    )

    private fun captureRequest(ownerDistinctId: String?): PresentationRequest =
        synchronized(stateLock) {
            captureRequestLocked(ownerDistinctId)
        }

    private fun captureRequestLocked(ownerDistinctId: String?): PresentationRequest =
        PresentationRequest(
            ownerDistinctId = ownerDistinctId,
            identityEpoch = ownerDistinctId?.let { identityEpochByOwner[it] } ?: 0L,
        )

    private fun isCurrentIdentity(request: PresentationRequest): Boolean =
        request.ownerDistinctId == null ||
            (identityEpochByOwner[request.ownerDistinctId] ?: 0L) == request.identityEpoch

    suspend fun present(
        experienceVersionId: String,
        journeyId: String? = null,
        ownerDistinctId: String? = null,
    ): ExperienceRef = presentPrepared(
        request = captureRequest(ownerDistinctId),
        journeyId = journeyId,
        reservationId = null,
        reservationRequired = false,
        outcomeSink = OutcomeSink.Legacy,
    ) {
        val admitted = releases.releaseFor(experienceVersionId)
            ?: throw ExperiencePresentationException(
                ExperiencePresentationException.Reason.RELEASE_NOT_FOUND,
                "Authenticated Experience release not found: $experienceVersionId",
            )
        val acquired = acquire(admitted)
        var purchasePrepared = false
        val purchaseSession = try {
            try {
                commerce.prepare(
                    admitted.release,
                    journeyId,
                    ownerDistinctId,
                ).also { purchasePrepared = true }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                throw ExperiencePresentationException(
                    ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Experience commerce preparation failed: " +
                        (error.message ?: "unknown error"),
                    error,
                )
            }
        } finally {
            if (!purchasePrepared) acquired.close()
        }
        val screenId = admitted.release.defaultScreenId()
        val viewModelProjection = try {
            GooglePlayProductViewModelProjection.prepare(
                descriptor = admitted.release.descriptor,
                products = purchaseSession?.resolvedProducts().orEmpty(),
                screenId = screenId,
            )
        } catch (error: Throwable) {
            purchaseSession?.retire()
            acquired.close()
            if (error is CancellationException) throw error
            throw ExperiencePresentationException(
                ExperiencePresentationException.Reason.PREPARATION_FAILED,
                "Experience commerce display preparation failed: " +
                    (error.message ?: "unknown error"),
                error,
            )
        }
        PreparedSource(
            identity = admitted.release.identity,
            descriptor = admitted.release.descriptor,
            acquired = acquired,
            artboardName = admitted.release.defaultArtboardName(),
            screenId = screenId.takeIf { purchaseSession != null },
            artboardSize = admitted.release.defaultArtboardSize(),
            commerce = purchaseSession,
            viewModelProjection = viewModelProjection,
        )
    }

    /**
     * Claims the surface before durable device-leg admission. A failed claim
     * leaves the signed arm untouched so the same trigger can fire later.
     */
    fun reserveDeviceLeg(ownerDistinctId: String): DeviceLegPresentationReservation? =
        synchronized(stateLock) {
            if (current != null || pendingReservation != null || transitionInProgress) {
                return@synchronized null
            }
            val request = captureRequestLocked(ownerDistinctId)
            if (!isCurrentIdentity(request)) return@synchronized null
            val id = UUID.randomUUID().toString()
            pendingReservation = PendingReservation(id, request)
            DeviceLegReservation(id, request)
        }

    suspend fun presentDeviceLeg(
        release: AuthenticatedDeviceLegRelease,
        screenId: String,
        journeyId: String,
        ownerDistinctId: String,
        reservation: DeviceLegPresentationReservation?,
        canPresent: () -> Boolean = { true },
        acquire: suspend () -> AcquiredRelease,
        nextBatchSequence: Long = 0,
        nextEmissionSequence: Long = 0,
        onScreenChanged: suspend (String) -> Boolean = { true },
        onScreenDismissed: suspend (
            String,
            String?,
            String,
        ) -> DeviceLegScreenDismissalResult = { _, _, _ ->
            DeviceLegScreenDismissalResult.HANDLED
        },
        onEmissionBatch: suspend (DeviceLegScreenEmissionBatch) -> Boolean = { true },
        onPresentationRevealed: suspend (String) -> Unit = {},
        onOutcome: suspend (DeviceLegSurfaceOutcome) -> Unit,
    ): ExperienceRef {
        val reserved = reservation as? DeviceLegReservation
        val request = reserved?.request ?: captureRequest(ownerDistinctId)
        if (request.ownerDistinctId != ownerDistinctId) throw declinedPresentation()
        return presentPrepared(
            request = request,
            journeyId = journeyId,
            reservationId = reserved?.id,
            reservationRequired = true,
            outcomeSink = OutcomeSink.DeviceLeg(
                screenId = screenId,
                onOutcome = onOutcome,
                onScreenDismissed = onScreenDismissed,
                emissions = DeviceLegRuntimeEmissionCoordinator(
                    journeyId = journeyId,
                    screenId = screenId,
                    descriptor = release.descriptor,
                    nextBatchSequence = nextBatchSequence,
                    nextEmissionSequence = nextEmissionSequence,
                    onEmissionBatch = onEmissionBatch,
                    onScreenChanged = onScreenChanged,
                    onPresentationRevealed = onPresentationRevealed,
                    onOpenLink = openLink,
                ),
            ),
            canPresent = canPresent,
        ) {
            val artboardName = release.descriptor.artboardName(screenId)
                ?: throw ExperiencePresentationException(
                    ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Authenticated device-leg screen is not renderable: $screenId",
                )
            PreparedSource(
                identity = release.identity,
                descriptor = release.descriptor,
                acquired = acquire(),
                artboardName = artboardName,
                screenId = screenId,
                artboardSize = release.descriptor.artboardSize(screenId),
            )
        }
    }

    private suspend fun presentPrepared(
        request: PresentationRequest,
        journeyId: String?,
        reservationId: String?,
        reservationRequired: Boolean,
        outcomeSink: OutcomeSink,
        canPresent: () -> Boolean = { true },
        prepare: suspend () -> PreparedSource,
    ): ExperienceRef {
        val active = presentationMutex.withLock {
            var transitionClaimed = false
            var published = false
            try {
                val existing = synchronized(stateLock) {
                    if (!isCurrentIdentity(request) || !canPresent()) {
                        throw supersededByIdentityTransition()
                    }
                    val active = current
                    if (active != null &&
                        !active.isOwnedBy(journeyId, request.ownerDistinctId)
                    ) {
                        throw declinedPresentation()
                    }
                    val reservationMatches = reservationId != null &&
                        pendingReservation?.id == reservationId &&
                        pendingReservation?.request == request
                    if (active == null && reservationRequired && !reservationMatches) {
                        throw declinedPresentation()
                    }
                    if (active == null && !reservationRequired && pendingReservation != null) {
                        throw declinedPresentation()
                    }
                    if (transitionInProgress) throw declinedPresentation()
                    transitionInProgress = true
                    transitionClaimed = true
                    active
                }
                if (!runtimeAvailable()) {
                    throw ExperiencePresentationException(
                        ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE,
                        "Experience renderer is unavailable on this device",
                    )
                }

                existing?.let {
                    val outgoing = it.outcomeSink as? OutcomeSink.DeviceLeg
                    val incoming = outcomeSink as? OutcomeSink.DeviceLeg
                    val dismissal = if (outgoing != null && incoming != null &&
                        outgoing.screenDismissed.compareAndSet(false, true)
                    ) {
                        try {
                            outgoing.onScreenDismissed(
                                outgoing.screenId,
                                incoming.screenId,
                                "navigate",
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            DeviceLegScreenDismissalResult.REJECTED
                        }
                    } else null
                    PresentationRegistry.dismiss(it.id, CloseReason.JourneyNavigation)
                    attemptOutcome(
                        it,
                        PresentationOutcome(
                            ref = it.ref,
                            reason = CloseReason.JourneyNavigation,
                            ownerDistinctId = it.ownerDistinctId,
                        ),
                    )
                    it.finished.await()
                    when (dismissal) {
                        DeviceLegScreenDismissalResult.COMPLETED ->
                            throw ExperiencePresentationException(
                                ExperiencePresentationException.Reason.JOURNEY_COMPLETED,
                                "Journey completed while dismissing its previous screen",
                            )
                        DeviceLegScreenDismissalResult.REJECTED ->
                            throw ExperiencePresentationException(
                                ExperiencePresentationException.Reason.HOST_FAILED,
                                "Journey screen dismissal was rejected",
                            )
                        DeviceLegScreenDismissalResult.HANDLED, null -> Unit
                    }
                }

                val source = try {
                    prepare()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error is ExperiencePresentationException) throw error
                    throw ExperiencePresentationException(
                        ExperiencePresentationException.Reason.ACQUISITION_FAILED,
                        "Experience artifact acquisition failed: ${error.message ?: "unknown error"}",
                        error,
                    )
                }
                val ref = ExperienceRef(
                    source.identity.experienceId,
                    source.identity.experienceVersionId,
                    journeyId,
                )
                val id = UUID.randomUUID().toString()
                val pending = ActivePresentation(
                    id = id,
                    ref = ref,
                    acquired = source.acquired,
                    commerce = source.commerce,
                    ownerDistinctId = request.ownerDistinctId,
                    outcomeSink = outcomeSink,
                    firstFrame = CompletableDeferred(),
                )
                val launched = synchronized(stateLock) {
                    val reservationStillMatches = reservationId != null &&
                        pendingReservation?.id == reservationId &&
                        pendingReservation?.request == request
                    if (!isCurrentIdentity(request) || !canPresent() ||
                        (existing == null && reservationRequired && !reservationStillMatches)
                    ) {
                        false
                    } else {
                        // Identity shutdown and late launch admission share this
                        // transition: teardown either invalidates the epoch first
                        // or observes a fully registered presentation afterward.
                        if (reservationStillMatches) pendingReservation = null
                        current = pending
                        PresentationRegistry.register(
                            id = id,
                            content = PreparedPresentation(
                                rivFile = source.acquired.rivFile,
                                artboardName = source.artboardName,
                                screenId = source.screenId,
                                clearColor = source.descriptor.presentationClearColor(),
                                shell = source.descriptor.presentationShell(),
                                descriptor = source.descriptor,
                                artifactsByKey = source.acquired.artifactsByKey,
                                artboardSize = source.artboardSize,
                                commerce = source.commerce,
                                viewModelProjection = source.viewModelProjection,
                            ),
                            onFirstFrame = { firstFrame(pending) },
                            onFailure = { error -> failed(pending, error) },
                            onDismissed = { reason -> ended(pending, reason) },
                            onOutcome = { reason ->
                                attemptOutcome(
                                    pending,
                                    PresentationOutcome(
                                        ref = pending.ref,
                                        reason = reason,
                                        ownerDistinctId = pending.ownerDistinctId,
                                    ),
                                )
                            },
                            onRuntimeStep = { outcome, correlationId ->
                                runtimeStep(pending, outcome, correlationId)
                            },
                        )
                        try {
                            launch(id)
                        } catch (error: Throwable) {
                            PresentationRegistry.reportFailure(id, error)
                        }
                        published = true
                        true
                    }
                }
                if (!launched) {
                    source.commerce?.retire()
                    source.acquired.close()
                    throw supersededByIdentityTransition()
                }
                pending
            } finally {
                if (transitionClaimed) {
                    synchronized(stateLock) {
                        transitionInProgress = false
                        if (!published && reservationId != null &&
                            pendingReservation?.id == reservationId
                        ) {
                            pendingReservation = null
                        }
                    }
                }
            }
        }
        return try {
            withTimeout(firstFrameTimeoutMillis) { active.firstFrame.await() }
        } catch (_: TimeoutCancellationException) {
            val timeout = ExperiencePresentationException(
                ExperiencePresentationException.Reason.FIRST_FRAME_TIMEOUT,
                "Experience presentation did not attach and render its first frame in time",
            )
            PresentationRegistry.reportFailure(active.id, timeout)
            throw timeout
        }
    }

    fun dismiss(reason: CloseReason = CloseReason.UserDismissed) {
        synchronized(stateLock) { current }?.let { active ->
            PresentationRegistry.dismiss(active.id, reason)
            attemptOutcome(
                active,
                PresentationOutcome(
                    ref = active.ref,
                    reason = reason,
                    ownerDistinctId = active.ownerDistinctId,
                ),
            )
        }
    }

    suspend fun dismissFromHost(initiatingDistinctId: String) {
        val active = synchronized(stateLock) { current } ?: return
        val teardownReason = if (active.ownerDistinctId == initiatingDistinctId) {
            CloseReason.HostDismissed
        } else {
            CloseReason.IdentityChanged
        }
        // Screen teardown is unconditional and starts before outcome work.
        beforeHostTeardownForTesting()
        PresentationRegistry.dismiss(active.id, teardownReason)
        attemptOutcome(
            active,
            PresentationOutcome(
                ref = active.ref,
                reason = teardownReason,
                ownerDistinctId = active.ownerDistinctId,
                initiatingDistinctId = initiatingDistinctId,
            ),
        )
        joinAll(active.finished, active.runTransitionFinished)
    }

    /**
     * Tears down a presentation owned by the departing customer and attempts
     * an owner-attributed identity terminal transition without a close fact.
     */
    suspend fun shutdownOwnedBy(ownerDistinctId: String) {
        synchronized(stateLock) {
            identityEpochByOwner[ownerDistinctId] =
                (identityEpochByOwner[ownerDistinctId] ?: 0L) + 1L
            if (pendingReservation?.request?.ownerDistinctId == ownerDistinctId) {
                pendingReservation = null
            }
        }
        val active = synchronized(stateLock) {
            current?.takeIf { it.ownerDistinctId == ownerDistinctId }
        } ?: return
        PresentationRegistry.dismiss(active.id, CloseReason.IdentityChanged)
        attemptOutcome(
            active,
            PresentationOutcome(
                ref = active.ref,
                reason = CloseReason.IdentityChanged,
                ownerDistinctId = active.ownerDistinctId,
                initiatingDistinctId = ownerDistinctId,
            ),
        )
        joinAll(active.finished, active.runTransitionFinished)
    }

    /** Closes only the terminal Journey surface without injecting another outcome. */
    suspend fun shutdownDeviceLeg(ownerDistinctId: String, journeyId: String) {
        val active = synchronized(stateLock) {
            current?.takeIf { it.isOwnedBy(journeyId, ownerDistinctId) }
        } ?: return
        PresentationRegistry.dismiss(active.id, CloseReason.JourneyNavigation)
        attemptOutcome(
            active,
            PresentationOutcome(
                ref = active.ref,
                reason = CloseReason.JourneyNavigation,
                ownerDistinctId = active.ownerDistinctId,
            ),
        )
        joinAll(active.finished, active.runTransitionFinished)
    }

    fun close() = dismiss(CloseReason.UserDismissed)

    private fun supersededByIdentityTransition() = ExperiencePresentationException(
        ExperiencePresentationException.Reason.SUPERSEDED,
        "Experience presentation was superseded by an identity transition",
    )

    private fun declinedPresentation() = ExperiencePresentationException(
        ExperiencePresentationException.Reason.DECLINED,
        "Another Journey owns the presentation surface",
    )

    private fun ActivePresentation.isOwnedBy(
        journeyId: String?,
        ownerDistinctId: String?,
    ): Boolean =
        journeyId != null &&
            ref.journeyId == journeyId &&
            this.ownerDistinctId == ownerDistinctId

    private fun firstFrame(active: ActivePresentation) {
        val deviceLeg = active.outcomeSink as? OutcomeSink.DeviceLeg
        if (deviceLeg == null) {
            if (markShown(active)) active.firstFrame.complete(active.ref)
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (deviceLeg.emissions.reveal() && markShown(active)) {
                active.firstFrame.complete(active.ref)
            } else if (!active.closed.get()) {
                PresentationRegistry.reportFailure(
                    active.id,
                    ExperiencePresentationException(
                        ExperiencePresentationException.Reason.HOST_FAILED,
                        "Journey presentation reveal was rejected",
                    ),
                )
            }
        }
    }

    private fun markShown(active: ActivePresentation): Boolean =
        synchronized(active.factLock) {
            if (
                active.closed.get() ||
                active.runTransitionFinished.isCompleted ||
                !active.shown.compareAndSet(false, true)
            ) return@synchronized false
            runCatching {
                emit(
                    SystemEventNames.EXPERIENCE_SHOWN,
                    mapOf(
                        "journey_id" to active.ref.journeyId,
                        "experience_id" to active.ref.experienceId,
                        "experience_version" to active.ref.experienceVersion,
                    ),
                    active.ownerDistinctId,
                )
            }
            true
        }

    private fun runtimeStep(
        active: ActivePresentation,
        outcome: NuxiePlayerStepOutcome,
        correlationId: ULong,
    ) {
        val deviceLeg = active.outcomeSink as? OutcomeSink.DeviceLeg ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val accepted = runCatching {
                deviceLeg.emissions.publish(outcome, correlationId)
            }.getOrDefault(false)
            if (!accepted && !active.closed.get()) {
                PresentationRegistry.reportFailure(
                    active.id,
                    ExperiencePresentationException(
                        ExperiencePresentationException.Reason.HOST_FAILED,
                        "Journey renderer effects crossed a stale publication boundary",
                    ),
                )
            }
        }
    }

    private fun failed(active: ActivePresentation, error: Throwable) {
        if (!active.closed.compareAndSet(false, true)) return
        clearCurrent(active)
        active.commerce?.retire()
        active.acquired.close()
        active.finished.complete(Unit)
        val typed = if (error is ExperiencePresentationException) error else {
            ExperiencePresentationException(
                ExperiencePresentationException.Reason.HOST_FAILED,
                "Experience presentation host failed: ${error.message ?: "unknown error"}",
                error,
            )
        }
        active.firstFrame.completeExceptionally(typed)
        emitStandaloneCloseFactIfShown(active, CloseReason.Error(typed))
    }

    private fun ended(active: ActivePresentation, reason: CloseReason) {
        if (!active.closed.compareAndSet(false, true)) return
        clearCurrent(active)
        active.commerce?.retire()
        active.acquired.close()
        if (!active.firstFrame.isCompleted) {
            active.firstFrame.completeExceptionally(
                ExperiencePresentationException(
                    ExperiencePresentationException.Reason.SUPERSEDED,
                    "Experience presentation ended before its first frame",
                ),
            )
        }
        emitStandaloneCloseFactIfShown(active, reason)
        active.finished.complete(Unit)
    }

    private fun attemptOutcome(
        active: ActivePresentation,
        outcome: PresentationOutcome,
    ) {
        if (outcome.reason == CloseReason.JourneyNavigation) {
            (active.outcomeSink as? OutcomeSink.DeviceLeg)?.let { deviceLeg ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    deviceLeg.emissions.close()
                }
            }
            active.runTransitionFinished.complete(Unit)
            return
        }
        val deviceLeg = active.outcomeSink as? OutcomeSink.DeviceLeg
        if (deviceLeg != null) {
            if (!active.outcomeStarted.compareAndSet(false, true)) return
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    deviceLeg.emissions.close()
                    val dismissal = outcome.reason.screenDismissalMethod()?.let { method ->
                        if (deviceLeg.screenDismissed.compareAndSet(false, true)) {
                            deviceLeg.onScreenDismissed(
                                deviceLeg.screenId,
                                null,
                                method,
                            )
                        } else {
                            DeviceLegScreenDismissalResult.HANDLED
                        }
                    }
                    when (dismissal) {
                        DeviceLegScreenDismissalResult.HANDLED,
                        DeviceLegScreenDismissalResult.COMPLETED -> Unit
                        DeviceLegScreenDismissalResult.REJECTED ->
                            deviceLeg.onOutcome(DeviceLegSurfaceOutcome.ABANDONED)
                        null -> deviceLeg.onOutcome(outcome.reason.deviceLegOutcome())
                    }
                } finally {
                    val emitClose = synchronized(active.factLock) {
                        active.runTransitionFinished.complete(Unit)
                        active.shown.get() && outcome.reason != CloseReason.IdentityChanged
                    }
                    if (emitClose) emitCloseFact(active, outcome.reason)
                }
            }
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val won = try {
                transitionOutcome(outcome)
            } catch (_: Throwable) {
                return@launch
            }
            val emitClose = synchronized(active.factLock) {
                active.runTransitionFinished.complete(Unit)
                won && active.shown.get() && outcome.reason != CloseReason.IdentityChanged
            }
            if (!won) return@launch
            if (emitClose) {
                emitCloseFact(active, outcome.reason)
            }
            scope.launch { runCatching { reportOutcome(outcome) } }
        }
    }

    private fun clearCurrent(active: ActivePresentation) {
        synchronized(stateLock) {
            if (current === active) current = null
        }
    }

    private fun emitStandaloneCloseFactIfShown(
        active: ActivePresentation,
        reason: CloseReason,
    ) {
        synchronized(active.factLock) {
            if (active.shown.get() && active.ref.journeyId == null) {
                emitCloseFact(active, reason)
            }
        }
    }

    private fun emitCloseFact(active: ActivePresentation, reason: CloseReason) {
        val ref = active.ref
        val properties = linkedMapOf<String, Any?>(
            "journey_id" to ref.journeyId,
            "experience_id" to ref.experienceId,
            "experience_version" to ref.experienceVersion,
        )
        when (reason) {
            CloseReason.UserDismissed -> properties["reason"] = "user"
            CloseReason.HostDismissed -> properties["reason"] = "host"
            CloseReason.JourneyNavigation -> return
            CloseReason.IdentityChanged -> return
            CloseReason.GoalMet -> properties["reason"] = "goal_met"
            is CloseReason.AuthenticatedExit -> return
            else -> Unit
        }
        val name = when (reason) {
            CloseReason.UserDismissed, CloseReason.HostDismissed, CloseReason.GoalMet ->
                SystemEventNames.EXPERIENCE_DISMISSED
            CloseReason.JourneyNavigation ->
                error("same-Journey navigation has no close fact")
            CloseReason.IdentityChanged -> error("identity-change shutdown has no close fact")
            CloseReason.PurchaseCompleted -> {
                properties["product_id"] = null
                SystemEventNames.EXPERIENCE_PURCHASED
            }
            CloseReason.Timeout -> SystemEventNames.EXPERIENCE_TIMED_OUT
            is CloseReason.AuthenticatedExit -> return
            is CloseReason.Error -> {
                properties["error_message"] = reason.cause.message
                SystemEventNames.EXPERIENCE_ERRORED
            }
        }
        runCatching { emit(name, properties, active.ownerDistinctId) }
    }

    private class AndroidPresentationLauncher(private val context: Context) : (String) -> Unit {
        override fun invoke(presentationId: String) {
            context.startActivity(
                Intent(context, NuxieExperienceActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, presentationId)
                },
            )
        }
    }
}

private fun AuthenticatedRelease.defaultArtboardName(): String? {
    val render = descriptor["render"] as? JsonObject ?: return null
    val firstScreen = (render["screens"] as? JsonArray)?.firstOrNull() as? JsonObject
    return (firstScreen?.get("artboardName") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
}

private fun AuthenticatedRelease.defaultScreenId(): String? {
    val render = descriptor["render"] as? JsonObject ?: return null
    val firstScreen = (render["screens"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    return (firstScreen["id"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf(String::isNotBlank)
}

private fun AuthenticatedRelease.defaultArtboardSize(): ExperienceArtboardSize? {
    val render = descriptor["render"] as? JsonObject ?: return null
    val firstScreen = (render["screens"] as? JsonArray)?.firstOrNull() as? JsonObject
        ?: return null
    return firstScreen.artboardSize()
}

private fun JsonObject.artboardName(screenId: String): String? {
    val render = this["render"] as? JsonObject ?: return null
    val screen = (render["screens"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.singleOrNull { it.string("id") == screenId }
        ?: return null
    return (screen["artboardName"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
}

private fun JsonObject.artboardSize(screenId: String): ExperienceArtboardSize? {
    val render = this["render"] as? JsonObject ?: return null
    val screen = (render["screens"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.singleOrNull { it.string("id") == screenId }
        ?: return null
    return screen.artboardSize()
}

private fun JsonObject.artboardSize(): ExperienceArtboardSize? {
    val width = float("width") ?: return null
    val height = float("height") ?: return null
    return runCatching { ExperienceArtboardSize(width, height) }.getOrNull()
}

private fun JsonObject.presentationClearColor(): Int {
    val presentation = this["presentation"] as? JsonObject ?: return OPAQUE_BLACK
    val value = (presentation["backgroundColor"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: return OPAQUE_BLACK
    val hex = value.removePrefix("#")
    return runCatching {
        when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> {
                val rgba = hex.toLong(16)
                ((rgba and 0xFF) shl 24 or (rgba ushr 8)).toInt()
            }
            else -> OPAQUE_BLACK
        }
    }.getOrDefault(OPAQUE_BLACK)
}

private fun JsonObject.presentationShell(): PresentationShell {
    val presentation = this["presentation"] as? JsonObject
        ?: return PresentationShell.FullScreen
    return when (presentation.string("style")) {
        "sheet" -> {
            val sheet = presentation["sheet"] as? JsonObject
                ?: return PresentationShell.FullScreen
            PresentationShell.Sheet(
                detent = when (sheet.string("detent")) {
                    "medium" -> PresentationShell.Sheet.Detent.MEDIUM
                    else -> PresentationShell.Sheet.Detent.LARGE
                },
                dismissible = sheet.boolean("dismissible") ?: true,
            )
        }
        "drawer" -> {
            val drawer = presentation["drawer"] as? JsonObject
                ?: return PresentationShell.FullScreen
            PresentationShell.Drawer(
                edge = when (drawer.string("edge")) {
                    "top" -> PresentationShell.Drawer.Edge.TOP
                    "leading", "left" -> PresentationShell.Drawer.Edge.LEADING
                    "trailing", "right" -> PresentationShell.Drawer.Edge.TRAILING
                    else -> PresentationShell.Drawer.Edge.BOTTOM
                },
                extentRatio = drawer.float("extentRatio")?.coerceIn(0.1f, 1f) ?: 0.5f,
                cornerRadiusDp = drawer.float("cornerRadius")?.coerceAtLeast(0f) ?: 0f,
                dismissible = drawer.boolean("dismissible") ?: true,
            )
        }
        else -> PresentationShell.FullScreen
    }
}

private fun CloseReason.deviceLegOutcome(): DeviceLegSurfaceOutcome = when (this) {
    CloseReason.UserDismissed, CloseReason.HostDismissed -> DeviceLegSurfaceOutcome.DISMISSED
    else -> DeviceLegSurfaceOutcome.ABANDONED
}

private fun CloseReason.screenDismissalMethod(): String? = when (this) {
    CloseReason.UserDismissed -> "user"
    CloseReason.GoalMet, CloseReason.PurchaseCompleted -> "goal_met"
    CloseReason.Timeout, is CloseReason.Error -> "error"
    CloseReason.HostDismissed,
    CloseReason.JourneyNavigation,
    CloseReason.IdentityChanged,
    is CloseReason.AuthenticatedExit,
    -> null
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

private fun JsonObject.float(key: String): Float? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toFloatOrNull()

private const val OPAQUE_BLACK = 0xFF000000.toInt()
private const val FIRST_FRAME_TIMEOUT_MILLIS = 30_000L
