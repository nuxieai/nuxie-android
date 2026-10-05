package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.graphics.SurfaceTexture
import android.os.Looper
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ExperienceSurfaceLayoutTest {
    @Test fun `first frame and resized frame set points then settle read and render at view density`() {
        withHost(3f) { host, texture, lane, native, bounds ->
            host.onSurfaceTextureAvailable(texture, 1179, 2556)
            drain(lane)
            host.doFrame(1_000_000_000L)
            drain(lane)
            assertEquals(listOf("size:393.0:852.0", "step:0.0", "read", "render:3.0"), native.calls)
            drainUi(host)
            assertEquals(ExperienceArtboardSize(393f, 852f), bounds.last())
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            native.calls.clear()
            native.presentation = 4
            host.doFrame(1_016_000_000L)
            drain(lane)
            assertEquals(listOf("step:0.016", "render:3.0"), native.calls)
            native.calls.clear()
            host.onSurfaceTextureSizeChanged(texture, 1125, 2001)
            host.doFrame(1_032_000_000L)
            drain(lane)
            assertEquals(listOf("size:375.0:667.0", "step:0.0", "read", "render:3.0"), native.calls)
            drainUi(host)
            assertEquals(ExperienceArtboardSize(375f, 667f), bounds.last())
        }
    }

    @Test fun `fractional density and configuration-only changes use current view resources`() {
        withHost(2.625f) { host, texture, lane, native, bounds ->
            host.onSurfaceTextureAvailable(texture, 1050, 2100)
            host.doFrame(1_000_000_000L)
            drain(lane)
            assertEquals(listOf("size:400.0:800.0", "step:0.0", "read", "render:2.625"), native.calls)
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            native.calls.clear()
            host.resources.displayMetrics.density = 3f
            host.dispatchConfigurationChanged(Configuration(host.resources.configuration))
            host.doFrame(1_016_000_000L)
            drain(lane)
            assertEquals(listOf("size:350.0:700.0", "step:0.0", "read", "render:3.0"), native.calls)
            drainUi(host)
            assertEquals(ExperienceArtboardSize(350f, 700f), bounds.last())
        }
    }

    @Test fun `zero extent or invalid density does not size step or draw and valid resize resumes`() {
        withHost(3f) { host, texture, lane, native, _ ->
            host.onSurfaceTextureAvailable(texture, 0, 0)
            host.doFrame(1_000_000_000L)
            drain(lane)
            assertTrue(native.calls.isEmpty())
            host.onSurfaceTextureSizeChanged(texture, 1179, 2556)
            host.doFrame(1_016_000_000L)
            drain(lane)
            assertEquals(listOf("size:393.0:852.0", "step:0.0", "read", "render:3.0"), native.calls)
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE)) {
                native.calls.clear()
                host.resources.displayMetrics.density = density
                host.onSurfaceTextureSizeChanged(texture, 1179, 2556)
                host.doFrame(1_032_000_000L)
                drain(lane)
                assertTrue("density $density", native.calls.isEmpty())
            }
        }
        assertNull(ExperienceSurfaceLayout.create(Int.MAX_VALUE, 1, Float.MIN_VALUE))
        assertNotNull(ExperienceSurfaceLayout.create(Int.MAX_VALUE, Int.MAX_VALUE, 1f))
    }

    @Test fun `failed size or read stops the frame without drawing stale geometry`() {
        for (failRead in listOf(false, true)) {
            withHost(3f, expectFailure = true) { host, texture, lane, native, _ ->
                native.failRead = failRead
                native.failSize = !failRead
                host.onSurfaceTextureAvailable(texture, 1179, 2556)
                host.doFrame(1_000_000_000L)
                drain(lane)
                assertFalse(native.calls.any { it.startsWith("render:") })
                assertEquals(if (failRead) listOf("size:393.0:852.0", "step:0.0", "read")
                    else listOf("size:393.0:852.0"), native.calls)
            }
        }
    }

    private fun withHost(density: Float, expectFailure: Boolean = false,
        body: (ExperienceSurfaceHost, SurfaceTexture, NuxieRuntimeLane, RecordingNative, MutableList<ExperienceArtboardSize>) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val previousDensity = context.resources.displayMetrics.density
        context.resources.displayMetrics.density = density
        val lane = NuxieRuntimeLane()
        val native = RecordingNative()
        val bounds = mutableListOf<ExperienceArtboardSize>()
        val failures = mutableListOf<ExperiencePresentationException>()
        val host = ExperienceSurfaceHost(context, lane, artboardSize = ExperienceArtboardSize(100f, 200f),
            runtime = NuxieRuntime(native), listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onLayoutBounds(value: ExperienceArtboardSize) { bounds += value }
                override fun onFailure(error: ExperiencePresentationException) { failures += error }
            })
        val texture = SurfaceTexture(0)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            drain(lane)
            body(host, texture, lane, native, bounds)
            drainUi(host)
            assertEquals(if (expectFailure) 1 else 0, failures.size)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
            context.resources.displayMetrics.density = previousDensity
        }
    }

    private fun drainUi(host: ExperienceSurfaceHost) {
        android.view.Choreographer.getInstance().removeFrameCallback(host)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun drain(lane: NuxieRuntimeLane) {
        val done = CountDownLatch(1)
        assertTrue(lane.enqueue { done.countDown() })
        assertTrue(done.await(2, TimeUnit.SECONDS))
    }

    private class RecordingNative : NuxieTypedRuntimeNative {
        val calls = mutableListOf<String>()
        var size = floatArrayOf(100f, 200f)
        var presentation = 1
        var failSize = false
        var failRead = false
        override fun newFile(rendererHandle: Long, bytes: ByteArray, expectedAssets: List<ExpectedFileAsset>,
            externalAssets: Map<Int, ByteArray>, imageDecoder: NuxImageDecoder, videoEnabled: Boolean) = 1L
        override fun freeFile(handle: Long) = Unit
        override fun newDefaultArtboard(fileHandle: Long) = 2L
        override fun freeArtboard(handle: Long) = Unit
        override fun stateMachineNames(fileHandle: Long, artboardName: String?) = NativeCallResult(0, emptyList<String>())
        override fun newDefaultPlayer(artboardHandle: Long) = 3L
        override fun freePlayer(handle: Long) = Unit
        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int) = 4L
        override fun freeRenderer(handle: Long) = Unit
        override fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int) = 0
        override fun attachRendererSurface(rendererHandle: Long, windowHandle: Long) = 0
        override fun detachRendererSurface(rendererHandle: Long) = 0
        override fun acquireWindow(surface: android.view.Surface) = 5L
        override fun releaseWindow(handle: Long) = Unit
        override fun setPlayerLayoutSize(playerHandle: Long, width: Float, height: Float): Int {
            calls += "size:$width:$height"
            size = floatArrayOf(width, height)
            return if (failSize) 4 else 0
        }
        override fun playerLayoutSize(playerHandle: Long): NativeCallResult<FloatArray> {
            calls += "read"
            return if (failRead) NativeCallResult(4, null) else NativeCallResult(0, size)
        }
        override fun stepPlayer(playerHandle: Long, inputs: List<NativePlayerInput>, pointers: List<NativePlayerPointer>,
            elapsedSeconds: Float, correlationId: Long, textRunNames: List<String>): NativeCallResult<NativePlayerStepOutcome> {
            calls += "step:$elapsedSeconds"
            return NativeCallResult(0, NativePlayerStepOutcome(true, intArrayOf(), emptyArray(), emptyArray(), emptyArray()))
        }
        override fun renderAndPresent(rendererHandle: Long, playerHandle: Long, windowHandle: Long,
            clearColor: Int, layoutScaleFactor: Float): Int {
            calls += "render:$layoutScaleFactor"
            return presentation
        }
    }
}
