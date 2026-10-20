package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperienceLayerHostContract.Style
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class ExperienceLayerStepTest {
    private val plan = ExperienceScreenTransitionPlan.Custom("pair", 400, true, "leave", "arrive")
    private class Trace : ExperienceLayerStep.Operations {
        val events = mutableListOf<String>()
        var enabled = true
        override fun input(enabled: Boolean) { this.enabled = enabled; events += "input:$enabled" }
        override suspend fun prepare() { events += "prepare"; assertFalse(enabled) }
        override suspend fun register(screenId: String, event: String): Deferred<Unit> {
            events += "register:$screenId:$event"
            return CompletableDeferred(Unit)
        }
        override suspend fun insert(index: Int) { events += "insert:$index"; assertFalse(enabled) }
        override suspend fun phases(values: List<Pair<String, ExperienceScreenLifecycle.Phase>>, transition: String) {
            events += values.joinToString(",") { "${it.first}:${it.second.wireValue}" } + ":$transition"
            assertFalse(enabled)
        }
        override suspend fun present() { events += "present" }
        override suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) { events += "wait:${completions.size}:$milliseconds" }
        override suspend fun remove() { events += "remove" }
        override suspend fun hidden() { events += "hidden" }
        override suspend fun active() { events += "active" }
    }
    private suspend fun perform(trace: Trace, style: Style, back: Boolean = false,
        plan: ExperienceScreenTransitionPlan.Custom? = this.plan, reduced: Boolean = false, target: String = "b") =
        ExperienceLayerStep.perform("a", target, style, back, plan, ExperienceLayerStep.Exit("declared", 350), reduced, trace)

    @Test fun `together styles register both waiters then batch phases before waiting and removal`() = runBlocking {
        for (style in listOf(Style.MAGIC_MOVE, Style.PUSH, Style.FADE)) {
            val trace = Trace()
            perform(trace, style)
            assertEquals(listOf("input:false", "prepare", "register:a:leave", "register:b:arrive", "insert:1",
                "a:exiting,b:entering:pair", "present", "wait:2:650", "remove", "a:hidden,b:active:", "hidden", "active", "input:true"), trace.events)
        }
    }
    @Test fun `published row order wins over styles in forward reverse and cut plans`() = runBlocking {
        for (style in listOf(Style.MAGIC_MOVE, Style.PUSH, Style.FADE, Style.NONE)) for (back in listOf(false, true)) {
            for (onTop in listOf(false, true)) {
                val trace = Trace()
                perform(trace, style, back, plan.copy(incomingOnTop = onTop))
                assertEquals("insert:${if (onTop) 1 else 0}", trace.events.single { it.startsWith("insert:") })
            }
        }
    }
    @Test fun `published cut waits for its own halves sequentially`() = runBlocking {
        val trace = Trace()
        perform(trace, Style.NONE)
        assertEquals(listOf("input:false", "prepare", "register:a:leave", "a:exiting:pair", "present", "wait:1:650",
            "insert:1", "remove", "register:b:arrive", "b:entering:pair", "present", "wait:1:650",
            "a:hidden,b:active:", "hidden", "active", "input:true"), trace.events)
        assertFalse(trace.events.any { "declared" in it })
    }
    @Test fun `unresolved styles use declared exit empty transition and only push back inserts below`() = runBlocking {
        for (style in listOf(Style.MAGIC_MOVE, Style.PUSH, Style.FADE, Style.NONE)) for (back in listOf(false, true)) {
            val trace = Trace()
            perform(trace, style, back, plan = null)
            assertEquals("insert:${if (style == Style.PUSH && back) 0 else 1}", trace.events.single { it.startsWith("insert:") })
            assertTrue(trace.events.indexOf("wait:1:350") < trace.events.indexOf("remove"))
            assertTrue("a:exiting:" in trace.events)
            assertTrue("b:entering:" in trace.events)
            assertEquals(1, trace.events.count { it.startsWith("register:") })
        }
    }
    @Test fun `reduced motion preserves phase order without a presentation or wait`() = runBlocking {
        for (style in listOf(Style.MAGIC_MOVE, Style.PUSH, Style.NONE)) {
            val trace = Trace()
            perform(trace, style, reduced = true)
            assertEquals(listOf("input:false", "prepare", "a:exiting:pair", "insert:1", "remove", "b:entering:pair",
                "a:hidden,b:active:", "hidden", "active", "input:true"), trace.events)
        }
    }
    @Test fun `self navigation performs no preparation and does not block input`() = runBlocking {
        val trace = Trace()
        perform(trace, Style.PUSH, target = "a")
        assertTrue(trace.events.isEmpty())
    }
    @Test fun `watchdog expires and both callbacks follow removal`() = runBlocking {
        val trace = Trace()
        val operations = object : ExperienceLayerStep.Operations by trace {
            override suspend fun register(screenId: String, event: String) = CompletableDeferred<Unit>()
            override suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) {
                super<ExperienceLayerStep.Operations>.wait(completions, 1)
            }
        }
        ExperienceLayerStep.perform("a", "b", Style.MAGIC_MOVE, false, plan,
            ExperienceLayerStep.Exit(null, null), false, operations)
        assertTrue(trace.events.indexOf("remove") < trace.events.indexOf("hidden"))
        assertTrue(trace.enabled)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `unpresented halves expire without a second completion timer and restore input`() = kotlinx.coroutines.test.runTest {
        ai.nuxie.sdk.logging.NuxieLog.configure(ai.nuxie.sdk.LogLevel.WARN)
        for (style in listOf(Style.MAGIC_MOVE, Style.NONE)) {
            org.robolectric.shadows.ShadowLog.clear()
            val trace = Trace()
            var presentations = 0
            val operations = object : ExperienceLayerStep.Operations by trace {
                override suspend fun present() { presentations++; kotlinx.coroutines.awaitCancellation() }
                override suspend fun wait(completions: List<Deferred<Unit>>, milliseconds: Long) {
                    fail("An expired presentation has already consumed this half's watchdog")
                }
            }
            val step = launch {
                ExperienceLayerStep.perform("a", "b", style, false, plan,
                    ExperienceLayerStep.Exit(null, null), false, operations)
            }
            testScheduler.advanceTimeBy(plan.watchdogMs * 2 + 1); testScheduler.runCurrent()
            try {
                assertTrue("Starved presentation must release navigation", step.isCompleted)
                assertTrue(trace.enabled)
                assertTrue("active" in trace.events)
                assertEquals(if (style == Style.NONE) 2 else 1, presentations)
                assertEquals("One presentation warning per navigation", 1,
                    org.robolectric.shadows.ShadowLog.getLogsForTag("ExperienceLayerStep").count {
                        it.msg.contains("presentation watchdog expired")
                    })
            } finally { step.cancel(); step.join() }
        }
    }
    @Test fun `layer history retains each forward pair truncates revisits and resolves skipping back`() {
        val push = kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"push","transitionId":"pair"}""") as kotlinx.serialization.json.JsonObject
        val history = ExperienceLayerHistory(listOf("a")).visit("b", push).visit("c", null)
        assertEquals(listOf("a", "b", "c"), history.screens)
        assertEquals(listOf("a", "b"), history.visit("b", null).screens)
        val descriptor = kotlinx.serialization.json.Json.parseToJsonElement("""{"render":{"transitions":[]}}""") as kotlinx.serialization.json.JsonObject
        assertEquals(push, history.visit("b", null).backTransition(1, "a", descriptor))
        assertNull(history.backTransition(2, "a", descriptor))
    }
}
