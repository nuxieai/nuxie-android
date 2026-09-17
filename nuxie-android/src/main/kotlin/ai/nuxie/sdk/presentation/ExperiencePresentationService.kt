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
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull

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
    val textInputState: ExperienceTextInputState = ExperienceTextInputState(),
    val screenLifecycle: ExperienceScreenLifecycle = ExperienceScreenLifecycle(),
    val transition: JsonObject? = null,
    val retainedViewModel: AtomicReference<NuxieViewModelSnapshot?> = AtomicReference(),
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

/** Screen-scoped close operations and access to its hosting Activity. */
internal interface PreparedScreenNavigation {
    suspend fun awaitExit() = Unit
    fun activate()
    suspend fun abort()
}

internal interface PresentationScreenHandle {
    val rendererEffects: RendererEffectLifetime? get() = null
    val nativeAttemptGeneration: Long? get() = null

    suspend fun retireForRetry() = Unit

    suspend fun prepareNavigation(id: String, content: PreparedPresentation): PreparedScreenNavigation? = null

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
internal data class NavigationRecoveryState(val token: String, val progress: AcquisitionProgress)

internal sealed interface PresentationContentState {
    data class Acquiring(val screen: AuthenticatedPresentationScreen, val progress: AcquisitionProgress? = null) : PresentationContentState
    data class Ready(val content: PreparedPresentation, val progress: AcquisitionProgress? = null, val navigationRecovery: NavigationRecoveryState? = null) : PresentationContentState
    data class Closed(val reason: CloseReason) : PresentationContentState
}

internal object PresentationRegistry {
    private class Callbacks(
        val onFirstFrame: () -> Unit,
        val onFailure: (Throwable) -> Unit,
        val onDismissed: (CloseReason) -> Unit,
        val onOutcome: (CloseReason) -> Unit,
        val onRuntimeStep: (NuxiePlayerStepOutcome, ULong, NuxieViewModelSnapshot?, RendererEffectLifetime?) -> Unit,
        val onTextCommitted: (String, String, RendererEffectLifetime?) -> Unit,
    )

    private class Entry(initial: PresentationContentState, var callbacks: Callbacks) {
        val state = MutableStateFlow(initial)
        val terminal = AtomicBoolean(false)
        val firstFrame = AtomicBoolean(false)
        var revealAdmitted = false
        var visible = false
        val revealFrames = IdentityHashMap<PresentationScreenHandle?, suspend () -> Boolean>()
        val detached = CompletableDeferred<Unit>()
        var latestScreen = WeakReference<PresentationScreenHandle>(null)
        val attachedScreens: MutableSet<PresentationScreenHandle> =
            Collections.newSetFromMap(IdentityHashMap())
        var dismissalReason: CloseReason? = null
        var retry: ((Long) -> Boolean)? = null
        var navigationRetry: Pair<String, (Long) -> Boolean>? = null
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
        onRuntimeStep: (NuxiePlayerStepOutcome, ULong, NuxieViewModelSnapshot?, RendererEffectLifetime?) -> Unit =
            { _, _, _, _ -> },
        onTextCommitted: (String, String, RendererEffectLifetime?) -> Unit = { _, _, _ -> },
        requiresAcquiring: Boolean = false,
    ) {
        synchronized(lock) {
            val callbacks = Callbacks(onFirstFrame, onFailure, onDismissed, onOutcome, onRuntimeStep, onTextCommitted)
            val existing = entries[id]
            if (existing == null) {
                check(!requiresAcquiring) { "Acquiring presentation was withdrawn" }
                entries[id] = Entry(PresentationContentState.Ready(content), callbacks)
            } else {
                val pending = existing.state.value as? PresentationContentState.Acquiring
                check(pending != null && existing.dismissalReason == null) { "duplicate or closed presentation id" }
                check(content.screenId == pending.screen.screenId && content.artboardName == pending.screen.artboardName &&
                    content.clearColor == pending.screen.clearColor && content.shell == pending.screen.shell) {
                    "Prepared content differs from authenticated shell"
                }
                existing.callbacks = callbacks
                existing.retry = null
                existing.state.value = PresentationContentState.Ready(content,
                    AcquisitionProgress(1, android.os.SystemClock.elapsedRealtime(), AcquisitionProgress.Phase.LOADING))
            }
        }
    }

    fun registerAcquiring(id: String, screen: AuthenticatedPresentationScreen,
        onRetry: (Long) -> Boolean = { false }, onClosed: (CloseReason) -> Unit): Deferred<Unit> =
        synchronized(lock) {
            check(id !in entries) { "duplicate presentation id" }
            Entry(PresentationContentState.Acquiring(screen), Callbacks({}, { onClosed(CloseReason.Error(it)) }, onClosed,
                onClosed, { _, _, _, _ -> }, { _, _, _ -> })).also { it.retry = onRetry; entries[id] = it }.detached
        }

    fun updateAcquisition(id: String, progress: AcquisitionProgress) = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized
        val state = entry.state.value as? PresentationContentState.Acquiring ?: return@synchronized
        if (entry.dismissalReason == null && !entry.terminal.get()) entry.state.value = state.copy(progress = progress)
    }

