package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperiencePageHostContract.Style
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExperiencePageStepTest {
    private val plan = ExperienceScreenTransitionPlan.Custom("pair", 400, false, "leave", "arrive")
    private class Trace : ExperiencePageStep.Operations {
        val events = mutableListOf<String>()
        var enabled = true
        override fun input(enabled: Boolean) { this.enabled = enabled; events += "input:$enabled" }
        override suspend fun prepare() { events += "prepare"; assertFalse(enabled) }
        override suspend fun register(screenId: String, event: String): Deferred<Unit> {
            events += "register:$screenId:$event"; return CompletableDeferred(Unit)
        }
        override suspend fun batch(present: Boolean, watchdogMs: Long, writes: suspend () -> Unit) {
            events += "batch"; writes(); events += "commit:$present"
        }
        override suspend fun takeIncoming() { events += "take"; assertFalse(enabled) }
        override suspend fun phases(values: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String) {
            events += values.joinToString(",") { "${it.first}:${it.second.wireValue}" } + ":$transition"
            assertFalse(enabled)
        }
        override suspend fun begin(transition: String?) { events += "begin:$transition" }
        override suspend fun end(transition: String?) { events += "end:$transition" }
        override suspend fun retire() { events += "retire" }
        override suspend fun present() { events += "present" }
        override suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) { events += "wait:${completions.size}:$milliseconds" }
        override suspend fun hidden() { events += "hidden" }
        override suspend fun active() { events += "active" }
    }
    private suspend fun perform(trace: Trace, style: Style, plan: ExperienceScreenTransitionPlan.Custom? = this.plan,
        reduced: Boolean = false, target: String = "b", exit: ExperiencePageStep.Exit = ExperiencePageStep.Exit("declared", 350)) =
        ExperiencePageStep.perform("a", target, style, false, plan, exit, reduced, trace)

    @Test fun `together step begins in phase batch and ends before outgoing retirement`() = runBlocking {
        for (style in listOf(Style.MAGIC_MOVE, Style.FADE)) {
            val trace = Trace(); perform(trace, style)
            assertEquals(listOf("input:false", "prepare", "batch", "take", "register:a:leave", "register:b:arrive",
                "a:exiting,b:entering:pair", "begin:pair", "commit:true", "present", "wait:2:650", "batch",
                "end:pair", "retire", "a:hidden,b:active:", "commit:false", "hidden", "active", "input:true"), trace.events)
        }
    }
    @Test fun `cut with transition waits for each published half and swaps after begin`() = runBlocking {
        val trace = Trace(); perform(trace, Style.NONE)
        assertEquals(listOf("input:false", "prepare", "register:a:leave", "batch", "a:exiting:pair", "commit:true",
            "present", "wait:1:650", "batch", "take", "register:b:arrive", "b:entering:pair", "begin:pair", "retire",
            "a:hidden:", "commit:true", "present", "wait:1:650", "batch", "end:pair", "b:active:", "commit:false",
            "hidden", "active", "input:true"), trace.events)
    }
    @Test fun `cut without transition uses declared exit and null step name with no arrival wait`() = runBlocking {
        val trace = Trace(); perform(trace, Style.NONE, plan = null)
        assertTrue("register:a:declared" in trace.events)
        assertTrue("a:exiting:" in trace.events)
        assertTrue("begin:null" in trace.events); assertTrue("end:null" in trace.events)
        assertEquals(1, trace.events.count { it.startsWith("wait:") })
        assertTrue(trace.events.indexOf("begin:null") < trace.events.indexOf("retire"))
        assertTrue(trace.events.indexOf("retire") < trace.events.indexOf("end:null"))
    }
    @Test fun `cut without declared halves swaps without waiting for a frame`() = runBlocking {
        val trace = Trace(); perform(trace, Style.NONE, plan = null, exit = ExperiencePageStep.Exit(null, null))
        assertFalse(trace.events.any { it == "present" || it.startsWith("wait:") || it.startsWith("register:") })
        assertTrue("begin:null" in trace.events); assertTrue("end:null" in trace.events)
    }
    @Test fun `reduced motion calls begin and end in one batch and never waits`() = runBlocking {
        for (style in listOf(Style.MAGIC_MOVE, Style.FADE, Style.NONE)) {
            val trace = Trace(); perform(trace, style, reduced = true)
            assertEquals(listOf("input:false", "prepare", "batch", "take", "a:exiting:pair", "b:entering:pair",
                "begin:pair", "end:pair", "retire", "a:hidden,b:active:", "commit:false", "hidden", "active", "input:true"), trace.events)
        }
    }
    @Test fun `self navigation has no step or phase change`() = runBlocking {
        val trace = Trace(); perform(trace, Style.MAGIC_MOVE, target = "a"); assertTrue(trace.events.isEmpty())
    }
    @Test fun `completion watchdog starts after the frame carrying the batch is presented`() = runTest {
        val trace = Trace()
        val start = testScheduler.currentTime
        val operations = object : ExperiencePageStep.Operations by trace {
            override suspend fun present() { delay(200) }
            override suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) {
                assertEquals(200L, testScheduler.currentTime - start)
                assertEquals(650L, milliseconds)
                super<ExperiencePageStep.Operations>.wait(listOf(CompletableDeferred()), milliseconds)
            }
        }
        ExperiencePageStep.perform("a", "b", Style.MAGIC_MOVE, false, plan, ExperiencePageStep.Exit(null, null), false, operations)
        assertEquals(850L, testScheduler.currentTime - start)
        assertTrue(trace.events.indexOf("end:pair") < trace.events.indexOf("retire"))
    }
    @Test fun `unpresented halves expire without another completion timer and restore input`() = runTest {
        val trace = Trace()
        val operations = object : ExperiencePageStep.Operations by trace {
            override suspend fun present() { awaitCancellation() }
            override suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) { fail("Already expired") }
        }
        ExperiencePageStep.perform("a", "b", Style.MAGIC_MOVE, false, plan, ExperiencePageStep.Exit(null, null), false, operations)
        assertEquals(650L, testScheduler.currentTime)
        assertTrue(trace.enabled); assertTrue("active" in trace.events)
    }
    @Test fun `reverse id is shared by lifecycle phases and both live step calls`() = runBlocking {
        val trace = Trace(); perform(trace, Style.MAGIC_MOVE, plan.copy(id = "pair.reverse"))
        assertTrue("a:exiting,b:entering:pair.reverse" in trace.events)
        assertTrue("begin:pair.reverse" in trace.events)
        assertTrue("end:pair.reverse" in trace.events)
    }

    @Test fun `skipping Back selects only the marked ordered pair and otherwise cuts`() {
        val history = ExperiencePageHistory(listOf("a")).visit("b", null).visit("c", null)
        fun descriptor(source: String, destination: String, marked: Boolean) = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"render":{"transitions":[{"id":"pair","kind":"page","sourceScreenId":"$source",
                "destinationScreenId":"$destination","durationMs":100,"defaultPair":$marked,
                "source":{"completeEventName":"leave"},"destination":{"completeEventName":"enter"},
                "reverse":{"source":{"completeEventName":"leave.reverse"},"destination":{"completeEventName":"enter.reverse"}}}]}}""") as kotlinx.serialization.json.JsonObject
        assertNull(history.backTransition(2, "a", descriptor("a", "c", true)))
        assertNull(history.backTransition(2, "a", descriptor("c", "a", false)))
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"magic-move","transitionId":"pair"}"""),
            history.backTransition(2, "a", descriptor("c", "a", true)))
    }

    @Test fun `each visit records the forward reference and Back truncates only popped entries`() {
        val forward = kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"fade","transitionId":"pair"}""") as kotlinx.serialization.json.JsonObject
        val history = ExperiencePageHistory(listOf("a")).visit("b", forward).visit("a", null)
        assertEquals(listOf("a", "b", "a"), history.screens)
        assertEquals(listOf("a", "b"), history.popping(1).screens)
        val descriptor = kotlinx.serialization.json.Json.parseToJsonElement("""{"render":{"transitions":[]}}""") as kotlinx.serialization.json.JsonObject
        assertEquals(forward, history.popping(1).backTransition(1, "a", descriptor))
        assertNull(history.backTransition(2, "a", descriptor))
    }
}
