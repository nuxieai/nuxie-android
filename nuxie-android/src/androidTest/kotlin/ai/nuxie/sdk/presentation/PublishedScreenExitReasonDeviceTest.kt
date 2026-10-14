package ai.nuxie.sdk.presentation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Activity, mounting, native phase writes, completion and teardown. */
class PublishedScreenExitReasonDeviceTest {
    @Test
    fun activeCloseKeepsTheScreenMountedUntilItsCompiledDismissalCompletes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("This positive animation witness requires enabled system animations",
            Settings.Global.getFloat(instrumentation.targetContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
        val assets = instrumentation.context.assets
        val fixture = "runtime/screen-exit-reason/dismissed-only"
        val id = UUID.randomUUID().toString()
        val scene = File(instrumentation.targetContext.cacheDir, "screen-exit-$id.nux")
        assets.open("$fixture/screen.nux").use { input -> scene.outputStream().use(input::copyTo) }
        val descriptor = assets.open("$fixture/mounted-screen.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        val render = descriptor.getValue("render").jsonObject
        val screen = render.getValue("screens").jsonArray.single().jsonObject
        val exit = screen.getValue("exit").jsonObject
        assertEquals(400, exit.getValue("durationMs").jsonPrimitive.int)
        // The native fade remains 400 ms. This generous host watchdog makes
        // the two-second bound prove a native event, not a timeout fallback.
        val hostExit = JsonObject(exit + ("durationMs" to JsonPrimitive(4000)))
        val hostScreen = JsonObject(screen + ("exit" to hostExit))
        val hostRender = JsonObject(render + ("screens" to JsonArray(listOf(hostScreen))))
        val hostDescriptor = JsonObject(descriptor + ("render" to hostRender))
        val prepared = PreparedPresentation(
            sceneFile = scene,
            artboardName = "tap",
            clearColor = Color.BLACK,
            shell = PresentationShell.FullScreen,
            screenId = "scr_screens_stap",
            descriptor = hostDescriptor,
            artboardSize = ExperienceArtboardSize(100f, 100f),
        )
        val firstFrame = CountDownLatch(1)
        val dismissed = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val dismissalCount = AtomicInteger()
        val dismissalReason = AtomicReference<CloseReason?>()
        val closeStartedAt = AtomicLong()
        val dismissedAt = AtomicLong()
        val outcomeCount = AtomicInteger()
        val outcomeReason = AtomicReference<CloseReason?>()
        val failure = AtomicReference<Throwable?>()
        val monitor = Instrumentation.ActivityMonitor(NuxieExperienceActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var activity: Activity? = null
        PresentationRegistry.register(
            id, prepared,
            onFirstFrame = {
                runBlocking { assertTrue(PresentationRegistry.reveal(id)) }
                firstFrame.countDown()
            },
            onFailure = { failure.set(it); retired.countDown() },
            onDismissed = {
                dismissedAt.set(SystemClock.elapsedRealtime())
                dismissalReason.set(it)
                dismissalCount.incrementAndGet()
                dismissed.countDown()
                retired.countDown()
            },
            onOutcome = { outcomeReason.set(it); outcomeCount.incrementAndGet() },
        )
        try {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, NuxieExperienceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, id)
            })
            val host = checkNotNull(monitor.waitForActivityWithTimeout(15_000)).also { activity = it }
            assertTrue("Native first frame: ${failure.get()}", firstFrame.await(30, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(NuxieViewModelScalarValue.BooleanValue(false), prepared.screenLifecycle.snapshot()["env/reduceMotion"])
            instrumentation.runOnMainSync {
                assertTrue(PresentationRegistry.currentScreen(id) != null)
                closeStartedAt.set(SystemClock.elapsedRealtime())
                PresentationRegistry.dismiss(id, CloseReason.UserDismissed)
                assertFalse("Active Close must keep the Activity through the native exit", host.isFinishing)
                assertEquals(NuxieViewModelScalarValue.StringValue("dismissed"), prepared.screenLifecycle.snapshot()["screen/exitReason"])
            }
            SystemClock.sleep(200)
            instrumentation.runOnMainSync {
                assertFalse("The 400 ms authored fade is still in progress", host.isFinishing)
                assertTrue(PresentationRegistry.currentScreen(id) != null)
            }
            assertEquals(0, dismissalCount.get())
            val remaining = 2000L - (SystemClock.elapsedRealtime() - closeStartedAt.get())
            assertTrue("The native completion deadline starts at Close", remaining > 0)
            assertTrue("Compiled completion must retire the screen within its bounded wait",
                dismissed.await(remaining, TimeUnit.MILLISECONDS))
            assertTrue("A 4250 ms watchdog cannot satisfy the native completion oracle",
                dismissedAt.get() - closeStartedAt.get() < 2000L)
            assertEquals(CloseReason.UserDismissed, dismissalReason.get())
            assertEquals(1, dismissalCount.get())
            assertEquals(CloseReason.UserDismissed, outcomeReason.get())
            assertEquals(1, outcomeCount.get())
            assertNull(PresentationRegistry.currentScreen(id))
            assertNull(failure.get())
        } finally {
            instrumentation.runOnMainSync {
                PresentationRegistry.dismiss(id, CloseReason.UserDismissed)
                activity?.let { host -> if (!host.isFinishing) host.finish() }
            }
            assertTrue("Cleanup must await terminal native retirement", retired.await(5, TimeUnit.SECONDS))
            instrumentation.removeMonitor(monitor)
            scene.delete()
        }
    }
}
