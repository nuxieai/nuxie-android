package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExperiencePresentationRetirementTest {
    @Test fun `noncancellable rollback stops waiting on a stalled lane without freeing its owner`() = runTest {
        val lane = NuxieRuntimeLane()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val rollback = async {
            runCatching {
                withContext(NonCancellable) {
                    awaitPresentationRetirement {
                        lane.call {
                            entered.countDown()
                            check(unblock.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
            }.exceptionOrNull()
        }
        try {
            runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            lane.shutdown { released.set(true) }
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue(rollback.isCompleted)
            assertEquals(ExperiencePresentationException.Reason.HOST_FAILED,
                (rollback.await() as ExperiencePresentationException).reason)
            assertFalse("A cancelled waiter cannot retire native ownership", released.get())
        } finally {
            unblock.countDown()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
        assertTrue(released.get())
    }

    @Test fun `caller cancellation remains cancellation rather than a retirement failure`() = runTest {
        val waiter = async { awaitPresentationRetirement { kotlinx.coroutines.awaitCancellation() } }
        runCurrent()
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
    }
}
