package ai.nuxie.sdk.features

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureRecoveryWorkerTest {
    @Test
    fun retriesBackOffWithoutNudgesPostponingTheExistingAttempt() = runTest {
        val attempts = mutableListOf<Long>()
        val worker = FeatureRecoveryWorker(this, { testScheduler.currentTime }, { 0L }) {
            attempts += testScheduler.currentTime
        }
        worker.start()
        worker.request()
        runCurrent()
        repeat(9) {
            advanceTimeBy(100)
            worker.request()
            runCurrent()
        }
        assertEquals(emptyList<Long>(), attempts)
        advanceTimeBy(100)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf(1_000L, 3_000L, 7_000L), attempts)
        advanceTimeBy(176_000)
        runCurrent()
        assertEquals(listOf(1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 63_000L, 123_000L, 183_000L), attempts)
        worker.close()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(8, attempts.size)
    }

    @Test
    fun newEligibleCommandWakesAnExistingLongServerCooldown() = runTest {
        var dueAt: Long? = 120_000
        val attempts = mutableListOf<Long>()
        val worker = FeatureRecoveryWorker(this, { testScheduler.currentTime }, { dueAt }) {
            attempts += testScheduler.currentTime
            dueAt = null
        }
        worker.start()
        worker.request()
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(emptyList<Long>(), attempts)
        dueAt = 0
        worker.request()
        runCurrent()
        assertEquals(listOf(2_000L), attempts)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, attempts.size)
        worker.close()
    }

    @Test
    fun sharedCooldownsGovernAutomaticRetryAndClosePreventsRestart() {
        FixtureRunner.run("features/command-recovery.json", "features/command-recovery") { vector -> runTest {
            val dueAt = vector.body.getValue("retryAfterMillis").jsonPrimitive.long
            val expected = vector.body.getValue("firstRetryDelayMillis").jsonPrimitive.long
            val attempts = mutableListOf<Long>()
            val worker = FeatureRecoveryWorker(this, { testScheduler.currentTime }, { dueAt }) {
                attempts += testScheduler.currentTime
            }
            worker.start()
            worker.request()
            runCurrent()
            advanceTimeBy(expected - 1)
            runCurrent()
            assertEquals(emptyList<Long>(), attempts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(expected), attempts)
            worker.close()
            worker.start()
            worker.request()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, attempts.size)
        } }
    }
}
