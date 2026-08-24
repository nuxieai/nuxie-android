package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeBridge
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * SurfaceView host driving the engine's Android WebGPU renderer: Kotlin owns
 * the frame clock (Choreographer) and issues one render per frame per the
 * iOS frame-ownership contract; the engine has no independent loop. Session
 * and surface lifetimes are separate: surface destroy/recreate preserves the
 * player and re-wraps the window.
 *
 * Tracer scope: continuous rendering while the surface is visible. The
 * needsFrame/wake/idle settle contract refines with the presentation
 * service.
 */
internal class ExperienceSurfaceHost(
    context: Context,
    private val lane: NuxieRuntimeLane,
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    /** Native handles; touched only on the runtime lane. */
    private var renderer = 0L
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
        onLoaded: ((Boolean) -> Unit)? = null,
    ) {
        lane.enqueue {
            file = NuxieRuntimeBridge.nativeFileNew(rivBytes)
            if (file == 0L) {
                Log.w(LOG_TAG, "Runtime rejected the riv bytes")
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
                onLoaded?.invoke(false)
                return@enqueue
            }
            player = NuxieRuntimeBridge.nativePlayerNewDefault(artboard)
            if (player == 0L) {
                Log.w(LOG_TAG, "Player creation failed")
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
            attached = true
            if (renderer == 0L) {
                renderer = NuxieRuntimeBridge.nativeRendererNewAndroidWgpu(
                    surface, width, height,
                )
                if (renderer == 0L) {
                    Log.w(LOG_TAG, "Android WebGPU renderer creation failed")
                    return@enqueue
                }
            } else {
                NuxieRuntimeBridge.nativeRendererRecreateSurface(renderer, surface)
            }
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
        // The marker gates queued render/resize jobs off the dead surface.
        lane.enqueue { attached = false }
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
                renderer, player, CLEAR_COLOR_OPAQUE_BLACK, true,
            )
            if (disposition < 0) {
                Log.w(LOG_TAG, "render_player failed with status ${-disposition}")
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Release every native handle. The host is not reusable afterwards. */
    fun release() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        lane.enqueue {
            if (player != 0L) {
                NuxieRuntimeBridge.nativeRendererResetPlayerDomain(player)
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

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val CLEAR_COLOR_OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
