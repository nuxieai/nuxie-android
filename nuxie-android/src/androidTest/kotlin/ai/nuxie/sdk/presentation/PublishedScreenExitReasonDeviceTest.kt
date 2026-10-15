package ai.nuxie.sdk.presentation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import ai.nuxie.sdk.runtime.NuxieAndroidVulkanRenderer
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieRuntimePlayer
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
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
    fun activeCloseKeepsTheScreenMountedUntilItsCompiledDismissalCompletes() =
        verifyCompiledDismissal(CloseReason.UserDismissed, capture = true)

    @Test
    fun journeyDismissKeepsTheScreenMountedUntilItsCompiledDismissalCompletes() =
        verifyCompiledDismissal(CloseReason.JourneyDismissed)

    private fun verifyCompiledDismissal(reason: CloseReason, capture: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frames = if (capture && InstrumentationRegistry.getArguments().getString("captureScreenMotionFrames") == "true")
            ScreenExitFrameCapture(instrumentation) else null
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
            onOutcome = {
                outcomeReason.set(it)
                outcomeCount.incrementAndGet()
                if (it is CloseReason.Error) failure.set(it.cause)
            },
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
            instrumentation.runOnMainSync { frames?.observeCompletion(host) }
            frames?.capture("active")
            instrumentation.runOnMainSync {
                assertTrue(PresentationRegistry.currentScreen(id) != null)
                closeStartedAt.set(SystemClock.elapsedRealtime())
                PresentationRegistry.dismiss(id, reason)
                assertFalse("Active Close must keep the Activity through the native exit", host.isFinishing)
                assertEquals(NuxieViewModelScalarValue.StringValue("dismissed"), prepared.screenLifecycle.snapshot()["screen/exitReason"])
            }
            SystemClock.sleep(200)
            instrumentation.runOnMainSync {
                assertFalse("The 400 ms authored fade is still in progress", host.isFinishing)
                assertTrue(PresentationRegistry.currentScreen(id) != null)
            }
            assertEquals(0, dismissalCount.get())
            frames?.capture("middle")
            val remaining = 2000L - (SystemClock.elapsedRealtime() - closeStartedAt.get())
            assertTrue("The native completion deadline starts at Close", remaining > 0)
            assertTrue("Compiled completion must retire the screen within its bounded wait",
                dismissed.await(remaining, TimeUnit.MILLISECONDS))
            assertTrue("A 4250 ms watchdog cannot satisfy the native completion oracle",
                dismissedAt.get() - closeStartedAt.get() < 2000L)
            assertEquals(reason, dismissalReason.get())
            assertEquals(1, dismissalCount.get())
            assertEquals(reason, outcomeReason.get())
            assertEquals(1, outcomeCount.get())
            assertNull(PresentationRegistry.currentScreen(id))
            assertNull(failure.get())
            if (frames != null) {
                instrumentation.waitForIdleSync()
                frames.capture("settled")
            }
        } finally {
            instrumentation.runOnMainSync {
                PresentationRegistry.dismiss(id, CloseReason.UserDismissed)
                activity?.let { host -> if (!host.isFinishing) host.finish() }
            }
            assertTrue("Cleanup must await terminal native retirement", retired.await(5, TimeUnit.SECONDS))
            instrumentation.removeMonitor(monitor)
            scene.delete()
            frames?.save()
        }
    }
}

/** Optional visual receipt; the existing assertions and deadlines still decide the verdict. */
private class ScreenExitFrameCapture(private val instrumentation: Instrumentation) {
    private val frames = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private lateinit var surface: ExperienceSurfaceHost

    fun capture(name: String) {
        if (name == "settled") {
            frames[name] = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        } else {
            runBlocking { (field("lane") as NuxieRuntimeLane).call { captureNative(name) } }
        }
    }

    private fun field(name: String): Any = checkNotNull(
        ExperienceSurfaceHost::class.java.getDeclaredField(name).apply { isAccessible = true }.get(surface),
    )

    private fun captureNative(name: String) {
        val frame = (field("renderer") as NuxieAndroidVulkanRenderer).renderToCpuFrame(
            field("player") as NuxieRuntimePlayer, Color.BLACK, 1f,
        )
        val pixels = IntArray(frame.width * frame.height) { index ->
            val offset = index * 4
            Color.argb(frame.rgba[offset + 3].toInt() and 255, frame.rgba[offset].toInt() and 255,
                frame.rgba[offset + 1].toInt() and 255, frame.rgba[offset + 2].toInt() and 255)
        }
        frames.put(name, Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888))?.recycle()
    }

    fun observeCompletion(activity: Activity) {
        fun find(view: View): ExperienceSurfaceHost? {
            if (view is ExperienceSurfaceHost) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        surface = checkNotNull(find(activity.window.decorView))
        // Keep the observer entirely in test code instead of adding a product capture hook.
        val field = ExperienceSurfaceHost::class.java.getDeclaredField("listener").apply { isAccessible = true }
        val listener = field.get(surface) as ExperienceSurfaceHost.Listener
        field.set(surface, object : ExperienceSurfaceHost.Listener by listener {
            override fun onRuntimeStep(outcome: NuxiePlayerStepOutcome, correlationId: ULong,
                viewModelSnapshot: NuxieViewModelSnapshot?, saves: List<ExperienceResponseSaveRequest>) {
                if (outcome.events.any { it.name == "nuxie:screen-exit-complete:scr_screens_stap" }) captureNative("completion")
                listener.onRuntimeStep(outcome, correlationId, viewModelSnapshot, saves)
            }
        })
    }

    fun save() {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screen-motion").apply { mkdirs() }
        frames.forEach { (name, bitmap) ->
            val file = File(directory, "android-exit-$name.png")
            file.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            // Gradle uninstalls this test APK after the run, including its external files.
            val retained = "/data/local/tmp/sdk-motion-${file.name}"
            shell("cp ${file.absolutePath} $retained")
            check(shell("cat $retained").contentEquals(file.readBytes())) {
                "Could not retain the screen motion picture $name"
            }
        }
    }

    private fun shell(command: String): ByteArray = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes() }
}
