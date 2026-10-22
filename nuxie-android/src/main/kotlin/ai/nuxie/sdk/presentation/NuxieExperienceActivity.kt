package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import ai.nuxie.sdk.logging.NuxieLog as Log
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ScreenCloseState(
    private val report: (CloseReason) -> Unit = {},
) {
    private val selected = AtomicReference<CloseReason?>(null)
    private val reported = AtomicBoolean(false)

    val reason: CloseReason? get() = selected.get()

    fun select(reason: CloseReason): Boolean = selected.compareAndSet(null, reason)

    fun prepareForTeardown(isChangingConfigurations: Boolean) {
        if (reason == null && isChangingConfigurations) return
        select(CloseReason.UserDismissed)
    }

    fun reportAtTeardown(isChangingConfigurations: Boolean) {
        prepareForTeardown(isChangingConfigurations)
        if (reason == null) return
        if (reported.compareAndSet(false, true)) report(requireNotNull(reason))
    }
}

/**
 * The engine-owned Activity hosts a stack of presentation layers. The Intent carries
 * only a process-local presentation id; authenticated content and callbacks
 * remain owned by [ExperiencePresentationService].
 *
 * Process-death policy (decision 10): a cold-recreated instance (no live
 * SDK state behind it) finishes immediately and never re-presents.
 */
internal class NuxieExperienceActivity : Activity() {
    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var registryObservation: kotlinx.coroutines.Job? = null
    private var acquiringScreen: AcquiringScreen? = null
    private var loadingView: ExperienceLoadingView? = null
    private var recoveryView: ExperienceRecoveryView? = null
    private var recoveryTimer: kotlinx.coroutines.Job? = null
    private var acquisitionProgress: AcquisitionProgress? = null
    private var recoveryOwnerToken: String? = null
    private var currentScreen: Screen? = null
    private inner class Layer(val root: FrameLayout, var screen: Screen, val shell: PresentationShell) {
        var sheet: ExperienceNativeLayerWindow? = null
    }
    private val layers = mutableListOf<Layer>()
    private var baseWindow: ExperienceNativeLayerWindow? = null
    /** The platform already finished dismissing the base sheet window; nothing is left to animate. */
    private var baseWindowDismissed = false
    private var closingLayer: Layer? = null
    private var restoringLayerScreen: Screen? = null
    private val screens = mutableSetOf<Screen>()
    private var navigation: Navigation? = null
    private lateinit var contentRoot: FrameLayout
    private var visible = false
    private var dismissible = true
    private var predictiveBackCallback: android.window.OnBackInvokedCallback? = null
    private var pendingPermission: Pair<Int, CompletableDeferred<Boolean>>? = null
    private var nextPermissionRequestCode = PERMISSION_REQUEST_CODE_START

    private inner class AcquiringScreen(val id: String) : PresentationScreenHandle {
        private val closeState = ScreenCloseState { PresentationRegistry.reportDismissed(id, it) }
        override fun requestCloseFromService(reason: CloseReason) = closeState.select(reason)
        override fun screenCloseReason() = closeState.reason
        override fun finishAfterServiceClose() { runOnUiThread { finish() } }
        fun close(recreating: Boolean) {
            closeState.reportAtTeardown(recreating)
            PresentationRegistry.detach(id, this)
        }
        fun finishTerminal(reason: CloseReason) {
            closeState.select(reason)
            PresentationRegistry.reportOutcome(id, reason)
            finish()
        }
    }

