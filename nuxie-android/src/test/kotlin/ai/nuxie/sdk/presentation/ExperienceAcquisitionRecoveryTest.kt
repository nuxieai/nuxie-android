package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExperienceAcquisitionRecoveryTest {
    private val fixture get() = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
        .resolve("journeys/planes/presentation-acquisition-recovery-android.json").readText()).jsonObject

    @Test fun `failure waits for one retry and transfers only the successful resource`() = runTest {
        val states = mutableListOf<AcquisitionProgress>()
        val recovery = ExperienceAcquisitionRecovery<Int>(states::add, { testScheduler.currentTime })
        var attempts = 0
        val result = async {
            recovery.acquire(prepare = { if (++attempts == 1) error("transport failed"); attempts },
                release = { fail("Successful resource belongs to caller") }, recoverable = { true }, onFailure = {})
        }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(AcquisitionProgress.Phase.FAILED, states.last().phase)
        val generation = states.last().generation
        assertTrue(recovery.retry(generation))
        assertFalse(recovery.retry(generation))
        assertEquals(2, result.await())
        assertEquals(fixture.getValue("failurePhases").jsonArray.map { it.jsonPrimitive.content }, states.map { it.phase.name })
        assertFalse(recovery.retry(states.last().generation))
    }

    @Test fun `retry drains a late lease before another attempt and rejects stale retry taps`() = runTest {
        val states = mutableListOf<AcquisitionProgress>()
        val order = mutableListOf<String>()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var attempts = 0
        val recovery = ExperienceAcquisitionRecovery<Int>(states::add, { testScheduler.currentTime })
        val result = async {
            recovery.acquire(prepare = {
                val attempt = ++attempts
                order += "start:$attempt"
                if (attempt == 1) withContext(NonCancellable) { first.await() } else second.await()
                attempt
            }, release = { order += "close:$it" }, recoverable = { true }, onFailure = { throw AssertionError(it) })
        }
        runCurrent()
        val oldGeneration = states.last().generation
        assertTrue(recovery.retry(oldGeneration))
        assertFalse(recovery.retry(oldGeneration))
        runCurrent()
        assertEquals(listOf("start:1"), order)
        assertFalse(result.isCompleted)
        first.complete(Unit)
        runCurrent()
        assertEquals(fixture.getValue("drainOrder").jsonArray.map { it.jsonPrimitive.content }, order)
        assertFalse(recovery.retry(oldGeneration))
        assertEquals(AcquisitionProgress.Phase.LOADING, states.last().phase)
        second.complete(Unit)
        assertEquals(2, result.await())
        assertEquals(1, order.count { it == "close:1" })
        assertFalse(order.contains("close:2"))
    }

    @Test fun `owner cancellation drains late resource and closes retry admission`() = runTest {
        val states = mutableListOf<AcquisitionProgress>()
        val release = CompletableDeferred<Unit>()
        var closed = 0
        val recovery = ExperienceAcquisitionRecovery<Int>(states::add, { testScheduler.currentTime })
        val result = async {
            recovery.acquire(prepare = { withContext(NonCancellable) { release.await() }; 1 },
                release = { closed++ }, recoverable = { true }, onFailure = { fail("Owner cancellation is not recoverable") })
        }
        runCurrent()
        result.cancel()
        runCurrent()
        assertFalse(result.isCompleted)
        assertFalse(recovery.retry(states.last().generation))
        release.complete(Unit)
        result.join()
        assertEquals(1, closed)
        assertTrue(result.isCancelled)
    }
    @Test fun `failed attempt releases a cancelled result before waiting for user recovery`() = runTest {
        val states = mutableListOf<AcquisitionProgress>()
        var closed = 0
        val recovery = ExperienceAcquisitionRecovery<Int>(states::add, { testScheduler.currentTime })
        val result = async {
            recovery.acquire(prepare = {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                1
            }, release = { closed++ }, recoverable = { true }, onFailure = {})
        }
        runCurrent()
        assertEquals(AcquisitionProgress.Phase.FAILED, states.last().phase)
        assertEquals(1, closed)
        assertFalse(result.isCompleted)
        result.cancel()
        result.join()
        assertEquals(1, closed)
    }

}
