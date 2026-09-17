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
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * The single engine-owned Activity hosting every signed presentation style
 * as in-Activity shells (spec section 16 decision 9). The Intent carries
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
        val ready = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        private var closing = false
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

        fun mount(): View {
            val resources = ExperienceMountedScreen(
                this@NuxieExperienceActivity, prepared, this, ::fail,
            )
            mounted = resources
            return resources.mount().also { view = it }
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
                mounted?.exit()
                if (closeState.reason != CloseReason.JourneyNavigation || navigation?.source !== this) finish()
            }
        }
        override suspend fun retireForRetry() = withContext(Dispatchers.Main.immediate) {
            close(true)
            closed.await()
        }
        override suspend fun prepareNavigation(id: String, content: PreparedPresentation): PreparedScreenNavigation =
            prepareScreenNavigation(this, id, content)

        override fun purchaseActivity(): Activity = this@NuxieExperienceActivity
        override suspend fun resolveJourneyPermission(request: JourneyPermissionRequest): Boolean =
            this@NuxieExperienceActivity.resolveJourneyPermission(request)

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
        ) {
            synchronized(effectsLock) {
                if (provisional) pendingEffects += {
                    PresentationRegistry.reportRuntimeStep(id, outcome, correlationId, viewModelSnapshot, this)
                } else if (closeState.reason == null) {
                    PresentationRegistry.reportRuntimeStep(id, outcome, correlationId, viewModelSnapshot, this)
                }
            }
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
                            dismissible = prepared.shell.dismissible
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
        override fun onTextCommitted(inputId: String, text: String) =
            PresentationRegistry.reportTextCommitted(id, this, inputId, text)
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
            if (closeState.select(reason)) finishAfterServiceClose()
            PresentationRegistry.reportOutcome(id, reason)
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
            sourceAccessibility = source.view?.importantForAccessibility
            source.view?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        private fun restoreInput() {
            sourceAccessibility?.let { source.view?.importantForAccessibility = it }
            targetAccessibility?.let { target.view?.importantForAccessibility = it }
            sourceAccessibility = null
            targetAccessibility = null
            source.mounted?.setInputEnabled(true)
            blocksInput = false
        }

        fun setVisible(visible: Boolean) {
            animation?.setVisible(visible)
            if (blocksInput) target.mounted?.setVisible(visible)
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
            if (plan.kind != ExperienceScreenTransitionPlan.Kind.CUSTOM || !animate) source.mounted?.awaitExit()
            check(navigation === this@Navigation && !isFinishing && !isDestroyed)
            if (animate) {
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
                        outgoingScreen.beginCustomTransition(custom.id, outgoing = true)
                        incomingScreen.beginCustomTransition(custom.id, outgoing = false)
                        incomingScreen.setVisible(visible)
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
                dismissible = true
                if (target.prepared.shell != source.prepared.shell) {
                    (contentRoot.parent as? android.view.ViewGroup)?.removeView(contentRoot)
                    setContentView(shellView(contentRoot, target.prepared.shell))
                }
                intent.putExtra(EXTRA_PRESENTATION_ID, target.id)
                target.view?.alpha = 1f
                target.view?.bringToFront()
                source.view?.let(contentRoot::removeView)
                source.close(false)
                screens.remove(source)
                navigation = null
                observePresentation(target.id, checkNotNull(PresentationRegistry.observe(target.id)))
                target.publishPreparedFrame()
                target.mounted?.setVisible(visible)
            }
        }

        override suspend fun abort() = withContext(NonCancellable + Dispatchers.Main.immediate) {
            animation?.restoreSource()
            restoreInput()
            if (navigation === this@Navigation) navigation = null
            target.view?.let(contentRoot::removeView)
            target.close(false)
            screens.remove(target)
            if (currentScreen === source) {
                if (source.screenCloseReason() != null) finish()
                else source.mounted?.activate()
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
                contentRoot.addView(target.mount().apply { alpha = 0f }, 0, FrameLayout.LayoutParams(-1, -1))
                target.mounted?.observeWindow()
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
        removeRecoveryView()
        loadingView?.setActive(false)
        loadingView?.visibility = View.INVISIBLE
        recoveryView = ExperienceRecoveryView(this, prepared.clearColor, progress.phase,
            retry = { PresentationRegistry.retryNative(id, progress.generation) },
            onClose = { finishTerminal(CloseReason.UserDismissed) },
        ).also { contentRoot.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun mountReadyScreen(id: String, prepared: PreparedPresentation) {
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
            val newRoot = !::contentRoot.isInitialized
            if (newRoot) contentRoot = ExperienceFocusRoot(this)
            contentRoot.addView(screen.mount().apply { alpha = 0f }, 0, FrameLayout.LayoutParams(-1, -1))
            if (newRoot) setContentView(shellView(contentRoot, prepared.shell))
            contentRoot.setBackgroundColor(prepared.clearColor)
            screen.mounted?.observeWindow()
        } catch (error: Throwable) {
            screen.fail(error)
            return
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep incoming and outgoing screens synchronized during navigation.
        screens.forEach { screen ->
            if (!screen.rendererEffects.isRetired) screen.mounted?.refreshFontScale(newConfig.fontScale)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        loadingView?.setActive(recoveryView == null)
        navigation?.setVisible(true)
        screens.forEach { if (!it.rendererEffects.isRetired && (!it.provisional || !it.ready.isCompleted)) it.mounted?.setVisible(true) }
    }

    override fun onStop() {
        visible = false
        loadingView?.setActive(false)
        navigation?.setVisible(false)
        screens.forEach { it.mounted?.setVisible(false) }
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        if (navigation?.blocksInput == true && event.keyCode != android.view.KeyEvent.KEYCODE_BACK) true
        else super.dispatchKeyEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (navigation?.blocksInput == true) true else super.dispatchTouchEvent(event)

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (dismissible) finishTerminal(CloseReason.UserDismissed)
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
        screens.forEach { it.close(isChangingConfigurations && !it.provisional) }
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
        removeRecoveryView()
        loadingView?.setActive(false)
        loadingView?.visibility = View.INVISIBLE
        recoveryView = ExperienceRecoveryView(this, screen.clearColor, progress.phase,
            retry = { PresentationRegistry.retryAcquisition(id, progress.generation) },
            onClose = { finishTerminal(CloseReason.UserDismissed) },
        ).also { contentRoot.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun removeRecoveryView() {
        recoveryView?.let { it.close(); contentRoot.removeView(it) }
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
        host.clipToOutline = false
        host.outlineProvider = ViewOutlineProvider.BACKGROUND
        host.background = null
        host.isClickable = false
        host.layoutParams = FrameLayout.LayoutParams(-1, -1)
        if (shell is PresentationShell.FullScreen) return host

        val root = ExperienceFocusRoot(this)
        val scrim = View(this).apply {
            setBackgroundColor(SCRIM_COLOR)
            if (shell.dismissible) {
                setOnClickListener { finishTerminal(CloseReason.UserDismissed) }
            }
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        host.isClickable = true
        if (shell is PresentationShell.Drawer) {
            // Clip the runtime texture and the native editable controls to the same shell.
            applyRoundedOutline(host, shell.cornerRadiusDp)
        }
        root.addView(host, shellLayoutParams(shell))
        return root
    }

    private fun shellLayoutParams(shell: PresentationShell): FrameLayout.LayoutParams = when (shell) {
        PresentationShell.FullScreen -> FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        is PresentationShell.Sheet -> {
            val height = when (shell.detent) {
                PresentationShell.Sheet.Detent.MEDIUM -> resources.displayMetrics.heightPixels / 2
                PresentationShell.Sheet.Detent.LARGE -> FrameLayout.LayoutParams.MATCH_PARENT
            }
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM)
        }
        is PresentationShell.Drawer -> {
            val vertical = shell.edge == PresentationShell.Drawer.Edge.TOP ||
                shell.edge == PresentationShell.Drawer.Edge.BOTTOM
            val width = if (vertical) {
                FrameLayout.LayoutParams.MATCH_PARENT
            } else {
                (resources.displayMetrics.widthPixels * shell.extentRatio).toInt().coerceAtLeast(1)
            }
            val height = if (vertical) {
                (resources.displayMetrics.heightPixels * shell.extentRatio).toInt().coerceAtLeast(1)
            } else {
                FrameLayout.LayoutParams.MATCH_PARENT
            }
            val gravity = when (shell.edge) {
                PresentationShell.Drawer.Edge.TOP -> Gravity.TOP
                PresentationShell.Drawer.Edge.BOTTOM -> Gravity.BOTTOM
                PresentationShell.Drawer.Edge.LEADING -> Gravity.START
                PresentationShell.Drawer.Edge.TRAILING -> Gravity.END
            }
            FrameLayout.LayoutParams(width, height, gravity)
        }
    }

    private fun applyRoundedOutline(content: View, cornerRadiusDp: Float) {
        if (cornerRadiusDp <= 0f) return
        val radius = cornerRadiusDp * resources.displayMetrics.density
        content.background = GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = radius
        }
        content.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        content.clipToOutline = true
    }

    private fun registerPredictiveBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val callback = android.window.OnBackInvokedCallback {
            if (dismissible) finishTerminal(CloseReason.UserDismissed)
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
        const val SCRIM_COLOR = 0x66000000

        const val EXTRA_PRESENTATION_ID = "ai.nuxie.sdk.internal.PRESENTATION_ID"
        const val PERMISSION_REQUEST_CODE_START = 0x4E00
        const val PERMISSION_REQUEST_CODE_END = 0x4EFF

        internal fun isColdRecreation(savedInstanceState: Bundle?, presentationId: String?): Boolean =
            savedInstanceState != null &&
                (presentationId == null || PresentationRegistry.observe(presentationId) == null)
    }
}
