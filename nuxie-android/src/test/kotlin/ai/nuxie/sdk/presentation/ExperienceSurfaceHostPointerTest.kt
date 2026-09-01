package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeCallResult
import ai.nuxie.sdk.runtime.NativePlayerInput
import ai.nuxie.sdk.runtime.NativePlayerPointer
import ai.nuxie.sdk.runtime.NativePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieCpuFrame
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExperienceSurfaceHostPointerTest {
    @Test
    fun `surface tap reaches the configured player and release closes the input seam`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(
            context = RuntimeEnvironment.getApplication(),
            lane = lane,
            artboardSize = ExperienceArtboardSize(400f, 200f),
            runtime = NuxieRuntime(native),
        )
        host.layout(0, 0, 1_000, 1_000)
        val loaded = CountDownLatch(1)
        val surfaceTexture = SurfaceTexture(0)
        val surface = Surface(surfaceTexture)

        try {
            host.loadArtboard(byteArrayOf(1), artboardName = null) { succeeded ->
                assertTrue(succeeded)
                loaded.countDown()
            }
            assertTrue("runtime did not load", loaded.await(2, TimeUnit.SECONDS))
            host.surfaceCreated(holder(surface))
            drain(lane)

            val down = motion(MotionEvent.ACTION_DOWN, 1_000, 500f, 500f)
            val up = motion(MotionEvent.ACTION_UP, 1_100, 500f, 500f)
            try {
                assertTrue(host.onTouchEvent(down))
                assertTrue(host.onTouchEvent(up))
            } finally {
                down.recycle()
                up.recycle()
            }
            host.doFrame(1_000_000_000L)
            drain(lane)

            assertEquals(
                listOf(
                    NativePlayerPointer(0, 200f, 100f, 0, 1f),
                    NativePlayerPointer(2, 200f, 100f, 0, 1.1f),
                ),
                native.pointerSteps.first { it.isNotEmpty() },
            )

            host.release()
            val afterRelease = motion(MotionEvent.ACTION_DOWN, 1_200, 500f, 500f)
            try {
                assertFalse(host.onTouchEvent(afterRelease))
            } finally {
                afterRelease.recycle()
            }
        } finally {
            host.release()
            lane.shutdown()
            assertTrue("runtime lane did not stop", lane.awaitQuiescence(2_000))
            surface.release()
            surfaceTexture.release()
        }
    }

    private fun drain(lane: NuxieRuntimeLane) {
        val drained = CountDownLatch(1)
        assertTrue(lane.enqueue { drained.countDown() })
        assertTrue("runtime lane did not drain", drained.await(2, TimeUnit.SECONDS))
    }

    private fun motion(action: Int, eventTime: Long, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0, eventTime, action, x, y, 0)

    private fun holder(surface: Surface): SurfaceHolder = Proxy.newProxyInstance(
        SurfaceHolder::class.java.classLoader,
        arrayOf(SurfaceHolder::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSurface" -> surface
            "getSurfaceFrame" -> Rect(0, 0, 1_000, 1_000)
            else -> error("Unexpected SurfaceHolder call ${method.name}")
        }
    } as SurfaceHolder

    private class RecordingNative : NuxieTypedRuntimeNative {
        val pointerSteps = mutableListOf<List<NativePlayerPointer>>()

        override fun newFile(
            rendererHandle: Long,
            bytes: ByteArray,
            expectedAssets: List<ai.nuxie.sdk.runtime.ExpectedFileAsset>,
            externalAssets: Map<Int, ByteArray>,
            imageDecoder: ai.nuxie.sdk.runtime.NuxImageDecoder,
        ): Long = 1L

        override fun freeFile(handle: Long) = Unit
        override fun newDefaultArtboard(fileHandle: Long): Long = 2L
        override fun freeArtboard(handle: Long) = Unit
        override fun newDefaultPlayer(artboardHandle: Long): Long = 3L
        override fun freePlayer(handle: Long) = Unit
        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long = 4L
        override fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int): Int = 0
        override fun acquireWindow(surface: android.view.Surface): Long = 5L
        override fun releaseWindow(handle: Long) = Unit

        override fun stepPlayer(
            playerHandle: Long,
            inputs: List<NativePlayerInput>,
            pointers: List<NativePlayerPointer>,
            elapsedSeconds: Float,
            correlationId: Long,
        ): NativeCallResult<NativePlayerStepOutcome> {
            pointerSteps += pointers
            return NativeCallResult(0, NativePlayerStepOutcome(true, emptyArray(), emptyArray()))
        }

        override fun renderAndPresent(
            rendererHandle: Long,
            playerHandle: Long,
            windowHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ): Int = 1

        override fun renderToCpuFrame(
            rendererHandle: Long,
            playerHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ): NuxieCpuFrame = error("not used")

        override fun freeRenderer(handle: Long) = Unit
    }
}
