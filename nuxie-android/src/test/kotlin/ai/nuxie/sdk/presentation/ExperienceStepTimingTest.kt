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
 * its last completion event arrived, plus one verbose line per player step in between (UNIV-5724), at
 * DEBUG while the tag's switch is on (UNIV-6104). The frames here are driven by hand, so every expected
 * time is known before the step runs.
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
                // The Back was sent before the forward's last picture was shown (at t4), so it counts from there.
                "V frame id=$backId frameNanos=$t4 elapsedMs=16.667",
                "V frame id=$backId frameNanos=$t5 elapsedMs=16.667",
                "D step land id=$backId frameNanos=$t5 lengthMs=16.667",
            ), lines)
        }
    }

    @Test fun `an app logging at DEBUG with the tag's switch off logs a step's start and landing but no frame lines`() = runTest {
        debuggable(true)
        NuxieLog.configure(LogLevel.DEBUG, redactSensitiveData = false)
        // Pinned rather than left to Robolectric's default answer: the switch is off.
        ShadowLog.setLoggable(TAG, Log.INFO)
        withPage { page ->
            val step = page.begin(page.toB, back = false)
            val t0 = page.frame()
            page.pump()
            page.frame()
            page.events("leave" to A_OWNER, "arrive" to B_OWNER)
            val t2 = page.frame()
            page.land(step)
            val lines = lines()
            assertTrue("Lines were logged", lines.isNotEmpty())
            val id = idOf(lines.first())
            assertEquals(listOf(
                "D step start id=$id from=a to=b back=0 frameNanos=$t0",
                "D step land id=$id frameNanos=$t2 lengthMs=33.333",
            ), lines)
        }
    }

    @Test fun `with the tag's switch on, an app logging at DEBUG gets each frame line at DEBUG with the same text`() = runTest {
        debuggable(true)
        NuxieLog.configure(LogLevel.DEBUG, redactSensitiveData = false)
        // The bench's `adb shell setprop log.tag.NuxieMagicMove VERBOSE`.
        ShadowLog.setLoggable(TAG, Log.VERBOSE)
        withPage { page ->
            val step = page.begin(page.toB, back = false)
            val t0 = page.frame()
            page.pump()
            val t1 = page.frame()
            page.events("leave" to A_OWNER, "arrive" to B_OWNER)
            val t2 = page.frame()
            page.land(step)
            val lines = stepLines()
            assertTrue("Lines were logged", lines.isNotEmpty())
            val id = idOf(lines.first())
            assertEquals(listOf(
                "D step start id=$id from=a to=b back=0 frameNanos=$t0",
                "D frame id=$id frameNanos=$t0 elapsedMs=0.0",
                "D frame id=$id frameNanos=$t1 elapsedMs=16.667",
                "D frame id=$id frameNanos=$t2 elapsedMs=16.667",
                "D step land id=$id frameNanos=$t2 lengthMs=33.333",
            ), lines)
        }
    }

    @Test fun `with the tag's switch on, each tick's present is logged with its result, stepping or polling`() = runTest {
        debuggable(true)
        NuxieLog.configure(LogLevel.DEBUG, redactSensitiveData = false)
        ShadowLog.setLoggable(TAG, Log.VERBOSE)
        withPage { page ->
            // At rest: a frame that completes at once, one left submitted, the tick that polls it, then a new step.
            val t0 = page.frame()
            page.presentation(4)
            val t1 = page.frame()
            page.presentation(1)
            val t2 = page.frame()
            val t3 = page.frame()
            val lines = lines().filter { it.startsWith("D present ") }
            val nanos = lines.map { checkNotNull(Regex(" nanos=(\\d+)$").find(it)) { "No nanos in: $it" }.groupValues[1].toLong() }
            assertEquals(listOf(
                "D present tick=step result=1 frameNanos=$t0",
                "D present tick=step result=4 frameNanos=$t1",
                "D present tick=poll result=1 frameNanos=$t2",
                "D present tick=step result=1 frameNanos=$t3",
            ), lines.map { it.substringBefore(" nanos=") })
            assertEquals("The present times run forward", nanos.sorted(), nanos)
        }
    }

    @Test fun `with the tag's switch on, the window's attach logs its present path once, before that tick's present`() = runTest {
        debuggable(true)
        NuxieLog.configure(LogLevel.DEBUG, redactSensitiveData = false)
        ShadowLog.setLoggable(TAG, Log.VERBOSE)
        withPage { page ->
            val t0 = page.frame()
            page.frame()
            page.frame()
            val lines = lines().map { it.substringBefore(" nanos=") }
            assertEquals(listOf("D attach path=swapchain", "D present tick=step result=1 frameNanos=$t0"), lines.take(2))
            assertEquals(1, lines.count { it.startsWith("D attach ") })
        }
    }

    @Test fun `a window the runtime cannot attach a swapchain to logs the CPU copy path`() = runTest {
        debuggable(true)
        NuxieLog.configure(LogLevel.DEBUG, redactSensitiveData = false)
        ShadowLog.setLoggable(TAG, Log.VERBOSE)
        // The runtime refuses the swapchain (NUX_SURFACE_ATTACHMENT_UNSUPPORTED), so each frame is copied into the window.
        withPage(rendererNative = { native ->
            object : NuxieTypedRuntimeNative by native {
                override fun attachRendererSurface(rendererHandle: Long, windowHandle: Long) = -1
                override fun copyPlayerToWindow(rendererHandle: Long, playerHandle: Long, windowHandle: Long,
                    clearColor: Int, layoutScaleFactor: Float) = 1
            }
        }) { page ->
            val t0 = page.frame()
            page.frame()
            val lines = lines().map { it.substringBefore(" nanos=") }
            assertEquals(listOf("D attach path=copy", "D present tick=step result=1 frameNanos=$t0"), lines.take(2))
            assertEquals(1, lines.count { it.startsWith("D attach ") })
        }
    }

    @Test fun `with the tag's switch off, no present is logged, even at the verbose level`() = runTest {
        debuggable(true)
        ShadowLog.setLoggable(TAG, Log.INFO)
        withPage { page ->
            page.frame()
            page.presentation(4)
            page.frame()
            page.presentation(1)
            page.frame()
            assertEquals(emptyList<String>(), lines().filter { it.contains("present") || it.contains("attach") })
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

    @Test fun `a step sent after its frame's vsync draws its first frame already moved, timed when it is shown`() = runTest {
        debuggable(true)
        withPage { page ->
            // At rest, so the clock is running. The phone's order (UNIV-6118): the step's batch is sent 5 ms after
            // the vsync of the frame that then presents it, which is shown one refresh after that vsync.
            page.frame()
            val t1 = page.frame()
            val t2 = t1 + FRAME_NANOS
            val sent = t2 + SENT_AFTER_FRAME_NANOS
            page.at(sent)
            val step = page.begin(page.toB, back = false)
            assertEquals(t2, page.frame())
            page.at(0L)
            page.pump()
            val t3 = page.frame()
            page.events("leave" to A_OWNER, "arrive" to B_OWNER)
            val t4 = page.frame()
            page.land(step)

            // The frame that presents the step first steps by zero, so the batch's phases and begin reach the
            // state machines, then by the time from the send to when that frame's picture is shown, t2 plus one
            // refresh. Each later frame steps by one refresh, on the same display-time base: no jump, no zero.
            val trace = page.trace()
            val begun = trace.indexOfFirst { it.startsWith("begin:") }
            assertTrue("The step began", begun >= 0)
            val steps = trace.drop(begun + 1).filter { it.startsWith("step:") }
            val firstFrame = (t2 + REFRESH_NANOS - sent) / 1_000_000_000.0
            // Frames come one vsync apart, so each later picture is shown one vsync after the one before.
            val refresh = (FRAME_NANOS / 1_000_000_000.0).toFloat()
            assertTrue("The first frame moves", firstFrame > 0)
            assertEquals(listOf("step:0.0", "step:${firstFrame.toFloat()}", "step:$refresh", "step:$refresh"),
                steps.take(4))
            val lines = lines()
            val id = idOf(lines.first())
            assertEquals(listOf(
                "D step start id=$id from=a to=b back=0 frameNanos=$t2",
                "V frame id=$id frameNanos=$t2 elapsedMs=11.667",
                "V frame id=$id frameNanos=$t3 elapsedMs=16.667",
                "V frame id=$id frameNanos=$t4 elapsedMs=16.667",
                "D step land id=$id frameNanos=$t4 lengthMs=33.333",
            ), lines.filter { it.contains(" id=$id ") })
        }
    }

    @Test fun `a step's first frame drawn too late for its refresh is shown a refresh later, and the next frame does not count that refresh again`() = runTest {
        debuggable(true)
        withPage { page ->
            page.frame()
            val t1 = page.frame()
            val t2 = t1 + FRAME_NANOS
            val sent = t2 + SENT_AFTER_FRAME_NANOS
            page.at(sent)
            page.begin(page.toB, back = false)
            // The frame is drawn 2 ms after its refresh (t2 plus one), so its picture can only reach the next one.
            page.at(t2 + REFRESH_NANOS + 2_000_000L)
            assertEquals(t2, page.frame())
            page.at(0L)
            // Busy past its refresh, the lane misses one vsync; the next frame is shown one refresh after the late one.
            page.skipFrame()
            val t4 = page.frame()
            val trace = page.trace()
            val steps = trace.drop(trace.indexOfFirst { it.startsWith("begin:") } + 1).filter { it.startsWith("step:") }
            val shown = t2 + 2 * REFRESH_NANOS
            val first = ((shown - sent) / 1_000_000_000.0).toFloat()
            val next = ((t4 + REFRESH_NANOS - shown) / 1_000_000_000.0).toFloat()
            assertEquals(listOf("step:0.0", "step:$first", "step:$next"), steps.take(3))
            assertEquals("One refresh, not two", REFRESH_NANOS / 1_000_000_000.0, next.toDouble(), 0.000_01)
        }
    }

    @Test fun `a change of refresh rate steps each frame by its vsync spacing, with no stall and no jump`() = runTest {
        debuggable(true)
        withPage { page ->
            page.frame()
            val t1 = page.frame()
            page.refresh(8_333_333L)
            val t2 = page.frameAt(t1 + 8_333_333L)
            page.refresh(REFRESH_NANOS)
            val t3 = page.frameAt(t2 + FRAME_NANOS)
            val steps = page.trace().filter { it.startsWith("step:") }.takeLast(2)
            assertEquals(listOf("step:${((t2 - t1) / 1_000_000_000.0).toFloat()}", "step:${((t3 - t2) / 1_000_000_000.0).toFloat()}"),
                steps)
        }
    }

    @Test fun `a step sent before the last picture is shown counts from that picture, one refresh before its own`() = runTest {
        debuggable(true)
        withPage { page ->
            page.frame()
            val t1 = page.frame()
            // Sent 5 ms after t1's vsync, before t1's picture is shown at t2: the time to t2 was already stepped.
            val step = page.begin(page.toB, back = false)
            val t2 = page.frame()
            page.pump()
            val t3 = page.frame()
            page.events("leave" to A_OWNER, "arrive" to B_OWNER)
            page.frame()
            page.land(step)

            val trace = page.trace()
            val begun = trace.indexOfFirst { it.startsWith("begin:") }
            val steps = trace.drop(begun + 1).filter { it.startsWith("step:") }
            val refresh = (FRAME_NANOS / 1_000_000_000.0).toFloat()
            assertEquals(listOf("step:0.0", "step:$refresh", "step:$refresh"), steps.take(3))
            assertTrue(t3 > t2 && t2 > t1)
        }
    }

    @Test fun `a batch that waits behind other lane work still counts that wait toward its first frame`() = runTest {
        debuggable(true)
        withPage { page ->
            page.frame()
            val t1 = page.frame()
            val t2 = t1 + FRAME_NANOS
            // Sent 2 ms after t2's vsync; the lane is busy until 12 ms after it, so it takes the batch 10 ms late.
            // That frame's picture is shown at t2 plus one refresh, and the step counts from the send.
            val sent = t2 + 2_000_000L
            val before = page.trace().size
            val write = page.sendWhileLaneBusy(sent = sent, taken = t2 + 12_000_000L)
            assertEquals(t2, page.frame())
            val steps = page.trace().drop(before).filter { it.startsWith("step:") }
            assertEquals(listOf("step:0.0", "step:${((t2 + REFRESH_NANOS - sent) / 1_000_000_000.0).toFloat()}"), steps)
            page.pump()
            assertTrue("The batch's frame was presented", write.await())
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

    /** The step's own lines: with the switch on, the attach and present lines print beside them. */
    private fun stepLines(): List<String> = lines().filterNot { it.startsWith("D present ") || it.startsWith("D attach ") }

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
        /** The next frame's time. The surface's own clock reads 5 ms after the last frame (see [withPage]). */
        private val clock: java.util.concurrent.atomic.AtomicLong,
        /** The surface's own clock, when a test sets it. */
        private val now: java.util.concurrent.atomic.AtomicLong,
        /** The display's refresh interval, as the surface reads it. */
        private val refreshNanos: java.util.concurrent.atomic.AtomicLong,
    ) {
        /** Sets the surface's own clock; 0 goes back to 5 ms after the last frame. */
        fun at(nanos: Long) = now.set(nanos)

        private val transition = Json.parseToJsonElement("""{"type":"magic-move","transitionId":"pair"}""").jsonObject
        val toB = source.copy(screenId = "b", artboardName = "B", pageHistory = listOf("a", "b"), transition = transition,
            screenLifecycle = ExperienceScreenLifecycle(), retainedViewModel = AtomicReference())
        val toA = source.copy(pageHistory = listOf("a"), transition = transition)

        /** One Choreographer frame at the next 60 Hz frame time, run to its end. Returns its frame time. */
        fun frame(): Long {
            val time = clock.get()
            surface.doFrame(time)
            lane.blockingCall {}
            clock.addAndGet(16_666_667L)
            return time
        }

        /** Sets the display's refresh interval, as a change of refresh rate does. */
        fun refresh(nanos: Long) = refreshNanos.set(nanos)

        /** One frame at [time]; the next [frame] follows it by one 60 Hz frame. */
        fun frameAt(time: Long): Long { clock.set(time); return frame() }

        /** A vsync whose frame callback never runs, as when the lane is still busy with the frame before. */
        fun skipFrame() { clock.addAndGet(16_666_667L) }

        /** Every native call the fake runtime recorded, in order. */
        fun trace(): List<String> = lane.blockingCall { native.trace.toList() }

        /** Sends a transition batch at [sent] while the lane is busy with other work, which ends at [taken]. */
        fun sendWhileLaneBusy(sent: Long, taken: Long): Deferred<Boolean> {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            lane.enqueue { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            now.set(sent)
            val write = scope.async { surface.applyLayerTransition(250) { } }
            scope.runCurrent()
            now.set(taken)
            release.countDown()
            lane.blockingCall {}
            return write
        }

        /** The code the fake renderer's next presents return: 4 leaves the frame submitted, 1 completes it. */
        fun presentation(code: Int) = lane.blockingCall { native.presentation = code }

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

    private suspend fun TestScope.withPage(
        rendererNative: (ExperienceInteractivePageTest.RecordingNative) -> NuxieTypedRuntimeNative = { it },
        body: suspend (Page) -> Unit,
    ) {
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
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000_000L)
        // A test that sets [now] reads that; otherwise a step's batch is sent between frames: 5 ms after the last
        // frame, 11.667 ms before the next.
        val now = java.util.concurrent.atomic.AtomicLong(0L)
        val refresh = java.util.concurrent.atomic.AtomicLong(REFRESH_NANOS)
        val surface = ExperienceSurfaceHost(app, lane, runtime = NuxieRuntime(native), usesSystemFrameCallbacks = false,
            nanoTime = { now.get().takeIf { it != 0L } ?: (clock.get() - FRAME_NANOS + SENT_AFTER_FRAME_NANOS) },
            refreshNanos = { refresh.get() })
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
                set(surface, "renderer", NuxieAndroidVulkanRenderer(900, rendererNative(native)))
                set(surface, "window", NuxieRuntimeWindow(901, native))
                set(surface, "surfaceLayout", layout); set(surface, "appliedLayout", layout)
                set(surface, "attached", true); set(surface, "running", true)
                set(surface, "firstFramePresented", true); set(surface, "firstFrameComposed", true)
            }
            mounted = ExperienceMountedScreen(activity.get(), source, listener, { throw AssertionError(it) })
            set(mounted, "surface", surface)
            body(Page(this, surface, native, lane, mounted, listener, source, clock, now, refresh))
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
        const val FRAME_NANOS = 16_666_667L
        /** When the harness sends a step's batch: this long after the frame before it. */
        const val SENT_AFTER_FRAME_NANOS = 5_000_000L
        /** The test display's refresh interval (60 Hz), as the surface reads it. */
        const val REFRESH_NANOS = 16_666_666L

        fun <T> NuxieRuntimeLane.blockingCall(work: () -> T): T = runBlocking { call(work) }
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }
}
