package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

@RunWith(RobolectricTestRunner::class)
class ExperienceSurfaceHostPointerTest {
    @Test
    fun `resume cancels a delivered press before the next gesture reaches the retained player`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane,
            artboardSize = ExperienceArtboardSize(400f, 200f), runtime = NuxieRuntime(native))
        host.layout(0, 0, 1_000, 1_000)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.surfaceCreated(holder(surface))
            val down = motion(MotionEvent.ACTION_DOWN, 1_000, 500f, 500f)
            try { assertTrue(host.onTouchEvent(down)) } finally { down.recycle() }
            host.doFrame(1_000_000_000L)
            drain(lane)
            assertEquals(listOf(NativePlayerPointer(0, 200f, 100f, 0, 1f)), native.pointerSteps.single())
            host.setPresentationVisible(false)
            host.setPresentationVisible(true)
            host.setPresentationVisible(false)
            host.setPresentationVisible(true)
            val next = motion(MotionEvent.ACTION_DOWN, 100_000, 750f, 500f)
            try { assertTrue(host.onTouchEvent(next)) } finally { next.recycle() }
            host.doFrame(100_000_000_000L)
            drain(lane)
            assertEquals(listOf(
                NativePlayerPointer(3, 200f, 100f, 0, 1f),
                NativePlayerPointer(0, 300f, 100f, 0, 100f),
            ), native.pointerSteps.last())
            assertEquals(listOf(0f, 0f), native.elapsedSteps)
            assertEquals(1, native.playersCreated)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `shared visibility contract pauses time without replacing the player`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-visibility-android.json").readText()).jsonObject
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane,
            artboardSize = ExperienceArtboardSize(400f, 200f), runtime = NuxieRuntime(native))
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        try {
            host.setPresentationVisible(fixture.getValue("initialVisible").jsonPrimitive.boolean)
            host.loadArtboard(byteArrayOf(1), null)
            for (value in fixture.getValue("steps").jsonArray) {
                val step = value.jsonObject
                when (step.getValue("action").jsonPrimitive.content) {
                    "attach" -> host.surfaceCreated(holder(surface))
                    "detach" -> host.surfaceDestroyed(holder(surface))
                    "visible" -> host.setPresentationVisible(step.getValue("value").jsonPrimitive.boolean)
                    "release" -> host.release()
                    "frame" -> {
                        val before = native.elapsedSteps.size
                        host.doFrame(step.getValue("nanos").jsonPrimitive.long)
                        drain(lane)
                        val expected = step.getValue("elapsed")
                        if (expected == JsonNull) assertEquals(step.toString(), before, native.elapsedSteps.size)
                        else {
                            assertEquals(step.toString(), before + 1, native.elapsedSteps.size)
                            assertEquals(expected.jsonPrimitive.float, native.elapsedSteps.last(), 0.000001f)
                        }
                    }
                    else -> error("Unknown visibility fixture action")
                }
                drain(lane)
            }
            assertEquals("Visibility changes retain the same player", 1, native.playersCreated)
            assertEquals("Only actual surface reattachment acquires another window", 2, native.windowsAcquired)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `queued frame and pointer cannot cross a hidden interval even after rapid resume`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane,
            artboardSize = ExperienceArtboardSize(400f, 200f), runtime = NuxieRuntime(native))
        host.layout(0, 0, 1_000, 1_000)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.surfaceCreated(holder(surface))
            host.doFrame(1_000_000_000L)
            drain(lane)
            val staged = motion(MotionEvent.ACTION_DOWN, 1_001, 500f, 500f)
            try { assertTrue(host.onTouchEvent(staged)) } finally { staged.recycle() }
            drain(lane)
            lane.enqueue { blocked.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            host.doFrame(1_016_000_000L)
            val down = motion(MotionEvent.ACTION_DOWN, 1_016, 500f, 500f)
            try { assertTrue(host.onTouchEvent(down)) } finally { down.recycle() }
            host.setPresentationVisible(false)
            host.doFrame(50_000_000_000L)
            host.setPresentationVisible(true)
            resume.countDown()
            drain(lane)
            assertEquals(listOf(0f), native.elapsedSteps)
            host.doFrame(100_000_000_000L)
            host.doFrame(100_010_000_000L)
            drain(lane)
            assertEquals(listOf(0f, 0f, 0.01f), native.elapsedSteps)
            assertTrue("Pre-hide input must not reach the resumed player", native.pointerSteps.all { it.isEmpty() })
        } finally {
            resume.countDown()
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `queued older frame cannot consume a tap ahead of its focus-loss text commit`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane,
            artboardSize = ExperienceArtboardSize(400f, 200f), runtime = NuxieRuntime(native))
        host.layout(0, 0, 1_000, 1_000)
        val surfaceTexture = SurfaceTexture(0)
        val surface = Surface(surfaceTexture)
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            host.loadArtboard(byteArrayOf(1), null,
                textInputs = ExperienceTextInput.forScreen(textInputDescriptor(), "survey"))
            host.surfaceCreated(holder(surface))
            drain(lane)
            lane.enqueue { blocked.countDown(); check(resume.await(2, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            host.doFrame(1_000_000_000L)
            host.writeText("name", "okay", true) {}
            val tap = motion(MotionEvent.ACTION_DOWN, 1_000, 500f, 500f)
            try { assertTrue(host.onTouchEvent(tap)) } finally { tap.recycle() }
            resume.countDown()
            drain(lane)
            host.doFrame(1_016_000_000L)
            drain(lane)
            assertEquals(listOf("frame:0", "write:headline:ok", "frame:1"), native.order)
        } finally {
            resume.countDown()
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            surface.release()
            surfaceTexture.release()
        }
    }

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
        val order = mutableListOf<String>()
        val elapsedSteps = mutableListOf<Float>()
        var playersCreated = 0
        var windowsAcquired = 0

        override fun setTextRun(handle: Long, name: String, text: String): NativeCallResult<Boolean> {
            order += "write:$name:$text"
            return NativeCallResult(0, true)
        }

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
        override fun newDefaultPlayer(artboardHandle: Long): Long = 3L.also { playersCreated += 1 }
        override fun freePlayer(handle: Long) = Unit
        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long = 4L
        override fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int): Int = 0
        override fun acquireWindow(surface: android.view.Surface): Long = 5L.also { windowsAcquired += 1 }
        override fun releaseWindow(handle: Long) = Unit

        override fun stepPlayer(
            playerHandle: Long,
            inputs: List<NativePlayerInput>,
            pointers: List<NativePlayerPointer>,
            elapsedSeconds: Float,
            correlationId: Long,
        ): NativeCallResult<NativePlayerStepOutcome> {
            pointerSteps += pointers
            elapsedSteps += elapsedSeconds
            order += "frame:${pointers.size}"
            return NativeCallResult(
                0,
                NativePlayerStepOutcome(
                    keepGoing = true,
                    pointerHits = intArrayOf(),
                    events = emptyArray(),
                    hostCommands = emptyArray(),
                    viewModelChanges = emptyArray(),
                ),
            )
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
