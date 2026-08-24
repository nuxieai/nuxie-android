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

        val intent = Intent(context, NuxieExperienceActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(NuxieExperienceActivity.EXTRA_RIV_PATH, rivFile.absolutePath)
        }
        context.startActivity(intent)
        activity = monitor.waitForActivityWithTimeout(15_000)
        assertTrue("Experience activity must launch", activity != null)

        try {
            // Let the frame loop run.
            SystemClock.sleep(3_000)
            assertTrue(
                "Experience activity must still be presenting (load failure finishes it)",
                !activity!!.isFinishing && !activity.isDestroyed,
            )

            val screenshot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertTrue("Screenshot must capture", screenshot.width > 0)

            // Non-blank proof: count pixels that differ from the clear color
            // (opaque black) in the center region.
            var lit = 0
            val colors = HashSet<Int>()
            // Dense sampling: sparse grids miss anti-aliased vector content
            // (text glyphs) and read a real frame as a flat fill.
            for (x in screenshot.width / 4 until screenshot.width * 3 / 4 step 4) {
                for (y in screenshot.height / 4 until screenshot.height * 3 / 4 step 4) {
                    val pixel = screenshot.getPixel(x, y)
                    colors.add(pixel)
                    if (pixel != 0xFF000000.toInt()) lit++
                }
            }
            assertTrue("Rendered frame must contain non-clear pixels (lit=$lit)", lit > 10)
            assertTrue(
                "Rendered frame must show real content, not a flat fill (colors=${colors.size})",
                // Bar chosen from observed failure modes: a blank frame is 1
                // color, clear + flat panel is 2; the rendered fixture shows
                // black, panel gray, white text, and anti-aliased blends.
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