    /** Registry ownership belongs to this screen, even when the Activity hosts another one. */
    private inner class Screen(val id: String, val prepared: PreparedPresentation, @Volatile var provisional: Boolean = false) :
        PresentationScreenHandle, ExperienceSurfaceHost.Listener {
        override val rendererEffects = RendererEffectLifetime()
        override val nativeAttemptGeneration = PresentationRegistry.nativeProgress(id)?.generation
        var registered = false
        var failure: Throwable? = null
            private set
        var view: View? = null
            private set
        val ready = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        private var closing = false
        private var finishingAfterExit = false
        private var revealed = false
        private val effectsLock = Any()
        private val pendingEffects = mutableListOf<() -> Unit>()
        var mounted: ExperienceMountedScreen? = null
            private set
        private val closeState = ScreenCloseState { reason ->
            when (reason) {
                is CloseReason.Error -> PresentationRegistry.reportFailure(id, reason.cause)
                else -> PresentationRegistry.reportDismissed(id, reason)
            }
        }

        fun mount(covered: Boolean = false): View {
            val resources = ExperienceMountedScreen(
                this@NuxieExperienceActivity, prepared, this, ::fail,
                videoDecoderPool = ai.nuxie.sdk.runtime.ExperienceVideoDecoderPool.shared,
            )
            mounted = resources
            return resources.mount(entering = !covered).also { view = it }
        }

        fun close(changingConfigurations: Boolean) {
            if (closing) return
            closing = true
            rendererEffects.retire()
            synchronized(effectsLock) { pendingEffects.clear() }
            ready.completeExceptionally(IllegalStateException("Screen closed during preparation"))
            closeState.prepareForTeardown(changingConfigurations)
            val complete: () -> Unit = {
                if (registered) {
                    closeState.reportAtTeardown(changingConfigurations)
                    PresentationRegistry.detach(id, this)
                }
                closed.complete(Unit)
            }
            mounted?.close(changingConfigurations, complete) ?: complete()
            mounted = null
        }

        override fun requestCloseFromService(reason: CloseReason): Boolean = closeState.select(reason)
        override fun screenCloseReason(): CloseReason? = closeState.reason
        override fun finishAfterServiceClose() {
            runOnUiThread {
                if (finishingAfterExit || closing || isDestroyed) return@runOnUiThread
                val reason = if (closeState.reason == CloseReason.JourneyNavigation)
                    ExperienceScreenLifecycle.ExitReason.NAVIGATION else ExperienceScreenLifecycle.ExitReason.DISMISSED
                if (closeState.reason == CloseReason.JourneyNavigation &&
                    (navigation?.source === this || currentScreen !== this || closingLayer?.screen === this)) {
                    // A navigation source, a covered layer or a closing layer never finishes the Activity.
                    if (navigation?.source === this && currentScreen === this && closingLayer?.screen !== this) {
                        mounted?.exit(reason)
                    }
                } else if (revealed && currentScreen === this && closingLayer?.screen !== this && !baseWindowDismissed &&
                        (closeState.reason == CloseReason.UserDismissed ||
                            closeState.reason == CloseReason.HostDismissed ||
                            closeState.reason == CloseReason.JourneyDismissed)) {
                    // Only the revealed current screen plays its compiled exit. A sheet the
                    // platform already dismissed has no window left, so it finishes at once.
                    finishingAfterExit = true
                    layers.forEach { it.sheet?.setInputEnabled(false); it.screen.mounted?.setInputEnabled(false) }
                    registryScope.launch {
                        try {
                            mounted?.awaitExit(reason)
                        } finally {
                            if (!closing && !isDestroyed) finish()
                        }
                    }
                } else {
                    mounted?.exit(reason)
                    finish()
                }
            }
        }
        override suspend fun retireForRetry() = withContext(Dispatchers.Main.immediate) {
            close(true)
            closed.await()
        }
        override suspend fun dismissLayerFromJourney(): Boolean = withContext(Dispatchers.Main.immediate) {
            // A native close of this same layer is already running. It reports the dismissal
            // itself, and that report may be queued behind the Journey work waiting here.
            if (closingLayer?.screen === this@Screen) return@withContext true
            val layer = layers.lastOrNull()?.takeIf { it.screen === this@Screen } ?: return@withContext false
            closeLayer(layer, authored = true)
        }
        override suspend fun prepareNavigation(id: String, content: PreparedPresentation): PreparedScreenNavigation =
            prepareScreenNavigation(this, id, content)

        override suspend fun updateRuntimeValues(values: Map<String, ai.nuxie.sdk.runtime.NuxieViewModelScalarValue>): Unit = withContext(Dispatchers.Main.immediate) {
            mounted?.surface?.updateRuntimeValues(values)
        }
        override fun purchaseActivity(): Activity = this@NuxieExperienceActivity
        override suspend fun resolveJourneyPermission(request: JourneyPermissionRequest): Boolean =
            this@NuxieExperienceActivity.resolveJourneyPermission(request)

        var measuredContentHeight: Float? = null
            private set
        var contentHeightReady = false
            private set
        override fun onPresentationHeight(height: Float?) {
            if (closing || isDestroyed || isFinishing) return
            if (prepared.fitsContentHeight) prepared.layerContentHeight.set(height)
            measuredContentHeight = prepared.layerContentHeight.get()
            contentHeightReady = true
            layers.firstOrNull { it.screen === this }?.sheet?.updateContentHeight(measuredContentHeight)
        }

        override fun onFirstFrame() {
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (provisional) {
                    mounted?.setVisible(false)
                    ready.complete(Unit)
                } else {
                    requestReveal()
                }
            }
        }
        override fun onRuntimeStep(
            outcome: ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome,
            correlationId: ULong,
            viewModelSnapshot: NuxieViewModelSnapshot?,
            saves: List<ExperienceResponseSaveRequest>,
        ) {
            val weakScreen = java.lang.ref.WeakReference(this)
            val frameSaves = saves.map { request ->
                ExperienceFrameSave(request, checkNotNull(prepared.screenId)) {
                    withContext(Dispatchers.Main.immediate) {
                        val screen = weakScreen.get()
                        if (screen != null && !screen.rendererEffects.isRetired && screen.closeState.reason == null &&
                            PresentationRegistry.currentScreen(screen.id) === screen) {
                            request.awaitTrigger?.let { screen.mounted?.surface?.confirmResponseSave(it, request.kept) }
                        }
                    }
                }
            }
            synchronized(effectsLock) {
                if (provisional) pendingEffects += {
                    PresentationRegistry.reportRuntimeStep(id, outcome, correlationId, viewModelSnapshot, this, frameSaves)
                } else if (closeState.reason == null) {
                    PresentationRegistry.reportRuntimeStep(id, outcome, correlationId, viewModelSnapshot, this, frameSaves)
                }
            }
        }

        fun retainCoveredFrame() {
            synchronized(effectsLock) {
                provisional = false
                pendingEffects.clear()
            }
            mounted?.setVisible(visible)
        }

