package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExperienceCustomTransitionTest {
    private val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
        .resolve("journeys/planes/screen-transition-plan-android.json").readText()).jsonObject
        .getValue("customExecution").jsonObject
    private val plan = ExperienceScreenTransitionPlan.Custom(
        contract.getValue("transitionId").jsonPrimitive.content,
        contract.getValue("durationMs").jsonPrimitive.long, true,
        contract.getValue("outgoingEvent").jsonPrimitive.content,
        contract.getValue("incomingEvent").jsonPrimitive.content,
    )

    @Test fun `both endpoints are registered before synchronous native completion`() = runTest {
        val outgoing = ExperienceScreenExitHandshake()
        val incoming = ExperienceScreenExitHandshake()
        outgoing.performWith(incoming, plan) {
            incoming.receive(plan.incomingCompletionEvent)
            outgoing.receive(plan.outgoingCompletionEvent)
        }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `one endpoint cannot complete the transition but the authored watchdog can`() = runTest {
        val outgoing = ExperienceScreenExitHandshake()
        val incoming = ExperienceScreenExitHandshake()
        val completed = async { outgoing.performWith(incoming, plan) {} }
        runCurrent()
        incoming.receive(plan.incomingCompletionEvent)
        outgoing.receive("unrelated")
        val watchdog = contract.getValue("watchdogMs").jsonPrimitive.long
        advanceTimeBy(watchdog - 1)
        runCurrent()
        assertFalse(completed.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        completed.await()
        assertEquals(watchdog, testScheduler.currentTime)
    }

    @Test fun `either endpoint closing interrupts the pair without waiting for its sibling`() = runTest {
        for (closeIncoming in listOf(false, true)) {
            val outgoing = ExperienceScreenExitHandshake()
            val incoming = ExperienceScreenExitHandshake()
            val completed = async { outgoing.performWith(incoming, plan) {} }
            runCurrent()
            if (closeIncoming) incoming.close() else outgoing.close()
            runCurrent()
            assertTrue(completed.isCancelled)
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test fun `cancelled pair cannot consume late events on a later transition`() = runTest {
        val outgoing = ExperienceScreenExitHandshake()
        val incoming = ExperienceScreenExitHandshake()
        val first = async { outgoing.performWith(incoming, plan) {} }
        runCurrent()
        first.cancelAndJoin()
        outgoing.receive(plan.outgoingCompletionEvent)
        incoming.receive(plan.incomingCompletionEvent)
        val second = async { outgoing.performWith(incoming, plan) {} }
        runCurrent()
        assertFalse(second.isCompleted)
        outgoing.receive(plan.outgoingCompletionEvent)
        runCurrent()
        assertFalse(second.isCompleted)
        incoming.receive(plan.incomingCompletionEvent)
        second.await()
    }

    @Test fun `prepared custom entry preserves its existing appearance`() {
        val lifecycle = ExperienceScreenLifecycle()
        lifecycle.move(ExperienceScreenLifecycle.Phase.ENTERING)
        val values = lifecycle.beginPreparedTransition(plan.id)
        assertEquals(contract.getValue("preparedAppearances").jsonPrimitive.long.toULong(), lifecycle.appearances)
        assertEquals(NuxieViewModelScalarValue.StringValue(plan.id), values["screen/transition"])
        assertEquals(ExperienceScreenLifecycle.Phase.ENTERING, lifecycle.phase)
        lifecycle.move(ExperienceScreenLifecycle.Phase.ACTIVE)
        assertEquals(NuxieViewModelScalarValue.StringValue(""), lifecycle.snapshot()["screen/transition"])
    }
}
