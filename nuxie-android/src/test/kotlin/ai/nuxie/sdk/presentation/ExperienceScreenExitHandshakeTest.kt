package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExperienceScreenExitHandshakeTest {
    private val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
        .resolve("journeys/planes/screen-exit-handshake.json").readText()).jsonObject
    private val declaration = contract.getValue("exit").jsonObject
    private val event = declaration.getValue("completeEventName").jsonPrimitive.content

    @Test fun `waiter observes completion emitted synchronously with phase write`() = runTest {
        val handshake = ExperienceScreenExitHandshake()
        handshake.perform(declaration, false) { handshake.receive(event) }
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `unrelated events do not bypass authored watchdog`() = runTest {
        val handshake = ExperienceScreenExitHandshake()
        val completion = async { handshake.perform(declaration, false) {} }
        runCurrent()
        handshake.receive(contract.getValue("unrelatedEvent").jsonPrimitive.content)
        val watchdog = contract.getValue("watchdogMilliseconds").jsonPrimitive.long
        advanceTimeBy(watchdog - 1)
        runCurrent()
        assertFalse(completion.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        completion.await()
        assertEquals(watchdog, testScheduler.currentTime)
    }

    @Test fun `reduced motion still writes exit but does not wait`() = runTest {
        var writes = 0
        ExperienceScreenExitHandshake().perform(declaration, true) { writes++ }
        assertEquals(1, writes)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `cancelled waiter cannot leak a late event into a later exit`() = runTest {
        val handshake = ExperienceScreenExitHandshake()
        val first = async { handshake.perform(declaration, false) {} }
        runCurrent()
        first.cancelAndJoin()
        handshake.receive(event)
        val second = async { handshake.perform(declaration, false) {} }
        runCurrent()
        assertFalse(second.isCompleted)
        handshake.receive(event)
        second.await()
    }

    @Test fun `screen teardown cancels pending exit rather than reporting success`() = runTest {
        val handshake = ExperienceScreenExitHandshake()
        val completion = async { handshake.perform(declaration, false) {} }
        runCurrent()
        handshake.close()
        completion.join()
        assertTrue(completion.isCancelled)
    }
}
