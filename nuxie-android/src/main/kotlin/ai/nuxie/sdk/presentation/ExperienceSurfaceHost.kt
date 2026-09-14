package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder
import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.runtime.NuxieAndroidVulkanRenderer
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
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Choreographer
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
) : TextureView(context), TextureView.SurfaceTextureListener, Choreographer.FrameCallback {
    interface Listener {
        fun onFirstFrame()
        fun onRuntimeStep(
            outcome: NuxiePlayerStepOutcome,
            correlationId: ULong,
            viewModelSnapshot: NuxieViewModelSnapshot?,
        ) {}
        fun onFailure(error: ExperiencePresentationException)
        fun onRuntimeEvent(event: NuxieRuntimeEvent, viewModelSnapshot: NuxieViewModelSnapshot?) {}
        /** UI-thread geometry update, after its frame has been presented. */
        fun onTextInputSnapshot(snapshot: NuxieViewModelSnapshot) {}
        /** Runtime-lane callback ordered with writes and renderer publications. */
        fun onTextCommitted(inputId: String, text: String) {}
    }

    /** Owned runtime wrappers; created, touched, and closed only on the runtime lane. */
    private var renderer: NuxieAndroidVulkanRenderer? = null
    private var window: NuxieRuntimeWindow? = null
    private var player: NuxieRuntimePlayer? = null
    private var file: NuxieRuntimeFile? = null
    private var artboard: NuxieRuntimeArtboard? = null
    private var viewModelState: NuxieRuntimeViewModelState? = null
    private var nextCorrelationId = 1uL
    @Volatile private var firstFramePresented = false
    @Volatile private var firstFrameComposed = false
    private var androidSurface: Surface? = null
    private val unpublishedSteps = ArrayDeque<PublishedStep>()
    private var textInputs: Map<String, ExperienceTextInput> = emptyMap()
    private val runtimeValues = linkedMapOf<String, NuxieViewModelScalarValue>()
    private val reportedStatePaths = mutableSetOf<String>()

    /** UI entry point; values submitted before loading are applied to the bound root before player creation. */
    fun updateRuntimeValues(values: Map<String, NuxieViewModelScalarValue>) {
        if (released.get()) return
        val copied = values.toMap()
        lane.enqueue {
            if (released.get()) return@enqueue
            runtimeValues.putAll(copied)
            applyRuntimeValues(copied)
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
                if (reportedStatePaths.add(path)) Log.w(LOG_TAG, "Experience state path rejected: $path", error)
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
    private val frameGeneration = AtomicLong(0)
    // At most one queued or executing frame. Input and cleanup share this FIFO lane.
    private val framePending = AtomicBoolean(false)
    private var surfaceAvailable = false
    private var presentationVisible = true
    private var lastFrameNanos = 0L
    private val pointerInput = ExperienceRuntimePointerInput(artboardSize)

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    /**
     * Load a verified release riv and select its artboard/player. Must be
     * called before the surface is created (tracer entry point; the
     * presentation service supersedes this with prepared releases).
     */
    fun loadArtboard(
        rivBytes: ByteArray,
        artboardName: String?,
        descriptor: JsonObject? = null,
        artifactsByKey: Map<String, File> = emptyMap(),
        viewModelProjection: NuxieViewModelListProjection? = null,
        textInputs: List<ExperienceTextInput> = emptyList(),
        onLoaded: ((Boolean) -> Unit)? = null,
    ) {
        lane.enqueue {
            this.textInputs = textInputs.associateBy(ExperienceTextInput::id)
            val activeRenderer = ensureRenderer(1, 1)
            if (activeRenderer == null) {
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience renderer creation failed",
                )
                onLoaded?.invoke(false)
                return@enqueue
            }
            file = if (descriptor == null) {
                runtime.importFile(activeRenderer, rivBytes)
            } else {
                val inspectedCatalog = runtime.inspectFileAssets(rivBytes)
                if (inspectedCatalog == null) {
                    reportFailure(
                        ExperiencePresentationException.Reason.PREPARATION_FAILED,
                        "Runtime could not inspect the Experience asset catalog",
                    )
                    onLoaded?.invoke(false)
                    return@enqueue
                }
                val import = runCatching {
                    ExperienceAssetImportBuilder.build(
                        descriptor = descriptor,
                        artifactsByKey = artifactsByKey,
                        inspectedCatalog = inspectedCatalog,
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
                runtime.importFile(
                    renderer = activeRenderer,
                    bytes = rivBytes,
                    expectedAssets = import.expectedAssets,
                    externalAssets = import.externalAssets,
                )
            }
            val loadedFile = file
            if (loadedFile == null) {
                Log.w(LOG_TAG, "Runtime rejected the riv bytes")
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
                if (viewModelProjection != null) {
                    viewModelState = runtime.bindViewModelList(
                        file = loadedFile,
                        artboard = loadedArtboard,
                        projection = viewModelProjection,
                    )
                } else {
                    descriptor?.let {
                        ExperienceViewModelBinding.defaultSchemaName(it, artboardName)
                    }?.let(loadedArtboard::bindDefaultViewModel)
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
            player = loadedArtboard.newPlayer()
            if (player == null) {
                val error = IllegalStateException("Experience player creation failed")
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
    fun writeText(inputId: String, text: String, commit: Boolean, completion: (Result<Unit>) -> Unit) {
        fun complete(result: Result<Unit>) { post { completion(result) } }
        if (released.get()) {
            complete(Result.failure(IllegalStateException("Experience surface is released")))
            return
        }
        val accepted = lane.enqueue {
            val result = runCatching {
                check(!released.get()) { "Experience surface is released" }
                val input = checkNotNull(textInputs[inputId]) { "Text input is not declared for this screen" }
                val limited = ExperienceTextInputLimit.apply(text, input.maxLength)
                checkNotNull(artboard) { "Experience artboard is unavailable" }
                    .setTextRun(input.runName, if (input.secure) "" else limited)
                if (commit) listener?.onTextCommitted(inputId, limited)
            }
            complete(result)
        }
        if (!accepted) complete(Result.failure(IllegalStateException("Runtime lane is shut down")))
    }

    /** UI-thread visibility input; a paused but visible Activity remains active. */
    fun setPresentationVisible(visible: Boolean) {
        presentationVisible = visible
        updateFrameScheduling()
    }

    private fun updateFrameScheduling() {
        val shouldRun = surfaceAvailable && presentationVisible && !released.get()
        if (running == shouldRun) return
        running = shouldRun
        frameGeneration.incrementAndGet()
        lastFrameNanos = 0L
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
            attached = true
        }
        surfaceAvailable = true
        updateFrameScheduling()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        lane.enqueue {
            if (attached) {
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
            }
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
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
                try {
                    renderer?.detachSurface()
                } finally {
                    window?.close()
                    window = null
                }
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
        if (!released.get() && firstFramePresented && !firstFrameComposed) {
            firstFrameComposed = true
            listener?.onFirstFrame()
            lane.enqueue { if (!released.get()) publishSteps() }
        }
    }

    private fun publishSteps() {
        while (firstFrameComposed && unpublishedSteps.isNotEmpty()) {
            val step = unpublishedSteps.removeFirst()
            listener?.onRuntimeStep(step.outcome, step.correlationId, step.viewModelSnapshot)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        if (!framePending.compareAndSet(false, true)) return
        val generation = frameGeneration.get()
        val elapsedSeconds = if (lastFrameNanos == 0L) {
            0.0
        } else {
            (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
        }
        lastFrameNanos = frameTimeNanos
        val accepted = lane.enqueue {
            try {
                if (!attached || !running || generation != frameGeneration.get()) return@enqueue
                val renderer = renderer ?: return@enqueue
                val player = player ?: return@enqueue
                // Invariant: attached is set only after a successful window
                // acquire and cleared in the same lane task that closes the
                // window, so attached implies a live window; this null-check is
                // a type-level guard, never a reachable behavior change.
                val window = window ?: return@enqueue
                val correlationId = nextCorrelationId
                nextCorrelationId = if (nextCorrelationId == ULong.MAX_VALUE) {
                    1uL
                } else {
                    nextCorrelationId + 1uL
                }
                val outcome = try {
                    player.stepTyped(
                        elapsedSeconds = elapsedSeconds,
                        pointers = pointerInput.takeBatch(),
                        correlationId = correlationId,
                    )
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
                if (outcome.hasPublishableEffects()) {
                    unpublishedSteps.addLast(PublishedStep(correlationId, outcome, viewModelSnapshot))
                }
                val disposition = renderer.renderAndPresent(player, window, clearColor, true)
                if (disposition < 0) {
                    Log.w(LOG_TAG, "render_player failed with status ${-disposition}")
                    reportFailure(
                        ExperiencePresentationException.Reason.HOST_FAILED,
                        "Experience rendering failed with status ${-disposition}",
                    )
                } else if (disposition > 0) {
                    if (textInputs.isNotEmpty() && viewModelSnapshot != null) {
                        post {
                            if (!released.get()) listener?.onTextInputSnapshot(viewModelSnapshot)
                        }
                    }
                    if (!firstFramePresented) {
                        firstFramePresented = true
                    }
                    publishSteps()
                }
                if (outcome.events.isNotEmpty()) {
                    post {
                        if (!released.get()) {
                            outcome.events.forEach {
                                listener?.onRuntimeEvent(it, viewModelSnapshot)
                            }
                        }
                    }
                }
            } finally {
                framePending.set(false)
            }
        }
        if (!accepted) framePending.set(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!running || released.get()) return false
        // Focus loss queues its text commit before dispatch reaches this view.
        // Stage the pointer on that same lane: an older queued frame must not
        // consume a button tap before the edit that preceded it on the UI thread.
        val copy = MotionEvent.obtain(event)
        val viewportWidth = width
        val viewportHeight = height
        val generation = frameGeneration.get()
        val accepted = lane.enqueue {
            try {
                if (!released.get() && running && generation == frameGeneration.get()) {
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
    fun release(finalState: Map<String, NuxieViewModelScalarValue> = emptyMap()) {
        if (!released.compareAndSet(false, true)) return
        val finalValues = finalState.toMap()
        pointerInput.release()
        updateFrameScheduling()
        lane.enqueue {
            applyRuntimeValues(finalValues)
            attached = false
            val closeHandles = listOfNotNull(
                renderer?.let { active -> { active.detachSurface(); Unit } },
                window?.let { it::close },
                player?.let { it::close },
                viewModelState?.let { it::close },
                artboard?.let { it::close },
                file?.let { it::close },
                renderer?.let { it::close },
            )
            window = null
            player = null
            viewModelState = null
            artboard = null
            file = null
            renderer = null
            unpublishedSteps.clear()
            var firstFailure: Throwable? = null
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
        listener?.onFailure(ExperiencePresentationException(reason, message, cause))
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
