package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperienceAssetImportBuilder
import ai.nuxie.sdk.runtime.NuxieRuntimeBridge
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    interface Listener {
        fun onFirstFrame()
        fun onFailure(error: ExperiencePresentationException)
    }

    /** Native handles; touched only on the runtime lane. */
    private var renderer = 0L
    private var window = 0L
    private var player = 0L
    private var file = 0L
    private var artboard = 0L

    /**
     * Lane-confined surface attachment. Jobs already queued when the
     * platform destroys the surface must not touch the abandoned window;
     * a destroy marker enqueued from the UI thread gates them in FIFO
     * order.
     */
    private var attached = false

    @Volatile
    private var running = false
    private var lastFrameNanos = 0L

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
        onLoaded: ((Boolean) -> Unit)? = null,
    ) {
        lane.enqueue {
            file = if (descriptor == null) {
                NuxieRuntimeBridge.fileNew(rivBytes)
            } else {
                val inspectedCatalog = NuxieRuntimeBridge.inspectFileAssets(rivBytes)
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
                NuxieRuntimeBridge.fileNew(
                    bytes = rivBytes,
                    expectedAssets = import.expectedAssets,
                    externalAssets = import.externalAssets,
                )
            }
            if (file == 0L) {
                Log.w(LOG_TAG, "Runtime rejected the riv bytes")
                reportFailure(
                    ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Runtime rejected the prepared Experience content",
                )
                onLoaded?.invoke(false)
                return@enqueue
            }
            artboard = if (artboardName != null) {
                NuxieRuntimeBridge.nativeArtboardInstanceNewNamed(file, artboardName)
            } else {
                NuxieRuntimeBridge.nativeArtboardInstanceNewDefault(file)
            }
            if (artboard == 0L) {
                Log.w(LOG_TAG, "Artboard unavailable")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience artboard is unavailable",
                )
                onLoaded?.invoke(false)
                return@enqueue
            }
            player = NuxieRuntimeBridge.nativePlayerNewDefault(artboard)
            if (player == 0L) {
                Log.w(LOG_TAG, "Player creation failed")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience player creation failed",
                )
                onLoaded?.invoke(false)
                return@enqueue
            }
            onLoaded?.invoke(true)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        val width = frame.width().coerceAtLeast(1)
        val height = frame.height().coerceAtLeast(1)
        val surface = holder.surface
        lane.enqueue {
            // Attach only once both the headless renderer and this surface's
            // window exist; a failed create/acquire keeps the frame gate shut.
            if (renderer == 0L) {
                renderer = NuxieRuntimeBridge.nativeRendererNewAndroidVulkan(
                    width, height,
                )
                if (renderer == 0L) {
                    Log.w(LOG_TAG, "Android Vulkan renderer creation failed")
                    reportFailure(
                        ExperiencePresentationException.Reason.HOST_FAILED,
                        "Experience renderer creation failed",
                    )
                    return@enqueue
                }
            }
            window = NuxieRuntimeBridge.nativeWindowAcquire(surface)
            if (window == 0L) {
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
            if (attached && renderer != 0L) {
                NuxieRuntimeBridge.nativeRendererResize(
                    renderer,
                    width.coerceAtLeast(1),
                    height.coerceAtLeast(1),
                )
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        // Session state (player/artboard) survives; only presentation stops.
        // The Surface contract requires rendering to have stopped before
        // this callback returns, so block until the lane drains past the
        // detach marker; a FIFO marker alone would let already-queued
        // frames render to the dead surface after we return.
        val detached = CountDownLatch(1)
        val accepted = lane.enqueue {
            attached = false
            if (window != 0L) {
                NuxieRuntimeBridge.nativeWindowRelease(window)
                window = 0L
            }
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
            if (!attached || renderer == 0L || player == 0L) return@enqueue
            NuxieRuntimeBridge.nativePlayerStep(player, elapsedSeconds)
            val disposition = NuxieRuntimeBridge.nativeRendererRenderPlayer(
                renderer, player, window, clearColor, true,
            )
            if (disposition < 0) {
                Log.w(LOG_TAG, "render_player failed with status ${-disposition}")
                reportFailure(
                    ExperiencePresentationException.Reason.HOST_FAILED,
                    "Experience rendering failed with status ${-disposition}",
                )
            } else if (disposition > 0) {
                listener?.onFirstFrame()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Release every native handle. The host is not reusable afterwards. */
    fun release() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        lane.enqueue {
            attached = false
            if (window != 0L) {
                NuxieRuntimeBridge.nativeWindowRelease(window)
                window = 0L
            }
            if (player != 0L) {
                if (renderer != 0L) {
                    NuxieRuntimeBridge.nativeRendererResetPlayerDomain(renderer, player)
                }
                NuxieRuntimeBridge.nativePlayerFree(player)
                player = 0L
            }
            if (artboard != 0L) {
                NuxieRuntimeBridge.nativeArtboardInstanceFree(artboard)
                artboard = 0L
            }
            if (file != 0L) {
                NuxieRuntimeBridge.nativeFileFree(file)
                file = 0L
            }
            if (renderer != 0L) {
                NuxieRuntimeBridge.nativeRendererFree(renderer)
                renderer = 0L
            }
        }
    }

    private fun reportFailure(
        reason: ExperiencePresentationException.Reason,
        message: String,
        cause: Throwable? = null,
    ) {
        listener?.onFailure(ExperiencePresentationException(reason, message, cause))
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val CLEAR_COLOR_OPAQUE_BLACK = 0xFF000000.toInt()

        /**
         * Bound on the surfaceDestroyed drain. A frame takes milliseconds;
         * this only trips if the runtime lane is wedged, and then leaking
         * one frame to a dead surface beats deadlocking the main thread.
         */
        const val SURFACE_DETACH_TIMEOUT_MS = 1_000L
    }
}