        fun publishPreparedFrame() {
            synchronized(effectsLock) {
                provisional = false
                requestReveal()
                pendingEffects.forEach { it() }
                pendingEffects.clear()
            }
        }

        private fun requestReveal() {
            PresentationRegistry.reportFirstFrame(id, this) {
                withContext(Dispatchers.Main.immediate) {
                    if (closing || isDestroyed || isFinishing || closeState.reason != null ||
                        currentScreen !== this@Screen || !PresentationRegistry.canReveal(id, this@Screen)) {
                        false
                    } else {
                        if (!revealed) {
                            revealed = true
                            dismissible = layers.lastOrNull()?.shell?.dismissible ?: prepared.shell.dismissible
                            layers.lastOrNull()?.sheet?.setInputEnabled(dismissible)
                            contentRoot.background = null
                            view?.alpha = 1f
                            clearAcquisitionRecovery()
                            removeLoadingView()
                            mounted?.activate()
                            (PresentationRegistry.observe(id)?.value as? PresentationContentState.Ready)?.navigationRecovery
                                ?.let { updateNavigationRecovery(id, it) }
                        }
                        true
                    }
                }
            }
        }
        override fun onRuntimeEvent(event: NuxieRuntimeEvent, viewModelSnapshot: NuxieViewModelSnapshot?) = Unit
        override fun onTextCommitted(inputId: String, text: String, snapshot: NuxieViewModelSnapshot?) =
            PresentationRegistry.reportTextCommitted(id, this, inputId, text, snapshot)
        override fun onTextInputEvent(inputId: String, event: ExperienceSemanticTextDraft.Event) =
            PresentationRegistry.reportTextInputEvent(id, this, inputId, event)
        override fun onFailure(error: ExperiencePresentationException) = fail(error)

        fun hasRevealed(): Boolean = revealed

