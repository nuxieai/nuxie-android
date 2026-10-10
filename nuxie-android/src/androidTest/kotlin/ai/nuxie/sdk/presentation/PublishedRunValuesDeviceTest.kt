package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PublishedRunValuesDeviceTest {
    @Test fun publishedDeviceGlobalIsInstalledBeforeTheFirstHostFrame() = runBlocking {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val run = ExperienceRunValues()
        val frames = mutableListOf<Bitmap>()
        try {
            val env = run.lane.call {
                val file = run.prepare(PublishedRunValuesFixture.bytes, PublishedRunValuesFixture.descriptor, emptyMap()).file
                val schema = file.viewModelCatalog().schemas.single { it.name == "env" }
                assertTrue(schema.isGlobal)
                checkNotNull(file.globalViewModel("env")).also {
                    assertSame(it, file.globalViewModel("env"))
                    assertEquals(NuxieViewModelScalarValue.BooleanValue(false), it.snapshot().resolveScalar(listOf("reduceMotion")))
                    assertEquals(NuxieViewModelScalarValue.NumberValue(0.0), it.snapshot().resolveScalar(listOf("safeArea", "top")))
                }
            }
            for ((index, motion) in listOf(false, true, false).withIndex()) {
                var host: ExperienceSurfaceHost? = null
                val image = AtomicReference<Bitmap?>()
                val failure = AtomicReference<Throwable?>()
                val loaded = CountDownLatch(1)
                val firstFrame = CountDownLatch(1)
                try {
                    instrumentation.runOnMainSync {
                        host = ExperienceSurfaceHost(activity, run.lane, clearColor = 0xff112233.toInt(),
                            artboardSize = ExperienceArtboardSize(393f, 852f), usesSystemFrameCallbacks = false,
                            runValues = run, listener = object : ExperienceSurfaceHost.Listener {
                                override fun onFirstFrame() {
                                    image.set(checkNotNull(host).bitmap)
                                    firstFrame.countDown()
                                }
                                override fun onFailure(error: ExperiencePresentationException) { failure.set(error) }
                            })
                        checkNotNull(host).updateRuntimeValues(mapOf(
                            "env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(motion),
                            "safeArea/top" to NuxieViewModelScalarValue.NumberValue(if (motion) 59.0 else 0.0)))
                        checkNotNull(host).loadArtboard(PublishedRunValuesFixture.bytes, "device",
                            PublishedRunValuesFixture.descriptor, onLoaded = { success ->
                                if (!success) failure.compareAndSet(null, AssertionError("F4 device did not load"))
                                loaded.countDown()
                            })
                        activity.setContentView(checkNotNull(host))
                        checkNotNull(host).setPresentationVisible(true)
                    }
                    assertTrue("F4 load completes", loaded.await(30, TimeUnit.SECONDS))
                    val deadline = SystemClock.uptimeMillis() + 30_000
                    while (firstFrame.count > 0 && failure.get() == null && SystemClock.uptimeMillis() < deadline) {
                        instrumentation.runOnMainSync { if (firstFrame.count > 0) checkNotNull(host).doFrame(System.nanoTime()) }
                        delay(16)
                    }
                    assertNull(failure.get())
                    assertEquals("Device first frame must compose", 0L, firstFrame.count)
                    val bitmap = checkNotNull(image.get())
                    frames += bitmap
                    val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "f4-run-values").apply { mkdirs() }
                    File(folder, "device-first-frame-$index.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    run.lane.call {
                        assertEquals(NuxieViewModelScalarValue.BooleanValue(motion), env.snapshot().resolveScalar(listOf("reduceMotion")))
                        assertEquals(NuxieViewModelScalarValue.NumberValue(if (motion) 59.0 else 0.0), env.snapshot().resolveScalar(listOf("safeArea", "top")))
                    }
                } finally {
                    instrumentation.runOnMainSync { host?.release() }
                    run.lane.call { }
                }
            }
            assertEquals("Device and 0", 2, inkBands(frames[0]).size)
            assertEquals("Device, 59, Still and copy Calm", 4, inkBands(frames[1]).size)
            val shift = inkBands(frames[1]).first().first - inkBands(frames[0]).first().first
            assertEquals("Device moves max(59,20)-max(0,20) points", 39.0 * activity.resources.displayMetrics.density, shift.toDouble(), 1.0)
            assertTrue("Restoring env restores the drawing", frames[0].sameAs(frames[2]))
        } finally {
            run.retire()
            frames.forEach(Bitmap::recycle)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun inkBands(bitmap: Bitmap): List<IntRange> {
        val background = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val bands = mutableListOf<IntRange>()
        var start: Int? = null
        // All published labels sit in the upper-left half, inside the canvas letterbox.
        for (y in 0 until bitmap.height / 2) {
            val ink = (0 until bitmap.width / 2).any { x ->
                val value = bitmap.getPixel(x, y)
                listOf(0, 8, 16).any { shift -> kotlin.math.abs(((value shr shift) and 255) - ((background shr shift) and 255)) > 3 }
            }
            if (ink && start == null) start = y
            if (!ink && start != null) { bands += start until y; start = null }
        }
        if (start != null) bands += start until bitmap.height / 2
        return bands
    }

    @Test fun publishedCopyDrawsSelectedValueOnItsFirstFrame() = runBlocking {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = PublishedRunValuesFixture.expectations.getValue("level").jsonObject
        val selected = expected.getValue("tickValue").jsonPrimitive.double
        val unselected = expected.getValue("noTickValue").jsonPrimitive.double
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val frames = mutableListOf<Bitmap>()
        try {
            for ((index, level) in listOf(selected, unselected, selected).withIndex()) {
                val run = ExperienceRunValues()
                var host: ExperienceSurfaceHost? = null
                val image = AtomicReference<Bitmap?>()
                val failure = AtomicReference<Throwable?>()
                val loaded = CountDownLatch(1)
                val firstFrame = CountDownLatch(1)
                try {
                    run.lane.call {
                        val values = checkNotNull(run.prepare(PublishedRunValuesFixture.bytes,
                            PublishedRunValuesFixture.descriptor, emptyMap()).values)
                        assertEquals(NuxieViewModelScalarValue.NumberValue(
                            PublishedRunValuesFixture.expectations.getValue("startingValues").jsonObject
                                .getValue("level").jsonPrimitive.double), values.snapshot().resolveScalar(listOf("level")))
                        assertEquals(NuxieViewModelScalarValue.BooleanValue(true),
                            values.snapshot().resolveScalar(listOf("isset:level")))
                        assertEquals(NuxieViewModelScalarValue.BooleanValue(true),
                            values.snapshot().resolveScalar(listOf("isset:trip_days")))
                        values.setValue("level", NuxieViewModelScalarValue.NumberValue(level))
                    }
                    instrumentation.runOnMainSync {
                        host = ExperienceSurfaceHost(activity, run.lane, clearColor = 0xff112233.toInt(),
                            artboardSize = ExperienceArtboardSize(393f, 852f), usesSystemFrameCallbacks = false,
                            runValues = run, listener = object : ExperienceSurfaceHost.Listener {
                                override fun onFirstFrame() {
                                    image.set(checkNotNull(host).bitmap)
                                    firstFrame.countDown()
                                }
                                override fun onFailure(error: ExperiencePresentationException) { failure.set(error) }
                            })
                        checkNotNull(host).loadArtboard(PublishedRunValuesFixture.bytes, "level",
                            PublishedRunValuesFixture.descriptor, onLoaded = { success ->
                                if (!success) failure.compareAndSet(null, AssertionError("F4 did not load"))
                                loaded.countDown()
                            })
                        activity.setContentView(checkNotNull(host))
                        checkNotNull(host).setPresentationVisible(true)
                    }
                    assertTrue("F4 load completes", loaded.await(30, TimeUnit.SECONDS))
                    val deadline = SystemClock.uptimeMillis() + 30_000
                    while (firstFrame.count > 0 && failure.get() == null && SystemClock.uptimeMillis() < deadline) {
                        instrumentation.runOnMainSync {
                            // No automatic frame callbacks. Stop at the first compositor acknowledgement.
                            if (firstFrame.count > 0) checkNotNull(host).doFrame(System.nanoTime())
                        }
                        delay(16)
                    }
                    assertNull(failure.get())
                    assertEquals("The copy's first frame must compose", 0L, firstFrame.count)
                    val bitmap = checkNotNull(image.get())
                    frames += bitmap
                    val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "f4-run-values").apply { mkdirs() }
                    File(folder, "first-frame-$index-level-$level.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    instrumentation.runOnMainSync { host?.release() }
                    run.lane.call { }
                    run.retire()
                }
            }
            assertTrue("Fresh selected copies have identical first-frame pixels", frames[0].sameAs(frames[2]))
            assertFalse("Selection changes the actual first frame", frames[0].sameAs(frames[1]))
            assertEquals(frames[0].width, frames[1].width)
            assertEquals(frames[0].height, frames[1].height)
            assertEquals("23, Tick and Next", 3, inkBands(frames[0]).size)
            assertEquals("23 and Next", 2, inkBands(frames[1]).size)

        } finally {
            frames.forEach(Bitmap::recycle)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}

internal object PublishedRunValuesFixture {
    fun read(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("runtime/run-values/$name").use { it.readBytes() }
    val bytes get() = read("screen.riv")
    val expectations get() = Json.parseToJsonElement(read("expectations.json").decodeToString()).jsonObject
    val descriptor get() = Json.parseToJsonElement(read("release.json").decodeToString()).jsonObject
}
