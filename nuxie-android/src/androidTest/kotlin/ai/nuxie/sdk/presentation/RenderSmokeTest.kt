package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime-host render smoke (UNIV-1182 tracer bullet): boot the engine
 * over a real surface, render a fixture riv, and prove the
 * presented surface shows varied authored pixels. Runs on an emulator/device via
 * connectedAndroidTest; requires the prebuilt engine in the AAR.
 */
class RenderSmokeTest {
    private companion object {
        /** Opaque magenta used to distinguish the clear color from authored content. */
        const val SENTINEL_CLEAR = 0xFFFF00FF.toInt()
    }

    @Test
    fun fixtureRivRendersNonBlankFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        assertTrue(
            "Engine library must load on the test device",
            NuxieRuntime.shared.isAvailable,
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
        val presentationId = UUID.randomUUID().toString()
        val presented = java.util.concurrent.CountDownLatch(1)
        PresentationRegistry.register(
            id = presentationId,
            content = PreparedPresentation(
                rivFile,
                null,
                SENTINEL_CLEAR,
                PresentationShell.FullScreen,
            ),
            onFirstFrame = {
                kotlinx.coroutines.runBlocking { assertTrue(PresentationRegistry.reveal(presentationId)) }
                presented.countDown()
            },
            onFailure = { throw AssertionError("Experience host failed", it) },
            onDismissed = {},
            onOutcome = {},
        )

        try {
            val intent = Intent(context, NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, presentationId)
            }
            context.startActivity(intent)
            activity = monitor.waitForActivityWithTimeout(15_000)
            assertTrue("Experience activity must launch", activity != null)

            assertTrue("The runtime must present a frame", presented.await(30, java.util.concurrent.TimeUnit.SECONDS))
            var screenshot: Bitmap? = null
            var lit = 0
            var colors = emptySet<Int>()
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var shot: Bitmap? = null
                instrumentation.runOnMainSync {
                    fun surface(view: android.view.View): ExperienceSurfaceHost? = when (view) {
                        is ExperienceSurfaceHost -> view
                        is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { surface(view.getChildAt(it)) }
                        else -> null
                    }
                    val host = checkNotNull(surface(checkNotNull(activity).window.decorView))
                    assertTrue("The rendered surface must be visible", host.isShown)
                    shot = host.bitmap
                }
                shot?.let { bitmap ->
                    val sampled = HashSet<Int>()
                    var content = 0
                    for (x in 0 until bitmap.width step 4) {
                        for (y in 0 until bitmap.height step 4) {
                            val pixel = bitmap.getPixel(x, y)
                            sampled.add(pixel)
                            if (pixel != SENTINEL_CLEAR) content++
                        }
                    }
                    screenshot?.recycle()
                    screenshot = bitmap
                    lit = content
                    colors = sampled
                }
                if (lit > 10 && colors.size >= 4) break
                SystemClock.sleep(100)
            }
            assertTrue("Presented surface must contain authored pixels (lit=$lit)", lit > 10)
            assertTrue("Presented surface must contain varied authored content (colors=${colors.size})", colors.size >= 4)

            // Persist the proof frame for the device rung's artifact trail.
            val proof = File(context.filesDir, "render-smoke-proof.png")
            FileOutputStream(proof).use { output ->
                checkNotNull(screenshot).compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            screenshot?.recycle()
        } finally {
            activity?.finish()
            instrumentation.removeMonitor(monitor)
            PresentationRegistry.clearForTesting()
        }
    }
}
