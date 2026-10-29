package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.logging.NuxieLog
import ai.nuxie.sdk.runtime.*
import android.content.pm.ApplicationInfo
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

/**
 * Magic Move step timing (README, "Magic Move step timing"): a debug build logs each step's start and
 * landing, with the Choreographer frame time of the player step that started it and of the one in which
 * its last completion event arrived, plus one verbose line per player step in between (UNIV-5724). The
 * frames here are driven by hand, so every expected time is known before the step runs.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExperienceStepTimingTest {
    private val app = RuntimeEnvironment.getApplication()
    private val flags = app.applicationInfo.flags

    @Before fun logEverything() {
        NuxieLog.configure(LogLevel.VERBOSE, redactSensitiveData = false)
        ShadowLog.clear()
    }

    @After fun restore() {
        app.applicationInfo.flags = flags
        NuxieLog.configure(LogLevel.WARN)
    }

    @Test fun `a debug build logs a Magic Move step and its Back at the frame times their player steps used`() = runTest {
        debuggable(true)
        withPage { page ->
            val forward = page.begin(page.toB, back = false)
            val t0 = page.frame()
            page.pump()
            val t1 = page.frame()
            page.events("leave" to A_OWNER, "arrive" to B_OWNER)
            val t2 = page.frame()
            // A frame can step before the landing batch applies. The step still lands where its events arrived.
            val t3 = page.frame()
            page.land(forward)

            val back = page.begin(page.toA, back = true)
            val t4 = page.frame()
            page.pump()
            page.events("leave.reverse" to B_OWNER, "arrive.reverse" to A_OWNER)
            val t5 = page.frame()
            page.land(back)

            val lines = lines()
            assertTrue("Lines were logged", lines.isNotEmpty())
            val forwardId = idOf(lines.first())
            val backId = idOf(lines.first { it.contains("step start") && !it.contains(" id=$forwardId ") })
            assertNotEquals(forwardId, backId)
            assertEquals(listOf(
                "D step start id=$forwardId from=a to=b back=0 frameNanos=$t0",
                "V frame id=$forwardId frameNanos=$t0 elapsedMs=0.0",
                "V frame id=$forwardId frameNanos=$t1 elapsedMs=16.667",
                "V frame id=$forwardId frameNanos=$t2 elapsedMs=16.667",
                "V frame id=$forwardId frameNanos=$t3 elapsedMs=16.667",
                "D step land id=$forwardId frameNanos=$t2 lengthMs=33.333",
                "D step start id=$backId from=b to=a back=1 frameNanos=$t4",
                "V frame id=$backId frameNanos=$t4 elapsedMs=0.0",
                "V frame id=$backId frameNanos=$t5 elapsedMs=16.667",
                "D step land id=$backId frameNanos=$t5 lengthMs=16.667",
            ), lines)
        }
    }

    @Test fun `a Magic Move that ends at its watchdog lands at its last player step`() = runTest {
        debuggable(true)
        withPage { page ->
            val step = page.begin(page.toB, back = false)
            val t0 = page.frame()
            page.pump()
            page.frame()
            val t2 = page.frame()
            // No completion event arrives: the step's watchdog (100 ms plus 250) lands it.
            advanceTimeBy(351)
            page.land(step)
            val lines = lines()
            assertTrue("Lines were logged", lines.isNotEmpty())
            val id = idOf(lines.first())
            assertEquals("D step start id=$id from=a to=b back=0 frameNanos=$t0", lines.first())
            assertEquals("D step land id=$id frameNanos=$t2 lengthMs=33.333", lines.last())
            assertEquals(1, lines.count { it.contains("step land") })
        }
    }

    @Test fun `an interrupted Magic Move logs no landing and no later frames`() = runTest {
        debuggable(true)
        withPage { page ->
            val step = page.begin(page.toB, back = false)
            val t0 = page.frame()
            page.pump()
            val t1 = page.frame()
            step.cancel()
            page.pump()
            assertTrue(step.isCompleted)
            page.frame()
            val lines = lines()
            assertTrue("Lines were logged", lines.isNotEmpty())
            val id = idOf(lines.first())
            assertEquals(listOf(
                "D step start id=$id from=a to=b back=0 frameNanos=$t0",
                "V frame id=$id frameNanos=$t0 elapsedMs=0.0",
                "V frame id=$id frameNanos=$t1 elapsedMs=16.667",
            ), lines)
        }
    }

    @Test fun `a release build logs nothing on this path, even at the verbose level`() = runTest {
        debuggable(false)
        withPage { page ->
            val step = page.begin(page.toB, back = false)
            page.frame()
            page.pump()
            page.events("leave" to A_OWNER, "arrive" to B_OWNER)
            page.frame()
            page.land(step)
            assertEquals(emptyList<String>(), lines())
        }
    }

    private fun debuggable(value: Boolean) {
        app.applicationInfo.flags = if (value) flags or ApplicationInfo.FLAG_DEBUGGABLE
            else flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
    }

    /** Each line under the tag, prefixed with its priority: D for debug, V for verbose. */
    private fun lines(): List<String> = ShadowLog.getLogsForTag(TAG).map {
        val priority = when (it.type) { Log.DEBUG -> "D"; Log.VERBOSE -> "V"; else -> "${it.type}" }
        "$priority ${it.msg}"
    }

    private fun idOf(line: String): String = checkNotNull(Regex(" id=(\\d+) ").find(line)) { "No id in: $line" }.groupValues[1]

    /** One page with Screens a and b, a Magic Move pair between them, and a surface whose frames the test drives. */
    private class Page(
        private val scope: TestScope,
        private val surface: ExperienceSurfaceHost,
        private val native: ExperienceInteractivePageTest.RecordingNative,
        private val lane: NuxieRuntimeLane,
        private val mounted: ExperienceMountedScreen,
        private val listener: ExperienceSurfaceHost.Listener,
        source: PreparedPresentation,
    ) {
        private var clock = 1_000_000_000L
        private val transition = Json.parseToJsonElement("""{"type":"magic-move","transitionId":"pair"}""").jsonObject
        val toB = source.copy(screenId = "b", artboardName = "B", pageHistory = listOf("a", "b"), transition = transition,
            screenLifecycle = ExperienceScreenLifecycle(), retainedViewModel = AtomicReference())
        val toA = source.copy(pageHistory = listOf("a"), transition = transition)

        /** One Choreographer frame at the next 60 Hz frame time, run to its end. Returns its frame time. */
        fun frame(): Long {
            val time = clock
            surface.doFrame(time)
            lane.blockingCall {}
            clock += 16_666_667L
            return time
        }

        /** Lets the step's coroutine run its lane handoffs, with no frame and no virtual time passing. */
        fun pump() = repeat(8) { scope.runCurrent(); lane.blockingCall {} }

        /** The events the player reports on its next step, each from the Screen whose values own it. */
        fun events(vararg names: Pair<String, Long>) = lane.blockingCall {
            native.events = names.map { (name, owner) -> NativeRuntimeEvent(0, 0, name, "", "", 0f, emptyArray(), owner) }
                .toTypedArray()
        }

        /** Starts a page step and runs it until its first batch waits for a frame. */
        fun begin(target: PreparedPresentation, back: Boolean): Deferred<Unit> {
            runBlocking { mounted.prepareRow(target, listener) }
            val step = scope.async { mounted.navigateLayer(target, back) }
            repeat(20) { if (!queued()) pump() }
            assertTrue("The step's first batch waits for a frame", queued())
            return step
        }

        fun land(step: Deferred<Unit>) {
            repeat(20) { if (!step.isCompleted) pump() }
            assertTrue("The step lands", step.isCompleted)
            runBlocking { step.await() }
        }

        private fun queued() = lane.blockingCall {
            (field(surface, "transitionWrites") as kotlin.collections.ArrayDeque<*>).isNotEmpty()
        }
    }

    private suspend fun TestScope.withPage(body: suspend (Page) -> Unit) {
        val native = ExperienceInteractivePageTest.RecordingNative().apply { presentation = 1 }
        val lane = TestRuntimeLane()
        val file = NuxieRuntimeFile(1, native)
        val screens = """{"leg":{"screens":[{"id":"a","defaultViewModelName":"A"},{"id":"b","defaultViewModelName":"B"},
            {"id":"c","defaultViewModelName":"C"}]},"render":{"screens":[{"id":"a","artboardName":"A"},
            {"id":"b","artboardName":"B"},{"id":"c","artboardName":"C"}],"layerHost":{"artboardName":"LayerList","list":"screens"}"""
        val layer = ExperienceInteractivePage.open(lane, NuxieRuntime(native), file, Json.parseToJsonElement("$screens}}").jsonObject, null)
        val descriptor = Json.parseToJsonElement("""$screens,"transitions":[{"id":"pair","kind":"page","sourceScreenId":"a",
            "destinationScreenId":"b","durationMs":100,"incomingOnTop":true,"source":{"completeEventName":"leave"},
            "destination":{"completeEventName":"arrive"},"reverse":{"durationMs":100,
            "source":{"completeEventName":"leave.reverse"},"destination":{"completeEventName":"arrive.reverse"}}}]}}""").jsonObject
        val surface = ExperienceSurfaceHost(app, lane, runtime = NuxieRuntime(native), usesSystemFrameCallbacks = false)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val scene = java.io.File.createTempFile("step-timing", ".riv").apply { writeBytes(byteArrayOf(1)) }
        val listener = object : ExperienceSurfaceHost.Listener {
            override fun onFirstFrame() = Unit
            override fun onFailure(error: ExperiencePresentationException) { throw AssertionError(error) }
        }
        val source = PreparedPresentation(scene, "A", 0, PresentationShell.FullScreen(), "a", descriptor, pageHistory = listOf("a"))
        var mounted: ExperienceMountedScreen? = null
        try {
            lane.blockingCall {
                layer.prepareOnLane("a"); layer.insertOnLane("a", 0); layer.prepareOnLane("b")
                set(surface, "interactiveLayer", layer); set(surface, "artboard", layer.artboard)
                set(surface, "player", layer.player); set(surface, "activeRowId", "a"); set(surface, "file", file)
                @Suppress("UNCHECKED_CAST")
                val retained = field(surface, "layerRetainedValues") as MutableMap<String, AtomicReference<NuxieViewModelSnapshot?>>
                retained["a"] = AtomicReference()
                // A drawing page: an attached window whose frames present at once.
                val layout = checkNotNull(ExperienceSurfaceLayout.create(100, 100, 1f))
                set(surface, "renderer", NuxieAndroidVulkanRenderer(900, native)); set(surface, "window", NuxieRuntimeWindow(901, native))
                set(surface, "surfaceLayout", layout); set(surface, "appliedLayout", layout)
                set(surface, "attached", true); set(surface, "running", true)
                set(surface, "firstFramePresented", true); set(surface, "firstFrameComposed", true)
            }
            mounted = ExperienceMountedScreen(activity.get(), source, listener, { throw AssertionError(it) })
            set(mounted, "surface", surface)
            body(Page(this, surface, native, lane, mounted, listener, source))
        } finally {
            mounted?.let {
                val ownedLane = field(it, "lane") as NuxieRuntimeLane
                it.close(false) {}
                assertTrue(ownedLane.awaitQuiescence(5_000))
            }
            surface.release(); lane.blockingCall {}; runBlocking { layer.close() }; lane.blockingCall { file.close() }
            lane.shutdown(); assertTrue(lane.awaitQuiescence(5_000))
            activity.pause().stop().destroy(); scene.delete()
        }
    }

    private companion object {
        const val TAG = "NuxieMagicMove"
        /** The values of Screens a and b in the fake runtime, which own the events each Screen reports. */
        const val A_OWNER = 200L
        const val B_OWNER = 300L

        fun <T> NuxieRuntimeLane.blockingCall(work: () -> T): T = runBlocking { call(work) }
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }
}
