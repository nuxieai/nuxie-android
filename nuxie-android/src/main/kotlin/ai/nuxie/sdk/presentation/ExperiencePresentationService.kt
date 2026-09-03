package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.billing.CommerceOutcomeCorrelation
import ai.nuxie.sdk.billing.JourneyCommercePreparing
import ai.nuxie.sdk.billing.JourneyCommerceSession
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.experiences.AcquiredJourneyRelease
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.journey.JourneyActionType
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
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
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.serialization.json.doubleOrNull

/** Kotlin analog of the iOS presentation close-reason set. */
internal sealed interface CloseReason {
    data object UserDismissed : CloseReason
    data object HostDismissed : CloseReason
    /** Physical replacement while the same Journey retains surface ownership. */
    data object JourneyNavigation : CloseReason
    data object IdentityChanged : CloseReason
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
        PRODUCTS_UNAVAILABLE,
        FIRST_FRAME_TIMEOUT,
        SUPERSEDED,
        DECLINED,
    }
}

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
    val viewModelProjection: NuxieViewModelListProjection? = null,
)

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

    fun purchaseActivity(): Activity? = null

    suspend fun resolveJourneyPermission(request: JourneyPermissionRequest): Boolean = false
}

internal enum class JourneyPermissionRequest {
    NOTIFICATIONS,
    CAMERA,
    LOCATION,
    MICROPHONE,
    PHOTOS,
    TRACKING,
    UNSUPPORTED,
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
        val onRuntimeStep: (NuxiePlayerStepOutcome, ULong, NuxieViewModelSnapshot?) -> Unit,
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
        onRuntimeStep: (NuxiePlayerStepOutcome, ULong, NuxieViewModelSnapshot?) -> Unit =
            { _, _, _ -> },
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

    fun reportRuntimeStep(
        id: String,
        outcome: NuxiePlayerStepOutcome,
        correlationId: ULong,
        viewModelSnapshot: NuxieViewModelSnapshot?,
    ) {
        val callback = synchronized(lock) {
            entries[id]?.takeUnless { it.terminal.get() }?.onRuntimeStep
        } ?: return
        callback(outcome, correlationId, viewModelSnapshot)
    }

