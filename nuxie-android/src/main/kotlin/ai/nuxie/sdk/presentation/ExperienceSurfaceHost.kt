package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperiencePageHostContract
import ai.nuxie.sdk.experiences.JourneyReleaseValuePolicy
import ai.nuxie.sdk.runtime.NuxieFocusInput
import ai.nuxie.sdk.runtime.NuxieFocusState
import ai.nuxie.sdk.runtime.ExperienceVideoPlayback
import ai.nuxie.sdk.experiences.ExperienceVideoAssetBinding
import ai.nuxie.sdk.experiences.ExperienceVideoElement
import kotlinx.coroutines.CompletableDeferred
import ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder
import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.experiences.SystemFontCache
import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NativeViewModelWrite
import ai.nuxie.sdk.runtime.NuxieSemanticSnapshot
import ai.nuxie.sdk.runtime.NuxieSemanticTree
import android.view.accessibility.AccessibilityNodeProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import ai.nuxie.sdk.runtime.NuxieAndroidVulkanRenderer
import ai.nuxie.sdk.runtime.NuxieTextGeometryCapture
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeArtboard
import ai.nuxie.sdk.runtime.NuxieRuntimeFile
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieRuntimePlayer
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeWindow
import ai.nuxie.sdk.runtime.NuxieRuntimeViewModelState
import ai.nuxie.sdk.runtime.NuxieViewModelMutationKind
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import ai.nuxie.sdk.runtime.NuxieViewModelListProjection
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import ai.nuxie.sdk.logging.NuxieLog as Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject

/**
 * TextureView host driving the engine's headless Android Vulkan renderer:
 * Kotlin owns the frame clock (Choreographer) and issues one render per frame
 * per the iOS frame-ownership contract; the engine has no independent loop.
 * Session and surface lifetimes are separate: surface destroy/recreate
 * preserves the renderer and player and only reacquires the window.
 *
 * Tracer scope: continuous rendering while the surface is visible. The
 * needsFrame/wake/idle settle contract refines with the presentation
 * service.
 */
