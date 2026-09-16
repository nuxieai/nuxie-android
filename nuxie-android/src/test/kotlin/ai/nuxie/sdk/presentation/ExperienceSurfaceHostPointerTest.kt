package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NativeViewModelCatalog
import ai.nuxie.sdk.runtime.NativeViewModelSchema
import ai.nuxie.sdk.runtime.NativeViewModelProperty
import ai.nuxie.sdk.runtime.NativeViewModelWrite
import ai.nuxie.sdk.runtime.NuxieViewModelListProjection
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import ai.nuxie.sdk.runtime.NativeCallResult
import ai.nuxie.sdk.runtime.NativePlayerInput
import ai.nuxie.sdk.runtime.NativePlayerPointer
import ai.nuxie.sdk.runtime.NativePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieCpuFrame
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `pending surface submission polls without stepping or publishing effects`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val published = mutableListOf<ULong>()
        var composed = 0
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane,
            runtime = NuxieRuntime(native), listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() { composed++ }
                override fun onFailure(error: ExperiencePresentationException) { throw error }
                override fun onRuntimeStep(
                    outcome: ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome,
                    correlationId: ULong,
                    viewModelSnapshot: ai.nuxie.sdk.runtime.NuxieViewModelSnapshot?,
                ) { published += correlationId }
            })
        val texture = SurfaceTexture(0)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            native.events = arrayOf(ai.nuxie.sdk.runtime.NativeRuntimeEvent(
                0, 0, "nx_exit_done:test", "", "", 0f, emptyArray()))
            native.presentation = 4
            host.doFrame(1_000_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            assertEquals("Submission is not completion", 0, composed)
            repeat(2) { index ->
                host.doFrame(1_016_000_000L + index * 16_000_000L)
                drain(lane)
            }
            assertEquals("Polls must retain the submitted player state", listOf(0f), native.elapsedSteps)
            assertTrue("Pending effects must remain unpublished", published.isEmpty())
            native.presentation = 1
            host.doFrame(1_048_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            assertEquals(1, composed)
            assertEquals(listOf(1uL), published)
            host.doFrame(1_064_000_000L)
            drain(lane)
            assertEquals("Busy time must reach the next actual step", listOf(0f, 0.064f), native.elapsedSteps)
            assertEquals("Completed effects publish once", listOf(1uL), published)
            native.presentation = 4
            native.events = arrayOf(ai.nuxie.sdk.runtime.NativeRuntimeEvent(
                0, 0, "nx_exit_done:resized", "", "", 0f, emptyArray()))
            host.doFrame(1_080_000_000L)
            drain(lane)
            val resizeQueued = CountDownLatch(1)
            val allowResize = CountDownLatch(1)
            lane.enqueue { resizeQueued.countDown(); allowResize.await(2, TimeUnit.SECONDS) }
            assertTrue(resizeQueued.await(2, TimeUnit.SECONDS))
            try {
                host.onSurfaceTextureSizeChanged(texture, 200, 100)
                native.presentation = 1
                host.doFrame(1_096_000_000L)
            } finally {
                allowResize.countDown()
            }
            drain(lane)
            assertEquals("Resize retires the pending frame and requires a fresh step", 4, native.elapsedSteps.size)
            assertEquals("Retired frame effects await the replacement delivery", listOf(1uL, 3uL), published)
            host.doFrame(1_112_000_000L)
            drain(lane)
            assertEquals("A queued resize must not count the same elapsed time twice", 0.016f, native.elapsedSteps.last(), 0.000001f)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
        }
    }

    @Test
    fun `transition events wait for a delivered frame after surface unavailability`() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val events = mutableListOf<String>()
        val host = ExperienceSurfaceHost(activity.get(), lane, runtime = NuxieRuntime(native),
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { throw error }
                override fun onRuntimeEvent(event: ai.nuxie.sdk.runtime.NuxieRuntimeEvent,
                    viewModelSnapshot: ai.nuxie.sdk.runtime.NuxieViewModelSnapshot?) {
                    events += event.name
                }
            })
        activity.get().setContentView(host)
        val texture = SurfaceTexture(0)
        fun drainUi() {
            host.setPresentationVisible(false)
            drain(lane)
            android.view.Choreographer.getInstance().removeFrameCallback(host)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            host.setPresentationVisible(true)
        }
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            host.doFrame(1_000_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            native.events = arrayOf(ai.nuxie.sdk.runtime.NativeRuntimeEvent(
                0, 0, "nx_exit_done:test", "", "", 0f, emptyArray()))
            native.presentation = 0
            host.doFrame(1_016_000_000L)
            drain(lane)
            drainUi()
            assertTrue("An undelivered frame cannot complete its transition", events.isEmpty())
            native.presentation = 1
            host.doFrame(1_032_000_000L)
            drain(lane)
            drainUi()
            assertEquals(listOf("nx_exit_done:test"), events)
            host.doFrame(1_048_000_000L)
            drain(lane)
            drainUi()
            assertEquals(listOf("nx_exit_done:test"), events)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `first composed frame hands off before another native frame can start`() {
        assertFirstFrameHandoff(false)
    }

    @Test
    fun `composition before native return still hands off without another native frame`() {
        assertFirstFrameHandoff(true)
    }

    private fun assertFirstFrameHandoff(earlyComposition: Boolean) {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        lateinit var host: ExperienceSurfaceHost
        var firstFrames = 0
        host = ExperienceSurfaceHost(
            RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native),
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() {
                    firstFrames++
                    host.setPresentationVisible(false)
                }
                override fun onFailure(error: ExperiencePresentationException) { throw error }
            },
        )
        val texture = SurfaceTexture(0)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            if (earlyComposition) native.onRender = { host.onSurfaceTextureUpdated(texture) }
            host.doFrame(1_000_000_000L)
            drain(lane)
            native.onRender = {}
            repeat(3) { host.doFrame(1_016_000_000L + it * 16_000_000L) }
            drain(lane)
            assertEquals("The prepared frame must await composition", 1, native.elapsedSteps.size)
            host.onSurfaceTextureUpdated(texture)
            host.doFrame(1_064_000_000L)
            drain(lane)
            assertEquals(1, firstFrames)
            assertEquals("First-frame pause must prevent queued work", 1, native.elapsedSteps.size)
            host.setPresentationVisible(true)
            host.doFrame(1_080_000_000L)
            drain(lane)
            assertEquals(listOf(0f, 0f), native.elapsedSteps)
            host.onSurfaceTextureUpdated(texture)
            assertEquals(1, firstFrames)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
        }
    }

    @Test
    fun `busy renderer coalesces display ticks so input precedes the next frame`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-visibility-android.json").readText()).jsonObject
            .getValue("frameBackpressure").jsonObject
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native))
        val texture = SurfaceTexture(0)
        val rendering = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            host.loadArtboard(byteArrayOf(1), null,
                textInputs = ExperienceTextInput.forScreen(textInputDescriptor(), "survey"))
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            native.onRender = { rendering.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            val first = fixture.getValue("firstFrameNanos").jsonPrimitive.long
            host.doFrame(first)
            assertTrue(rendering.await(2, TimeUnit.SECONDS))
            repeat(fixture.getValue("busyFrameCount").jsonPrimitive.content.toInt()) {
                host.doFrame(first + (it + 1) * fixture.getValue("busyTickNanos").jsonPrimitive.long)
            }
            host.writeText("name", "okay", true) {}
            resume.countDown()
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
            host.doFrame(fixture.getValue("nextFrameNanos").jsonPrimitive.long)
            drain(lane)
            assertEquals(fixture.getValue("expectedOrder").jsonArray.map { it.jsonPrimitive.content }, native.order)
            assertEquals(fixture.getValue("expectedElapsed").jsonArray.map { it.jsonPrimitive.float }, native.elapsedSteps)
        } finally {
            resume.countDown()
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
        }
    }

    @Test
    fun `failed surface resize reports failure and stops native frame submission`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val failures = mutableListOf<ExperiencePresentationException>()
        val host = ExperienceSurfaceHost(
            RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native),
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) { failures += error }
            },
        )
        val texture = SurfaceTexture(0)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            host.doFrame(1_000_000_000L)
            drain(lane)
            val submitted = native.elapsedSteps.size
            assertTrue(submitted > 0)
            native.resizeStatus = 5
            host.onSurfaceTextureSizeChanged(texture, 200, 100)
            drain(lane)
            host.doFrame(1_100_000_000L)
            drain(lane)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, failures.size)
            assertTrue(failures.single().message.orEmpty().contains("resize failed"))
            assertEquals(submitted, native.elapsedSteps.size)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
        }
    }

    @Test
    fun `unavailable swapchain frame does not activate the screen and later presentation recovers`() {
        assertUndeliveredFrameRecovers(0)
    }

    @Test
    fun `retired surface does not activate the screen and reattachment recovers`() {
        assertUndeliveredFrameRecovers(3)
    }

    private fun assertUndeliveredFrameRecovers(disposition: Int) {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        var firstFrames = 0
        val failures = mutableListOf<ExperiencePresentationException>()
        val host = ExperienceSurfaceHost(
            RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native),
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() { firstFrames++ }
                override fun onFailure(error: ExperiencePresentationException) { failures += error }
            },
        )
        val texture = SurfaceTexture(0)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            native.presentation = disposition
            host.doFrame(1_000_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
            assertEquals(0, firstFrames)
            assertTrue(failures.isEmpty())
            native.presentation = 1
            host.doFrame(1_016_000_000L)
            drain(lane)
            assertEquals("Submission alone is not composition", 0, firstFrames)
            host.onSurfaceTextureUpdated(texture)
            assertEquals(1, firstFrames)
            host.doFrame(1_032_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
            assertEquals(1, firstFrames)
            assertTrue(failures.isEmpty())
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
        }
    }

    @Test
    fun `environment state queues before loading and updates the retained commerce root`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native))
        try {
            host.updateRuntimeValues(linkedMapOf(
                "missing" to NuxieViewModelScalarValue.NumberValue(1.0),
                "safeArea/top" to NuxieViewModelScalarValue.NumberValue(24.0),
            ))
            host.loadArtboard(byteArrayOf(1), null, viewModelProjection =
                NuxieViewModelListProjection("Root", "products", null, "Product", emptyList()))
            drain(lane)
            assertEquals(listOf(24f), native.stateAtPlayerCreation)
            host.updateRuntimeValues(ExperienceSafeAreaInsets(12.0, 0.0, 0.0, 0.0).stateValues())
            drain(lane)
            assertEquals(listOf(24f, 12f), native.stateWrites)
            assertEquals(1, native.playersCreated)
            host.release(ExperienceSafeAreaInsets.ZERO.stateValues())
            host.updateRuntimeValues(ExperienceSafeAreaInsets.ZERO.stateValues())
            drain(lane)
            assertEquals(listOf(24f, 12f, 0f), native.stateWrites)
            assertTrue(native.boundStateFreed)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
        }
    }

    @Test
    fun `environment updates wait for pending presentation and apply before the next step`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane, runtime = NuxieRuntime(native))
        val texture = SurfaceTexture(0)
        try {
            host.updateRuntimeValues(mapOf("safeArea/top" to NuxieViewModelScalarValue.NumberValue(12.0)))
            host.loadArtboard(byteArrayOf(1), null, viewModelProjection =
                NuxieViewModelListProjection("Root", "products", null, "Product", emptyList()))
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            native.presentation = 4
            host.doFrame(1_000_000_000L)
            drain(lane)
            host.updateRuntimeValues(mapOf("safeArea/top" to NuxieViewModelScalarValue.NumberValue(18.0)))
            host.updateRuntimeValues(mapOf("safeArea/top" to NuxieViewModelScalarValue.NumberValue(30.0)))
            drain(lane)
            assertEquals("Pending pixels retain their original model revision", listOf(12f), native.stateWrites)
            native.presentation = 1
            host.doFrame(1_016_000_000L)
            drain(lane)
            assertEquals(listOf(12f), native.stateWrites)
            host.onSurfaceTextureUpdated(texture)
            host.doFrame(1_032_000_000L)
            drain(lane)
            assertEquals(listOf(12f, 30f), native.stateWrites)
            assertEquals(listOf(12f, 30f), native.stateAtSteps)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
        }
    }

    @Test
    fun `resume cancels a delivered press before the next gesture reaches the retained player`() {
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(RuntimeEnvironment.getApplication(), lane,
            artboardSize = ExperienceArtboardSize(400f, 200f), runtime = NuxieRuntime(native))
        host.layout(0, 0, 1_000, 1_000)
        val texture = SurfaceTexture(0)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.onSurfaceTextureAvailable(texture, 1_000, 1_000)
            val down = motion(MotionEvent.ACTION_DOWN, 1_000, 500f, 500f)
            try { assertTrue(host.onTouchEvent(down)) } finally { down.recycle() }
            host.doFrame(1_000_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
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
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            assertEquals(listOf(
                NativePlayerPointer(3, 200f, 100f, 0, 1f),
                NativePlayerPointer(0, 300f, 100f, 0, 100f),
            ), native.pointerSteps.last())
            assertEquals(listOf(0f, 0f), native.elapsedSteps)
            assertEquals(1, native.playersCreated)
        } finally {
            host.onSurfaceTextureDestroyed(texture)
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
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
        var texture = SurfaceTexture(0)
        try {
            host.setPresentationVisible(fixture.getValue("initialVisible").jsonPrimitive.boolean)
            host.loadArtboard(byteArrayOf(1), null)
            for (value in fixture.getValue("steps").jsonArray) {
                val step = value.jsonObject
                when (step.getValue("action").jsonPrimitive.content) {
                    "attach" -> {
                        texture.release()
                        texture = SurfaceTexture(0)
                        host.onSurfaceTextureAvailable(texture, 1_000, 1_000)
                    }
                    "detach" -> host.onSurfaceTextureDestroyed(texture)
                    "visible" -> host.setPresentationVisible(step.getValue("value").jsonPrimitive.boolean)
                    "release" -> host.release()
                    "frame" -> {
                        val before = native.elapsedSteps.size
                        host.doFrame(step.getValue("nanos").jsonPrimitive.long)
                        drain(lane)
                        host.onSurfaceTextureUpdated(texture)
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
                host.onSurfaceTextureUpdated(texture)
                drain(lane)
            }
            assertEquals("Visibility changes retain the same player", 1, native.playersCreated)
            assertEquals("Only actual surface reattachment acquires another window", 2, native.windowsAcquired)
        } finally {
            host.onSurfaceTextureDestroyed(texture)
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
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
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            host.loadArtboard(byteArrayOf(1), null)
            host.onSurfaceTextureAvailable(texture, 1_000, 1_000)
            host.doFrame(1_000_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            val staged = motion(MotionEvent.ACTION_DOWN, 1_001, 500f, 500f)
            try { assertTrue(host.onTouchEvent(staged)) } finally { staged.recycle() }
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
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
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            assertEquals(listOf(0f), native.elapsedSteps)
            host.doFrame(100_000_000_000L)
            drain(lane)
            host.doFrame(100_010_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            assertEquals(listOf(0f, 0f, 0.01f), native.elapsedSteps)
            assertTrue("Pre-hide input must not reach the resumed player", native.pointerSteps.all { it.isEmpty() })
        } finally {
            resume.countDown()
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
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
        val blocked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            host.loadArtboard(byteArrayOf(1), null,
                textInputs = ExperienceTextInput.forScreen(textInputDescriptor(), "survey"))
            host.onSurfaceTextureAvailable(surfaceTexture, 1_000, 1_000)
            drain(lane)
            host.onSurfaceTextureUpdated(surfaceTexture)
            drain(lane)
            lane.enqueue { blocked.countDown(); check(resume.await(2, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            host.doFrame(1_000_000_000L)
            host.writeText("name", "okay", true) {}
            val tap = motion(MotionEvent.ACTION_DOWN, 1_000, 500f, 500f)
            try { assertTrue(host.onTouchEvent(tap)) } finally { tap.recycle() }
            resume.countDown()
            drain(lane)
            host.onSurfaceTextureUpdated(surfaceTexture)
            drain(lane)
            host.doFrame(1_016_000_000L)
            drain(lane)
            host.onSurfaceTextureUpdated(surfaceTexture)
            drain(lane)
            assertEquals(listOf("frame:0", "write:headline:ok", "frame:1"), native.order)
        } finally {
            resume.countDown()
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
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

        try {
            host.loadArtboard(byteArrayOf(1), artboardName = null) { succeeded ->
                assertTrue(succeeded)
                loaded.countDown()
            }
            assertTrue("runtime did not load", loaded.await(2, TimeUnit.SECONDS))
            host.onSurfaceTextureAvailable(surfaceTexture, 1_000, 1_000)
            drain(lane)
            host.onSurfaceTextureUpdated(surfaceTexture)
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
            host.onSurfaceTextureUpdated(surfaceTexture)
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
            host.onSurfaceTextureDestroyed(surfaceTexture)
            host.release()
            lane.shutdown()
            assertTrue("runtime lane did not stop", lane.awaitQuiescence(2_000))
            surfaceTexture.release()
        }
    }

    @Test fun `semantic capture failure reaches recovery on the main thread`() {
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val native = RecordingNative().apply { semanticCaptureStatus = 9; presentation = 1 }
        val lane = NuxieRuntimeLane()
        val failures = mutableListOf<ExperiencePresentationException>()
        var callbackOnMain: Boolean? = null
        lateinit var host: ExperienceSurfaceHost
        host = ExperienceSurfaceHost(controller.get(), lane, runtime = NuxieRuntime(native),
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() = Unit
                override fun onFailure(error: ExperiencePresentationException) {
                    callbackOnMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                    host.setPresentationVisible(false)
                    failures += error
                }
            })
        controller.get().setContentView(host)
        host.layout(0, 0, 100, 100)
        val texture = SurfaceTexture(0)
        val descriptor = Json.parseToJsonElement("""{
            "requirements":{"requiredCapabilities":["scene-semantics-v1"]},
            "render":{"assets":[],"screens":[{"id":"screen","artboardName":"Main"}]},
            "leg":{"screens":[{"id":"screen"}]}
        }""").jsonObject
        try {
            host.loadArtboard(byteArrayOf(1), null, descriptor)
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            host.doFrame(1_000_000_000L)
            drain(lane)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(true, callbackOnMain)
            assertEquals(1, failures.size)
            assertTrue(failures.single().message.orEmpty().contains("semantic capture failed"))
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
            controller.pause().stop().destroy()
        }
    }

    @Test fun `semantics wait for delivery and composition then retire before hidden work can act`() {
        val controller = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().visible()
        val native = RecordingNative()
        val lane = NuxieRuntimeLane()
        val host = ExperienceSurfaceHost(controller.get(), lane,
            artboardSize = ExperienceArtboardSize(100f, 100f), runtime = NuxieRuntime(native),
            listener = object : ExperienceSurfaceHost.Listener {
                override fun onFirstFrame() {}
                override fun onFailure(error: ExperiencePresentationException) { throw error }
            })
        controller.get().setContentView(host)
        host.layout(0, 0, 100, 100)
        val texture = SurfaceTexture(0)
        val descriptor = Json.parseToJsonElement("""{
            "requirements":{"requiredCapabilities":["scene-semantics-v1"]},
            "render":{"assets":[],"screens":[{"id":"screen","artboardName":"Main"}]},
            "leg":{"screens":[{"id":"screen"}]}
        }""").jsonObject
        try {
            host.loadArtboard(byteArrayOf(1), null, descriptor)
            host.onSurfaceTextureAvailable(texture, 100, 100)
            drain(lane)
            assertEquals(1, native.semanticsEnabled)
            native.presentation = 4
            host.doFrame(1_000_000_000L)
            drain(lane)
            assertEquals(0, native.semanticCaptures)
            native.presentation = 1
            host.doFrame(1_016_000_000L)
            drain(lane)
            android.view.Choreographer.getInstance().removeFrameCallback(host)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, native.semanticCaptures)
            assertEquals(0, checkNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(-1)).childCount)
            host.onSurfaceTextureUpdated(texture)
            drain(lane)
            android.view.Choreographer.getInstance().removeFrameCallback(host)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, checkNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(-1)).childCount)
            // Hold activation until the pending submission completes. Native
            // activation invalidates the render revision, so executing it early
            // would make the in-flight frame's semantic capture unpresented.
            native.presentation = 4
            host.doFrame(1_018_000_000L)
            drain(lane)
            assertTrue(host.accessibilityNodeProvider.performAction(1,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            drain(lane)
            assertTrue(native.semanticActions.isEmpty())
            assertFalse("Only one activation may wait for presentation", host.accessibilityNodeProvider.performAction(1,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            native.presentation = 1
            host.doFrame(1_019_000_000L)
            drain(lane)
            assertEquals(listOf(42L to 0), native.semanticActions)
            native.presentation = 4
            host.doFrame(1_020_000_000L)
            drain(lane)
            assertTrue(host.accessibilityNodeProvider.performAction(1,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            drain(lane)
            native.semanticRevision = 2
            native.presentation = 1
            host.doFrame(1_021_000_000L)
            drain(lane)
            assertEquals("Deferred activation cannot rebind to a changed capture", listOf(42L to 0), native.semanticActions)
            val blocked = CountDownLatch(1)
            val resume = CountDownLatch(1)
            lane.enqueue { blocked.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            try {
                assertTrue(host.accessibilityNodeProvider.performAction(1,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
                val pointer = motion(MotionEvent.ACTION_DOWN, 1_020, 50f, 50f)
                try { assertTrue(host.onTouchEvent(pointer)) } finally { pointer.recycle() }
                host.setInputEnabled(false)
                assertNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(1))
                assertEquals(0, checkNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(-1)).childCount)
                assertFalse(host.accessibilityNodeProvider.performAction(1,
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
                host.setInputEnabled(true)
            } finally { resume.countDown() }
            drain(lane)
            assertEquals("Queued action cannot cross suspended input", listOf(42L to 0), native.semanticActions)
            native.presentation = 1
            host.doFrame(1_032_000_000L)
            drain(lane)
            assertTrue("Queued pointer cannot cross suspended input", native.pointerSteps.all { it.isEmpty() })
            host.setPresentationVisible(false)
            assertEquals(0, checkNotNull(host.accessibilityNodeProvider.createAccessibilityNodeInfo(-1)).childCount)
            assertFalse(host.accessibilityNodeProvider.performAction(1,
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
            drain(lane)
            assertEquals(listOf(99L, 99L, 99L, 99L), native.semanticFreed)
        } finally {
            host.release()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
            texture.release()
            controller.pause().stop().destroy()
        }
    }

    private fun drain(lane: NuxieRuntimeLane) {
        val drained = CountDownLatch(1)
        assertTrue(lane.enqueue { drained.countDown() })
        assertTrue("runtime lane did not drain", drained.await(2, TimeUnit.SECONDS))
    }

    private fun motion(action: Int, eventTime: Long, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0, eventTime, action, x, y, 0)

    private class RecordingNative : NuxieTypedRuntimeNative {
        var semanticsEnabled = 0
        var semanticCaptures = 0
        var semanticCaptureStatus = 0
        var semanticRevision = 1L
        val semanticFreed = mutableListOf<Long>()
        val semanticActions = mutableListOf<Pair<Long, Int>>()
        override fun inspectFileAssets(bytes: ByteArray) = emptyList<ai.nuxie.sdk.runtime.ExpectedFileAsset>()
        override fun enableSemantics(player: Long): Int { semanticsEnabled++; return 0 }
        override fun captureSemantics(player: Long): NativeCallResult<Long> { semanticCaptures++; return NativeCallResult(semanticCaptureStatus, if (semanticCaptureStatus == 0) 99L else null) }
        override fun semanticInfo(snapshot: Long) = NativeCallResult(0, longArrayOf(semanticRevision, 1, 1))
        override fun semanticNode(snapshot: Long, index: Int) = NativeCallResult(0,
            ai.nuxie.sdk.runtime.NativeSemanticNode(42, -1, 0, 1, 0, 0, 0, 1, 10f, 10f, 80f, 80f, "Continue", "", ""))
        override fun freeSemantics(snapshot: Long): Int { semanticFreed += snapshot; return 0 }
        override fun queueSemanticAction(player: Long, snapshot: Long, nodeId: Long, action: Int): Int {
            semanticActions += nodeId to action; return 0
        }
        var onRender: () -> Unit = {}
        var events = emptyArray<ai.nuxie.sdk.runtime.NativeRuntimeEvent>()
        val pointerSteps = mutableListOf<List<NativePlayerPointer>>()
        val order = mutableListOf<String>()
        val elapsedSteps = mutableListOf<Float>()
        var playersCreated = 0
        var windowsAcquired = 0
        var boundStateFreed = false
        val stateWrites = mutableListOf<Float>()
        val stateAtPlayerCreation = mutableListOf<Float>()
        val stateAtSteps = mutableListOf<Float>()
        override fun viewModelCatalog(fileHandle: Long) = NativeCallResult(0, NativeViewModelCatalog(
            arrayOf(NativeViewModelSchema(0, "Root", 0, 2, 0, 0, -1, false),
                NativeViewModelSchema(1, "Product", 2, 0, 0, 0, -1, false)),
            arrayOf(NativeViewModelProperty(0, 0, "safeArea/top", 2, -1, emptyArray()),
                NativeViewModelProperty(0, 1, "products", 8, 1, emptyArray())), emptyArray(),
        ))
        override fun newDefaultViewModel(artboardHandle: Long) = NativeCallResult(0, 40L)
        override fun bindViewModel(artboardHandle: Long, viewModelHandle: Long) = 0
        override fun freeViewModel(handle: Long): Int { boundStateFreed = true; return 0 }
        override fun mutateViewModel(handle: Long, write: NativeViewModelWrite): Int {
            assertFalse(boundStateFreed)
            assertEquals(40L, handle)
            assertEquals("safeArea/top", write.path)
            stateWrites += write.numberValue
            return 0
        }

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
        override fun stateMachineNames(fileHandle: Long, artboardName: String?): NativeCallResult<List<String>> = NativeCallResult(0, emptyList())
        override fun newDefaultPlayer(artboardHandle: Long): Long = 3L.also { playersCreated += 1; stateWrites.lastOrNull()?.let(stateAtPlayerCreation::add) }
        override fun freePlayer(handle: Long) = Unit
        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long = 4L
        override fun attachRendererSurface(rendererHandle: Long, windowHandle: Long): Int = 0

        override fun detachRendererSurface(rendererHandle: Long): Int = 0

        var resizeStatus = 0
        override fun resizeRenderer(handle: Long, pixelWidth: Int, pixelHeight: Int): Int = resizeStatus
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
            stateWrites.lastOrNull()?.let(stateAtSteps::add)
            return NativeCallResult(
                0,
                NativePlayerStepOutcome(
                    keepGoing = true,
                    pointerHits = intArrayOf(),
                    events = events.also { events = emptyArray() },
                    hostCommands = emptyArray(),
                    viewModelChanges = emptyArray(),
                ),
            )
        }

        var presentation = 1

        override fun renderAndPresent(
            rendererHandle: Long,
            playerHandle: Long,
            windowHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ): Int { onRender(); return presentation }

        override fun renderToCpuFrame(
            rendererHandle: Long,
            playerHandle: Long,
            clearColor: Int,
            fitContainCenter: Boolean,
        ): NuxieCpuFrame = error("not used")

        override fun freeRenderer(handle: Long) = Unit
    }
}