    fun currentActivity(id: String): PresentationActivityHandle? = synchronized(lock) {
        entries[id]?.latestActivity?.get()
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
    private val emit: (String, Map<String, Any?>, String?) -> Unit,
    private val scope: CoroutineScope,
    private val runtimeAvailable: () -> Boolean,
    private val launch: (String) -> Unit,
    private val commerce: JourneyCommercePreparing = JourneyCommercePreparing.NONE,
    private val openLink: (String, String?) -> Unit = { _, _ -> },
    private val firstFrameTimeoutMillis: Long = FIRST_FRAME_TIMEOUT_MILLIS,
    private val beforeHostTeardownForTesting: () -> Unit = {},
) {
    constructor(
        context: Context,
        emit: (String, Map<String, Any?>, String?) -> Unit,
        scope: CoroutineScope,
        runtimeAvailable: () -> Boolean,
        commerce: JourneyCommercePreparing = JourneyCommercePreparing.NONE,
    ) : this(
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
        firstFrameTimeoutMillis = FIRST_FRAME_TIMEOUT_MILLIS,
    )

    private data class ActivePresentation(
        val id: String,
        val ref: ExperienceRef,
        val acquired: AcquiredJourneyRelease,
        val ownerDistinctId: String?,
        val journey: JourneyOutcome,
        val firstFrame: CompletableDeferred<ExperienceRef>,
        val closed: AtomicBoolean = AtomicBoolean(false),
        val shown: AtomicBoolean = AtomicBoolean(false),
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
        // Shown and the run-terminal observation are one fact-pairing decision.
        val factLock: Any = Any(),
        // Every close path observes the same first-terminal run transition.
        val runTransitionFinished: CompletableDeferred<Unit> = CompletableDeferred(),
        val outcomeStarted: AtomicBoolean = AtomicBoolean(false),
        val latestViewModelSnapshot: AtomicReference<NuxieViewModelSnapshot?> = AtomicReference(),
    )

    private class JourneyOutcome(
        val screenId: String,
        val onOutcome: suspend (JourneySurfaceOutcome) -> Unit,
        val onScreenDismissed: suspend (
            String,
            String?,
            String,
        ) -> JourneyScreenDismissalResult,
        val emissions: JourneyRuntimeEmissionCoordinator,
        val screenDismissed: AtomicBoolean = AtomicBoolean(false),
        var navigationHistory: List<String> = emptyList(),
        val commerce: JourneyCommerceSession? = null,
    )

    private data class PreparedSource(
        val identity: ai.nuxie.sdk.experiences.JourneyReleaseIdentity,
        val descriptor: JsonObject,
        val acquired: AcquiredJourneyRelease,
        val artboardName: String?,
        val screenId: String? = null,
        val artboardSize: ExperienceArtboardSize? = null,
        val viewModelProjection: NuxieViewModelListProjection? = null,
    )

    private data class PendingReservation(
        val id: String,
        val request: PresentationRequest,
    )

    private inner class JourneyReservation(
        val id: String,
        val request: PresentationRequest,
    ) : JourneyPresentationReservation {
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
    private var pendingBackNavigation: PendingBackNavigation? = null
    private val identityEpochByOwner = mutableMapOf<String, Long>()

    private data class PendingBackNavigation(
        val owner: JourneyPresentationOwner,
        val target: String,
        val history: List<String>,
    )

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

    /**
     * Claims the surface before durable journey admission. A failed claim
     * leaves the signed arm untouched so the same trigger can fire later.
     */
    fun reserveJourney(ownerDistinctId: String): JourneyPresentationReservation? =
        synchronized(stateLock) {
            if (current != null || pendingReservation != null || transitionInProgress) {
                return@synchronized null
            }
            val request = captureRequestLocked(ownerDistinctId)
            if (!isCurrentIdentity(request)) return@synchronized null
            val id = UUID.randomUUID().toString()
            pendingReservation = PendingReservation(id, request)
            JourneyReservation(id, request)
        }

    suspend fun presentJourney(
        release: AuthenticatedJourneyRelease,
        screenId: String,
        journeyId: String,
        ownerDistinctId: String,
        reservation: JourneyPresentationReservation?,
        canPresent: () -> Boolean = { true },
        acquire: suspend () -> AcquiredJourneyRelease,
        nextBatchSequence: Long = 0,
        nextEmissionSequence: Long = 0,
        onScreenChanged: suspend (String) -> Boolean = { true },
        onScreenDismissed: suspend (
            String,
            String?,
            String,
        ) -> JourneyScreenDismissalResult = { _, _, _ ->
            JourneyScreenDismissalResult.HANDLED
        },
        onEmissionBatch: suspend (JourneyScreenEmissionBatch) -> Boolean = { true },
        onPresentationRevealed: suspend (String) -> Unit = {},
        onOutcome: suspend (JourneySurfaceOutcome) -> Unit,
    ): ExperienceRef {
        val reserved = reservation as? JourneyReservation
        val request = reserved?.request ?: captureRequest(ownerDistinctId)
        if (request.ownerDistinctId != ownerDistinctId) throw declinedPresentation()
        val commerceSession = try {
            commerce.prepare(release)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw ExperiencePresentationException(
                ExperiencePresentationException.Reason.PRODUCTS_UNAVAILABLE,
                "Journey product preparation failed: ${error.message ?: "unknown error"}",
                error,
            )
        }
        val viewModelProjection = try {
            GooglePlayProductViewModelProjection.prepare(
                descriptor = release.descriptor,
                products = commerceSession?.products.orEmpty(),
                screenId = screenId,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw ExperiencePresentationException(
                ExperiencePresentationException.Reason.PRODUCTS_UNAVAILABLE,
                "Journey product projection failed: ${error.message ?: "unknown error"}",
                error,
            )
        }
        return presentPrepared(
            request = request,
            journeyId = journeyId,
            reservationId = reserved?.id,
            reservationRequired = true,
            journey = JourneyOutcome(
                screenId = screenId,
                onOutcome = onOutcome,
                onScreenDismissed = onScreenDismissed,
                emissions = JourneyRuntimeEmissionCoordinator(
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
                commerce = commerceSession,
            ),
            canPresent = canPresent,
        ) {
            val artboardName = release.descriptor.artboardName(screenId)
                ?: throw ExperiencePresentationException(
                    ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Authenticated journey screen is not renderable: $screenId",
                )
            PreparedSource(
                identity = release.identity,
                descriptor = release.descriptor,
                acquired = acquire(),
                artboardName = artboardName,
                screenId = screenId,
                artboardSize = release.descriptor.artboardSize(screenId),
                viewModelProjection = viewModelProjection,
            )
        }
    }

    private suspend fun presentPrepared(
        request: PresentationRequest,
        journeyId: String?,
        reservationId: String?,
        reservationRequired: Boolean,
        journey: JourneyOutcome,
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
                    val outgoing = it.journey
                    val incoming = journey
                    val owner = JourneyPresentationOwner(
                        journeyId = checkNotNull(journeyId),
                        distinctId = checkNotNull(request.ownerDistinctId),
                    )
                    val back = synchronized(stateLock) {
                        pendingBackNavigation?.takeIf { pending ->
                            pending.owner == owner && pending.target == incoming.screenId
                        }
                    }
                    incoming.navigationHistory = back?.history
                        ?: (outgoing.navigationHistory + outgoing.screenId)
                    val dismissal = if (outgoing.screenDismissed.compareAndSet(false, true)
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
                            JourneyScreenDismissalResult.REJECTED
                        }
                    } else null
                    PresentationRegistry.dismiss(it.id, CloseReason.JourneyNavigation)
                    attemptOutcome(it, CloseReason.JourneyNavigation)
                    it.finished.await()
                    when (dismissal) {
                        JourneyScreenDismissalResult.COMPLETED ->
                            throw ExperiencePresentationException(
                                ExperiencePresentationException.Reason.JOURNEY_COMPLETED,
                                "Journey completed while dismissing its previous screen",
                            )
                        JourneyScreenDismissalResult.REJECTED ->
                            throw ExperiencePresentationException(
                                ExperiencePresentationException.Reason.HOST_FAILED,
                                "Journey screen dismissal was rejected",
                            )
                        JourneyScreenDismissalResult.HANDLED, null -> Unit
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
                    ownerDistinctId = request.ownerDistinctId,
                    journey = journey,
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
                                viewModelProjection = source.viewModelProjection,
                            ),
                            onFirstFrame = { firstFrame(pending) },
                            onFailure = { error -> failed(pending, error) },
                            onDismissed = { reason -> ended(pending, reason) },
                            onOutcome = { reason -> attemptOutcome(pending, reason) },
                            onRuntimeStep = { outcome, correlationId, snapshot ->
                                pending.latestViewModelSnapshot.set(snapshot)
                                runtimeStep(pending, outcome, correlationId)
                            },
                        )
                        try {
                            launch(id)
                        } catch (error: Throwable) {
                            PresentationRegistry.reportFailure(id, error)
                        }
                        published = true
                        if (pendingBackNavigation?.target == journey.screenId) {
                            pendingBackNavigation = null
                        }
                        true
                    }
                }
                if (!launched) {
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

    fun ownsJourney(owner: JourneyPresentationOwner): Boolean = synchronized(stateLock) {
        current?.isOwnedBy(owner.journeyId, owner.distinctId) == true
    }

    fun journeyScreenId(owner: JourneyPresentationOwner): String? = synchronized(stateLock) {
        current?.takeIf { it.isOwnedBy(owner.journeyId, owner.distinctId) }
            ?.journey
            ?.screenId
    }

    fun resolveJourneyAction(
        owner: JourneyPresentationOwner,
        action: JsonObject,
        source: JourneyScreenEmissionSource?,
    ): JsonObject? {
        val active = synchronized(stateLock) {
            current?.takeIf { it.isOwnedBy(owner.journeyId, owner.distinctId) }
        } ?: return null
        if (JourneyActionType.from(action) != JourneyActionType.PURCHASE) return action
        val placement = action["placementId"] ?: return null
        val placementId = when (placement) {
            is JsonPrimitive -> placement.takeIf(JsonPrimitive::isString)?.content
            is JsonObject -> {
                (placement["literal"] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.content
                    ?: (placement["ref"] as? JsonObject)
                        ?.let { reference ->
                            (reference["path"] as? JsonPrimitive)
                                ?.takeIf(JsonPrimitive::isString)
                                ?.content
                        }
                        ?.let { path -> active.latestViewModelSnapshot.get()?.resolveString(path) }
            }
            else -> null
        }?.takeIf(String::isNotEmpty) ?: return null
        return JsonObject(action + ("placementId" to JsonPrimitive(placementId)))
    }

    suspend fun dispatchJourneyAction(
        owner: JourneyPresentationOwner,
        action: JsonObject,
        effectId: String,
    ): JourneyPresentationActionResult {
        val active = synchronized(stateLock) {
            current?.takeIf { it.isOwnedBy(owner.journeyId, owner.distinctId) }
        } ?: return JourneyPresentationActionResult.NoPresentation
        return when (JourneyActionType.from(action)) {
            JourneyActionType.BACK -> prepareBackNavigation(owner, active, action)
            JourneyActionType.PURCHASE -> {
                val placementId = action.string("placementId")
                    ?: return JourneyPresentationActionResult.Failed
                val session = active.journey.commerce
                    ?: return JourneyPresentationActionResult.ProductsUnavailable
                if (session.products.none { it.placementId == placementId }) {
                    return JourneyPresentationActionResult.ProductsUnavailable
                }
                val activity = PresentationRegistry.currentActivity(active.id)?.purchaseActivity()
                    ?: return JourneyPresentationActionResult.NoPresentation
                scope.launch {
                    session.purchase(
                        activity,
                        placementId,
                        CommerceOutcomeCorrelation(effectId, owner.distinctId),
                    )
                }
                JourneyPresentationActionResult.AwaitingOutcome
            }
            JourneyActionType.RESTORE -> {
                val session = active.journey.commerce
                    ?: return JourneyPresentationActionResult.ProductsUnavailable
                scope.launch {
                    session.restore(CommerceOutcomeCorrelation(effectId, owner.distinctId))
                }
                JourneyPresentationActionResult.AwaitingOutcome
            }
            JourneyActionType.REQUEST_NOTIFICATIONS -> permissionResult(
                active,
                owner,
                JourneyPermissionRequest.NOTIFICATIONS,
                null,
            )
            JourneyActionType.REQUEST_PERMISSION -> {
                val permissionType = action.string("permissionType")
                    ?: return JourneyPresentationActionResult.Failed
                permissionResult(
                    active,
                    owner,
                    permissionRequest(permissionType),
                    permissionType,
                )
            }
            JourneyActionType.REQUEST_TRACKING -> permissionResult(
                active,
                owner,
                JourneyPermissionRequest.TRACKING,
                null,
            )
            JourneyActionType.OPEN_LINK -> {
                val url = action.string("url")
                    ?: return JourneyPresentationActionResult.Failed
                val target = action.string("target")
                    ?: return JourneyPresentationActionResult.Failed
                if (runCatching { openLink(url, target) }.isFailure) {
                    JourneyPresentationActionResult.Failed
                } else {
                    JourneyPresentationActionResult.Advanced("next")
                }
            }
            JourneyActionType.DISMISS -> {
                PresentationRegistry.dismiss(active.id, CloseReason.UserDismissed)
                attemptOutcome(active, CloseReason.UserDismissed)
                JourneyPresentationActionResult.Handled
            }
            else -> JourneyPresentationActionResult.Failed
        }
    }

    fun cancelBackNavigation(owner: JourneyPresentationOwner) = synchronized(stateLock) {
        if (pendingBackNavigation?.owner == owner) pendingBackNavigation = null
    }

    private fun prepareBackNavigation(
        owner: JourneyPresentationOwner,
        active: ActivePresentation,
        action: JsonObject,
    ): JourneyPresentationActionResult {
        val stepsValue = action["steps"]
        val steps = when (stepsValue) {
            null -> 1
            is JsonPrimitive -> stepsValue.doubleOrNull
                ?.takeIf { it.isFinite() && it == kotlin.math.floor(it) && it in 1.0..256.0 }
                ?.toInt()
            else -> null
        } ?: return JourneyPresentationActionResult.Failed
        val history = active.journey.navigationHistory
        if (history.isEmpty()) return JourneyPresentationActionResult.Failed
        val targetIndex = (history.size - steps).coerceAtLeast(0)
        val target = history[targetIndex]
        synchronized(stateLock) {
            if (current !== active || !active.isOwnedBy(owner.journeyId, owner.distinctId)) {
                return JourneyPresentationActionResult.NoPresentation
            }
            pendingBackNavigation = PendingBackNavigation(
                owner,
                target,
                history.subList(0, targetIndex).toList(),
            )
        }
        return JourneyPresentationActionResult.Navigate(target)
    }

    private suspend fun permissionResult(
        active: ActivePresentation,
        owner: JourneyPresentationOwner,
        request: JourneyPermissionRequest,
        permissionType: String?,
    ): JourneyPresentationActionResult {
        val host = PresentationRegistry.currentActivity(active.id)
            ?: return JourneyPresentationActionResult.NoPresentation
        val granted = runCatching { host.resolveJourneyPermission(request) }
            .getOrElse { return JourneyPresentationActionResult.Failed }
        if (!ownsJourney(owner)) return JourneyPresentationActionResult.Failed
        val name = when (request) {
            JourneyPermissionRequest.NOTIFICATIONS -> if (granted) {
                SystemEventNames.NOTIFICATIONS_ENABLED
            } else {
                SystemEventNames.NOTIFICATIONS_DENIED
            }
            JourneyPermissionRequest.TRACKING -> if (granted) {
                SystemEventNames.TRACKING_AUTHORIZED
            } else {
                SystemEventNames.TRACKING_DENIED
            }
            else -> if (granted) {
                SystemEventNames.PERMISSION_GRANTED
            } else {
                SystemEventNames.PERMISSION_DENIED
            }
        }
        return JourneyPresentationActionResult.PermissionResolved(
            outlet = "next",
            event = JourneyPresentationPermissionEvent(
                name,
                buildMap {
                    put("journey_id", owner.journeyId)
                    permissionType?.let { put("type", it) }
                },
            ),
        )
    }

    private fun permissionRequest(type: String): JourneyPermissionRequest = when (type) {
        "camera" -> JourneyPermissionRequest.CAMERA
        "location" -> JourneyPermissionRequest.LOCATION
        "microphone" -> JourneyPermissionRequest.MICROPHONE
        "photos" -> JourneyPermissionRequest.PHOTOS
        else -> JourneyPermissionRequest.UNSUPPORTED
    }

    fun dismiss(reason: CloseReason = CloseReason.UserDismissed) {
        synchronized(stateLock) { current }?.let { active ->
            PresentationRegistry.dismiss(active.id, reason)
            attemptOutcome(active, reason)
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
        attemptOutcome(active, teardownReason)
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
        attemptOutcome(active, CloseReason.IdentityChanged)
        joinAll(active.finished, active.runTransitionFinished)
    }

    /** Closes only the terminal Journey surface without injecting another outcome. */
    suspend fun shutdownJourney(ownerDistinctId: String, journeyId: String) {
        val active = synchronized(stateLock) {
            current?.takeIf { it.isOwnedBy(journeyId, ownerDistinctId) }
        } ?: return
        PresentationRegistry.dismiss(active.id, CloseReason.JourneyNavigation)
        attemptOutcome(active, CloseReason.JourneyNavigation)
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
        val journey = active.journey
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (journey.emissions.reveal() && markShown(active)) {
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
        val journey = active.journey
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val accepted = runCatching {
                journey.emissions.publish(outcome, correlationId)
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
    }

    private fun ended(active: ActivePresentation, reason: CloseReason) {
        if (!active.closed.compareAndSet(false, true)) return
        clearCurrent(active)
        active.acquired.close()
        if (!active.firstFrame.isCompleted) {
            active.firstFrame.completeExceptionally(
                ExperiencePresentationException(
                    ExperiencePresentationException.Reason.SUPERSEDED,
                    "Experience presentation ended before its first frame",
                ),
            )
        }
        active.finished.complete(Unit)
    }

    private fun attemptOutcome(
        active: ActivePresentation,
        reason: CloseReason,
    ) {
        if (reason == CloseReason.JourneyNavigation) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                active.journey.emissions.close()
            }
            active.runTransitionFinished.complete(Unit)
            return
        }
        val journey = active.journey
        if (!active.outcomeStarted.compareAndSet(false, true)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                journey.emissions.close()
                val dismissal = reason.screenDismissalMethod()?.let { method ->
                    if (journey.screenDismissed.compareAndSet(false, true)) {
                        journey.onScreenDismissed(
                            journey.screenId,
                            null,
                            method,
                        )
                    } else {
                        JourneyScreenDismissalResult.HANDLED
                    }
                }
                when (dismissal) {
                    JourneyScreenDismissalResult.HANDLED,
                    JourneyScreenDismissalResult.COMPLETED -> Unit
                    JourneyScreenDismissalResult.REJECTED ->
                        journey.onOutcome(JourneySurfaceOutcome.ABANDONED)
                    null -> journey.onOutcome(reason.journeyOutcome())
                }
            } finally {
                val emitClose = synchronized(active.factLock) {
                    active.runTransitionFinished.complete(Unit)
                    active.shown.get() && reason != CloseReason.IdentityChanged
                }
                if (emitClose) emitCloseFact(active, reason)
            }
        }
    }

    private fun clearCurrent(active: ActivePresentation) {
        synchronized(stateLock) {
            if (current === active) current = null
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
            is CloseReason.Error -> Unit
        }
        val name = when (reason) {
            CloseReason.UserDismissed, CloseReason.HostDismissed ->
                SystemEventNames.EXPERIENCE_DISMISSED
            CloseReason.JourneyNavigation ->
                error("same-Journey navigation has no close fact")
            CloseReason.IdentityChanged -> error("identity-change shutdown has no close fact")
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

private fun CloseReason.journeyOutcome(): JourneySurfaceOutcome = when (this) {
    CloseReason.UserDismissed, CloseReason.HostDismissed -> JourneySurfaceOutcome.DISMISSED
    else -> JourneySurfaceOutcome.ABANDONED
}

private fun CloseReason.screenDismissalMethod(): String? = when (this) {
    CloseReason.UserDismissed -> "user"
    is CloseReason.Error -> "error"
    CloseReason.HostDismissed,
    CloseReason.JourneyNavigation,
    CloseReason.IdentityChanged,
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
