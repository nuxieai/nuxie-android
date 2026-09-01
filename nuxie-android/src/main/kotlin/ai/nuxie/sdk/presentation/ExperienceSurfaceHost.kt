package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder
import ai.nuxie.sdk.experiences.ExperienceViewModelBinding
import ai.nuxie.sdk.runtime.NuxieAndroidVulkanRenderer
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeArtboard
import ai.nuxie.sdk.runtime.NuxieRuntimeFile
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieRuntimePlayer
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeWindow
import ai.nuxie.sdk.runtime.NuxieRuntimeViewModelState
import ai.nuxie.sdk.runtime.NuxieViewModelListProjection
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject

/**
 * SurfaceView host driving the engine's headless Android Vulkan renderer:
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
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    interface Listener {
        fun onFirstFrame()
        fun onFailure(error: ExperiencePresentationException)
        fun onRuntimeEvent(event: NuxieRuntimeEvent) {}
    }

    /** Owned runtime wrappers; created, touched, and closed only on the runtime lane. */
    private var renderer: NuxieAndroidVulkanRenderer? = null
    private var window: NuxieRuntimeWindow? = null
    private var player: NuxieRuntimePlayer? = null
    private var file: NuxieRuntimeFile? = null
    private var artboard: NuxieRuntimeArtboard? = null
    private var viewModelState: NuxieRuntimeViewModelState? = null

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
    private var lastFrameNanos = 0L
    private val pointerInput = ExperienceRuntimePointerInput(artboardSize)

    init {
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
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
        onLoaded: ((Boolean) -> Unit)? = null,
    ) {
        lane.enqueue {
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        val width = (frame?.width() ?: width).coerceAtLeast(1)
        val height = (frame?.height() ?: height).coerceAtLeast(1)
        val surface = holder.surface
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
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        lane.enqueue {
            if (attached) {
                renderer?.resize(
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                )
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        pointerInput.reset()
        Choreographer.getInstance().removeFrameCallback(this)
        // Session state (player/artboard) survives; only presentation stops.
        // The Surface contract requires rendering to have stopped before
        // this callback returns, so block until the lane drains past the
        // detach marker; a FIFO marker alone would let already-queued
        // frames render to the dead surface after we return.
        val detached = CountDownLatch(1)
        val accepted = lane.enqueue {
            attached = false
            window?.close()
            window = null
            detached.countDown()
        }
        // A rejected marker means the lane is shutting down, but orderly
        // shutdown still drains frames accepted before it; wait for full
        // termination instead so none of them can touch the dead surface.
        val drained = if (accepted) {
            detached.await(SURFACE_DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } else {
            lane.awaitQuiescence(SURFACE_DETACH_TIMEOUT_MS)
        }
        if (!drained) {
            Log.w(LOG_TAG, "Runtime lane did not confirm surface detach in time")
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val elapsedSeconds = if (lastFrameNanos == 0L) {
            0.0
        } else {
            (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
        }
        lastFrameNanos = frameTimeNanos
        lane.enqueue {
            if (!attached) return@enqueue
            val renderer = renderer ?: return@enqueue
            val player = player ?: return@enqueue
            // Invariant: attached is set only after a successful window
            // acquire and cleared in the same lane task that closes the
            // window, so attached implies a live window; this null-check is
            // a type-level guard, never a reachable behavior change.
            val window = window ?: return@enqueue
            val outcome = try {
                player.stepWithEvents(
                    elapsedSeconds = elapsedSeconds,
                    pointers = pointerInput.takeBatch(),
                )
            } catch (error: Throwable) {
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience runtime step failed",
                    error,
                )
                return@enqueue
            }
            val disposition = renderer.renderAndPresent(player, window, clearColor, true)
            if (disposition < 0) {
                Log.w(LOG_TAG, "render_player failed with status ${-disposition}")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience rendering failed with status ${-disposition}",
                )
            } else if (disposition > 0) {
                listener?.onFirstFrame()
            }
            if (outcome.events.isNotEmpty()) {
                post {
                    if (!released.get()) {
                        outcome.events.forEach { listener?.onRuntimeEvent(it) }
                    }
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!running || released.get()) return false
        return pointerInput.enqueue(event, width, height)
    }

    /** Release every native handle. The host is not reusable afterwards. */
    fun release() {
        released.set(true)
        pointerInput.release()
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        lane.enqueue {
            attached = false
            val closeHandles = listOfNotNull(
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

        /**
         * Bound on the surfaceDestroyed drain. A frame takes milliseconds;
         * this only trips if the runtime lane is wedged, and then leaking
         * one frame to a dead surface beats deadlocking the main thread.
         */
        const val SURFACE_DETACH_TIMEOUT_MS = 1_000L
    }
}