internal class ExperienceSurfaceHost(
    context: Context,
    private val lane: NuxieRuntimeLane,
    private val clearColor: Int = CLEAR_COLOR_OPAQUE_BLACK,
    private val listener: Listener? = null,
    artboardSize: ExperienceArtboardSize? = null,
    private val runtime: NuxieRuntime = NuxieRuntime.shared,
    private val systemFontCache: SystemFontCache = SystemFontCache.shared,
    private val videoDecoderPool: ai.nuxie.sdk.runtime.ExperienceVideoDecoderPool? = null,
    private val usesSystemFrameCallbacks: Boolean = true,
    private val runValues: ExperienceRunValues? = null,
    private val fitsContentHeight: Boolean = false,
    private val initialPresentationSize: ExperienceArtboardSize? = null,
) : TextureView(context), TextureView.SurfaceTextureListener, Choreographer.FrameCallback {
    @Volatile private var layoutBounds = artboardSize
    private data class LayoutDelivery(val bounds: ExperienceArtboardSize, val contentHeight: Float?)
    // Written only after delivery on main; frame-lane measurements are not acknowledgements.
    @Volatile private var deliveredLayout: LayoutDelivery? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var requestedDensity = 0f
    private var surfaceLayout: ExperienceSurfaceLayout? = null
    private var appliedLayout: ExperienceSurfaceLayout? = null
    private var layoutStepPending = false
    private val presentationHeight = ai.nuxie.sdk.runtime.NuxiePresentationHeight()
    private val mainHandler = Handler(Looper.getMainLooper())
    interface Listener {
        fun onVideoCaptions(captions: Map<Long, ai.nuxie.sdk.runtime.NuxieVideoCaption>) {}
        fun onLayoutBounds(bounds: ExperienceArtboardSize) {}
        fun onPresentationHeight(height: Float?) {}
        fun onFirstFrame()
        fun onRuntimeStep(
            outcome: NuxiePlayerStepOutcome,
            correlationId: ULong,
            viewModelSnapshot: NuxieViewModelSnapshot?,
            saves: List<ExperienceResponseSaveRequest>,
        ) {}
        fun onLayerRuntimeStep(screenId: String, outcome: NuxiePlayerStepOutcome, correlationId: ULong,
            snapshot: NuxieViewModelSnapshot, saves: List<ExperienceResponseSaveRequest>) {}
        fun onLayerRuntimeEvent(screenId: String, event: NuxieRuntimeEvent, snapshot: NuxieViewModelSnapshot) {}
        fun onFailure(error: ExperiencePresentationException)
        fun onRuntimeEvent(event: NuxieRuntimeEvent, viewModelSnapshot: NuxieViewModelSnapshot?) {}
        /** UI-thread geometry update, after its frame has been presented. */
        fun onTextInputSnapshot(snapshot: NuxieViewModelSnapshot, geometry: NuxieTextGeometryCapture) {}
        /** Complete visible native-field association map, copied from the same presented capture. */
        fun onSemanticFields(fields: Map<String, NativeSemanticNode>): Map<Long, android.view.View> = emptyMap()
        /** Execution-only values for native editors; never part of accessibility tree diagnostics. */
        fun onNativeTextFields(fields: List<ExperienceNativeTextField>): Map<Long, android.view.View> = emptyMap()
        /** UI-thread callback after native fields and virtual controls share a committed tree. */
        fun onSemanticTreePublished() {}
        /** Runtime-lane callback ordered with writes and renderer publications. */
        fun onTextCommitted(inputId: String, text: String, snapshot: NuxieViewModelSnapshot?) {}
        fun onTextInputEvent(inputId: String, event: ExperienceSemanticTextDraft.Event) {}
    }

    /** Owned runtime wrappers; created, touched, and closed only on the runtime lane. */
    private var renderer: NuxieAndroidVulkanRenderer? = null
    private var window: NuxieRuntimeWindow? = null
    private var player: NuxieRuntimePlayer? = null
    private var interactiveLayer: ExperienceInteractivePage? = null
    private var activeRowId: String? = null
    private val layerRetainedValues = mutableMapOf<String, java.util.concurrent.atomic.AtomicReference<NuxieViewModelSnapshot?>>()

    /** Native mutations wait until this surface has delivered its submitted revision. */
    internal suspend fun <T> layerOperation(work: (ExperienceInteractivePage) -> T): T {
        val completion = CompletableDeferred<T>()
        val accepted = lane.enqueue {
            pendingTextWrites.addLast {
                if (completion.isActive) try {
                    check(!released.get() && !failureReported.get()) { "Layer surface is unavailable" }
                    completion.complete(work(checkNotNull(interactiveLayer)))
                } catch (error: Throwable) { completion.completeExceptionally(error) }
            }
            drainTextWrites()
        }
        if (!accepted) completion.cancel()
        try {
            if (kotlinx.coroutines.withTimeoutOrNull(250) { completion.await(); true } != true) {
                retireStalledLayerSubmission(completion)
            }
            return completion.await()
        } finally { completion.cancel() }
    }

    private suspend fun retireStalledLayerSubmission(completion: kotlinx.coroutines.Deferred<*>) = lane.call {
        if (!completion.isCompleted) {
            check(retireSubmission()) { "Experience layer submission retirement failed" }
            drainTextWrites()
            settleHiddenTransitions(force = true)
        }
    }

    internal suspend fun prepareLayerRow(prepared: PreparedPresentation) = layerOperation { layer ->
        val id = checkNotNull(prepared.screenId)
        layer.prepareOnLane(id, projection = prepared.viewModelProjection, snapshot = prepared.retainedViewModel.get(),
            lifecycle = prepared.screenLifecycle)
        layerRetainedValues[id] = prepared.retainedViewModel
        layer.applyRuntimeValuesOnLane(id, runtimeValues.filterKeys {
            it == "env/fontScale" || it == "env/reduceMotion" || it.startsWith("safeArea/")
        } + prepared.commerceValues.get())
    }

    internal fun takePageIncomingOnLane(page: ExperienceInteractivePage, target: String, source: String, isBack: Boolean) {
        page.takeIncomingOnLane(target, source, isBack, nextCorrelationId++) { step ->
            captureLayerStep(step)
            publishSteps()
        }
    }

    internal suspend fun selectLayerRow(screenId: String) = layerOperation { layer ->
        activeRowId = screenId
        viewModelState = layer.valuesOnLane(screenId)
        textInputs = layer.textInputsOnLane(screenId).associateBy(ExperienceTextInput::id)
    }

    internal suspend fun applyLayerTransition(presentationTimeoutMs: Long = 250,
        work: (ExperienceInteractivePage) -> Unit): Boolean {
        val completion = CompletableDeferred<Unit>()
        val accepted = lane.enqueue {
            if (released.get() || failureReported.get()) completion.cancel()
            else {
                transitionWrites.addLast(TransitionWrite(emptyMap(), completion) { work(checkNotNull(interactiveLayer)) })
                settleHiddenTransitions()
            }
        }
        if (!accepted) completion.cancel()
        try {
            if (kotlinx.coroutines.withTimeoutOrNull(presentationTimeoutMs) { completion.await(); true } == true) return true
            // A healthy renderer may never present. Retire its submission before settling the
            // phase on the lane, so later row mutations cannot race that submitted revision.
            retireStalledLayerSubmission(completion)
            completion.await()
            return false
        } finally { completion.cancel() }
    }

    internal suspend fun removeLayerRow(screenId: String, insertion: Pair<String, Int>? = null,
        waiters: List<Pair<String, kotlinx.coroutines.Deferred<Unit>>> = emptyList()) = layerOperation { layer ->
        waiters.forEach { (id, waiter) -> layer.removeCompletionOnLane(id, waiter) }
        insertion?.let { layer.insertOnLane(it.first, it.second) }
        val correlation = nextCorrelationId++
        captureLayerStep(layer.removeOnLane(screenId, correlation))
        publishSteps()
    }

    internal fun queueLayerPhases(phases: List<Pair<String, ExperienceScreenLifecycle.Phase>>,
        reason: ExperienceScreenLifecycle.ExitReason = ExperienceScreenLifecycle.ExitReason.NONE) {
        lane.enqueue {
            pendingTextWrites.addLast {
                if (!released.get() && !failureReported.get()) interactiveLayer?.setPhasesOnLane(phases, exitReason = reason)
            }
            drainTextWrites()
        }
    }

    internal suspend fun restoreLayerRow(sourceId: String, targetId: String, keepTarget: Boolean = false) = layerOperation { layer ->
        if (sourceId != targetId) {
            layer.cancelStepOnLane(sourceId)
            val targetMounted = targetId in layer.mountedScreenIdsOnLane()
            if (targetMounted) {
                if (keepTarget) layer.setCollapsedOnLane(targetId, true)
                else layer.removeOnLane(targetId)
            }
            layer.takeIncomingOnLane(sourceId)
            // Cancelled preparation may not have created the target row.
            layer.setPhasesOnLane(listOfNotNull((targetId to ExperienceScreenLifecycle.Phase.HIDDEN).takeIf { targetMounted },
                sourceId to ExperienceScreenLifecycle.Phase.ACTIVE))
        }
        publishSteps()
    }

    private var videoPlayback: ExperienceVideoPlayback? = null
    private var file: NuxieRuntimeFile? = null
    private var artboard: NuxieRuntimeArtboard? = null
    private var viewModelState: NuxieRuntimeViewModelState? = null
    private var environment: NuxieRuntimeViewModelState? = null
    private var environmentBindings: ExperienceEnvironmentBindings? = null
    private val themeInstances = mutableMapOf<ExperienceEnvironmentBindings.ThemeSelection, NuxieRuntimeViewModelState>()
    @Volatile private var requestedColorScheme = ExperienceColorScheme.fromUiMode(resources.configuration.uiMode)
    private var appliedColorScheme: ExperienceColorScheme? = null
    @Volatile private var semanticsEnabled = false
    private var semanticSnapshot: NuxieSemanticSnapshot? = null
    private var semanticSnapshotEpoch = -1L
    private var semanticFields: Map<String, NativeSemanticNode> = emptyMap()
    private var queuedSemanticAction: QueuedSemanticAction? = null
    private val semanticActionPending = AtomicBoolean(false)
    private val sceneInputEnabled = AtomicBoolean(true)
    private val semanticEpoch = AtomicLong()
    private val accessibility = ExperienceAccessibilityProvider(this,
        bounds = { semanticBounds(this, layoutBounds, it) }, dispatch = ::dispatchSemanticAction)

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.hover(event) || super.dispatchHoverEvent(event)

    fun semanticKeyboardEntry(direction: Int): android.view.View? = accessibility.keyboardEntry(direction)

    private val semanticKeys = mutableSetOf<Int>()
    private val riveKeys = mutableSetOf<Int>()
    @Volatile var riveFocusState = NuxieFocusState(false, false)
        private set

    fun dispatchExperienceKeyEvent(event: KeyEvent): Boolean {
        if (!running || !sceneInputEnabled.get() || released.get()) {
            semanticKeys.clear()
            riveKeys.clear()
            return false
        }
        val code = event.keyCode
        if (rootView.findFocus()?.onCheckIsTextEditor() == true) {
            riveKeys.clear()
            val authoredTab = code == KeyEvent.KEYCODE_TAB &&
                (event.hasNoModifiers() || event.hasModifiers(KeyEvent.META_SHIFT_ON))
            if (!authoredTab) { semanticKeys.clear(); return false }
            if (code in semanticKeys) {
                if (event.action == KeyEvent.ACTION_UP) { semanticKeys.remove(code); return true }
                return accessibility.key(event)
            }
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
            return accessibility.key(event).also { if (it) semanticKeys.add(code) }
        }
        if (code in semanticKeys) {
            if (event.action == KeyEvent.ACTION_UP) {
                semanticKeys.remove(code)
                return true
            }
            return accessibility.key(event)
        }
        if (code in riveKeys) {
            if (event.action == KeyEvent.ACTION_UP) riveKeys.remove(code)
            ExperienceHardwareKey.input(event)?.let(::receiveFocusInput)
            return true
        }
        // A repeat or release belongs to the owner that accepted its initial press.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (!riveFocusState.hasFocus && accessibility.hasKeyboardTargets) {
            val accepted = accessibility.key(event)
            if (accepted) semanticKeys.add(code)
            return accepted
        }
        if (code != KeyEvent.KEYCODE_TAB && !riveFocusState.hasFocus) return false
        val input = ExperienceHardwareKey.input(event) ?: return false
        val accepted = receiveFocusInput(input)
        if (accepted && event.action == KeyEvent.ACTION_DOWN) riveKeys.add(code)
        return accepted
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        dispatchExperienceKeyEvent(event) || super.dispatchKeyEvent(event)

    fun receiveFocusInput(input: NuxieFocusInput): Boolean {
        if (!running || !sceneInputEnabled.get() || released.get() || failureReported.get()) return false
        val generation = focusGeneration.get()
        return lane.enqueue {
            if (!released.get() && !failureReported.get() && generation == focusGeneration.get()) {
                if (queuedFocusGeneration != generation) {
                    focusInput.clear()
                    queuedFocusGeneration = generation
                }
                focusInput.add(input)
            }
        }
    }

    private fun discardFocusInput() {
        focusGeneration.incrementAndGet()
        lane.enqueue { focusInput.clear() }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibility.hostFocusChanged(gainFocus, direction)
    }

    private fun dispatchSemanticAction(tree: NuxieSemanticTree, nodeId: Long, action: Int): Boolean {
        if (!running || !sceneInputEnabled.get() || released.get() || !semanticActionPending.compareAndSet(false, true)) return false
        val generation = frameGeneration.get()
        val epoch = semanticEpoch.get()
        val accepted = lane.enqueue {
            queuedSemanticAction = QueuedSemanticAction(tree, nodeId, action, generation, epoch)
            drainSemanticAction()
        }
        if (!accepted) semanticActionPending.set(false)
        return accepted
    }

    /** Activation invalidates the render revision; never mutate an in-flight submission. */
    private fun drainSemanticAction() {
        val request = queuedSemanticAction ?: return
        if (pendingPresentation) return
        queuedSemanticAction = null
        try {
            val capture = semanticSnapshot ?: return
            val active = player ?: return
            if (!running || !sceneInputEnabled.get() || released.get() ||
                request.generation != frameGeneration.get() || request.epoch != semanticEpoch.get() ||
                capture.tree.treeVersion != request.tree.treeVersion ||
                capture.tree.nodes != request.tree.nodes) return
            // A completed frame may replace the render revision without changing
            // the accessibility tree. Preserve the exact node intent and submit
            // against that fresh capture; native validation still owns current
            // membership, supported actions and ancestor eligibility.
            active.queueSemanticAction(capture, request.nodeId, request.action)
        } finally { semanticActionPending.set(false) }
    }

    private data class QueuedSemanticAction(
        val tree: NuxieSemanticTree,
        val nodeId: Long,
        val action: Int,
        val generation: Long,
        val epoch: Long,
    )

    /** UI invalidation precedes queued native teardown, excluding already-posted old publications. */
    private fun retireSemantics(preserveFocus: Boolean = false) {
        semanticEpoch.incrementAndGet()
        if (preserveFocus) accessibility.withdraw() else accessibility.retire()
        if (semanticsEnabled) listener?.onSemanticFields(emptyMap())
        if (semanticsEnabled) listener?.onNativeTextFields(emptyList())
        lane.enqueue {
            semanticSnapshot?.close()
            semanticSnapshot = null
            nativeTextFields = emptyList()
            nativeTextOwners.values.forEach { it.close() }
            nativeTextOwners = emptyMap()
            acceptedNativeText.clear()
            queuedSemanticAction = null
            semanticActionPending.set(false)
        }
    }

    private var nativeTextFields: List<ExperienceNativeTextField> = emptyList()
    private var nativeTextCaptureId = 0L
    private var nativeTextOwners: Map<Long, ai.nuxie.sdk.runtime.NuxieFieldViewModel> = emptyMap()
    private class AcceptedNativeText(val text: String, val snapshot: NuxieViewModelSnapshot)
    private val acceptedNativeText = mutableMapOf<ExperienceTextFieldTarget, AcceptedNativeText>()

    private fun publishSemantics(active: NuxieRuntimePlayer, generation: Long, epoch: Long) {
        if (!semanticsEnabled || epoch != semanticEpoch.get() || (interactiveLayer != null && !sceneInputEnabled.get())) return
        val next = active.captureSemantics()
        val fields = emptyMap<String, NativeSemanticNode>()
        val nextCaptureId = nativeTextCaptureId + 1
        val nextOwners = mutableMapOf<Long, ai.nuxie.sdk.runtime.NuxieFieldViewModel>()
        val nativeFields = try {
            ExperienceNativeTextFieldCapture.read(textInputs.values.toList(), next.tree.nodes,
                nextCaptureId,
                geometry = { nodeId, name -> next.textInputGeometry(active.requireHandle(), nodeId, name) },
                readText = { nodeId, name -> next.readFieldString(active.requireHandle(), nodeId, name) },
                readOwner = { nodeId, name ->
                    val owner = checkNotNull(active.fieldOwner(next, nodeId, name)) { "Native input has no state owner" }
                    nextOwners[nodeId] = owner
                    owner.snapshot()
                })
        } catch (error: Throwable) {
            nextOwners.values.forEach { owner -> runCatching { owner.close() }.exceptionOrNull()?.let(error::addSuppressed) }
            next.close()
            throw error
        }
        semanticSnapshot?.close()
        nativeTextOwners.values.forEach { it.close() }
        nativeTextOwners = nextOwners
        nativeTextCaptureId = nextCaptureId
        nativeTextFields = nativeFields
        acceptedNativeText.entries.removeAll { (target, accepted) ->
            nativeFields.none { it.target == target && it.ownerId == accepted.snapshot.nativeRootInstanceId }
        }
        semanticFields = fields
        semanticSnapshot = next
        semanticSnapshotEpoch = epoch
        postSemanticTree(next.tree, fields, nativeFields, generation, epoch)
    }

    private fun postSemanticTree(tree: NuxieSemanticTree, fields: Map<String, NativeSemanticNode>,
        nativeInputs: List<ExperienceNativeTextField>, generation: Long, epoch: Long) {
        post {
            if (!released.get() && running && sceneInputEnabled.get() && firstFrameComposed && generation == frameGeneration.get() && epoch == semanticEpoch.get()) {
                try {
                    val nativeFields = listener?.onSemanticFields(fields).orEmpty() +
                        listener?.onNativeTextFields(nativeInputs).orEmpty()
                    accessibility.publish(tree, nativeFields)
                    listener?.onSemanticTreePublished()
                } catch (error: Exception) {
                    retireSemantics()
                    reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Experience semantic publication failed", error)
                }
            }
        }
    }

    private var nextCorrelationId = 1uL
    private val surfaceUpdates = AtomicLong()
    private var firstFrameUpdateBaseline = 0L
    @Volatile private var firstFramePresented = false
    @Volatile private var firstFrameComposed = false
    @Volatile private var androidSurface: Surface? = null
    private var saveDescriptor: JsonObject? = null
    private val unpublishedSteps = ArrayDeque<PublishedStep>()
    // SUBMITTED retains the exact frame until native completion. Polling must
    // neither step the player nor publish effects from its unfinished frame.
    private val saveFrameOwner = java.util.UUID.randomUUID().toString()
    private var pendingPresentation = false
    private val pendingTextWrites = ArrayDeque<() -> Unit>()

    /**
     * Fires a confirmed save's continuation in a step of its own, with the
     * values the save kept for it written back first.
     */
    fun confirmResponseSave(trigger: String, kept: JsonArray, screenId: String? = null) {
        val generation = frameGeneration.get()
        lane.enqueue {
            pendingTextWrites.addLast {
                if (!released.get() && !failureReported.get() && generation == frameGeneration.get()) {
                    try {
                        val writes = ExperienceRunSnapshot(kept).writes() +
                            NativeViewModelWrite(NuxieViewModelMutationKind.FIRE_TRIGGER, trigger)
                        val row = screenId?.let { interactiveLayer?.valuesOnLane(it) } ?: viewModelState
                        row?.restoreWrites(writes) ?: checkNotNull(artboard).confirmDefaultSave(writes)
                        val correlationId = nextCorrelationId
                        nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) 1uL else nextCorrelationId + 1uL
                        val outcome = stepAndRefreshFocus {
                            checkNotNull(player).stepAfterStateMutation(correlationId = correlationId)
                        }
                        captureStepSnapshot(outcome, correlationId)?.let(::retainScreenValues)
                        publishSteps()
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) {
                        reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Response save continuation failed", error)
                    }
                }
            }
            drainTextWrites()
        }
    }

    private fun drainTextWrites() {
        if (pendingPresentation) return
        while (pendingTextWrites.isNotEmpty()) pendingTextWrites.removeFirst().invoke()
    }
    private data class SubmittedTextSnapshot(
        val snapshot: NuxieViewModelSnapshot,
        val geometry: NuxieTextGeometryCapture,
        val generation: Long,
        val epoch: Long,
    )
    private data class SubmittedCaptions(
        val captions: Map<Long, ai.nuxie.sdk.runtime.NuxieVideoCaption>,
        val generation: Long,
        val epoch: Long,
    )
    private var submittedCaptions: SubmittedCaptions? = null
    private val captionPublication = AtomicLong()
    private var submittedSnapshot: SubmittedTextSnapshot? = null
    private val textPublication = AtomicLong()
    private var sharedValuesLinked = false
    private var ownsRuntimeFile = true
    private fun retainScreenValues(snapshot: NuxieViewModelSnapshot) {
        retainedViewModel?.set(if (sharedValuesLinked) snapshot.withoutRootProperty("experience") else snapshot)
    }

    private var retainedViewModel: java.util.concurrent.atomic.AtomicReference<NuxieViewModelSnapshot?>? = null
    private var textInputs: Map<String, ExperienceTextInput> = emptyMap()
    private val runtimeValues = linkedMapOf<String, NuxieViewModelScalarValue>()
    private var runtimeValuesPending = false
    private val reportedStatePaths = mutableSetOf<String>()
    private var screenRuntimeValues = ExperienceScreenRuntimeValues(false)

    private data class RuntimeWrite(
        val paths: Set<String>,
        val completion: CompletableDeferred<Boolean>,
    )
    private val pendingRuntimeWrites = ArrayDeque<RuntimeWrite>()

    private fun completeRuntimeWrites(acceptedPaths: Set<String>) {
        while (pendingRuntimeWrites.isNotEmpty()) {
            val write = pendingRuntimeWrites.removeFirst()
            write.completion.complete(acceptedPaths.containsAll(write.paths))
        }
    }

    private fun cancelRuntimeWrites() {
        while (pendingRuntimeWrites.isNotEmpty()) pendingRuntimeWrites.removeFirst().completion.complete(false)
    }

    /**
     * Reports whether every path was accepted after applying the latest values;
     * queued writes stay coalesced. An optional path the scene does not have,
     * such as `screen/exitReason`, is skipped and counts as not accepted.
     */
    fun updateRuntimeValues(values: Map<String, NuxieViewModelScalarValue>): kotlinx.coroutines.Deferred<Boolean> {
        val completion = CompletableDeferred<Boolean>()
        if (released.get() || failureReported.get()) return completion.apply { complete(false) }
        val copied = values.toMap()
        val accepted = lane.enqueue {
            if (released.get() || failureReported.get()) {
                completion.complete(false)
                return@enqueue
            }
            runtimeValues.putAll(copied)
            if (pendingPresentation || artboard == null) {
                pendingRuntimeWrites.addLast(RuntimeWrite(copied.keys.toSet(), completion))
                if (pendingPresentation) runtimeValuesPending = true
            } else completion.complete(applyRuntimeValues(copied).containsAll(copied.keys))
        }
        if (!accepted) completion.complete(false)
        return completion
    }

    private data class TransitionWrite(
        val values: Map<String, NuxieViewModelScalarValue>,
        val completion: CompletableDeferred<Unit>,
        val apply: (() -> Unit)? = null,
    )
    private val transitionWrites = ArrayDeque<TransitionWrite>()
    private var presentedTransitionWrite: TransitionWrite? = null

    /** Match iOS: apply the phase and settle it before the transition watchdog starts. */
    suspend fun applyTransitionValues(values: Map<String, NuxieViewModelScalarValue>) {
        val completion = CompletableDeferred<Unit>()
        val write = TransitionWrite(values.toMap(), completion)
        val accepted = lane.enqueue {
            if (released.get() || failureReported.get()) {
                completion.cancel()
            } else {
                transitionWrites.addLast(write)
                settleHiddenTransitions()
            }
        }
        if (!accepted) completion.cancel()
        try { completion.await() } finally { completion.cancel() }
    }

    private fun applyNextTransitionWrite() {
        if (presentedTransitionWrite != null) return
        while (transitionWrites.isNotEmpty()) {
            val write = transitionWrites.removeFirst()
            if (!write.completion.isActive) continue
            try {
                write.apply?.invoke()
                runtimeValues.putAll(write.values)
                if (write.apply == null || runtimeValuesPending || pendingRuntimeWrites.isNotEmpty()) {
                    completeRuntimeWrites(applyRuntimeValues(runtimeValues))
                    runtimeValuesPending = false
                }
                presentedTransitionWrite = write
            } catch (error: Throwable) {
                write.completion.completeExceptionally(error)
                reportFailure(ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience transition mutation failed", error)
            }
            return
        }
    }

    /** A hidden screen settles its phase without presenting, matching the iOS zero-delta path. */
    private fun settleHiddenTransitions(force: Boolean = false) {
        if ((running && !force) || pendingPresentation) return
        if (released.get() || failureReported.get()) {
            cancelTransitionWrites()
            cancelRuntimeWrites()
            return
        }
        // Retirement completed a frame whose phase was already stepped.
        presentedTransitionWrite?.completion?.complete(Unit)
        presentedTransitionWrite = null
        while (transitionWrites.isNotEmpty()) {
            applyNextTransitionWrite()
            val write = presentedTransitionWrite ?: break
            val result = runCatching {
                val correlationId = nextCorrelationId
                nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) 1uL else nextCorrelationId + 1uL
                val outcome = stepAndRefreshFocus {
                    checkNotNull(player).stepTyped(elapsedSeconds = 0.0, correlationId = correlationId,
                        textRunNames = emptyList())
                }
                val snapshot = captureStepSnapshot(outcome, correlationId)
                snapshot?.let(::retainScreenValues)
                publishSteps()
            }
            presentedTransitionWrite = null
            result.fold({ write.completion.complete(Unit) }, {
                write.completion.completeExceptionally(it)
                reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Experience transition step failed", it)
            })
            if (result.isFailure) return
        }
    }

    private fun cancelTransitionWrites() {
        presentedTransitionWrite?.completion?.cancel()
        presentedTransitionWrite = null
        while (transitionWrites.isNotEmpty()) transitionWrites.removeFirst().completion.cancel()
    }

    private fun applyRuntimeValues(values: Map<String, NuxieViewModelScalarValue>): Set<String> {
        val boundArtboard = artboard ?: return emptySet()
        interactiveLayer?.let { layer ->
            val accepted = mutableSetOf<String>()
            val common = values.filterKeys { it == "env/fontScale" || it == "env/reduceMotion" || it.startsWith("safeArea/") }
            val rowValues = values - common.keys
            for (id in layerRetainedValues.keys) {
                accepted += layer.applyRuntimeValuesOnLane(id, common)
            }
            activeRowId?.let { accepted += layer.applyRuntimeValuesOnLane(it, rowValues) }
            runtimeValues.keys.retainAll { it == "env/fontScale" || it == "env/reduceMotion" || it.startsWith("safeArea/") }
            return accepted
        }
        val accepted = mutableSetOf<String>()
        screenRuntimeValues.apply(values, write = { path, value ->
            val globalPath = when (path) {
                "env/reduceMotion" -> "reduceMotion"
                "env/fontScale" -> "fontScale"
                "safeArea/top", "safeArea/bottom", "safeArea/left", "safeArea/right" -> path
                else -> null
            }
            if (globalPath != null) {
                // This screen's own env instance: like its other screen values, it is written
                // between this screen's frames and never waits on another screen's frame. A write
                // that throws is reported through onRejected and is not counted as accepted.
                if (!released.get()) environment?.setValue(globalPath, value)
                accepted += path
                return@apply
            }
            val projected = viewModelState
            if (projected != null) {
                projected.setValue(path, value)
                accepted += path
            } else if (boundArtboard.setDefaultViewModelValue(path, value)) accepted += path
            // A screen without a default view model ignores the write without a
            // warning; the write's result still reports it as not accepted.
        }, onRejected = { path, error ->
            if (reportedStatePaths.add(path)) Log.w(LOG_TAG, "Experience state path rejected", error, Log.sensitive("path", path))
        })
        return accepted
    }

    /**
     * Lane-confined surface attachment. Jobs already queued when the
     * platform destroys the surface must not touch the abandoned window;
     * a destroy marker enqueued from the UI thread gates them in FIFO
     * order.
     */
    private var attached = false

    @Volatile
    private var running = false
    private val released = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val frameGeneration = AtomicLong(0)
    // At most one queued or executing frame. Input and cleanup share this FIFO lane.
    private val framePending = AtomicBoolean(false)
    private var surfaceAvailable = false
    private var presentationVisible = true
    private var lastFrameNanos = 0L
    private var lastSteppedGeneration = -1L
    private val pointerInput = ExperienceRuntimePointerInput(artboardSize)
    private val focusInput = ExperienceFocusInputQueue()
    private val focusGeneration = AtomicLong()
    private var queuedFocusGeneration = 0L

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    private fun enqueuePreparation(onLoaded: ((Boolean) -> Unit)?, prepare: () -> Unit) {
        val reject = { onLoaded?.invoke(false); Unit }
        val accepted = lane.enqueue {
            val apply = {
                if (released.get()) reject() else prepare()
            }
            val shared = runValues
            if (shared == null) apply() else shared.mutateWhenPresented(apply, reject)
        }
        if (!accepted) reject()
    }

    /**
     * Load a verified Nuxie scene and select its artboard/player. Must be
     * called before the surface is created (tracer entry point; the
     * presentation service supersedes this with prepared releases).
     */
    fun loadArtboard(
        sceneBytes: ByteArray,
        artboardName: String?,
        descriptor: JsonObject? = null,
        artifactsByKey: Map<String, File> = emptyMap(),
        viewModelProjection: NuxieViewModelListProjection? = null,
        textInputs: List<ExperienceTextInput> = emptyList(),
        retainedViewModel: java.util.concurrent.atomic.AtomicReference<NuxieViewModelSnapshot?>? = null,
        layerScreen: PreparedPresentation? = null,
        enteringPage: Boolean = true,
        onLoaded: ((Boolean) -> Unit)? = null,
    ) {
        refreshColorScheme(resources.configuration.uiMode)
        retireSemantics()
        discardFocusInput()
        riveFocusState = NuxieFocusState(false, false)
        enqueuePreparation(onLoaded) {
            saveDescriptor = descriptor
            val requirements = descriptor?.get("requirements") as? JsonObject
            semanticsEnabled = layerScreen != null || (requirements?.get("requiredCapabilities") as? JsonArray).orEmpty()
                .any { (it as? JsonPrimitive)?.content == "experience-accessibility" } ||
                textInputs.isNotEmpty()
            this.textInputs = textInputs.associateBy(ExperienceTextInput::id)
            this.retainedViewModel = retainedViewModel
            val shared = try { runValues?.prepare(sceneBytes, descriptor, artifactsByKey, runtime, systemFontCache) }
            catch (error: Exception) {
                reportFailure(ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Experience run values could not be prepared", error)
                onLoaded?.invoke(false)
                return@enqueuePreparation
            }
            ownsRuntimeFile = shared?.values == null
            if (shared != null) renderer = shared.renderer
            val activeRenderer = ensureRenderer(1, 1)
            if (activeRenderer == null) {
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience renderer creation failed",
                )
                onLoaded?.invoke(false)
                return@enqueuePreparation
            }
            var videoBindings: List<ExperienceVideoAssetBinding> = emptyList()
            var videoTargets: List<ExperienceVideoElement> = emptyList()
            file = if (shared != null) {
                videoBindings = shared.imports?.videos.orEmpty()
                videoTargets = shared.imports?.videoElements.orEmpty()
                shared.file
            } else if (descriptor == null) {
                runtime.importFile(activeRenderer, sceneBytes)
            } else {
                val inspectedCatalog = runtime.inspectFileAssets(sceneBytes)
                if (inspectedCatalog == null) {
                    reportFailure(
                        ExperiencePresentationException.Reason.PREPARATION_FAILED,
                        "Runtime could not inspect the Experience asset catalog",
                    )
                    onLoaded?.invoke(false)
                    return@enqueuePreparation
                }
                val systemFonts = mutableListOf<SystemFontCache.Lease>()
                val import = runCatching {
                    ExperienceAssetImportBuilder.build(
                        descriptor = descriptor,
                        artifactsByKey = artifactsByKey,
                        inspectedCatalog = inspectedCatalog,
                        systemFontBytes = { requirement ->
                            systemFontCache.prepare(requirement).also(systemFonts::add).candidate.bytes
                        },
                    )
                }.getOrElse { error ->
                    Log.w(LOG_TAG, "Experience asset preparation failed", error)
                    reportFailure(
                        ExperiencePresentationException.Reason.PREPARATION_FAILED,
                        "Experience asset preparation failed",
                        error,
                    )
                    onLoaded?.invoke(false)
                    return@enqueuePreparation
                }
                videoBindings = import.videos
                videoTargets = import.videoElements
                try {
                    runtime.importFile(
                        renderer = activeRenderer,
                        bytes = sceneBytes,
                        expectedAssets = import.expectedAssets,
                        externalAssets = import.externalAssets,
                        videoEnabled = videoBindings.isNotEmpty(),
                        valuePolicy = JourneyReleaseValuePolicy.parse(descriptor),
                    ).also { imported ->
                        if (imported == null) systemFontCache.didFailImport(systemFonts)
                        else systemFontCache.didImport(systemFonts)
                    }
                } catch (error: Throwable) {
                    systemFontCache.didFailImport(systemFonts)
                    reportFailure(
                        ExperiencePresentationException.Reason.PREPARATION_FAILED,
                        "Runtime could not import the prepared Experience content",
                        error,
                    )
                    onLoaded?.invoke(false)
                    return@enqueuePreparation
                }
            }
            val loadedFile = file
            if (loadedFile == null) {
                Log.w(LOG_TAG, "Runtime rejected the scene bytes")
                reportFailure(
                    ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Runtime rejected the prepared Experience content",
                )
                onLoaded?.invoke(false)
                return@enqueuePreparation
            }
            if (layerScreen != null) {
                try {
                    requireLayerPresentation(layerScreen.fitsContentHeight)
                    val layer = ExperienceInteractivePage.openOnLane(lane, runtime, loadedFile,
                        checkNotNull(descriptor), shared?.values)
                    interactiveLayer = layer
                    artboard = layer.artboard
                    player = layer.player
                    environment = layer.environment
                    environmentBindings = ExperienceEnvironmentBindings(loadedFile.viewModelCatalog())
                    applyColorScheme()
                    val id = checkNotNull(layerScreen.screenId)
                    layer.prepareOnLane(id, projection = viewModelProjection, snapshot = layerScreen.retainedViewModel.get(),
                        lifecycle = layerScreen.screenLifecycle)
                    layerRetainedValues[id] = layerScreen.retainedViewModel
                    activeRowId = id
                    viewModelState = layer.valuesOnLane(id)
                    layer.mountInitialOnLane(id, enteringPage)
                    completeRuntimeWrites(applyRuntimeValues(runtimeValues))
                    if (semanticsEnabled) layer.player.enableSemantics()
                    if (videoBindings.isNotEmpty()) {
                        videoPlayback = ExperienceVideoPlayback(context.applicationContext, layer.player, videoBindings, videoTargets,
                            decoderPool = videoDecoderPool, initialViewport = ai.nuxie.sdk.runtime.VideoViewport(0f, 0f, 0f, 0f))
                        videoPlayback?.setVisible(running)
                    }
                    initialPresentationSize?.let { size -> layer.player.setLayoutSize(size.width, size.height) }
                    onLoaded?.invoke(true)
                } catch (error: Throwable) {
                    reportFailure(ExperiencePresentationException.Reason.PREPARATION_FAILED, "Experience layer preparation failed", error)
                    onLoaded?.invoke(false)
                }
                return@enqueuePreparation
            }
            artboard = if (artboardName != null) {
                loadedFile.newArtboard(artboardName)
            } else {
                loadedFile.newArtboard()
            }
            val loadedArtboard = artboard
            if (loadedArtboard == null) {
                Log.w(LOG_TAG, "Artboard unavailable")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience artboard is unavailable",
                )
                onLoaded?.invoke(false)
                return@enqueuePreparation
            }
            try {
                // Each screen owns its env: a covered layer keeps the values of its own space.
                environment = loadedFile.newGlobalViewModel("env")
                environmentBindings = ExperienceEnvironmentBindings(loadedFile.viewModelCatalog())
                appliedColorScheme = null
                val retained = retainedViewModel?.get()
                if (retained != null) {
                    viewModelState = runtime.restoreViewModel(loadedFile, loadedArtboard, retained)
                } else if (viewModelProjection != null) {
                    viewModelState = runtime.bindViewModelList(
                        file = loadedFile,
                        artboard = loadedArtboard,
                        projection = viewModelProjection,
                        instanceBindings = descriptor?.let(ExperienceViewModelBinding::instanceBindings).orEmpty(),
                    )
                } else {
                    descriptor?.let { signed ->
                        ExperienceViewModelBinding.defaultSchemaName(signed, artboardName)?.let { name ->
                            loadedArtboard.bindDefaultViewModel(name,
                                ExperienceViewModelBinding.defaultInstanceId(signed, artboardName),
                                ExperienceViewModelBinding.instanceBindings(signed))
                        }
                    }
                }
                screenRuntimeValues = ExperienceScreenRuntimeValues(
                    viewModelState?.supportsEnumPath("screen/exitReason")
                        ?: loadedArtboard.supportsDefaultEnumPath("screen/exitReason"),
                )
                val values = shared?.values
                if (values != null) {
                    sharedValuesLinked = viewModelState?.linkViewModel("experience", values)
                        ?: loadedArtboard.linkDefaultViewModel("experience", values)
                }
            } catch (error: Exception) {
                // Do not retain a partially bound graph after a signed state
                // contract failure. The renderer remains available for retry.
                artboard = null
                file = null
                runCatching { environment?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                environment = null
                runCatching { loadedArtboard.close() }.exceptionOrNull()?.let(error::addSuppressed)
                if (ownsRuntimeFile) runCatching { loadedFile.close() }.exceptionOrNull()?.let(error::addSuppressed)
                reportFailure(
                    ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Experience view-model binding failed",
                    error,
                )
                onLoaded?.invoke(false)
                return@enqueuePreparation
            }
            completeRuntimeWrites(applyRuntimeValues(runtimeValues))
            try {
                player = loadedFile.newExperiencePlayer(loadedArtboard, artboardName)
                environment?.let { checkNotNull(player).bindGlobalViewModel("env", it) }
                // Entry transitions and measurement must see the device's appearance on their first advance.
                applyColorScheme()
                if (semanticsEnabled) checkNotNull(player).enableSemantics()
                if (videoBindings.isNotEmpty()) {
                    videoPlayback = ExperienceVideoPlayback(context.applicationContext, checkNotNull(player), videoBindings, videoTargets,
                        decoderPool = videoDecoderPool, initialViewport = ai.nuxie.sdk.runtime.VideoViewport(0f, 0f, 0f, 0f))
                    videoPlayback?.setVisible(running)
                }
            } catch (error: Exception) {
                val failedPlayer = player
                player = null
                runCatching { failedPlayer?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                runCatching { closeThemeInstances() }.exceptionOrNull()?.let(error::addSuppressed)
                artboard = null
                file = null
                runCatching { environment?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                environment = null
                runCatching { loadedArtboard.close() }.exceptionOrNull()?.let(error::addSuppressed)
                if (ownsRuntimeFile) runCatching { loadedFile.close() }.exceptionOrNull()?.let(error::addSuppressed)
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience player creation failed",
                    error,
                )
                onLoaded?.invoke(false)
                return@enqueuePreparation
            }
            try {
                val active = checkNotNull(player)
                initialPresentationSize?.let { size ->
                    presentationHeight.apply(loadedFile, active, fitsContentHeight, size.width, size.height)
                    val correlation = nextCorrelationId
                    nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) 1uL else nextCorrelationId + 1uL
                    val outcome = stepAndRefreshFocus { active.stepTyped(elapsedSeconds = 0.0, correlationId = correlation) }
                    // Same per-step capture as the frame loop, so a response save on this step is kept.
                    captureStepSnapshot(outcome, correlation)?.let(::retainScreenValues)
                    val laidOut = active.layoutSize()
                    val bounds = ExperienceArtboardSize(laidOut.first, laidOut.second)
                    layoutBounds = bounds
                    pointerInput.updateBounds(bounds)
                    val contentHeight = if (presentationHeight.measuresContent) bounds.height else null
                    postLayoutBounds(bounds, contentHeight, notifyHeight = true)
                }
            } catch (error: Throwable) {
                reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Experience content measurement failed", error)
                onLoaded?.invoke(false)
                return@enqueuePreparation
            }
            onLoaded?.invoke(true)
        }
    }

    private fun postLayoutBounds(
        bounds: ExperienceArtboardSize,
        contentHeight: Float?,
        generation: Long? = null,
        epoch: Long? = null,
        notifyHeight: Boolean = fitsContentHeight,
    ) {
        val delivery = LayoutDelivery(bounds, contentHeight)
        if (deliveredLayout == delivery) return
        mainHandler.post {
            if (released.get() || (generation != null && generation != frameGeneration.get()) ||
                (epoch != null && epoch != semanticEpoch.get()) || deliveredLayout == delivery) return@post
            listener?.onLayoutBounds(bounds)
            if (notifyHeight) listener?.onPresentationHeight(contentHeight)
            deliveredLayout = delivery
        }
    }

    private fun stepAndRefreshFocus(step: () -> NuxiePlayerStepOutcome): NuxiePlayerStepOutcome {
        // A submitted frame keeps its model revision. Deferred appearance changes apply
        // before the next advance, including hidden transitions and native input steps.
        applyColorScheme()
        return step().also { riveFocusState = it.focusState ?: NuxieFocusState(false, false) }
    }

    /** UI-thread input, also used for detached screens waiting behind another screen. */
    fun refreshColorScheme(uiMode: Int) {
        val scheme = ExperienceColorScheme.fromUiMode(uiMode)
        if (requestedColorScheme == scheme || released.get() || failureReported.get()) return
        requestedColorScheme = scheme
        lane.enqueue {
            if (released.get() || failureReported.get() || pendingPresentation) return@enqueue
            try {
                applyColorScheme()
            } catch (error: Exception) {
                reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Experience appearance update failed", error)
            }
        }
        // Visible surfaces already request a frame every Choreographer tick. The next
        // tick advances these bindings; hidden surfaces apply them before re-entry.
    }

    /** One lane operation writes env and rebinds every state-machine player, as on iOS. */
    private fun applyColorScheme() {
        val active = player ?: return
        val loadedFile = file ?: return
        val bindings = environmentBindings ?: return
        val scheme = requestedColorScheme
        if (appliedColorScheme == scheme) return
        bindings.colorSchemeMutation(scheme)?.let { mutation ->
            environment?.let { env ->
                env.restoreWrites(listOf(mutation))
                active.requestInteractionStep()
            }
        }
        bindings.themeSelection(scheme)?.let { selection ->
            val instance = themeInstances.getOrPut(selection) {
                selection.authoredInstanceIndex?.let {
                    checkNotNull(loadedFile.newAuthoredViewModel(ExperienceEnvironmentBindings.THEME_GLOBAL_NAME, it))
                } ?: loadedFile.newSchemaViewModel(selection.schemaIndex)
            }
            active.bindGlobalViewModel(ExperienceEnvironmentBindings.THEME_GLOBAL_NAME, instance)
        }
        appliedColorScheme = scheme
    }

    private fun closeThemeInstances() {
        val instances = themeInstances.values.toList()
        themeInstances.clear()
        var failure: Throwable? = null
        for (instance in instances) {
            try { instance.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private var nativeEditingTarget: ExperienceTextFieldTarget? = null
    private var nativeEditingOwnerId: Long? = null

    /** Runs on the frame lane, with the same effects and values as a scene step. */
    private fun nativeEditingStep(
        pointers: List<ai.nuxie.sdk.runtime.NuxiePlayerPointerEvent> = emptyList(),
        inputs: List<NuxieFocusInput> = emptyList(),
    ) {
        val correlationId = nextCorrelationId
        nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) 1uL else nextCorrelationId + 1uL
        val outcome = stepAndRefreshFocus {
            checkNotNull(player).stepTyped(elapsedSeconds = 0.0, pointers = pointers, focusInputs = inputs, correlationId = correlationId)
        }
        val root = captureStepSnapshot(outcome, correlationId)
        root?.let { retainScreenValues(it) }
    }

    private fun focusNativeField(field: ExperienceNativeTextField, point: Pair<Float, Float>? = null): Boolean {
        nativeEditingTarget = null
        val geometry = field.geometry
        val x = (geometry.textBounds.minX + geometry.textBounds.maxX) / 2f
        val y = (geometry.textBounds.minY + geometry.textBounds.maxY) / 2f
        val transform = geometry.worldTransform
        val position = point ?: (transform.a * x + transform.c * y + transform.tx to
            transform.b * x + transform.d * y + transform.ty)
        for (pointers in pointerInput.takeNativeTap(position.first, position.second)) {
            check(!released.get() && running && sceneInputEnabled.get()) { "Native input is no longer eligible" }
            nativeEditingStep(pointers = pointers)
        }
        val accepted = riveFocusState.hasFocus && riveFocusState.expectsKeyboardInput
        if (accepted) { nativeEditingTarget = field.target; nativeEditingOwnerId = field.ownerId }
        return accepted
    }

    fun beginNativeEditing(target: ExperienceTextFieldTarget, screenPoint: Pair<Float, Float>?, completion: (Boolean) -> Unit) {
        val generation = frameGeneration.get()
        val epoch = semanticEpoch.get()
        val location = IntArray(2)
        getLocationOnScreen(location)
        val point = screenPoint?.let { touch ->
            layoutBounds?.let { bounds ->
                ExperienceLayoutTransform.create(bounds, width.toFloat(), height.toFloat(), resources.displayMetrics.density)
                    ?.project(touch.first - location[0], touch.second - location[1])
            }
        }
        val queued = lane.enqueue {
            pendingTextWrites.addLast {
                val field = nativeTextFields.singleOrNull { it.target == target }
                val accepted = if (released.get() || !running || !sceneInputEnabled.get() ||
                    generation != frameGeneration.get() || epoch != semanticEpoch.get() || field == null) false
                else try {
                    focusNativeField(field, point).also { publishSteps() }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    false
                }
                post { completion(accepted) }
            }
            drainTextWrites()
        }
        if (!queued) post { completion(false) }
    }

    /** UI entry point. Native edits and optional response commits share the frame lane. */
    fun writeNativeText(target: ExperienceTextFieldTarget, write: ExperienceSemanticTextDraft.Write,
        completion: (ExperienceSemanticTextDraft.Outcome) -> Unit) {
        val generation = frameGeneration.get()
        val epoch = semanticEpoch.get()
        fun complete(outcome: ExperienceSemanticTextDraft.Outcome) { post { completion(outcome) } }
        val accepted = lane.enqueue {
            pendingTextWrites.addLast {
                val capture = semanticSnapshot
                val active = player
                val input = textInputs[target.inputId]
                val field = nativeTextFields.singleOrNull { it.target == target }
                if (released.get() || !running || !sceneInputEnabled.get() || generation != frameGeneration.get() ||
                    epoch != semanticEpoch.get() || semanticSnapshotEpoch != epoch || write.captureId != nativeTextCaptureId ||
                    capture == null || active == null || field == null || input == null ||
                    capture.validate(active.requireHandle()) != 0) {
                    complete(ExperienceSemanticTextDraft.Outcome.STALE_CAPTURE)
                    return@addLast
                }
                if (!ExperienceTextInputLimit.fits(write.text, input.maxLength)) {
                    complete(ExperienceSemanticTextDraft.Outcome.REJECTED)
                    return@addLast
                }
                var owner: ai.nuxie.sdk.runtime.NuxieFieldViewModel? = null
                val result = runCatching {
                    owner = checkNotNull(active.fieldOwner(capture, target.nodeId, input.textInputName))
                    if (nativeEditingTarget != target || nativeEditingOwnerId != field.ownerId || !riveFocusState.hasFocus || !riveFocusState.expectsKeyboardInput) {
                        check(focusNativeField(field)) { "Native field did not accept keyboard focus" }
                    }
                    val inputs = ExperienceFocusInputQueue()
                    inputs.add(NuxieFocusInput.Key(65, 8, true, false))
                    inputs.add(NuxieFocusInput.Key(65, 8, false, false))
                    if (write.text.isEmpty()) {
                        inputs.add(NuxieFocusInput.Key(259, 0, true, false))
                        inputs.add(NuxieFocusInput.Key(259, 0, false, false))
                    } else inputs.add(NuxieFocusInput.Text(write.text))
                    while (!inputs.isEmpty) nativeEditingStep(inputs = inputs.takeBatch())
                    val evaluated = checkNotNull(owner).snapshot()
                    check(evaluated.nativeRootInstanceId == field.ownerId) { "Native field owner changed during write" }
                    acceptedNativeText[target] = AcceptedNativeText(write.text, evaluated)
                    publishSteps()
                }
                try { owner?.close() } catch (error: Exception) {
                    reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Native input owner cleanup failed", error)
                }
                result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                val status = (result.exceptionOrNull() as? ai.nuxie.sdk.runtime.NuxieRuntimeCallException)?.status
                complete(if (result.isSuccess) ExperienceSemanticTextDraft.Outcome.ACCEPTED
                    else if (status == 9) ExperienceSemanticTextDraft.Outcome.STALE_CAPTURE
                    else ExperienceSemanticTextDraft.Outcome.REJECTED)
            }
            drainTextWrites()
        }
        if (!accepted) complete(ExperienceSemanticTextDraft.Outcome.STALE_CAPTURE)
    }

    /** Value notifications consume the evaluated owner from that accepted write, never the screen root. */
    fun notifyNativeText(target: ExperienceTextFieldTarget, text: String) {
        val generation = frameGeneration.get()
        val epoch = semanticEpoch.get()
        lane.enqueue {
            if (released.get() || !running || !sceneInputEnabled.get() || generation != frameGeneration.get() ||
                epoch != semanticEpoch.get()) return@enqueue
            val accepted = acceptedNativeText[target] ?: return@enqueue
            val field = nativeTextFields.singleOrNull { it.target == target } ?: return@enqueue
            if (accepted.text != text || accepted.snapshot.nativeRootInstanceId != field.ownerId) return@enqueue
            listener?.onTextCommitted(target.inputId, text, accepted.snapshot)
        }
    }

    /** Lifecycle actions are separate from value notifications, including when text is unchanged. */
    fun nativeTextEvent(target: ExperienceTextFieldTarget, ownerId: Long, event: ExperienceSemanticTextDraft.Event) {
        val generation = frameGeneration.get()
        val epoch = semanticEpoch.get()
        lane.enqueue {
            pendingTextWrites.addLast {
                if (released.get() || !running || !sceneInputEnabled.get() || generation != frameGeneration.get() ||
                    epoch != semanticEpoch.get()) return@addLast
                if (event.kind == ExperienceSemanticTextDraft.EventKind.EDITING_ENDED && nativeEditingTarget == target && nativeEditingOwnerId == ownerId) {
                    nativeEditingTarget = null
                    nativeEditingStep(inputs = listOf(NuxieFocusInput.Clear))
                    publishSteps()
                }
                val input = textInputs[target.inputId] ?: return@addLast
                if (event.kind != input.actionEvent) return@addLast
                val field = nativeTextFields.singleOrNull { it.target == target && it.ownerId == ownerId } ?: return@addLast
                val owner = nativeTextOwners[target.nodeId] ?: return@addLast
                try {
                    check(owner.snapshot().nativeRootInstanceId == field.ownerId) { "Native input action owner changed" }
                    val scripted = owner.commitTextInput(checkNotNull(file).viewModelCatalog(), input.viewNodeId, event.text)
                    if (scripted) {
                        val correlationId = nextCorrelationId
                        nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) 1uL else nextCorrelationId + 1uL
                        val outcome = stepAndRefreshFocus {
                            checkNotNull(player).stepAfterStateMutation(correlationId = correlationId)
                        }
                        val root = captureStepSnapshot(outcome, correlationId)
                        root?.let { retainScreenValues(it) }
                        publishSteps()
                    } else listener?.onTextInputEvent(target.inputId, event)
                } catch (error: Exception) {
                    reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Native input action failed", error)
                }
            }
            drainTextWrites()
        }
    }

    /** Suspend scene actions during navigation while allowing authored exit animation to render. */
    fun setInputEnabled(enabled: Boolean) {
        if (sceneInputEnabled.getAndSet(enabled) == enabled) return
        isEnabled = enabled
        semanticEpoch.incrementAndGet()
        if (!enabled) {
            semanticKeys.clear()
            riveKeys.clear()
            discardFocusInput()
            lane.enqueue { pointerInput.reset() }
        }
        if (!enabled) {
            accessibility.withdraw()
        } else accessibility.invalidateState()
    }

    /** UI-thread visibility input; a paused but visible Activity remains active. */
    fun setPresentationVisible(visible: Boolean, preservePendingInput: Boolean = false) {
        if (visible) refreshColorScheme(resources.configuration.uiMode)
        if (!visible) {
            semanticKeys.clear()
            riveKeys.clear()
            if (!preservePendingInput) discardFocusInput()
            captionPublication.incrementAndGet()
            listener?.onVideoCaptions(emptyMap())
            retireSemantics(preserveFocus = true)
        }
        presentationVisible = visible
        updateFrameScheduling()
    }

    private fun updateFrameScheduling() {
        val shouldRun = surfaceAvailable && presentationVisible && !released.get()
        if (running == shouldRun) return
        running = shouldRun
        lane.enqueue { videoPlayback?.setVisible(shouldRun) }
        frameGeneration.incrementAndGet()
        if (shouldRun) {
            if (usesSystemFrameCallbacks) Choreographer.getInstance().postFrameCallback(this)
        } else {
            // Reset after any staging operation that already started on this
            // lane; clearing on the UI thread could race its final enqueue.
            lane.enqueue {
                pointerInput.reset()
                if (retireSubmission()) settleHiddenTransitions()
            }
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released.get()) return
        surfaceWidth = width
        surfaceHeight = height
        requestedDensity = resources.displayMetrics.density
        val surface = Surface(texture)
        androidSurface = surface
        enqueuePreparation(null) {
            if (androidSurface !== surface) return@enqueuePreparation
            val width = surfaceWidth
            val height = surfaceHeight
            val layout = ExperienceSurfaceLayout.create(width, height, requestedDensity)
            // Attach only once both the headless renderer and this surface's
            // window exist; a failed create/acquire keeps the frame gate shut.
            val activeRenderer = ensureRenderer(width.coerceAtLeast(1), height.coerceAtLeast(1))
            if (activeRenderer == null) {
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience renderer creation failed",
                )
                return@enqueuePreparation
            }
            if ((if (ownsRuntimeFile) activeRenderer.resize(width.coerceAtLeast(1), height.coerceAtLeast(1)) else activeRenderer.resizeIfIdle(width.coerceAtLeast(1), height.coerceAtLeast(1))) != NUX_STATUS_OK) {
                Log.w(LOG_TAG, "Android Vulkan renderer resize failed")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience renderer resize failed",
                )
                return@enqueuePreparation
            }
            window = runtime.acquireWindow(surface)
            if (window == null) {
                Log.w(LOG_TAG, "Native window acquisition failed")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience surface acquisition failed",
                )
                return@enqueuePreparation
            }
            surfaceLayout = layout
            appliedLayout = null
            attached = true
            player?.let(::applyLayoutSize)
        }
        surfaceAvailable = true
        updateFrameScheduling()
    }

    private fun applyLayoutSize(active: NuxieRuntimePlayer): Boolean {
        val layout = surfaceLayout ?: return false
        if (appliedLayout == layout) return true
        return try {
            if (interactiveLayer != null) active.setLayoutSize(layout.widthPoints, layout.heightPoints)
            else presentationHeight.apply(checkNotNull(file), active, fitsContentHeight, layout.widthPoints, layout.heightPoints)
            appliedLayout = layout
            layoutStepPending = true
            true
        } catch (error: Throwable) {
            reportFailure(ExperiencePresentationException.Reason.HOST_FAILED,
                "Experience layout size failed", error)
            false
        }
    }

    private fun currentVideoViewport(layout: ExperienceSurfaceLayout): ai.nuxie.sdk.runtime.VideoViewport {
        val bounds = layoutBounds
        if (interactiveLayer != null && bounds != null) return ai.nuxie.sdk.runtime.VideoViewport(
            bounds.originX, bounds.originY, bounds.originX + bounds.width, bounds.originY + bounds.height)
        return ai.nuxie.sdk.runtime.VideoViewport.layout(layout.pixelWidth, layout.pixelHeight, layout.density,
            bounds?.originX ?: 0f, bounds?.originY ?: 0f)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshColorScheme(newConfig.uiMode)
        refreshLayoutDensity()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshColorScheme(resources.configuration.uiMode)
    }

    private fun refreshLayoutDensity() {
        if (surfaceAvailable && requestedDensity != resources.displayMetrics.density) {
            resizeSurface(surfaceWidth, surfaceHeight)
        }
    }

    /** Cancels only this window's native work before discarding its captured frame. */
    private fun retireSubmission(): Boolean {
        val status = window?.let { renderer?.detachSurface(it) } ?: NUX_STATUS_OK
        if (status != NUX_STATUS_OK) {
            attached = false
            reportFailure(
                ExperiencePresentationException.Reason.HOST_FAILED,
                "Experience renderer detach failed with status $status",
            )
            return false
        }
        pendingPresentation = false
        runValues?.setPresentationPending(saveFrameOwner, false)
        submittedSnapshot = null
        submittedCaptions = null
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        resizeSurface(width, height)
    }

    private fun resizeSurface(width: Int, height: Int) {
        if (released.get()) return
        surfaceWidth = width
        surfaceHeight = height
        requestedDensity = resources.displayMetrics.density
        val layout = ExperienceSurfaceLayout.create(width, height, requestedDensity)
        retireSemantics(preserveFocus = true)
        lane.enqueue {
            surfaceLayout = layout
            appliedLayout = null
            pointerInput.reset()
            if (attached) {
                // The renderer retires GPU work during resize while preserving a
                // connected CPU producer on this same window.
                val status = renderer?.resizeForWindow(
                    checkNotNull(window),
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                )
                pendingPresentation = false
                submittedSnapshot = null
                submittedCaptions = null
                if (status == NUX_STATUS_OK) runValues?.setPresentationPending(saveFrameOwner, false)
                if (status != NUX_STATUS_OK) {
                    attached = false
                    reportFailure(
                        ExperiencePresentationException.Reason.HOST_FAILED,
                        "Experience renderer resize failed with status $status",
                    )
                    return@enqueue
                }
                player?.let(::applyLayoutSize)
                if (layout == null) videoPlayback?.setViewport(ai.nuxie.sdk.runtime.VideoViewport(0f, 0f, 0f, 0f))
            }
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        captionPublication.incrementAndGet()
        listener?.onVideoCaptions(emptyMap())
        retireSemantics(preserveFocus = true)
        surfaceAvailable = false
        updateFrameScheduling()
        val surface = androidSurface
        androidSurface = null
        val releaseTexture: () -> Unit = {
            try { surface?.release() } finally { texture.release() }
        }
        val accepted = lane.enqueue {
            try {
                attached = false
                pendingPresentation = false
                submittedSnapshot = null
                submittedCaptions = null
                try {
                    window?.let { renderer?.detachSurface(it) }
                } finally {
                    window?.close()
                    window = null
                }
                runValues?.setPresentationPending(saveFrameOwner, false)
            } finally {
                releaseTexture()
            }
        }
        if (!accepted) lane.afterTermination(releaseTexture)
        // Keep ownership until queued native rendering has drained. TextureView
        // must not release this texture when the callback returns.
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        surfaceUpdates.incrementAndGet()
        notifyFirstFrame()
    }

    private fun notifyFirstFrame() {
        if (!released.get() && firstFramePresented && !firstFrameComposed) {
            listener?.onFirstFrame()
            firstFrameComposed = true
            val generation = frameGeneration.get()
            lane.enqueue {
                if (!released.get()) {
                    publishSteps()
                    semanticSnapshot?.tree?.let { postSemanticTree(it, semanticFields, nativeTextFields, generation, semanticSnapshotEpoch) }
                }
            }
        }
    }

    private fun publishSteps() {
        while (firstFrameComposed && unpublishedSteps.isNotEmpty()) {
            val step = unpublishedSteps.removeFirst()
            if (step.screenId != null) listener?.onLayerRuntimeStep(step.screenId, step.outcome, step.correlationId,
                checkNotNull(step.viewModelSnapshot), step.saves)
            else listener?.onRuntimeStep(step.outcome, step.correlationId, step.viewModelSnapshot, step.saves)
            if (step.outcome.events.isNotEmpty()) {
                post {
                    if (!released.get()) {
                        step.outcome.events.forEach {
                            if (step.screenId != null) listener?.onLayerRuntimeEvent(step.screenId, it, checkNotNull(step.viewModelSnapshot))
                            else listener?.onRuntimeEvent(it, step.viewModelSnapshot)
                        }
                    }
                }
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || failureReported.get()) return
        refreshLayoutDensity()
        if (usesSystemFrameCallbacks) Choreographer.getInstance().postFrameCallback(this)
        if (!framePending.compareAndSet(false, true)) return
        // The preceding native frame can finish while this tick acquires the
        // slot. Wait for composition and the owner's preparation handoff before
        // submitting another frame that could delay its transition state writes.
        if (firstFramePresented && !firstFrameComposed) {
            // A compositor callback can precede the native call's return. Its
            // counter still proves composition of this first delivered attempt.
            if (surfaceUpdates.get() != firstFrameUpdateBaseline) notifyFirstFrame()
            framePending.set(false)
            return
        }
        val generation = frameGeneration.get()
        val epoch = semanticEpoch.get()
        val accepted = lane.enqueue {
            try {
                if (!attached || !running || failureReported.get() || generation != frameGeneration.get()) return@enqueue
                val renderer = renderer ?: return@enqueue
                val player = player ?: return@enqueue
                // Invariant: attached is set only after a successful window
                // acquire and cleared in the same lane task that closes the
                // window, so attached implies a live window; this null-check is
                // a type-level guard, never a reachable behavior change.
                val window = window ?: return@enqueue
                val layout = surfaceLayout ?: return@enqueue
                // Covered layers keep rendering through the Journey's one renderer. While
                // another screen's frame is queued, skip this tick entirely: the clock keeps
                // running for the next step, and no pending presentation is claimed, so run
                // writes (including the next screen's preparation) are not held back.
                if (renderer.isPresentingAnotherWindow(window)) return@enqueue
                if (!applyLayoutSize(player)) return@enqueue
                if (!pendingPresentation) {
                    drainTextWrites()
                    applyNextTransitionWrite()
                    if (failureReported.get()) return@enqueue
                    // A submitted frame owns its model revision through completion and semantic capture.
                    // Coalesce newer environment values and apply them before advancing the next frame.
                    if (runtimeValuesPending) {
                        completeRuntimeWrites(applyRuntimeValues(runtimeValues))
                        runtimeValuesPending = false
                    }
                    // Keep the clock on the lane: a resize queued ahead of this
                    // tick may have retired its pending submission. Polling does
                    // not consume time, and visibility generations reset it.
                    val elapsedSeconds = if (layoutStepPending || presentedTransitionWrite != null || lastSteppedGeneration != generation) {
                        0.0
                    } else {
                        (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                    }
                    if (!layoutStepPending || lastSteppedGeneration != generation) {
                        lastFrameNanos = frameTimeNanos
                    }
                    lastSteppedGeneration = generation
                    val correlationId = nextCorrelationId
                    nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) {
                        1uL
                    } else {
                        nextCorrelationId + 1uL
                    }
                    val outcome = try {
                        if (!sceneInputEnabled.get()) pointerInput.reset()
                        val currentFocusGeneration = focusGeneration.get()
                        if (!sceneInputEnabled.get() || queuedFocusGeneration != currentFocusGeneration) {
                            focusInput.clear()
                            queuedFocusGeneration = currentFocusGeneration
                        }
                        val focusBatch = focusInput.takeBatch()
                        stepAndRefreshFocus {
                            player.stepTyped(
                                elapsedSeconds = elapsedSeconds,
                                pointers = pointerInput.takeBatch(),
                                focusInputs = focusBatch,
                                correlationId = correlationId,
                                textRunNames = emptyList(),
                            )
                        }.also {
                            if (layoutStepPending || presentationHeight.measuresContent) {
                                val size = player.layoutSize()
                                val bounds = ExperienceArtboardSize(size.first, size.second)
                                layoutBounds = bounds
                                pointerInput.updateBounds(bounds)
                                layoutStepPending = false
                                videoPlayback?.setViewport(currentVideoViewport(layout))
                            }
                            // A post invalidated by input activation or a new frame generation must
                            // retry even when the runtime's measured bounds have not changed.
                            layoutBounds?.let { bounds ->
                                val contentHeight = if (presentationHeight.measuresContent) bounds.height else null
                                postLayoutBounds(bounds, contentHeight, generation, epoch)
                            }
                            if (it.focusState?.hasFocus != true) {
                                val direction = focusBatch.withIndex().firstNotNullOfOrNull { (index, input) ->
                                    val moved = it.focusResults.getOrNull(index) == true
                                    if (moved) null else when (input) {
                                        NuxieFocusInput.Next -> android.view.View.FOCUS_FORWARD
                                        NuxieFocusInput.Previous -> android.view.View.FOCUS_BACKWARD
                                        else -> null
                                    }
                                }
                                if (direction != null) post {
                                    if (!released.get() && running && sceneInputEnabled.get() &&
                                        generation == frameGeneration.get() && currentFocusGeneration == focusGeneration.get() &&
                                        !riveFocusState.hasFocus) {
                                        if (!accessibility.enterKeyboardOrder(direction)) {
                                            val current = rootView.findFocus() ?: this
                                            current.focusSearch(direction)?.takeIf { target -> target !== current }?.requestFocus(direction)
                                        }
                                    }
                                }
                            }
                            videoPlayback?.advance(renderer, frameTimeNanos / 1_000_000_000.0)
                        }
                    } catch (error: Throwable) {
                        reportFailure(
                            ExperiencePresentationException.Reason.HOST_FAILED,
                            "Experience runtime step failed",
                            error,
                        )
                        return@enqueue
                    }
                    val viewModelSnapshot = if (
                        interactiveLayer != null || textInputs.isNotEmpty() || outcome.events.isNotEmpty() || outcome.hasPublishableEffects()
                    ) {
                        try {
                            captureStepSnapshot(outcome, correlationId)
                        } catch (error: Throwable) {
                            reportFailure(
                                ExperiencePresentationException.Reason.HOST_FAILED,
                                "Experience view-model snapshot failed",
                                error,
                            )
                            return@enqueue
                        }
                    } else {
                        null
                    }
                    viewModelSnapshot?.let { retainScreenValues(it) }
                    submittedCaptions = SubmittedCaptions(videoPlayback?.captionSnapshot().orEmpty(), generation, epoch)
                    submittedSnapshot = viewModelSnapshot?.let { SubmittedTextSnapshot(it, outcome.textGeometry, generation, epoch) }
                    if (!firstFramePresented) firstFrameUpdateBaseline = surfaceUpdates.get()
                }
                if (!pendingPresentation) {
                    try {
                        if (videoPlayback?.isReadyForPresentation() == false) return@enqueue
                    } catch (error: Throwable) {
                        reportFailure(ExperiencePresentationException.Reason.HOST_FAILED,
                            "Experience video readiness failed", error)
                        return@enqueue
                    }
                }
                val disposition = renderer.renderAndPresent(player, window, clearColor, layout.density, layout.pixelWidth, layout.pixelHeight)
                pendingPresentation = disposition == 4
                if (disposition < 0) {
                    Log.w(LOG_TAG, "render_player failed", null, Log.status("status", -disposition))
                    reportFailure(
                        ExperiencePresentationException.Reason.HOST_FAILED,
                        "Experience rendering failed with status ${-disposition}",
                    )
                } else if (disposition == 1 || disposition == 2) {
                    try { publishSemantics(player, generation, epoch) } catch (error: Exception) {
                        reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Experience semantic capture failed", error)
                        return@enqueue
                    }
                    val captions = submittedCaptions
                    val captionVersion = captionPublication.incrementAndGet()
                    post {
                        if (captions != null && !released.get() && running && captions.generation == frameGeneration.get() &&
                            captions.epoch == semanticEpoch.get() && captionVersion == captionPublication.get()) {
                            listener?.onVideoCaptions(captions.captions)
                        }
                    }
                    val submitted = submittedSnapshot
                    submittedSnapshot = null
                    submittedCaptions = null
                    val publication = textPublication.incrementAndGet()
                    if (textInputs.isNotEmpty() && submitted != null) {
                        post {
                            // A delayed completion keeps the submission's lifecycle identity.
                            // Hide/resize/input changes invalidate it even if the host resumes
                            // before this UI callback executes; newer delivered frames supersede it.
                            if (!released.get() && running && sceneInputEnabled.get() &&
                                submitted.generation == frameGeneration.get() &&
                                submitted.epoch == semanticEpoch.get() && publication == textPublication.get()) {
                                listener?.onTextInputSnapshot(submitted.snapshot, submitted.geometry)
                            }
                        }
                    }
                    if (!firstFramePresented) {
                        firstFramePresented = true
                    }
                    publishSteps()
                    presentedTransitionWrite?.completion?.complete(Unit)
                    presentedTransitionWrite = null
                    drainTextWrites()
                    drainSemanticAction()
                }
            } finally {
                runValues?.setPresentationPending(saveFrameOwner, pendingPresentation)
                framePending.set(false)
            }
        }
        if (!accepted) framePending.set(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!running || !sceneInputEnabled.get() || released.get()) return false
        // Focus loss queues its text commit before dispatch reaches this view.
        // Stage the pointer on that same lane: an older queued frame must not
        // consume a button tap before the edit that preceded it on the UI thread.
        val copy = MotionEvent.obtain(event)
        val viewportWidth = width
        val viewportHeight = height
        val density = resources.displayMetrics.density
        val generation = frameGeneration.get()
        val epoch = semanticEpoch.get()
        val accepted = lane.enqueue {
            try {
                if (!released.get() && running && sceneInputEnabled.get() && generation == frameGeneration.get() &&
                    epoch == semanticEpoch.get()) {
                    pointerInput.enqueue(copy, viewportWidth, viewportHeight, density)
                }
            } finally {
                copy.recycle()
            }
        }
        if (!accepted) copy.recycle()
        return accepted
    }

    /** Release every native handle. The host is not reusable afterwards. */
    fun release(finalState: Map<String, NuxieViewModelScalarValue> = emptyMap(), onMediaReleased: () -> Unit = {}) {
        if (!released.compareAndSet(false, true)) return
        discardFocusInput()
        captionPublication.incrementAndGet()
        listener?.onVideoCaptions(emptyMap())
        retireSemantics()
        val finalValues = finalState.toMap()
        pointerInput.release()
        updateFrameScheduling()
        lane.enqueue {
            pendingPresentation = false
            cancelTransitionWrites()
            cancelRuntimeWrites()
            var firstFailure = runCatching {
                drainTextWrites() // Complete queued edits as failed before closing native handles.
                if (!failureReported.get()) applyRuntimeValues(finalValues)
            }.exceptionOrNull()
            // Capture on the original lane after input/exit writes and before releasing handles.
            val snapshotFailure = retainedViewModel?.let { retained -> runCatching {
                (viewModelState?.snapshot() ?: artboard?.defaultViewModelSnapshot())?.let(::retainScreenValues)
            }.exceptionOrNull() }
            snapshotFailure?.let { if (firstFailure == null) firstFailure = it else firstFailure?.addSuppressed(it) }
            attached = false
            val closeHandles = listOfNotNull(
                window?.let { ownedWindow -> renderer?.let { active -> { active.detachSurface(ownedWindow); Unit } } },
                window?.let { it::close },
                videoPlayback?.let { owner -> { owner.closeAfterRetirement(onMediaReleased) } } ?: onMediaReleased,
                interactiveLayer?.let { it::closeOnLane } ?: player?.let { it::close },
                ::closeThemeInstances,
                presentationHeight::close,
                environment?.let { it::close },
                viewModelState?.takeIf { interactiveLayer == null }?.let { it::close },
                artboard?.takeIf { interactiveLayer == null }?.let { it::close },
                file?.takeIf { ownsRuntimeFile }?.let { it::close },
                renderer?.takeIf { ownsRuntimeFile }?.let { it::close },
            )
            window = null
            player = null
            interactiveLayer = null
            videoPlayback = null
            viewModelState = null
            environment = null
            environmentBindings = null
            appliedColorScheme = null
            artboard = null
            file = null
            renderer = null
            unpublishedSteps.clear()
            pendingPresentation = false
            submittedSnapshot = null
            submittedCaptions = null
            closeHandles.forEach { close ->
                try {
                    close()
                } catch (error: Throwable) {
                    if (firstFailure == null) firstFailure = error else firstFailure?.addSuppressed(error)
                }
            }
            runValues?.setPresentationPending(saveFrameOwner, false)
            firstFailure?.let { throw it }
        }
    }

    private fun reportFailure(
        reason: ExperiencePresentationException.Reason,
        message: String,
        cause: Throwable? = null,
    ) {
        if (!failureReported.compareAndSet(false, true)) return
        discardFocusInput()
        captionPublication.incrementAndGet()
        lane.enqueue {
            interactiveLayer?.fail(cause ?: IllegalStateException(message))
            cancelTransitionWrites()
            cancelRuntimeWrites()
            videoPlayback?.setVisible(false)
        }
        mainHandler.post { listener?.onVideoCaptions(emptyMap()) }
        val failure = ExperiencePresentationException(reason, message, cause)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!released.get()) listener?.onFailure(failure)
        } else {
            mainHandler.post { if (!released.get()) listener?.onFailure(failure) }
        }
    }

    private fun NuxiePlayerStepOutcome.hasPublishableEffects(): Boolean =
        events.isNotEmpty() || hostCommands.isNotEmpty() || viewModelChanges.isNotEmpty()

    /** Called once after each player step, before any later native writes. */
    private fun captureStepSnapshot(outcome: NuxiePlayerStepOutcome, correlationId: ULong): NuxieViewModelSnapshot? {
        interactiveLayer?.let { layer ->
            val step = layer.captureOnLane(outcome, correlationId)
            captureLayerStep(step)
            return step.rows[activeRowId]?.snapshot
        }
        val captured = viewModelState?.captureSnapshot(outcome.viewModelChanges) ?: artboard?.captureDefaultSnapshot()
        val saves = mutableListOf<ExperienceResponseSaveRequest>()
        val descriptor = saveDescriptor
        if (descriptor != null && captured != null && (outcome.events.any { it.name == ExperienceResponseSaveRequest.EVENT } ||
            outcome.hostCommands.any { it.name == ExperienceResponseSaveRequest.EVENT })) {
            val catalog = checkNotNull(file).viewModelCatalog()
            saves += ExperienceResponseSaveRequest.captureFrame(outcome, captured.native, catalog, descriptor) {
                Log.w(LOG_TAG, "Rejected native response save")
            }
        }
        if (outcome.hasPublishableEffects()) {
            unpublishedSteps.addLast(PublishedStep(correlationId, outcome, captured?.values, saves))
        }
        return captured?.values
    }

    /** Keep the complete step, even when a removed row requested the advance. */
    private fun captureLayerStep(step: ExperienceInteractivePage.Step) {
        for ((id, row) in step.rows) {
            layerRetainedValues[id]?.set(row.snapshot.withoutRootProperty("experience"))
            val saves = saveDescriptor?.takeIf {
                row.outcome.events.any { it.name == ExperienceResponseSaveRequest.EVENT } ||
                    row.outcome.hostCommands.any { it.name == ExperienceResponseSaveRequest.EVENT }
            }?.let { descriptor ->
                ExperienceResponseSaveRequest.captureFrame(row.outcome, row.nativeSnapshot,
                    checkNotNull(file).viewModelCatalog(), descriptor) { Log.w(LOG_TAG, "Rejected native response save") }
            }.orEmpty()
            if (row.outcome.hasPublishableEffects()) unpublishedSteps.addLast(PublishedStep(step.correlationId,
                row.outcome, row.snapshot, saves, id))
        }
        if (step.unattributedEvents.isNotEmpty() || step.unattributedHostCommands.isNotEmpty()) {
            Log.w(LOG_TAG, "Experience layer effects have no unique Screen owner")
        }
    }

    private data class PublishedStep(
        val correlationId: ULong,
        val outcome: NuxiePlayerStepOutcome,
        val viewModelSnapshot: NuxieViewModelSnapshot?,
        val saves: List<ExperienceResponseSaveRequest>,
        val screenId: String? = null,
    )

    /**
     * Exact upstream import is factory-first. A renderer therefore exists
     * before the file is decoded, and its retained Vulkan factory is the one
     * used by every resource created for that file.
     */
    private fun ensureRenderer(pixelWidth: Int, pixelHeight: Int): NuxieAndroidVulkanRenderer? {
        renderer?.let { return it }
        return runtime.newAndroidVulkanRenderer(pixelWidth, pixelHeight).also { created ->
            renderer = created
            if (created == null) Log.w(LOG_TAG, "Android Vulkan renderer creation failed")
        }
    }

    internal companion object {
        internal fun requireLayerPresentation(fitContent: Boolean) {
            if (fitContent) Log.w(LOG_TAG, "Page content height is unavailable; fit-content uses the large sheet height")
        }

        const val LOG_TAG = "Nuxie"
        const val CLEAR_COLOR_OPAQUE_BLACK = 0xFF000000.toInt()
        const val NUX_STATUS_OK = 0

    }
}