    fun retryAcquisition(id: String, generation: Long): Boolean {
        val retry = synchronized(lock) {
            val entry = entries[id] ?: return false
            if (entry.dismissalReason != null || entry.state.value !is PresentationContentState.Acquiring) return false
            entry.retry
        }
        return retry?.invoke(generation) == true
    }

    fun beginNavigationRecovery(id: String, token: String, retry: (Long) -> Boolean): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        if (entry.state.value !is PresentationContentState.Ready || entry.dismissalReason != null ||
            entry.terminal.get() || entry.navigationRetry != null) return@synchronized false
        entry.navigationRetry = token to retry
        true
    }

    fun updateNavigationRecovery(id: String, token: String, progress: AcquisitionProgress) = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized
        val ready = entry.state.value as? PresentationContentState.Ready ?: return@synchronized
        if (entry.navigationRetry?.first != token || entry.dismissalReason != null || entry.terminal.get()) return@synchronized
        entry.state.value = ready.copy(navigationRecovery = NavigationRecoveryState(token, progress))
    }

    fun retryNavigation(id: String, token: String, generation: Long): Boolean {
        val retry = synchronized(lock) {
            val entry = entries[id] ?: return false
            if (entry.dismissalReason != null || entry.terminal.get()) return false
            entry.navigationRetry?.takeIf { it.first == token }?.second
        }
        return retry?.invoke(generation) == true
    }

    fun endNavigationRecovery(id: String, token: String) = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized
        if (entry.navigationRetry?.first != token) return@synchronized
        entry.navigationRetry = null
        val ready = entry.state.value as? PresentationContentState.Ready ?: return@synchronized
        entry.state.value = ready.copy(navigationRecovery = null)
    }

    fun nativeProgress(id: String): AcquisitionProgress? = synchronized(lock) {
        (entries[id]?.state?.value as? PresentationContentState.Ready)?.progress
    }

    /** Failure retains the lease and logical outcome owner until Retry or Close. */
    fun recoverNative(id: String, source: PresentationScreenHandle?, generation: Long?, error: Throwable): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        val ready = entry.state.value as? PresentationContentState.Ready ?: return@synchronized false
        val progress = ready.progress ?: return@synchronized false
        if (entry.visible || entry.dismissalReason != null || entry.terminal.get() ||
            (generation != null && generation != progress.generation) ||
            (source != null && entry.latestScreen.get() !== source)) return@synchronized false
        if (progress.phase == AcquisitionProgress.Phase.RETRYING) return@synchronized true
        // A timeout may still receive its late frame. A failed native host cannot.
        if (source != null) source.rendererEffects?.retire()
        entry.state.value = ready.copy(progress = progress.copy(phase = AcquisitionProgress.Phase.FAILED))
        ai.nuxie.sdk.logging.NuxieLog.w("ExperiencePresentation", "Native presentation requires recovery", error)
        true
    }

    fun retryNative(id: String, generation: Long): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        val ready = entry.state.value as? PresentationContentState.Ready ?: return@synchronized false
        val progress = ready.progress ?: return@synchronized false
        if (entry.visible || entry.dismissalReason != null || entry.terminal.get() ||
            progress.generation != generation || progress.phase == AcquisitionProgress.Phase.RETRYING) return@synchronized false
        entry.latestScreen.get()?.rendererEffects?.retire()
        entry.revealFrames.clear()
        entry.firstFrame.set(false)
        entry.state.value = ready.copy(progress = AcquisitionProgress(generation + 1,
            android.os.SystemClock.elapsedRealtime(), AcquisitionProgress.Phase.RETRYING))
        true
    }

    suspend fun drainNativeAttempts(id: String, generation: Long) {
        val old = synchronized(lock) { entries[id]?.attachedScreens?.filter {
            it.nativeAttemptGeneration != null && it.nativeAttemptGeneration != generation
        }.orEmpty() }
        old.forEach { it.retireForRetry() }
    }

    suspend fun drainRetiredScreens(id: String) {
        val retired = synchronized(lock) {
            entries[id]?.attachedScreens?.filter { it.rendererEffects?.isRetired == true }.orEmpty()
        }
        retired.forEach { it.retireForRetry() }
    }

    fun nativeRetired(id: String, generation: Long): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        val ready = entry.state.value as? PresentationContentState.Ready ?: return@synchronized false
        val progress = ready.progress ?: return@synchronized false
        if (entry.dismissalReason != null || entry.terminal.get() || progress.generation != generation ||
            progress.phase != AcquisitionProgress.Phase.RETRYING || entry.attachedScreens.any {
                it.nativeAttemptGeneration != null && it.nativeAttemptGeneration != generation
            }) return@synchronized false
        entry.state.value = ready.copy(progress = progress.copy(startedAtMillis = android.os.SystemClock.elapsedRealtime(),
            phase = AcquisitionProgress.Phase.LOADING))
        true
    }

    fun observe(id: String): StateFlow<PresentationContentState>? = synchronized(lock) { entries[id]?.state?.asStateFlow() }

    fun resolve(id: String): PreparedPresentation? = synchronized(lock) {
        (entries[id]?.state?.value as? PresentationContentState.Ready)?.content
    }

    fun attach(id: String, screen: PresentationScreenHandle): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        if (entry.dismissalReason != null) return@synchronized false
        val generation = (entry.state.value as? PresentationContentState.Ready)?.progress?.generation
        if (screen.nativeAttemptGeneration != null && screen.nativeAttemptGeneration != generation) return@synchronized false
        entry.latestScreen.get()?.takeUnless { it === screen }?.rendererEffects?.retire()
        entry.attachedScreens += screen
        entry.latestScreen = WeakReference(screen)
        true
    }

    fun detach(id: String, screen: PresentationScreenHandle) {
        val completion = synchronized(lock) {
            val entry = entries[id] ?: return
            screen.rendererEffects?.retire()
            entry.attachedScreens.remove(screen)
            entry.revealFrames.remove(screen)
            if (entry.latestScreen.get() === screen) {
                entry.latestScreen = WeakReference(null)
            }
            if (entry.dismissalReason == null) {
                entry.dismissalReason = screen.screenCloseReason()
            }
            completionIfReady(id, entry)
        }
        completion?.invoke()
    }

    fun reportFirstFrame(
        id: String,
        source: PresentationScreenHandle? = null,
        present: suspend () -> Boolean = { true },
    ) {
        val callback = synchronized(lock) {
            val entry = entries[id] ?: return
            if (entry.state.value !is PresentationContentState.Ready || entry.dismissalReason != null || entry.terminal.get()) return
            if (source != null && (source !in entry.attachedScreens || entry.latestScreen.get() !== source ||
                source.rendererEffects?.isRetired == true || (source.nativeAttemptGeneration != null &&
                source.nativeAttemptGeneration != (entry.state.value as? PresentationContentState.Ready)?.progress?.generation))) return
            val first = entry.firstFrame.compareAndSet(false, true)
            if (!first && source == null) return
            entry.revealFrames[source] = present
            if (!first && !entry.revealAdmitted) return
            entry.callbacks.onFirstFrame
        }
        callback()
    }

    fun canReveal(id: String, source: PresentationScreenHandle): Boolean = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized false
        entry.revealAdmitted && entry.dismissalReason == null && !entry.terminal.get() &&
            entry.latestScreen.get() === source && source in entry.attachedScreens && source.rendererEffects?.isRetired != true &&
            (source.nativeAttemptGeneration == null || source.nativeAttemptGeneration ==
                (entry.state.value as? PresentationContentState.Ready)?.progress?.generation)
    }

    suspend fun reveal(id: String): Boolean {
        val frames = synchronized(lock) {
            val entry = entries[id] ?: return false
            if (entry.dismissalReason != null || entry.terminal.get()) return false
            entry.revealAdmitted = true
            entry.revealFrames.values.toList().also { entry.revealFrames.clear() }
        }
        var visible = false
        for (present in frames) visible = present() || visible
        if (visible) synchronized(lock) {
            entries[id]?.let { entry ->
                entry.visible = true
                val ready = entry.state.value as? PresentationContentState.Ready
                ready?.progress?.let { progress ->
                    entry.state.value = ready.copy(progress = progress.copy(phase = AcquisitionProgress.Phase.LOADING))
                }
            }
        }
        return visible
    }

    fun reportFailure(id: String, error: Throwable) {
        val outcome = synchronized(lock) { entries[id]?.callbacks?.onOutcome }
        dismiss(id, CloseReason.Error(error))
        outcome?.invoke(CloseReason.Error(error))
    }

    fun reportDismissed(id: String, reason: CloseReason) {
        var outcome: ((CloseReason) -> Unit)? = null
        val completion = synchronized(lock) {
            val entry = entries[id] ?: return
            outcome = entry.callbacks.onOutcome
            if (entry.dismissalReason == null) entry.dismissalReason = reason
            completionIfReady(id, entry)
        }
        completion?.invoke()
        outcome?.invoke(reason)
    }

    fun reportOutcome(id: String, reason: CloseReason) {
        val callback = synchronized(lock) { entries[id]?.callbacks?.onOutcome } ?: return
        callback(reason)
    }

    fun reportRuntimeStep(
        id: String,
        outcome: NuxiePlayerStepOutcome,
        correlationId: ULong,
        viewModelSnapshot: NuxieViewModelSnapshot?,
        source: PresentationScreenHandle? = null,
    ) {
        val callback = synchronized(lock) {
            entries[id]?.takeUnless {
                it.terminal.get() || it.dismissalReason != null ||
                    (source != null && (it.latestScreen.get() !== source || source.rendererEffects?.isRetired == true))
            }?.callbacks?.onRuntimeStep
        } ?: return
        callback(outcome, correlationId, viewModelSnapshot, source?.rendererEffects)
    }

    fun currentScreen(id: String): PresentationScreenHandle? = synchronized(lock) {
        entries[id]?.latestScreen?.get()
    }

    fun reportTextCommitted(id: String, screen: PresentationScreenHandle, inputId: String, text: String) {
        val callback = synchronized(lock) {
            entries[id]?.takeUnless {
                it.terminal.get() || it.dismissalReason != null || it.latestScreen.get() !== screen || screen.rendererEffects?.isRetired == true
            }?.callbacks?.onTextCommitted
        } ?: return
        callback(inputId, text, screen.rendererEffects)
    }

    fun dismiss(id: String, reason: CloseReason) {
        var completion: (() -> Unit)? = null
        val screenToFinish = synchronized(lock) {
            val entry = entries[id] ?: return
            val attached = entry.latestScreen.get()
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
        screenToFinish?.finishAfterServiceClose()
    }

    private fun completionIfReady(id: String, entry: Entry): (() -> Unit)? {
        val reason = entry.dismissalReason ?: return null
        if (entry.attachedScreens.isNotEmpty()) return null
        entries.remove(id)
        entry.state.value = PresentationContentState.Closed(reason)
        entry.detached.complete(Unit)
        if (!entry.terminal.compareAndSet(false, true)) return null
        return when (reason) {
            is CloseReason.Error -> ({ entry.callbacks.onFailure(reason.cause) })
            else -> ({ entry.callbacks.onDismissed(reason) })
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
        val nativeRecovery: Boolean,
        val firstFrame: CompletableDeferred<ExperienceRef>,
        val closed: AtomicBoolean = AtomicBoolean(false),
        val shown: AtomicBoolean = AtomicBoolean(false),
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
        // Shown and the run-terminal observation are one fact-pairing decision.
        val factLock: Any = Any(),
        // Every close path observes the same first-terminal run transition.
        val runTransitionFinished: CompletableDeferred<Unit> = CompletableDeferred(),
        val outcomeReason: AtomicReference<CloseReason?> = AtomicReference(),
        var nativePreparationFinished: CompletableDeferred<Unit>? = null,
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
        var navigationDismissal: NavigationDismissal? = null,
        var navigationHistory: List<String> = emptyList(),
        var lifecycleByScreen: MutableMap<String, ExperienceScreenLifecycle> = mutableMapOf(),
        var textInputsByScreen: MutableMap<String, ExperienceTextInputState> = mutableMapOf(),
        var viewModelsByScreen: MutableMap<String, AtomicReference<NuxieViewModelSnapshot?>> = mutableMapOf(),
        var commerce: JourneyCommerceSession? = null,
    )

    private data class NavigationDismissal(
        val destination: String,
        val result: Deferred<JourneyScreenDismissalResult>,
    )

    private suspend fun dismissForNavigation(outgoing: JourneyOutcome, destination: String): JourneyScreenDismissalResult {
        val checkpoint = synchronized(outgoing) {
            outgoing.navigationDismissal?.let {
                if (it.destination != destination) throw supersededByIdentityTransition()
                return@synchronized it
            }
            if (!outgoing.screenDismissed.compareAndSet(false, true)) throw supersededByIdentityTransition()
            // Admission belongs to the screen. Cancelling a caller stops waiting,
            // but must not cancel or forget a checkpoint that can durably commit.
            NavigationDismissal(destination, scope.async {
                try {
                    outgoing.onScreenDismissed(outgoing.screenId, destination, "navigate")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    JourneyScreenDismissalResult.REJECTED
                }
            }).also { outgoing.navigationDismissal = it }
        }
        return checkpoint.result.await()
    }

    private data class PreparedSource(
        val identity: ai.nuxie.sdk.experiences.JourneyReleaseIdentity,
        val descriptor: JsonObject,
        val acquired: AcquiredJourneyRelease,
        val screen: AuthenticatedPresentationScreen,
        val commerceSession: JourneyCommerceSession?,
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

    private class PreparationAttempt(
        val request: PresentationRequest,
        val journeyId: String?,
        val job: CompletableJob,
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private var preparation: PreparationAttempt? = null

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
        transition: JsonObject? = null,
    ): ExperienceRef {
        val reserved = reservation as? JourneyReservation
        val request = reserved?.request ?: captureRequest(ownerDistinctId)
        if (request.ownerDistinctId != ownerDistinctId) throw declinedPresentation()
        val selectedScreen = AuthenticatedPresentationScreen.resolve(release, screenId)
        return presentPrepared(
            selectedScreen = selectedScreen,
            transition = transition,
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
            ),
            canPresent = canPresent,
        ) {
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
            currentCoroutineContext().ensureActive()
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
            PreparedSource(
                identity = release.identity,
                descriptor = release.descriptor,
                acquired = acquire(),
                screen = selectedScreen,
                commerceSession = commerceSession,
                viewModelProjection = viewModelProjection,
            )
        }
    }

    private suspend fun presentPrepared(
        selectedScreen: AuthenticatedPresentationScreen,
        request: PresentationRequest,
        journeyId: String?,
        reservationId: String?,
        reservationRequired: Boolean,
        journey: JourneyOutcome,
        canPresent: () -> Boolean = { true },
        transition: JsonObject? = null,
        prepare: suspend () -> PreparedSource,
    ): ExperienceRef {
        // stateLock atomically claims transitionInProgress below. Do not queue another
        // request behind acquisition, native preparation, or a future recovery wait.
        val active = run {
            val attempt = PreparationAttempt(request, journeyId, Job(currentCoroutineContext()[Job]))
            var transitionClaimed = false
            var published = false
            var acquiringId: String? = null
            var acquiringDetached: Deferred<Unit>? = null
            var recovery: ExperienceAcquisitionRecovery<PreparedSource>? = null
            val acquiringFailure = AtomicReference<Throwable?>()
            var unownedAcquisition: AcquiredJourneyRelease? = null
            var navigation: PreparedScreenNavigation? = null
            var navigationRecoveryOwner: String? = null
            val navigationRecoveryToken = UUID.randomUUID().toString()
            val nativePreparationFinished = CompletableDeferred<Unit>()
            try {
                val existing = synchronized(stateLock) {
                    if (!attempt.job.isActive || !isCurrentIdentity(request) || !canPresent()) {
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
                    preparation = attempt
                    active
                }
                if (!runtimeAvailable()) {
                    throw ExperiencePresentationException(
                        ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE,
                        "Experience renderer is unavailable on this device",
                    )
                }

                if (existing == null) {
                    synchronized(stateLock) {
                        if (!attempt.job.isActive || !isCurrentIdentity(request) || !canPresent()) {
                            throw supersededByIdentityTransition()
                        }
                        val id = UUID.randomUUID().toString()
                        acquiringId = id
                        val controller = ExperienceAcquisitionRecovery<PreparedSource>(
                            publish = { PresentationRegistry.updateAcquisition(id, it) },
                        )
                        recovery = controller
                        acquiringDetached = PresentationRegistry.registerAcquiring(id, selectedScreen, controller::retry) { reason ->
                            if (reason is CloseReason.Error) acquiringFailure.compareAndSet(null, reason.cause)
                            attempt.job.cancel()
                        }
                        launch(id)
                    }
                }

                val id = acquiringId ?: UUID.randomUUID().toString()
                fun checkOwnership() = synchronized(stateLock) {
                    if (!attempt.job.isActive || !isCurrentIdentity(request) || !canPresent() || current !== existing ||
                        existing?.outcomeReason?.get() != null) throw supersededByIdentityTransition()
                }
                fun contentFor(source: PreparedSource, isolated: Boolean): PreparedPresentation {
                    val sameRelease = existing?.acquired?.identity?.let { previous ->
                        previous.streamKey == source.identity.streamKey && previous.experienceVersionId == source.identity.experienceVersionId &&
                            previous.buildId == source.identity.buildId
                    } == true
                    val texts = if (sameRelease) existing!!.journey.textInputsByScreen else journey.textInputsByScreen
                    val lifecycles = if (sameRelease) existing!!.journey.lifecycleByScreen else journey.lifecycleByScreen
                    val text = texts[journey.screenId]?.let { if (isolated) it.copyForPreparation() else it } ?: ExperienceTextInputState()
                    val lifecycle = lifecycles[journey.screenId]?.let { if (isolated) it.copyForPreparation() else it } ?: ExperienceScreenLifecycle()
                    val retained = if (existing?.acquired?.identity == source.identity)
                        existing.journey.viewModelsByScreen[journey.screenId]?.get() else null
                    return PreparedPresentation(source.acquired.rivFile, source.screen.artboardName, source.screen.clearColor,
                        source.screen.shell, source.screen.screenId, source.descriptor, source.acquired.artifactsByKey,
                        source.screen.artboardSize, source.viewModelProjection, text, lifecycle, transition,
                        AtomicReference(retained))
                }
                data class Destination(val source: PreparedSource, val content: PreparedPresentation, val navigation: PreparedScreenNavigation?)
                suspend fun acquireInitial(): PreparedSource = try {
                    withContext(attempt.job) {
                        // Capture ownership before withContext can discard a late result on cancellation.
                        val source = recovery?.acquire(
                            prepare = {
                                synchronized(stateLock) {
                                    if (!isCurrentIdentity(request) || !canPresent() ||
                                        (reservationRequired && pendingReservation?.id != reservationId)) {
                                        throw supersededByIdentityTransition()
                                    }
                                }
                                prepare()
                            },
                            release = { it.acquired.close() },
                            recoverable = { error -> error !is ExperiencePresentationException ||
                                error.reason in setOf(ExperiencePresentationException.Reason.ACQUISITION_FAILED,
                                    ExperiencePresentationException.Reason.PRODUCTS_UNAVAILABLE) },
                            onFailure = { error -> ai.nuxie.sdk.logging.NuxieLog.w(
                                "ExperiencePresentation", "Experience acquisition requires recovery", error) },
                        ) ?: prepare()
                        source.also { unownedAcquisition = it.acquired }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error is ExperiencePresentationException) throw error
                    throw ExperiencePresentationException(
                        ExperiencePresentationException.Reason.ACQUISITION_FAILED,
                        "Experience artifact acquisition failed: ${error.message ?: "unknown error"}",
                        error,
                    )
                }

                val destination = if (existing == null) {
                    val source = acquireInitial()
                    checkOwnership()
                    Destination(source, contentFor(source, false), null)
                } else {
                    val controller = ExperienceAcquisitionRecovery<Destination>(publish = {
                        PresentationRegistry.updateNavigationRecovery(existing.id, navigationRecoveryToken, it)
                    })
                    if (!PresentationRegistry.beginNavigationRecovery(existing.id, navigationRecoveryToken, controller::retry)) {
                        throw supersededByIdentityTransition()
                    }
                    navigationRecoveryOwner = existing.id
                    synchronized(stateLock) {
                        checkOwnership()
                        existing.nativePreparationFinished = nativePreparationFinished
                    }
                    withContext(attempt.job) {
                        controller.acquire(
                            prepare = {
                                checkOwnership()
                                var acquired: PreparedSource? = null
                                var provisional: PreparedScreenNavigation? = null
                                try {
                                    val source = prepare().also { acquired = it }
                                    currentCoroutineContext().ensureActive()
                                    checkOwnership()
                                    val content = contentFor(source, true)
                                    provisional = withTimeout(firstFrameTimeoutMillis) {
                                        PresentationRegistry.currentScreen(existing.id)?.prepareNavigation(id, content).also { provisional = it }
                                    }
                                    provisional?.awaitExit()
                                    currentCoroutineContext().ensureActive()
                                    checkOwnership()
                                    Destination(source, content, provisional)
                                } catch (error: Throwable) {
                                    withContext(NonCancellable) {
                                        try { provisional?.abort() } finally { acquired?.acquired?.close() }
                                    }
                                    throw error
                                }
                            },
                            release = { retired -> withContext(NonCancellable) {
                                try { retired.navigation?.abort() } finally { retired.source.acquired.close() }
                            } },
                            recoverable = { error -> error !is ExperiencePresentationException || error.reason in setOf(
                                ExperiencePresentationException.Reason.ACQUISITION_FAILED,
                                ExperiencePresentationException.Reason.PRODUCTS_UNAVAILABLE,
                                ExperiencePresentationException.Reason.PREPARATION_FAILED,
                                ExperiencePresentationException.Reason.HOST_FAILED,
                                ExperiencePresentationException.Reason.FIRST_FRAME_TIMEOUT) },
                            onFailure = { error -> ai.nuxie.sdk.logging.NuxieLog.w("ExperiencePresentation", "Navigation preparation requires recovery", error) },
                        ).also { unownedAcquisition = it.source.acquired; navigation = it.navigation }
                    }.also { PresentationRegistry.endNavigationRecovery(existing.id, navigationRecoveryToken) }
                }
                val source = destination.source
                val preparedContent = destination.content
                val textInputState = preparedContent.textInputState
                navigation = destination.navigation
                journey.commerce = source.commerceSession
                existing?.takeIf {
                    val previous = it.acquired.identity
                    previous.streamKey == source.identity.streamKey && previous.experienceVersionId == source.identity.experienceVersionId &&
                        previous.buildId == source.identity.buildId
                }?.let {
                    journey.textInputsByScreen = it.journey.textInputsByScreen
                    journey.lifecycleByScreen = it.journey.lifecycleByScreen
                    if (it.acquired.identity == source.identity) journey.viewModelsByScreen = it.journey.viewModelsByScreen
                }
                val ref = ExperienceRef(source.identity.experienceId, source.identity.experienceVersionId, journeyId)
                val pending = ActivePresentation(id = id, ref = ref, acquired = source.acquired,
                    ownerDistinctId = request.ownerDistinctId, journey = journey, nativeRecovery = acquiringId != null,
                    firstFrame = CompletableDeferred())
                checkOwnership()

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
                    val dismissal = dismissForNavigation(outgoing, incoming.screenId)
                    synchronized(stateLock) {
                        if (!attempt.job.isActive || !isCurrentIdentity(request) || !canPresent() || current !== existing ||
                            it.outcomeReason.get() != null) {
                            throw supersededByIdentityTransition()
                        }
                    }
                    if (dismissal == JourneyScreenDismissalResult.COMPLETED ||
                        dismissal == JourneyScreenDismissalResult.REJECTED) {
                        navigation?.abort()
                        navigation = null
                    }
                    outgoing.textInputsByScreen[outgoing.screenId]?.detach()
                    PresentationRegistry.dismiss(it.id, CloseReason.JourneyNavigation)
                    attemptOutcome(it, CloseReason.JourneyNavigation)
                    if (navigation == null) it.finished.await()
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
                        JourneyScreenDismissalResult.HANDLED -> Unit
                    }
                }

                val launched = synchronized(stateLock) {
                    val reservationStillMatches = reservationId != null &&
                        pendingReservation?.id == reservationId &&
                        pendingReservation?.request == request
                    if (!attempt.job.isActive || !isCurrentIdentity(request) || !canPresent() ||
                        (existing == null && reservationRequired && !reservationStillMatches) ||
                        (navigation != null && current !== existing) ||
                        existing?.outcomeReason?.get()?.let { it != CloseReason.JourneyNavigation } == true
                    ) {
                        false
                    } else {
                        // Identity shutdown and late launch admission share this
                        // transition: teardown either invalidates the epoch first
                        // or observes a fully registered presentation afterward.
                        if (reservationStillMatches) pendingReservation = null
                        journey.textInputsByScreen[journey.screenId] = preparedContent.textInputState
                        journey.lifecycleByScreen[journey.screenId] = preparedContent.screenLifecycle
                        journey.viewModelsByScreen[journey.screenId] = preparedContent.retainedViewModel
                        current = pending
                        try {
                            PresentationRegistry.register(
                            requiresAcquiring = acquiringId != null,
                            id = id,
                            content = preparedContent,
                            onFirstFrame = { firstFrame(pending) },
                            onFailure = { error -> failed(pending, error) },
                            onDismissed = { reason -> ended(pending, reason) },
                            onOutcome = { reason -> attemptOutcome(pending, reason) },
                            onRuntimeStep = { outcome, correlationId, snapshot, lifetime ->
                                pending.latestViewModelSnapshot.set(snapshot)
                                runtimeStep(pending, outcome, correlationId, lifetime, snapshot)
                            },
                            onTextCommitted = { inputId, text, lifetime ->
                                publishScreenEffects(pending) {
                                    pending.journey.emissions.publishTextCommit(inputId, text, textInputState, lifetime)
                                }
                            },
                        )
                        } catch (error: Throwable) {
                            current = existing
                            throw error
                        }
                        unownedAcquisition = null // The registered presentation now owns cleanup.
                        try {
                            if (navigation != null) navigation.activate()
                            else if (acquiringId == null) launch(id)
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
                if (!launched) throw supersededByIdentityTransition()
                pending
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { navigation?.abort() }.exceptionOrNull()?.let(error::addSuppressed)
                }
                runCatching { unownedAcquisition?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                if (error is CancellationException && !attempt.job.isActive &&
                    currentCoroutineContext()[Job]?.isActive == true) {
                    acquiringFailure.get()?.let { failure ->
                        throw (failure as? ExperiencePresentationException ?: ExperiencePresentationException(
                            ExperiencePresentationException.Reason.HOST_FAILED, "Authenticated shell failed", failure,
                        ))
                    }
                    // Owned withdrawal is a presentation result, not cancellation of the Journey worker.
                    throw ExperiencePresentationException(
                        ExperiencePresentationException.Reason.SUPERSEDED,
                        "Presentation preparation was withdrawn", error,
                    )
                }
                throw error
            } finally {
                navigationRecoveryOwner?.let { PresentationRegistry.endNavigationRecovery(it, navigationRecoveryToken) }
                if (!published) withContext(NonCancellable) {
                    acquiringId?.let { PresentationRegistry.dismiss(it, CloseReason.HostDismissed) }
                    acquiringDetached?.await()
                }
                synchronized(stateLock) {
                    if (preparation === attempt) preparation = null
                    if (transitionClaimed) {
                        transitionInProgress = false
                        if (!published && reservationId != null && pendingReservation?.id == reservationId) {
                            pendingReservation = null
                        }
                    }
                }
                attempt.job.complete()
                nativePreparationFinished.complete(Unit)
                attempt.finished.complete(Unit)
            }
        }
        val nativeGeneration = PresentationRegistry.nativeProgress(active.id)?.generation
        return try {
            withTimeout(firstFrameTimeoutMillis) { active.firstFrame.await() }
        } catch (_: TimeoutCancellationException) {
            val timeout = ExperiencePresentationException(
                ExperiencePresentationException.Reason.FIRST_FRAME_TIMEOUT,
                "Experience presentation did not attach and render its first frame in time",
            )
            if (active.nativeRecovery) {
                PresentationRegistry.recoverNative(active.id, null, nativeGeneration, timeout)
                active.firstFrame.await()
            } else {
                PresentationRegistry.reportFailure(active.id, timeout)
                throw timeout
            }
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
        if (source != null && source.screenId != active.journey.screenId) return null
        val placement = action["placementId"] ?: return null
        val placementId = when (placement) {
            is JsonPrimitive -> placement.takeIf(JsonPrimitive::isString)?.content
            is JsonObject -> when (placement.keys) {
                setOf("literal") -> (placement["literal"] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)?.content
                setOf("ref") -> {
                    val reference = placement["ref"] as? JsonObject ?: return null
                    if (reference.string("kind") != "path") return null
                    val path = reference.string("path") ?: return null
                    val model = reference["viewModelName"]?.let {
                        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null
                    }
                    val relative = reference["isRelative"]?.let {
                        (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: return null
                    }
                    active.latestViewModelSnapshot.get()?.resolveScopedString(path, model, source?.instanceId, relative)
                }
                else -> null
            }
            else -> null
        }?.takeIf(String::isNotEmpty) ?: return null
        return JsonObject(action + ("placementId" to JsonPrimitive(placementId)))
    }

    /** Activity currently owning the presented Experience; called by native checkout UI on Main. */
    internal fun purchaseActivity(): Activity? {
        val id = synchronized(stateLock) { current?.id } ?: return null
        return PresentationRegistry.currentScreen(id)?.purchaseActivity()
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
                val activity = PresentationRegistry.currentScreen(active.id)?.purchaseActivity()
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
        val host = PresentationRegistry.currentScreen(active.id)
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
        val active = synchronized(stateLock) {
            preparation?.job?.cancel()
            current
        }
        active?.let { active ->
            PresentationRegistry.dismiss(active.id, reason)
            attemptOutcome(active, reason)
        }
    }

    suspend fun dismissFromHost(initiatingDistinctId: String) {
        val (active, attempt) = synchronized(stateLock) {
            preparation?.job?.cancel()
            current to preparation
        }
        if (active == null) {
            attempt?.finished?.await()
            return
        }
        val teardownReason = if (active.ownerDistinctId == initiatingDistinctId) {
            CloseReason.HostDismissed
        } else {
            CloseReason.IdentityChanged
        }
        // Screen teardown is unconditional and starts before outcome work.
        beforeHostTeardownForTesting()
        PresentationRegistry.dismiss(active.id, teardownReason)
        attemptOutcome(active, teardownReason)
        val nativePreparation = synchronized(stateLock) { active.nativePreparationFinished }
        joinAll(active.finished, active.runTransitionFinished)
        nativePreparation?.await()
        attempt?.finished?.await()
    }

    /**
     * Tears down a presentation owned by the departing customer and attempts
     * an owner-attributed identity terminal transition without a close fact.
     */
    suspend fun shutdownOwnedBy(ownerDistinctId: String) {
        val (active, attempt) = synchronized(stateLock) {
            val departing = current?.takeIf { it.ownerDistinctId == ownerDistinctId }
            identityEpochByOwner[ownerDistinctId] =
                (identityEpochByOwner[ownerDistinctId] ?: 0L) + 1L
            if (pendingReservation?.request?.ownerDistinctId == ownerDistinctId) {
                pendingReservation = null
            }
            val pending = preparation?.takeIf { it.request.ownerDistinctId == ownerDistinctId }
                ?.also { it.job.cancel() }
            departing to pending
        }
        if (active == null) {
            attempt?.finished?.await()
            return
        }
        PresentationRegistry.dismiss(active.id, CloseReason.IdentityChanged)
        attemptOutcome(active, CloseReason.IdentityChanged)
        val nativePreparation = synchronized(stateLock) { active.nativePreparationFinished }
        joinAll(active.finished, active.runTransitionFinished)
        nativePreparation?.await()
        attempt?.finished?.await()
    }

    /** Closes only the terminal Journey surface without injecting another outcome. */
    suspend fun shutdownJourney(ownerDistinctId: String, journeyId: String) {
        val (active, attempt) = synchronized(stateLock) {
            val pending = preparation?.takeIf {
                it.request.ownerDistinctId == ownerDistinctId && it.journeyId == journeyId
            }?.also { it.job.cancel() }
            current?.takeIf { it.isOwnedBy(journeyId, ownerDistinctId) } to pending
        }
        if (active == null) {
            attempt?.finished?.await()
            return
        }
        PresentationRegistry.dismiss(active.id, CloseReason.JourneyNavigation)
        attemptOutcome(active, CloseReason.JourneyNavigation)
        // The caller may be the Journey worker completing a terminal action.
        // An earlier identity/host outcome can be queued to that same worker;
        // joining it here would prevent the worker from ever processing it.
        // Its outer shutdown owner still joins runTransitionFinished.
        active.finished.await()
        attempt?.finished?.await()
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
            val result = journey.emissions.reveal { PresentationRegistry.reveal(active.id) }
            if (result == JourneyRuntimeEmissionCoordinator.RevealResult.VISIBLE) {
                if (markShown(active)) active.firstFrame.complete(active.ref)
            } else if (result == JourneyRuntimeEmissionCoordinator.RevealResult.REJECTED && !active.closed.get()) {
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
                active.outcomeReason.get() != null ||
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
        lifetime: RendererEffectLifetime?,
        snapshot: NuxieViewModelSnapshot?,
    ) {
        publishScreenEffects(active) { active.journey.emissions.publish(outcome, correlationId, lifetime, snapshot) }
    }

    private fun publishScreenEffects(active: ActivePresentation, publish: suspend () -> Boolean) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val accepted = runCatching { publish() }.getOrDefault(false)
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
        val wasShown = synchronized(active.factLock) {
            if (!active.outcomeReason.compareAndSet(null, reason)) return
            active.shown.get()
        }
        if (reason == CloseReason.JourneyNavigation) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                active.journey.emissions.close()
            }
            active.runTransitionFinished.complete(Unit)
            return
        }
        synchronized(stateLock) {
            if (current === active || current == null) {
                preparation?.takeIf {
                    it.journeyId == active.ref.journeyId &&
                        it.request.ownerDistinctId == active.ownerDistinctId
                }?.job?.cancel()
            }
        }
        val journey = active.journey
        // Close checkpoint admission under the same lock as navigation. A
        // cancelled navigation waiter does not cancel its durable callback.
        val (checkpoint, ownsDismissal) = synchronized(journey) {
            journey.navigationDismissal to journey.screenDismissed.compareAndSet(false, true)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                journey.emissions.close()
                val admittedDismissal = checkpoint?.result?.await()
                val dismissal = reason.screenDismissalMethod()?.takeIf { wasShown }?.let { method ->
                    if (ownsDismissal) {
                        journey.onScreenDismissed(
                            journey.screenId,
                            null,
                            method,
                        )
                    } else {
                        admittedDismissal ?: JourneyScreenDismissalResult.HANDLED
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
                    wasShown && reason != CloseReason.IdentityChanged
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

private const val FIRST_FRAME_TIMEOUT_MILLIS = 30_000L
