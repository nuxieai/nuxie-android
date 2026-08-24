package ai.nuxie.sdk.presentation

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime-host render smoke (UNIV-1182 tracer bullet): boot the engine
 * over a real SurfaceView, render a fixture riv for a second, and prove the
 * surface shows non-clear pixels. Runs on an emulator/device via
 * connectedAndroidTest; requires the prebuilt engine in the AAR.
 */
class RenderSmokeTest {
    private companion object {
        /** Opaque magenta: never present on the launcher or system chrome. */
        const val SENTINEL_CLEAR = 0xFFFF00FF.toInt()

        /**
         * The fit-contain artboard covers most of the sampled center region,
         * so the sentinel background is a minority; a healthy margin of
         * exact-sentinel samples still only ever comes from engine frames.
         */
        const val SENTINEL_MIN_SAMPLES = 100

    }

    @Test
    fun fixtureRivRendersNonBlankFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        assertTrue(
            "Engine library must load on the test device",
            ai.nuxie.sdk.runtime.NuxieRuntimeBridge.isAvailable,
        )

        // Stage the fixture riv from test assets into app files.
        val rivFile = File(context.filesDir, "smoke.riv")
        instrumentation.context.assets.open("data_binding_test.riv").use { input ->
            FileOutputStream(rivFile).use { output -> input.copyTo(output) }
        }

        var activity: Activity? = null
        val monitor = Instrumentation.ActivityMonitor(
            NuxieExperienceActivity::class.java.name, null, false,
        )
        instrumentation.addMonitor(monitor)
        val application = context.applicationContext as Application

        try {
            val intent = Intent(context, NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NuxieExperienceActivity.EXTRA_RIV_PATH, rivFile.absolutePath)
                // Sentinel background: the launcher showing through a
                // translucent activity can never be mistaken for engine
                // output (an earlier revision passed on exactly that).
                putExtra(NuxieExperienceActivity.EXTRA_CLEAR_COLOR, SENTINEL_CLEAR)
            }
            context.startActivity(intent)
            activity = monitor.waitForActivityWithTimeout(15_000)
            assertTrue("Experience activity must launch", activity != null)

            // The first frame can take several seconds on cold Vulkan
            // drivers (pipeline compilation, notably on emulators), so poll
            // for rendered content with a deadline instead of a fixed sleep.
            var screenshot: Bitmap? = null
            var sentinelCount = 0
            var sampleCount = 0
            var lit = 0
            var colors = HashSet<Int>()
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                assertTrue(
                    "Experience activity must still be presenting (load failure finishes it)",
                    !activity!!.isFinishing && !activity.isDestroyed,
                )
                val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
                assertTrue("Screenshot must capture", shot.width > 0)
                // Non-blank proof: count pixels that differ from the clear
                // color (opaque black) in the center region. Dense sampling:
                // sparse grids miss anti-aliased vector content (text
                // glyphs) and read a real frame as a flat fill.
                var sentinel = 0
                var samples = 0
                var shotLit = 0
                val shotColors = HashSet<Int>()
                for (x in shot.width / 4 until shot.width * 3 / 4 step 4) {
                    for (y in shot.height / 4 until shot.height * 3 / 4 step 4) {
                        val pixel = shot.getPixel(x, y)
                        samples++
                        shotColors.add(pixel)
                        if (pixel == SENTINEL_CLEAR) sentinel++ else shotLit++
                    }
                }
                screenshot = shot
                sentinelCount = sentinel
                sampleCount = samples
                lit = shotLit
                colors = shotColors
                // Engine frames show the sentinel background around the
                // fit-contain artboard plus real content pixels; the
                // launcher shows neither.
                if (sentinel >= SENTINEL_MIN_SAMPLES && shotLit > 10 && shotColors.size >= 4) break
                SystemClock.sleep(500)
            }
            checkNotNull(screenshot)
            assertTrue(
                "Engine frames must reach the screen: the sentinel clear color was never observed " +
                    "(sentinel=$sentinelCount of $sampleCount samples)",
                sentinelCount >= SENTINEL_MIN_SAMPLES,
            )
            assertTrue("Rendered frame must contain content pixels (lit=$lit)", lit > 10)
            assertTrue(
                "Rendered frame must show real content, not a flat fill (colors=${colors.size})",
                colors.size >= 4,
            )

            // Persist the proof frame for the device rung's artifact trail.
            val proof = File(context.filesDir, "render-smoke-proof.png")
            FileOutputStream(proof).use { output ->
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            activity?.finish()
            instrumentation.removeMonitor(monitor)
        }
    }
}
