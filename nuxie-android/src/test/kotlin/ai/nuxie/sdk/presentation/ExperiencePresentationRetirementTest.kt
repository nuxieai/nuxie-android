package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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
    @Test fun `retirement stops waiting for a blocked dispatch without running late cleanup`() = runTest {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val cleaned = AtomicBoolean(false)
        executor.execute { entered.countDown(); check(unblock.await(30, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val retirement = async {
            runCatching {
                withContext(NonCancellable) {
                    awaitPresentationRetirement { withContext(dispatcher) { cleaned.set(true) } }
                }
            }.exceptionOrNull()
        }
        try {
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue("A blocked dispatch must not extend the retirement deadline", retirement.isCompleted)
            val failure = retirement.await() as ExperiencePresentationException
            assertEquals(ExperiencePresentationException.Reason.HOST_FAILED, failure.reason)
            assertTrue(failure.cause is kotlinx.coroutines.TimeoutCancellationException)
            assertFalse(cleaned.get())
        } finally {
            unblock.countDown()
            retirement.join()
            dispatcher.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertFalse("Cancelled work must not run after the dispatcher recovers", cleaned.get())
    }

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

    @Test fun `cleanup timeout preserves in-flight failures and fails normal completion`() = runTest {
        val lane = NuxieRuntimeLane()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        lane.enqueue { entered.countDown(); check(unblock.await(30, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            val originals = listOf(null, kotlinx.coroutines.CancellationException("page step cancelled"),
                ExperiencePresentationException(ExperiencePresentationException.Reason.SUPERSEDED, "withdrawn"),
                ExperiencePresentationException(ExperiencePresentationException.Reason.LAYER_DISMISSED, "dismissed"),
                IllegalStateException("step failed"))
            for (original in originals) {
                var blockCompleted = false
                val result = async {
                    runCatching {
                        var failure: Throwable? = null
                        try {
                            if (original != null) throw original
                            blockCompleted = true
                        } catch (error: Throwable) {
                            failure = error
                            throw error
                        } finally {
                            withContext(NonCancellable) {
                                runPresentationCleanup(failure) {
                                    awaitPresentationRetirement { lane.call { } }
                                }
                            }
                        }
                    }.exceptionOrNull()
                }
                runCurrent()
                advanceTimeBy(5_001)
                runCurrent()
                assertTrue(result.isCompleted)
                val actual = result.await()
                assertEquals(original == null, blockCompleted)
                val cleanupFailure = if (original == null) actual else {
                    org.junit.Assert.assertSame(original, actual)
                    actual!!.suppressed.single()
                }
                assertEquals(ExperiencePresentationException.Reason.HOST_FAILED,
                    (cleanupFailure as ExperiencePresentationException).reason)
                assertTrue(cleanupFailure.cause is kotlinx.coroutines.TimeoutCancellationException)
            }
        } finally {
            unblock.countDown()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test fun `caller cancellation remains cancellation rather than a retirement failure`() = runTest {
        val waiter = async { awaitPresentationRetirement { kotlinx.coroutines.awaitCancellation() } }
        runCurrent()
        waiter.cancelAndJoin()
        assertTrue(waiter.isCancelled)
    }
}