        fun fail(error: Throwable) {
            failure = error
            if (provisional) {
                ready.completeExceptionally(error)
                mounted?.transitionEvents?.close()
            } else if (!closing && !revealed && currentScreen === this &&
                PresentationRegistry.recoverNative(id, this, nativeAttemptGeneration, error)) {
                mounted?.setVisible(false)
            } else if (!closing && currentScreen === this && rendererEffects.isRetired.not()) {
                finishTerminal(CloseReason.Error(error))
            }
        }
        fun finishTerminal(reason: CloseReason) {
            if (closeState.select(reason)) {
                finishAfterServiceClose()
                PresentationRegistry.reportOutcome(id, reason)
            }
        }
    }

    private inner class Navigation(val source: Screen, val target: Screen) : PreparedScreenNavigation {
        var blocksInput = false
            private set
        private var animation: ExperienceScreenViewTransition? = null
        private var sourceAccessibility: Int? = null
        private var targetAccessibility: Int? = null

        fun freezeInput() {
            blocksInput = true
            source.mounted?.setInputEnabled(false)
            layers.lastOrNull()?.sheet?.setInputEnabled(false)
            sourceAccessibility = source.view?.importantForAccessibility
            source.view?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        private fun restoreInput() {
            sourceAccessibility?.let { source.view?.importantForAccessibility = it }
            targetAccessibility?.let { target.view?.importantForAccessibility = it }
            sourceAccessibility = null
            targetAccessibility = null
            source.mounted?.setInputEnabled(true)
            layers.lastOrNull()?.let { it.sheet?.setInputEnabled(it.shell.dismissible) }
            blocksInput = false
        }

        fun setVisible(visible: Boolean, preservePendingInput: Boolean = false) {
            animation?.setVisible(visible)
            if (blocksInput) target.mounted?.setVisible(visible, preservePendingInput)
        }

        fun cancel() { animation?.cancel() }

        override suspend fun awaitExit() = withContext(Dispatchers.Main.immediate) {
            check(navigation === this@Navigation && currentScreen === source && !isFinishing && !isDestroyed) {
                "Navigation source is no longer active"
            }
            val render = target.prepared.descriptor?.get("render") as? JsonObject
            val plan = ExperienceScreenTransitionPlan.resolve(
                target.prepared.transition,
                render?.get("transitions") as? JsonArray ?: JsonArray(emptyList()),
                checkNotNull(source.prepared.screenId), checkNotNull(target.prepared.screenId),
            )
            val animate = plan.shouldAnimate(source.mounted?.reduceMotion == true)
            if (!target.prepared.opensLayer && (plan.kind != ExperienceScreenTransitionPlan.Kind.CUSTOM || !animate)) source.mounted?.awaitExit(ExperienceScreenLifecycle.ExitReason.NAVIGATION)
            check(navigation === this@Navigation && !isFinishing && !isDestroyed)
            if (animate && !target.prepared.opensLayer) {
                val outgoing = checkNotNull(source.view)
                val incoming = checkNotNull(target.view)
                blocksInput = true
                outgoing.clearFocus()
                targetAccessibility = incoming.importantForAccessibility
                outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                incoming.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                val custom = plan.custom
                if (custom != null) {
                    val outgoingScreen = checkNotNull(source.mounted)
                    val incomingScreen = checkNotNull(target.mounted)
                    outgoingScreen.transitionEvents.performWith(incomingScreen.transitionEvents, custom) {
                        incoming.alpha = 1f
                        if (custom.incomingOnTop) incoming.bringToFront() else outgoing.bringToFront()
                        incomingScreen.setVisible(visible)
                        coroutineScope {
                            val outgoingPhase = async { outgoingScreen.beginCustomTransition(custom.id, outgoing = true) }
                            val incomingPhase = async { incomingScreen.beginCustomTransition(custom.id, outgoing = false) }
                            outgoingPhase.await()
                            incomingPhase.await()
                        }
                    }
                } else {
                    animation = ExperienceScreenViewTransition(outgoing, incoming, plan.kind)
                    target.mounted?.setVisible(visible)
                    animation!!.play(visible)
                }
            }
            target.failure?.let { throw it }
            Unit
        }

        override fun activate() {
            runOnUiThread {
                if (isDestroyed || isFinishing || navigation !== this || target.failure != null ||
                    !PresentationRegistry.attach(target.id, target)) {
                    target.close(false)
                    target.closed.invokeOnCompletion {
                        PresentationRegistry.reportFailure(target.id, IllegalStateException("Navigation host was withdrawn"))
                    }
                    finish()
                    return@runOnUiThread
                }
                restoreInput()
                target.registered = true
                currentScreen = target
                dismissible = layers.lastOrNull()?.shell?.dismissible ?: true
                intent.putExtra(EXTRA_PRESENTATION_ID, target.id)
                target.view?.alpha = 1f
                if (target.prepared.opensLayer) {
                    source.mounted?.setInputEnabled(false)
                    layers.lastOrNull()?.sheet?.setInputEnabled(false)
                    val root = ExperienceFocusRoot(this@NuxieExperienceActivity)
                    (target.view?.parent as? android.view.ViewGroup)?.removeView(target.view)
                    root.addView(target.view, FrameLayout.LayoutParams(-1, -1))
                    val layer = Layer(root, target, target.prepared.shell)
                    layers += layer
                    contentRoot = root
                    installLayerWindow(layer)
                    target.mounted?.observeWindow()
                } else {
                    layers.lastOrNull()?.let {
                        it.screen = target
                    }
                    target.view?.bringToFront()
                    source.view?.let(contentRoot::removeView)
                    source.close(false)
                    screens.remove(source)
                }
                navigation = null
                observePresentation(target.id, checkNotNull(PresentationRegistry.observe(target.id)))
                target.publishPreparedFrame()
                target.mounted?.setInputEnabled(true)
                target.mounted?.setVisible(visible)
            }
        }

        override suspend fun abort() = withContext(NonCancellable + Dispatchers.Main.immediate) {
            animation?.restoreSource()
            restoreInput()
            if (navigation === this@Navigation) navigation = null
            target.view?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
            target.close(false)
            screens.remove(target)
            if (currentScreen === source) {
                if (source.screenCloseReason() != null) finish()
                else if (closingLayer == null && !target.prepared.opensLayer) source.mounted?.activate()
            }
            target.closed.await()
        }
    }

    private suspend fun prepareScreenNavigation(source: Screen, id: String, prepared: PreparedPresentation): PreparedScreenNavigation {
        var pending: Navigation? = null
        try {
            return withContext(Dispatchers.Main.immediate) {
                check(currentScreen === source && navigation == null && !isFinishing && !isDestroyed) {
                    "Navigation source is no longer active"
                }
                val target = Screen(id, prepared, provisional = true)
                val candidate = Navigation(source, target)
                // Capture ownership before the main-thread handoff can discard a cancelled result.
                pending = candidate
                navigation = candidate
                candidate.freezeInput()
                prepared.textInputState.refreshBeforeMount()
                screens += target
                val targetView = target.mount().apply { alpha = 0f }
                target.mounted?.setInputEnabled(false)
                val params = if (prepared.fitsContentHeight) {
                    val size = ExperienceNativeLayerWindow.availableSize(this@NuxieExperienceActivity, prepared.shell)
                    val density = resources.displayMetrics.density
                    FrameLayout.LayoutParams((size.width * density).toInt(), (size.height * density).toInt())
                } else FrameLayout.LayoutParams(-1, -1)
                contentRoot.addView(targetView, 0, params)
                // A new layer's screen is parked in its presenter until its own window shows it:
                // it is laid out for that layer's space, and observes real insets once installed.
                if (prepared.opensLayer) target.mounted?.assumeLayerInsets(prepared.shell)
                else target.mounted?.observeWindow()
                target.mounted?.setVisible(visible)
                target.ready.await()
                candidate
            }
        } catch (error: Throwable) {
            pending?.abort()
            throw error
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_PRESENTATION_ID)
        if (isColdRecreation(savedInstanceState, id) || id == null) {
            finish()
            return
        }
        if (!AndroidRenderCapability.isAvailable()) {
            PresentationRegistry.reportFailure(id, ExperiencePresentationException(
                ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE,
                "Experience renderer is unavailable on this device",
            ))
            finish()
            return
        }
        val state = PresentationRegistry.observe(id)
        if (state == null) {
            Log.i(LOG_TAG, "Presentation state unavailable; finishing.")
            finish()
            return
        }
        registerPredictiveBack()
        observePresentation(id, state)
    }

    private fun observePresentation(id: String, state: kotlinx.coroutines.flow.StateFlow<PresentationContentState>) {
        registryObservation?.cancel()
        registryObservation = registryScope.launch {
            state.collect { content ->
                if (isFinishing || isDestroyed) return@collect
                when (content) {
                    is PresentationContentState.Acquiring -> {
                        if (acquiringScreen == null && currentScreen == null) {
                            val pending = AcquiringScreen(id)
                            if (!PresentationRegistry.attach(id, pending)) { finish(); return@collect }
                            acquiringScreen = pending
                            // A failed/slow acquisition must always permit leaving the native shell.
                            dismissible = true
                            contentRoot = ExperienceFocusRoot(this@NuxieExperienceActivity).apply { setBackgroundColor(content.screen.clearColor) }
                            setContentView(shellView(contentRoot, content.screen.shell))
                            contentRoot.setBackgroundColor(content.screen.clearColor)
                            loadingView = ExperienceLoadingView(this@NuxieExperienceActivity, content.screen.clearColor).also {
                                contentRoot.addView(it, FrameLayout.LayoutParams(-1, -1))
                                it.setActive(visible)
                            }
                        }
                        content.progress?.let { updateAcquisitionRecovery(id, content.screen, it) }
                    }
                    is PresentationContentState.Ready -> {
                        // An old screen's flow stays observed through initial recovery, but
                        // may not take ownership back after Journey navigation.
                        if (currentScreen != null && currentScreen?.id != id) return@collect
                        val navigationRecovery = content.navigationRecovery
                        if (navigationRecovery != null && currentScreen?.hasRevealed() == true) {
                            updateNavigationRecovery(id, navigationRecovery)
                            return@collect
                        }
                        if (currentScreen?.hasRevealed() == true && acquisitionProgress != null) clearAcquisitionRecovery()
                        val progress = content.progress
                        if (progress?.phase == AcquisitionProgress.Phase.RETRYING) {
                            retireNativeAttempt(id, content)
                            return@collect
                        }
                        if (currentScreen == null && progress != null) {
                            ensureNativeRecoveryShell(id, content.content)
                            if (progress.phase == AcquisitionProgress.Phase.FAILED) {
                                updateNativeRecovery(id, content.content, progress)
                                return@collect
                            }
                        }
                        if (currentScreen == null) {
                            clearAcquisitionRecovery()
                            mountReadyScreen(id, content.content)
                            acquiringScreen?.let { PresentationRegistry.detach(id, it) }
                            acquiringScreen = null
                            currentScreen?.takeUnless { it.rendererEffects.isRetired }?.mounted?.setVisible(visible)
                        }
                        if (progress != null && currentScreen?.hasRevealed() == false) {
                            updateNativeRecovery(id, content.content, progress)
                        }
                    }
                    is PresentationContentState.Closed -> {
                        if (currentScreen?.id == id || acquiringScreen?.id == id) finish()
                    }
                }
            }
        }
    }

    private fun updateNavigationRecovery(id: String, recovery: NavigationRecoveryState) {
        val progress = recovery.progress
        if (acquisitionProgress == progress && recoveryOwnerToken == recovery.token) return
        acquisitionProgress = progress
        recoveryOwnerToken = recovery.token
        recoveryTimer?.cancel()
        fun show() {
            val prepared = currentScreen?.takeIf { it.id == id }?.prepared ?: return
            removeRecoveryView()
            recoveryView = ExperienceRecoveryView(this, prepared.clearColor, progress.phase,
                retry = { PresentationRegistry.retryNavigation(id, recovery.token, progress.generation) },
                onClose = { finishTerminal(CloseReason.UserDismissed) },
            ).also { contentRoot.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        if (progress.phase != AcquisitionProgress.Phase.LOADING) show() else {
            removeRecoveryView()
            recoveryTimer = registryScope.launch {
                kotlinx.coroutines.delay((progress.startedAtMillis + 5_000 - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (acquisitionProgress == progress && recoveryOwnerToken == recovery.token && currentScreen?.id == id && !isFinishing && !isDestroyed) show()
            }
        }
    }

    private fun ensureNativeRecoveryShell(id: String, prepared: PreparedPresentation) {
        if (!::contentRoot.isInitialized) {
            contentRoot = ExperienceFocusRoot(this).apply { setBackgroundColor(prepared.clearColor) }
            setContentView(shellView(contentRoot, prepared.shell))
            loadingView = ExperienceLoadingView(this, prepared.clearColor).also {
                contentRoot.addView(it, FrameLayout.LayoutParams(-1, -1))
                it.setActive(visible)
            }
        }
        if (acquiringScreen == null && currentScreen == null) {
            val pending = AcquiringScreen(id)
            if (PresentationRegistry.attach(id, pending)) acquiringScreen = pending
        }
        dismissible = true
    }

    private suspend fun retireNativeAttempt(id: String, content: PresentationContentState.Ready) {
        val progress = checkNotNull(content.progress)
        ensureNativeRecoveryShell(id, content.content)
        val old = currentScreen
        val pending = AcquiringScreen(id)
        if (!PresentationRegistry.attach(id, pending)) return
        acquiringScreen?.let { PresentationRegistry.detach(id, it) }
        acquiringScreen = pending
        dismissible = true
        showNativeRecovery(id, content.content, progress)
        PresentationRegistry.drainNativeAttempts(id, progress.generation)
        old?.let {
            // Retire without selecting a terminal reason; the lease stays service-owned.
            it.close(true)
            it.closed.await()
            it.view?.let(contentRoot::removeView)
            screens.remove(it)
            if (currentScreen === it) currentScreen = null
        }
        if (isFinishing || isDestroyed) return
        content.content.screenLifecycle.resetUnrevealedAttempt()
        content.content.textInputState.resetUnrevealedAttempt()
        PresentationRegistry.nativeRetired(id, progress.generation)
    }

    private fun updateNativeRecovery(id: String, prepared: PreparedPresentation, progress: AcquisitionProgress) {
        if (acquisitionProgress == progress) return
        acquisitionProgress = progress
        recoveryTimer?.cancel()
        if (progress.phase != AcquisitionProgress.Phase.LOADING) {
            showNativeRecovery(id, prepared, progress)
        } else {
            removeRecoveryView()
            loadingView?.visibility = View.VISIBLE
            loadingView?.setActive(visible)
            recoveryTimer = registryScope.launch {
                kotlinx.coroutines.delay((progress.startedAtMillis + 5_000 - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (acquisitionProgress == progress && currentScreen?.id == id &&
                    currentScreen?.hasRevealed() == false && !isFinishing && !isDestroyed) {
                    showNativeRecovery(id, prepared, progress)
                }
            }
        }
    }

    private fun showNativeRecovery(id: String, prepared: PreparedPresentation, progress: AcquisitionProgress) {
        layers.lastOrNull()?.sheet?.updateContentHeight(null)
        removeRecoveryView()
        loadingView?.setActive(false)
        loadingView?.visibility = View.INVISIBLE
        recoveryView = ExperienceRecoveryView(this, prepared.clearColor, progress.phase,
            retry = { PresentationRegistry.retryNative(id, progress.generation) },
            onClose = { finishTerminal(CloseReason.UserDismissed) },
        ).also { contentRoot.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    private suspend fun mountReadyScreen(id: String, prepared: PreparedPresentation) {
        // Recreation must consume the prior renderer's final authored state before import.
        PresentationRegistry.drainRetiredScreens(id)
        try {
            if (layers.isEmpty() && prepared.layerAncestors.isNotEmpty()) {
                for (ancestorId in prepared.layerAncestors) {
                    val ancestor = (PresentationRegistry.observe(ancestorId)?.value as? PresentationContentState.Ready)?.content
                        ?: throw IllegalStateException("Covered presentation is no longer owned")
                    PresentationRegistry.drainRetiredScreens(ancestorId)
                    val retained = Screen(ancestorId, ancestor, provisional = true)
                    check(PresentationRegistry.attach(ancestorId, retained))
                    retained.registered = true
                    screens += retained
                    val root = ExperienceFocusRoot(this)
                    root.addView(retained.mount(covered = true), FrameLayout.LayoutParams(-1, -1))
                    val layer = Layer(root, retained, ancestor.shell)
                    layers += layer
                    if (layers.size == 1) {
                        setContentView(shellView(root, ancestor.shell))
                        layer.sheet = baseWindow
                    } else installLayerWindow(layer)
                    retained.mounted?.observeWindow()
                    restoringLayerScreen = retained
                    retained.mounted?.setInputEnabled(false)
                    retained.mounted?.setVisible(visible)
                    retained.ready.await()
                    retained.retainCoveredFrame()
                    restoringLayerScreen = null
                    retained.mounted?.setInputEnabled(false)
                    layer.sheet?.setInputEnabled(false)
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            PresentationRegistry.reportFailure(id, error)
            finish()
            return
        } finally { restoringLayerScreen = null }
        val screen = Screen(id, prepared)
        if (!PresentationRegistry.attach(id, screen)) {
            finish()
            return
        }
        screen.registered = true
        screens += screen
        currentScreen = screen
        // Authored dismissal restrictions apply only once content is revealed.
        dismissible = true
        try {
            val retryLayer = layers.lastOrNull()?.takeIf { it.screen.id == id }
            val restoredUpperLayer = retryLayer == null && layers.isNotEmpty() && prepared.layerAncestors.isNotEmpty()
            val newRoot = !::contentRoot.isInitialized || restoredUpperLayer
            if (newRoot) contentRoot = ExperienceFocusRoot(this)
            contentRoot.addView(screen.mount().apply { alpha = 0f }, 0, FrameLayout.LayoutParams(-1, -1))
            if (retryLayer != null) retryLayer.screen = screen else {
                val layer = Layer(contentRoot, screen, prepared.shell)
                layers += layer
                if (restoredUpperLayer) installLayerWindow(layer)
                else {
                    if (newRoot) setContentView(shellView(contentRoot, prepared.shell))
                    layer.sheet = baseWindow
                }
            }
            contentRoot.setBackgroundColor(prepared.clearColor)
            screen.mounted?.observeWindow()
        } catch (error: Throwable) {
            screen.fail(error)
            return
        }
    }

    private fun installLayerWindow(layer: Layer) {
        layer.sheet = ExperienceNativeLayerWindow(this, layer.root, layer.shell) { requestLayerClose(layer) }.also {
            it.setInputEnabled(false)
            if (layer.screen.contentHeightReady) it.updateContentHeight(layer.screen.measuredContentHeight)
            it.show()
        }
    }

    private fun closeTopLayerOrExperience() {
        if (!dismissible || navigation != null || closingLayer != null) return
        if (layers.size > 1) layers.last().sheet?.closeFromUser()
        else finishTerminal(CloseReason.UserDismissed)
    }

    private fun requestLayerClose(layer: Layer) {
        registryScope.launch { closeLayer(layer, authored = false) }
    }

    private suspend fun closeLayer(layer: Layer, authored: Boolean): Boolean {
        if (layers.lastOrNull() !== layer || layers.size < 2 || closingLayer != null) return false
        closingLayer = layer
        layers.forEach { it.sheet?.setInputEnabled(false) }
        val source = layer.screen
        source.mounted?.setInputEnabled(false)
        try {
            if (authored) source.mounted?.awaitExit(ExperienceScreenLifecycle.ExitReason.DISMISSED)
            else source.mounted?.exit(ExperienceScreenLifecycle.ExitReason.DISMISSED)
            source.mounted?.hide()
            if (authored) layer.sheet?.retire()
            val accepted = source.prepared.closeLayer?.invoke(object : PreparedLayerReveal {
                override val method = if (authored) "journey" else "native_sheet"
                override suspend fun prepare() = withContext(Dispatchers.Main.immediate) {
                    check(layers.lastOrNull() === layer && !isFinishing && !isDestroyed)
                    layers.removeAt(layers.lastIndex)
                    layer.sheet?.retire()
                    val lower = layers.last()
                    currentScreen = lower.screen
                    contentRoot = lower.root
                    intent.putExtra(EXTRA_PRESENTATION_ID, lower.screen.id)
                    observePresentation(lower.screen.id, checkNotNull(PresentationRegistry.observe(lower.screen.id)))
                    source.close(false)
                    screens.remove(source)
                    Unit
                }
                override fun activate() {
                    runOnUiThread {
                        if (isFinishing || isDestroyed || closingLayer !== layer) return@runOnUiThread
                        val lower = layers.last()
                        dismissible = lower.shell.dismissible
                        lower.sheet?.setInputEnabled(dismissible)
                        lower.screen.mounted?.setInputEnabled(true)
                        lower.screen.mounted?.setVisible(visible)
                    }
                }
            }) == true
            if (!accepted) finishTerminal(CloseReason.Error(IllegalStateException("Layer reveal was rejected")))
            return accepted
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            finishTerminal(CloseReason.Error(error))
            return false
        } finally { closingLayer = null }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Sheet chrome follows a light or dark switch while its window stays open.
        (layers.mapNotNull { it.sheet } + listOfNotNull(baseWindow)).distinct().forEach { it.refreshChrome() }
        // Keep incoming and outgoing screens synchronized during navigation.
        screens.forEach { screen ->
            if (!screen.rendererEffects.isRetired) screen.mounted?.let { mounted ->
                mounted.refreshFontScale(newConfig.fontScale)
                mounted.surface.refreshColorScheme(newConfig.uiMode)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        loadingView?.setActive(recoveryView == null)
        navigation?.setVisible(true)
        screens.forEach { if (!it.rendererEffects.isRetired && (layers.any { layer -> layer.screen === it } || it === navigation?.target || it === restoringLayerScreen)) it.mounted?.setVisible(true) }
    }

    override fun onStop() {
        visible = false
        loadingView?.setActive(false)
        navigation?.setVisible(false, preservePendingInput = true)
        screens.forEach { it.mounted?.setVisible(false, preservePendingInput = true) }
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        if (navigation?.blocksInput == true && event.keyCode != android.view.KeyEvent.KEYCODE_BACK) true
        else super.dispatchKeyEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (navigation?.blocksInput == true) true else super.dispatchTouchEvent(event)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        closeTopLayerOrExperience()
    }

    override fun onDestroy() {
        registryScope.cancel()
        clearAcquisitionRecovery()
        removeLoadingView()
        acquiringScreen?.close(isChangingConfigurations)
        acquiringScreen = null
        navigation?.cancel()
        unregisterPredictiveBack()
        super.onDestroy()
        layers.forEach { it.sheet?.retire() }
        baseWindow?.retire()
        baseWindow = null
        screens.forEach {
            if (!isChangingConfigurations && it !== currentScreen) it.requestCloseFromService(CloseReason.JourneyNavigation)
            it.close(isChangingConfigurations && (it !== navigation?.target))
        }
        layers.clear()
        screens.clear()
        currentScreen = null
        pendingPermission?.second?.complete(false)
        pendingPermission = null
    }

    private fun removeLoadingView() {
        loadingView?.let {
            it.close()
            // First frame can arrive inside display-list traversal. Stop drawing now,
            // but defer child-list mutation until traversal has finished.
            contentRoot.post { contentRoot.removeView(it) }
        }
        loadingView = null
    }

    private fun updateAcquisitionRecovery(id: String, screen: AuthenticatedPresentationScreen, progress: AcquisitionProgress) {
        if (acquisitionProgress == progress) return
        acquisitionProgress = progress
        recoveryTimer?.cancel()
        if (progress.phase != AcquisitionProgress.Phase.LOADING) {
            showAcquisitionRecovery(id, screen, progress)
        } else {
            removeRecoveryView()
            loadingView?.visibility = View.VISIBLE
            loadingView?.setActive(visible)
            recoveryTimer = registryScope.launch {
                kotlinx.coroutines.delay((progress.startedAtMillis + 5_000 - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (acquisitionProgress == progress && acquiringScreen?.id == id && !isFinishing && !isDestroyed) {
                    showAcquisitionRecovery(id, screen, progress)
                }
            }
        }
    }

    private fun showAcquisitionRecovery(id: String, screen: AuthenticatedPresentationScreen, progress: AcquisitionProgress) {
        baseWindow?.updateContentHeight(null)
        removeRecoveryView()
        loadingView?.setActive(false)
        loadingView?.visibility = View.INVISIBLE
        recoveryView = ExperienceRecoveryView(this, screen.clearColor, progress.phase,
            retry = { PresentationRegistry.retryAcquisition(id, progress.generation) },
            onClose = { finishTerminal(CloseReason.UserDismissed) },
        ).also { contentRoot.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun removeRecoveryView() {
        recoveryView?.let {
            it.close()
            it.visibility = View.INVISIBLE
            // First-frame reveal may still be traversing this child list.
            contentRoot.post { contentRoot.removeView(it) }
        }
        recoveryView = null
    }

    private fun clearAcquisitionRecovery() {
        recoveryTimer?.cancel()
        recoveryTimer = null
        acquisitionProgress = null
        recoveryOwnerToken = null
        removeRecoveryView()
        loadingView?.visibility = View.VISIBLE
        loadingView?.setActive(visible)
    }

    private suspend fun resolveJourneyPermission(request: JourneyPermissionRequest): Boolean =
        withContext(Dispatchers.Main.immediate) {
            when (request) {
                JourneyPermissionRequest.TRACKING,
                JourneyPermissionRequest.UNSUPPORTED,
                -> false
                JourneyPermissionRequest.NOTIFICATIONS -> resolveNotificationPermission()
                else -> resolveRuntimePermission(request.androidPermission())
            }
        }

    @Deprecated("Deprecated in Android SDK; required for minSdk-compatible permission delivery")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val pending = pendingPermission?.takeIf { it.first == requestCode } ?: return
        pendingPermission = null
        pending.second.complete(
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED },
        )
    }

    private suspend fun resolveNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) {
            return if (Build.VERSION.SDK_INT < 24) {
                true
            } else {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .areNotificationsEnabled()
            }
        }
        return resolveRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
    }

    private suspend fun resolveRuntimePermission(permission: String): Boolean {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .contains(permission)
        }.getOrDefault(false)
        if (!declared || pendingPermission != null || isFinishing || isDestroyed) return false
        val requestCode = nextPermissionRequestCode
        nextPermissionRequestCode = if (requestCode == PERMISSION_REQUEST_CODE_END) {
            PERMISSION_REQUEST_CODE_START
        } else {
            requestCode + 1
        }
        val result = CompletableDeferred<Boolean>()
        pendingPermission = requestCode to result
        requestPermissions(arrayOf(permission), requestCode)
        return try {
            result.await()
        } finally {
            if (pendingPermission?.first == requestCode) pendingPermission = null
        }
    }

    private fun JourneyPermissionRequest.androidPermission(): String = when (this) {
        JourneyPermissionRequest.CAMERA -> Manifest.permission.CAMERA
        JourneyPermissionRequest.LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
        JourneyPermissionRequest.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        JourneyPermissionRequest.PHOTOS -> if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        else -> error("Permission request has no Android runtime permission")
    }

    private fun finishTerminal(reason: CloseReason) {
        acquiringScreen?.finishTerminal(reason) ?: currentScreen?.finishTerminal(reason)
    }

    private fun shellView(host: View, shell: PresentationShell): View {
        baseWindow?.retire()
        baseWindow = null
        host.layoutParams = FrameLayout.LayoutParams(-1, -1)
        if (shell is PresentationShell.FullScreen) return host
        // The first layer uses the same native sheet as upper layers, including acquisition.
        val window = ExperienceNativeLayerWindow(this, host, shell) {
            baseWindowDismissed = true
            finishTerminal(CloseReason.UserDismissed)
        }
        baseWindow = window
        window.setInputEnabled(true, allowDismissalDuringLoading = true)
        window.show()
        return ExperienceFocusRoot(this)
    }

    private fun registerPredictiveBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val callback = android.window.OnBackInvokedCallback {
            closeTopLayerOrExperience()
        }
        predictiveBackCallback = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback,
        )
    }

    private fun unregisterPredictiveBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        predictiveBackCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        predictiveBackCallback = null
    }

    internal companion object {
        const val LOG_TAG = "Nuxie"

        const val EXTRA_PRESENTATION_ID = "ai.nuxie.sdk.internal.PRESENTATION_ID"
        const val PERMISSION_REQUEST_CODE_START = 0x4E00
        const val PERMISSION_REQUEST_CODE_END = 0x4EFF

        internal fun isColdRecreation(savedInstanceState: Bundle?, presentationId: String?): Boolean =
            savedInstanceState != null &&
                (presentationId == null || PresentationRegistry.observe(presentationId) == null)
    }
}
