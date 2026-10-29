package ai.nuxie.sdk.runtime

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lifecycle guarantees shared by the native executor and JVM test lane. */
abstract class RuntimeLaneContract {
    internal abstract fun createLane(): NuxieRuntimeLane

    @Test
    fun callsShareOneDaemonOwnerAndAnExceptionDoesNotKillTheQueue() = kotlinx.coroutines.runBlocking {
        val lane = createLane()
        try {
            val caller = Thread.currentThread().id
            val first = lane.call { Thread.currentThread().id to Thread.currentThread().isDaemon }
            val failure = runCatching { lane.call<Int> { throw IllegalArgumentException("job failed") } }
            assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
            val second = lane.call { Thread.currentThread().id }
            assertEquals(first.first, second)
            assertTrue(first.first != caller && first.second)
        } finally {
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(2_000))
        }
    }

    @Test
    fun aCallbackCanEnqueueAndCloseItsOwnOwnerWithoutWaitingOnItself() {
        val lane = createLane()
        val ran = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val selfWait = java.util.concurrent.atomic.AtomicBoolean(true)
        lane.enqueue {
            lane.enqueue { ran.countDown() }
            lane.shutdown()
            selfWait.set(lane.awaitQuiescence(2_000))
            closed.countDown()
        }
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertFalse(selfWait.get())
        assertTrue(lane.awaitQuiescence(2_000))
        assertEquals(0L, ran.count)
    }

    @Test
    fun independentCleanupOwnersAllRunAfterNativeWorkIncludingLateRegistration() {
        val lane = createLane()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        lane.enqueue {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            order += "native-work"
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        lane.shutdown { order += "presentation-owner" }
        lane.afterTermination { order += "texture-owner" }
        try {
            assertTrue(order.isEmpty())
        } finally {
            release.countDown()
        }
        assertTrue(lane.awaitQuiescence(2_000))
        lane.afterTermination { order += "late-owner" }
        assertEquals(listOf("native-work", "presentation-owner", "texture-owner", "late-owner"), order.toList())
    }

    @Test
    fun enqueueAcceptsBeforeShutdownAndRejectsAfter() {
        val lane = createLane()
        val ran = CountDownLatch(1)
        assertTrue("live lane must accept work", lane.enqueue { ran.countDown() })
        assertTrue("accepted work must run", ran.await(2, TimeUnit.SECONDS))

        lane.shutdown()
        assertFalse("shut-down lane must report rejection", lane.enqueue { })
    }

    @Test
    fun awaitQuiescenceDrainsWorkAcceptedBeforeShutdown() {
        val lane = createLane()
        val release = CountDownLatch(1)
        var drainedWorkFinished = false
        assertTrue(
            lane.enqueue {
                release.await(2, TimeUnit.SECONDS)
                drainedWorkFinished = true
            },
        )
        lane.shutdown()
        release.countDown()
        assertTrue(
            "termination must wait for the accepted task",
            lane.awaitQuiescence(timeoutMs = 2_000),
        )
        assertTrue("the accepted task must have completed", drainedWorkFinished)
    }

    @Test
    fun awaitQuiescenceTimesOutWhileAcceptedWorkIsStillRunning() {
        val lane = createLane()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(
            lane.enqueue {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            },
        )
        lane.shutdown()
        try {
            assertTrue("the accepted task must be running", started.await(2, TimeUnit.SECONDS))
            assertFalse(
                "quiescence must not be reported while work is in flight",
                lane.awaitQuiescence(timeoutMs = 50),
            )
        } finally {
            release.countDown()
        }
        assertTrue(lane.awaitQuiescence(timeoutMs = 2_000))
    }

    @Test
    fun nestedTerminationObserversFollowAlreadyRegisteredCleanup() {
        val lane = createLane()
        val workStarted = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>()
        lane.enqueue { workStarted.countDown(); releaseWork.await(5, TimeUnit.SECONDS) }
        try {
            assertTrue(workStarted.await(2, TimeUnit.SECONDS))
            lane.afterTermination {
                order += "first"
                lane.afterTermination { order += "nested" }
            }
            lane.afterTermination { order += "second" }
            lane.shutdown()
            releaseWork.countDown()
            assertTrue(lane.awaitQuiescence(5_000))
            assertEquals(listOf("first", "second", "nested"), order.toList())
        } finally {
            releaseWork.countDown()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun registrationDuringTeardownReturnsWithoutRunningCleanupOnTheCallerAndWaitIncludesIt() {
        val lane = createLane()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val registered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val selfWait = java.util.concurrent.atomic.AtomicBoolean(true)
        val registrationError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        lane.afterTermination {
            firstStarted.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
        }
        lane.shutdown()
        val registrar = Thread {
            try {
                lane.afterTermination {
                    selfWait.set(lane.awaitQuiescence(0))
                    secondStarted.countDown()
                    releaseSecond.await(5, TimeUnit.SECONDS)
                }
            } catch (error: Throwable) {
                registrationError.set(error)
            } finally {
                registered.countDown()
            }
        }
        try {
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            registrar.start()
            assertTrue("An unfinished executor owns newly registered callbacks", registered.await(2, TimeUnit.SECONDS))
            assertEquals(null, registrationError.get())
            releaseFirst.countDown()
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse("An observer must not wait on its own worker", selfWait.get())
            assertFalse("All cleanup must finish before quiescence", lane.awaitQuiescence(50))
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            registrar.join(5_000)
            assertTrue(lane.awaitQuiescence(5_000))
        }
        val caller = Thread.currentThread().id
        var lateThread = 0L
        lane.afterTermination { lateThread = Thread.currentThread().id }
        assertEquals("Completed executors run late cleanup inline", caller, lateThread)
    }

    @Test
    fun concurrentRegistrationShutdownAndWaitsDeliverEveryObserverExactlyOnce() {
        val callers = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            repeat(20) {
                val lane = createLane()
                val start = CountDownLatch(1)
                val calls = java.util.concurrent.atomic.AtomicIntegerArray(16)
                val futures = (0 until 16).map { index ->
                    callers.submit {
                        start.await(5, TimeUnit.SECONDS)
                        lane.afterTermination { calls.incrementAndGet(index) }
                        if (index % 3 == 0) lane.shutdown()
                        lane.awaitQuiescence(1)
                    }
                }
                start.countDown()
                try {
                    futures.forEach { it.get(5, TimeUnit.SECONDS) }
                    lane.shutdown()
                    assertTrue(lane.awaitQuiescence(5_000))
                    repeat(16) { assertEquals(1, calls.get(it)) }
                } finally {
                    lane.shutdown()
                    assertTrue(lane.awaitQuiescence(5_000))
                }
            }
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun throwingTerminationCleanupDoesNotStrandLaterCleanupOrItsNativeOwner() {
        val lane = createLane()
        val completed = java.util.concurrent.atomic.AtomicInteger()
        lane.afterTermination { throw IllegalStateException("Expected termination callback failure") }
        lane.afterTermination { completed.incrementAndGet() }
        lane.shutdown()
        assertTrue(lane.awaitQuiescence(5_000))
        assertEquals(1, completed.get())
        lane.afterTermination { completed.incrementAndGet() }
        assertEquals(2, completed.get())
        lane.shutdown()
        assertTrue(lane.awaitQuiescence(0))
    }

    @Test
    fun shutdownWithoutPriorWorkDeliversCleanupOnItsWorkerWithoutBlockingTheCaller() {
        val lane = createLane()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        lane.afterTermination {
            worker.set(Thread.currentThread())
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val closer = Thread {
            try {
                lane.shutdown()
            } finally {
                returned.countDown()
            }
        }
        closer.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue("Shutdown must return while cleanup is blocked", returned.await(2, TimeUnit.SECONDS))
            assertTrue(worker.get() !== closer && worker.get() !== Thread.currentThread())
            assertTrue(worker.get().isDaemon)
            assertFalse(lane.awaitQuiescence(0))
        } finally {
            release.countDown()
            closer.join(5_000)
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }
}
