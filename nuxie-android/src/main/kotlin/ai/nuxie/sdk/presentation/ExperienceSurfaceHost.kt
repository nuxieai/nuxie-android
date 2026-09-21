package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.ExperienceVideoPlayback
import ai.nuxie.sdk.experiences.ExperienceVideoAssetBinding
import ai.nuxie.sdk.experiences.ExperienceVideoElement
import ai.nuxie.sdk.experiences.JourneyVideoAction
import kotlinx.coroutines.CompletableDeferred
import ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder
import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.experiences.SystemFontCache
import ai.nuxie.sdk.runtime.NativeSemanticNode
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
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import ai.nuxie.sdk.runtime.NuxieViewModelListProjection
import android.content.Context
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
    private val artboardSize: ExperienceArtboardSize? = null,
    private val runtime: NuxieRuntime = NuxieRuntime.shared,
    private val systemFontCache: SystemFontCache = SystemFontCache.shared,
    private val videoDecoderPool: ai.nuxie.sdk.runtime.ExperienceVideoDecoderPool? = null,
) : TextureView(context), TextureView.SurfaceTextureListener, Choreographer.FrameCallback {
    private val mainHandler = Handler(Looper.getMainLooper())
    interface Listener {
        fun onVideoCaptions(captions: Map<Long, ai.nuxie.sdk.runtime.NuxieVideoCaption>) {}
        fun onFirstFrame()
        fun onRuntimeStep(
            outcome: NuxiePlayerStepOutcome,
            correlationId: ULong,
            viewModelSnapshot: NuxieViewModelSnapshot?,
        ) {}
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
    private var videoPlayback: ExperienceVideoPlayback? = null
    private var videoArtboardSize: Pair<Float, Float>? = null
    private data class VideoCommand(val action: JourneyVideoAction, val generation: Long, val result: CompletableDeferred<Boolean>)
    private val videoCommands = ArrayDeque<VideoCommand>()

    suspend fun applyVideoCommand(action: JourneyVideoAction): Boolean {
        val result = CompletableDeferred<Boolean>()
        val generation = frameGeneration.get()
        if (!lane.enqueue {
            if (released.get() || failureReported.get() || videoCommands.size >= 64) result.complete(false)
            else {
                videoCommands.addLast(VideoCommand(action, generation, result))
                drainVideoCommands()
            }
        }) return false
        return try { result.await() } finally { result.cancel() }
    }

    private fun drainVideoCommands() {
        if (pendingPresentation) return
        while (videoCommands.isNotEmpty()) {
            val command = videoCommands.removeFirst()
            val applied = command.result.isActive && !released.get() && !failureReported.get() && command.generation == frameGeneration.get() &&
                runCatching { checkNotNull(videoPlayback).apply(command.action) }.isSuccess
            command.result.complete(applied)
        }
    }
    private var file: NuxieRuntimeFile? = null
    private var artboard: NuxieRuntimeArtboard? = null
    private var viewModelState: NuxieRuntimeViewModelState? = null
    @Volatile private var semanticsEnabled = false
    private var semanticSnapshot: NuxieSemanticSnapshot? = null
    private var semanticSnapshotEpoch = -1L
    private var semanticFields: Map<String, NativeSemanticNode> = emptyMap()
    private var queuedSemanticAction: QueuedSemanticAction? = null
    private val semanticActionPending = AtomicBoolean(false)
    private val sceneInputEnabled = AtomicBoolean(true)
    private val semanticEpoch = AtomicLong()
    private val accessibility = ExperienceAccessibilityProvider(this,
        bounds = { semanticBounds(this, artboardSize, it) }, dispatch = ::dispatchSemanticAction)

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.hover(event) || super.dispatchHoverEvent(event)

    fun semanticKeyboardEntry(direction: Int): android.view.View? = accessibility.keyboardEntry(direction)

    fun dispatchSemanticKeyEvent(event: KeyEvent): Boolean = accessibility.key(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        accessibility.key(event) || super.dispatchKeyEvent(event)

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
        if (!semanticsEnabled || epoch != semanticEpoch.get()) return
        val next = active.captureSemantics()
        val fields = try {
            textInputs.values.filter { it.editableValueName == null }.mapNotNull { input ->
                next.nodeForTextRun(active.requireHandle(), input.runName)?.let { input.id to it }
            }.toMap().also { fields ->
                check(fields.values.map { it.id }.distinct().size == fields.size) {
                    "Multiple native fields name the same semantic owner"
                }
            }
        } catch (error: Throwable) { next.close(); throw error }
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
    private var androidSurface: Surface? = null
    private val unpublishedSteps = ArrayDeque<PublishedStep>()
    // SUBMITTED retains the exact frame until native completion. Polling must
    // neither step the player nor publish effects from its unfinished frame.
    private var pendingPresentation = false
    private val pendingTextWrites = ArrayDeque<() -> Unit>()

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
    private var retainedViewModel: java.util.concurrent.atomic.AtomicReference<NuxieViewModelSnapshot?>? = null
    private var textInputs: Map<String, ExperienceTextInput> = emptyMap()
    private val runtimeValues = linkedMapOf<String, NuxieViewModelScalarValue>()
    private var runtimeValuesPending = false
    private val reportedStatePaths = mutableSetOf<String>()

    /** UI entry point; values submitted before loading are applied to the bound root before player creation. */
    fun updateRuntimeValues(values: Map<String, NuxieViewModelScalarValue>) {
        if (released.get()) return
        val copied = values.toMap()
        lane.enqueue {
            if (released.get()) return@enqueue
            runtimeValues.putAll(copied)
            if (pendingPresentation) runtimeValuesPending = true
            else applyRuntimeValues(copied)
        }
    }

    private fun applyRuntimeValues(values: Map<String, NuxieViewModelScalarValue>) {
        val boundArtboard = artboard ?: return
        for ((path, value) in values) {
            try {
                val projected = viewModelState
                if (projected != null) projected.setValue(path, value)
                else boundArtboard.setDefaultViewModelValue(path, value)
            } catch (error: Exception) {
                // Reserved environment fields are optional in older releases. One
                // rejected field must not prevent valid siblings or close the screen.
                if (reportedStatePaths.add(path)) Log.w(LOG_TAG, "Experience state path rejected", error, Log.sensitive("path", path))
            }
        }
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

    init {
        isOpaque = false
        surfaceTextureListener = this
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
        onLoaded: ((Boolean) -> Unit)? = null,
    ) {
        retireSemantics()
        lane.enqueue {
            val requirements = descriptor?.get("requirements") as? JsonObject
            semanticsEnabled = (requirements?.get("requiredCapabilities") as? JsonArray).orEmpty()
                .any { (it as? JsonPrimitive)?.content == "experience-accessibility" } ||
                textInputs.any { it.editableValueName != null }
            this.textInputs = textInputs.associateBy(ExperienceTextInput::id)
            this.retainedViewModel = retainedViewModel
            val activeRenderer = ensureRenderer(1, 1)
            if (activeRenderer == null) {
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience renderer creation failed",
                )
                onLoaded?.invoke(false)
                return@enqueue
            }
            var videoBindings: List<ExperienceVideoAssetBinding> = emptyList()
            var videoTargets: List<ExperienceVideoElement> = emptyList()
            file = if (descriptor == null) {
                runtime.importFile(activeRenderer, sceneBytes)
            } else {
                val inspectedCatalog = runtime.inspectFileAssets(sceneBytes)
                if (inspectedCatalog == null) {
                    reportFailure(
                        ExperiencePresentationException.Reason.PREPARATION_FAILED,
                        "Runtime could not inspect the Experience asset catalog",
                    )
                    onLoaded?.invoke(false)
                    return@enqueue
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
                    return@enqueue
                }
                videoBindings = import.videos
                val render = descriptor["render"] as? JsonObject
                val screen = (render?.get("screens") as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.singleOrNull { (it["artboardName"] as? JsonPrimitive)?.content == artboardName }
                val artboardId = (screen?.get("artboardId") as? JsonPrimitive)?.content
                videoArtboardSize = if (videoBindings.isEmpty()) null else {
                    val sceneWidth = (screen?.get("width") as? JsonPrimitive)?.content?.toFloatOrNull()
                    val sceneHeight = (screen?.get("height") as? JsonPrimitive)?.content?.toFloatOrNull()
                    if (sceneWidth != null && sceneHeight != null) sceneWidth to sceneHeight else null
                }
                videoTargets = import.videoElements
                try {
                    runtime.importFile(
                        renderer = activeRenderer,
                        bytes = sceneBytes,
                        expectedAssets = import.expectedAssets,
                        externalAssets = import.externalAssets,
                        videoEnabled = videoBindings.isNotEmpty(),
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
                    return@enqueue
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
                return@enqueue
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
                return@enqueue
            }
            try {
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
            } catch (error: Exception) {
                // Do not retain a partially bound graph after a signed state
                // contract failure. The renderer remains available for retry.
                artboard = null
                file = null
                runCatching { loadedArtboard.close() }.exceptionOrNull()?.let(error::addSuppressed)
                runCatching { loadedFile.close() }.exceptionOrNull()?.let(error::addSuppressed)
                reportFailure(
                    ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Experience view-model binding failed",
                    error,
                )
                onLoaded?.invoke(false)
                return@enqueue
            }
            applyRuntimeValues(runtimeValues)
            try {
                player = loadedFile.newExperiencePlayer(loadedArtboard, artboardName)
                if (semanticsEnabled) checkNotNull(player).enableSemantics()
                if (videoBindings.isNotEmpty()) {
                    videoPlayback = ExperienceVideoPlayback(context.applicationContext, checkNotNull(player), videoBindings, videoTargets,
                        decoderPool = videoDecoderPool, initialViewport = currentVideoViewport(width, height))
                    videoPlayback?.setVisible(running)
                }
            } catch (error: Exception) {
                val failedPlayer = player
                player = null
                runCatching { failedPlayer?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                artboard = null
                file = null
                runCatching { loadedArtboard.close() }.exceptionOrNull()?.let(error::addSuppressed)
                runCatching { loadedFile.close() }.exceptionOrNull()?.let(error::addSuppressed)
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience player creation failed",
                    error,
                )
                onLoaded?.invoke(false)
                return@enqueue
            }
            onLoaded?.invoke(true)
        }
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
                    capture == null || active == null || field == null || input?.editableValueName == null ||
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
                    owner = checkNotNull(active.fieldOwner(capture, target.nodeId, input.editableValueName))
                    val status = capture.writeFieldString(active.requireHandle(), target.nodeId,
                        input.editableValueName, write.text.encodeToByteArray())
                    if (status != 0) throw ai.nuxie.sdk.runtime.NuxieRuntimeCallException("write native input", status)
                    // Settle reverse bindings on the ordinary player before reading the typed source.
                    val correlationId = nextCorrelationId
                    nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) 1uL else nextCorrelationId + 1uL
                    val outcome = active.stepAfterStateMutation(correlationId = correlationId,
                        textRunNames = textInputs.values.filter { it.editableValueName == null }.map { it.runName }.distinct())
                    val root = viewModelState?.snapshot() ?: artboard?.defaultViewModelSnapshot()
                    root?.let { retainedViewModel?.set(it) }
                    if (outcome.hasPublishableEffects()) unpublishedSteps.addLast(PublishedStep(correlationId, outcome, root))
                    val evaluated = checkNotNull(owner).snapshot()
                    check(evaluated.nativeRootInstanceId == field.ownerId) { "Native field owner changed during write" }
                    acceptedNativeText[target] = AcceptedNativeText(write.text, evaluated)
                    publishSteps()
                }
                try { owner?.close() } catch (error: Exception) {
                    reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Native input owner cleanup failed", error)
                }
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
                        val outcome = checkNotNull(player).stepAfterStateMutation(correlationId = correlationId)
                        val root = viewModelState?.snapshot() ?: artboard?.defaultViewModelSnapshot()
                        root?.let { retainedViewModel?.set(it) }
                        if (outcome.hasPublishableEffects()) unpublishedSteps.addLast(PublishedStep(correlationId, outcome, root))
                        publishSteps()
                    } else listener?.onTextInputEvent(target.inputId, event)
                } catch (error: Exception) {
                    reportFailure(ExperiencePresentationException.Reason.HOST_FAILED, "Native input action failed", error)
                }
            }
            drainTextWrites()
        }
    }

    fun writeText(inputId: String, text: String, commit: Boolean, completion: (Result<Unit>) -> Unit) {
        fun complete(result: Result<Unit>) { post { completion(result) } }
        if (released.get()) {
            complete(Result.failure(IllegalStateException("Experience surface is released")))
            return
        }
        val write = {
            val result = runCatching {
                check(!released.get()) { "Experience surface is released" }
                val input = checkNotNull(textInputs[inputId]) { "Text input is not declared for this screen" }
                check(input.editableValueName == null) { "Native input requires a captured occurrence" }
                val limited = ExperienceTextInputLimit.apply(text, input.maxLength)
                checkNotNull(artboard) { "Experience artboard is unavailable" }
                    .setTextRun(input.runName, if (input.secure) "" else limited)
                if (commit) {
                    val snapshot = if (input.responseCapture == ExperienceTextInput.ResponseCapture.BINDING) {
                        check(!input.secure) { "Converted secure input is unsupported" }
                        val correlationId = nextCorrelationId
                        nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) 1uL else nextCorrelationId + 1uL
                        val outcome = checkNotNull(player).stepTyped(
                            elapsedSeconds = 0.0,
                            correlationId = correlationId,
                            textRunNames = textInputs.values.map { it.runName }.distinct(),
                        )
                        val captured = viewModelState?.snapshot() ?: artboard?.defaultViewModelSnapshot()
                        captured?.let { retainedViewModel?.set(it) }
                        if (outcome.hasPublishableEffects()) {
                            unpublishedSteps.addLast(PublishedStep(correlationId, outcome, captured))
                        }
                        publishSteps()
                        captured
                    } else null
                    listener?.onTextCommitted(inputId, limited, snapshot)
                }
            }
            complete(result)
        }
        val accepted = lane.enqueue {
            // A submitted render owns its revision until completion; text edits wait on the same lane.
            pendingTextWrites.addLast(write)
            drainTextWrites()
        }
        if (!accepted) complete(Result.failure(IllegalStateException("Runtime lane is shut down")))
    }

    /** Suspend scene actions during navigation while allowing authored exit animation to render. */
    fun setInputEnabled(enabled: Boolean) {
        if (sceneInputEnabled.getAndSet(enabled) == enabled) return
        isEnabled = enabled
        semanticEpoch.incrementAndGet()
        if (!enabled) lane.enqueue { pointerInput.reset() }
        if (!enabled) accessibility.withdraw() else accessibility.invalidateState()
    }

    /** UI-thread visibility input; a paused but visible Activity remains active. */
    fun setPresentationVisible(visible: Boolean) {
        if (!visible) {
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
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            // Reset after any staging operation that already started on this
            // lane; clearing on the UI thread could race its final enqueue.
            lane.enqueue { pointerInput.reset() }
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (released.get()) return
        val surface = Surface(texture)
        androidSurface = surface
        lane.enqueue {
            // Attach only once both the headless renderer and this surface's
            // window exist; a failed create/acquire keeps the frame gate shut.
            val activeRenderer = ensureRenderer(width, height)
            if (activeRenderer == null) {
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience renderer creation failed",
                )
                return@enqueue
            }
            if (activeRenderer.resize(width, height) != NUX_STATUS_OK) {
                Log.w(LOG_TAG, "Android Vulkan renderer resize failed")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience renderer resize failed",
                )
                return@enqueue
            }
            window = runtime.acquireWindow(surface)
            if (window == null) {
                Log.w(LOG_TAG, "Native window acquisition failed")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience surface acquisition failed",
                )
                return@enqueue
            }
            videoPlayback?.setViewport(currentVideoViewport(width, height))
            attached = true
        }
        surfaceAvailable = true
        updateFrameScheduling()
    }

    private fun currentVideoViewport(surfaceWidth: Int, surfaceHeight: Int): ai.nuxie.sdk.runtime.VideoViewport {
        val size = checkNotNull(videoArtboardSize) { "Video screen dimensions are unavailable" }
        return ai.nuxie.sdk.runtime.VideoViewport.contain(size.first, size.second, surfaceWidth, surfaceHeight)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        retireSemantics(preserveFocus = true)
        lane.enqueue {
            if (attached) {
                pendingPresentation = false
                submittedSnapshot = null
                submittedCaptions = null
                val status = renderer?.resize(
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                )
                if (status != NUX_STATUS_OK) {
                    attached = false
                    reportFailure(
                        ExperiencePresentationException.Reason.HOST_FAILED,
                        "Experience renderer resize failed with status $status",
                    )
                }
                videoPlayback?.setViewport(currentVideoViewport(width, height))
                drainVideoCommands()
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
                    renderer?.detachSurface()
                } finally {
                    window?.close()
                    window = null
                }
                drainVideoCommands()
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
            listener?.onRuntimeStep(step.outcome, step.correlationId, step.viewModelSnapshot)
            if (step.outcome.events.isNotEmpty()) {
                post {
                    if (!released.get()) {
                        step.outcome.events.forEach {
                            listener?.onRuntimeEvent(it, step.viewModelSnapshot)
                        }
                    }
                }
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || failureReported.get()) return
        Choreographer.getInstance().postFrameCallback(this)
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
                if (!pendingPresentation) {
                    drainTextWrites()
                    // A submitted frame owns its model revision through completion and semantic capture.
                    // Coalesce newer environment values and apply them before advancing the next frame.
                    if (runtimeValuesPending) {
                        applyRuntimeValues(runtimeValues)
                        runtimeValuesPending = false
                    }
                    // Keep the clock on the lane: a resize queued ahead of this
                    // tick may have retired its pending submission. Polling does
                    // not consume time, and visibility generations reset it.
                    val elapsedSeconds = if (lastSteppedGeneration != generation) {
                        0.0
                    } else {
                        (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                    }
                    lastFrameNanos = frameTimeNanos
                    lastSteppedGeneration = generation
                    val correlationId = nextCorrelationId
                    nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) {
                        1uL
                    } else {
                        nextCorrelationId + 1uL
                    }
                    val outcome = try {
                        if (!sceneInputEnabled.get()) pointerInput.reset()
                        player.stepTyped(
                            elapsedSeconds = elapsedSeconds,
                            pointers = pointerInput.takeBatch(),
                            correlationId = correlationId,
                            textRunNames = textInputs.values.map { it.runName }.distinct(),
                        ).also { videoPlayback?.advance(renderer, frameTimeNanos / 1_000_000_000.0) }
                    } catch (error: Throwable) {
                        reportFailure(
                            ExperiencePresentationException.Reason.HOST_FAILED,
                            "Experience runtime step failed",
                            error,
                        )
                        return@enqueue
                    }
                    val viewModelSnapshot = if (
                        textInputs.isNotEmpty() || outcome.events.isNotEmpty() || outcome.hasPublishableEffects()
                    ) {
                        try {
                            viewModelState?.snapshot() ?: artboard?.defaultViewModelSnapshot()
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
                    viewModelSnapshot?.let { retainedViewModel?.set(it) }
                    if (outcome.hasPublishableEffects()) {
                        unpublishedSteps.addLast(PublishedStep(correlationId, outcome, viewModelSnapshot))
                    }
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
                val disposition = renderer.renderAndPresent(player, window, clearColor, true)
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
                    drainTextWrites()
                    drainSemanticAction()
                    drainVideoCommands()
                }
            } finally {
                framePending.set(false)
                drainVideoCommands()
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
        val generation = frameGeneration.get()
        val epoch = semanticEpoch.get()
        val accepted = lane.enqueue {
            try {
                if (!released.get() && running && sceneInputEnabled.get() && generation == frameGeneration.get() &&
                    epoch == semanticEpoch.get()) {
                    pointerInput.enqueue(copy, viewportWidth, viewportHeight)
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
        captionPublication.incrementAndGet()
        listener?.onVideoCaptions(emptyMap())
        retireSemantics()
        val finalValues = finalState.toMap()
        pointerInput.release()
        updateFrameScheduling()
        lane.enqueue {
            pendingPresentation = false
            drainTextWrites() // Complete queued edits as failed before closing native handles.
            applyRuntimeValues(finalValues)
            // Capture on the original lane after input/exit writes and before releasing handles.
            var firstFailure = retainedViewModel?.let { retained -> runCatching {
                (viewModelState?.snapshot() ?: artboard?.defaultViewModelSnapshot())?.let(retained::set)
            }.exceptionOrNull() }
            attached = false
            val closeHandles = listOfNotNull(
                renderer?.let { active -> { active.detachSurface(); Unit } },
                window?.let { it::close },
                videoPlayback?.let { owner -> { owner.closeAfterRetirement(onMediaReleased) } } ?: onMediaReleased,
                player?.let { it::close },
                viewModelState?.let { it::close },
                artboard?.let { it::close },
                file?.let { it::close },
                renderer?.let { it::close },
            )
            window = null
            player = null
            videoPlayback = null
            viewModelState = null
            artboard = null
            file = null
            renderer = null
            unpublishedSteps.clear()
            pendingPresentation = false
            drainVideoCommands()
            submittedSnapshot = null
            submittedCaptions = null
            closeHandles.forEach { close ->
                try {
                    close()
                } catch (error: Throwable) {
                    if (firstFailure == null) firstFailure = error else firstFailure?.addSuppressed(error)
                }
            }
            firstFailure?.let { throw it }
        }
    }

    private fun reportFailure(
        reason: ExperiencePresentationException.Reason,
        message: String,
        cause: Throwable? = null,
    ) {
        if (!failureReported.compareAndSet(false, true)) return
        captionPublication.incrementAndGet()
        lane.enqueue {
            while (videoCommands.isNotEmpty()) videoCommands.removeFirst().result.complete(false)
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

    private data class PublishedStep(
        val correlationId: ULong,
        val outcome: NuxiePlayerStepOutcome,
        val viewModelSnapshot: NuxieViewModelSnapshot?,
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

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val CLEAR_COLOR_OPAQUE_BLACK = 0xFF000000.toInt()
        const val NUX_STATUS_OK = 0

    }
}
