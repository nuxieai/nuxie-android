package ai.nuxie.sdk.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lane semantics the surface teardown path relies on: enqueue reports
 * shutdown rejection, and awaitQuiescence drains work accepted before
 * shutdown so texture teardown can guarantee no frame outlives the
 * platform surface.
 */
abstract class RuntimeExecutorContract {
    @org.junit.Before
    fun requireNativeQueue() {
        assertTrue("Executor qualification requires the native runtime", NuxieRuntimeBridge.isAvailable)
    }

    private class BridgeJob(
        private val operation: () -> Unit,
        private val releases: java.util.concurrent.atomic.AtomicInteger,
    ) : Runnable, java.io.Closeable {
        override fun run() = operation()
        override fun close() { releases.incrementAndGet() }
    }

    @Test
    fun `JNI releases accepted contexts once and clears foreign callback exceptions`() {
        val executor = NuxieRuntimeBridge.nativeExecutorNew()
        assertTrue(executor != 0L)
        val releases = java.util.concurrent.atomic.AtomicInteger()
        val runs = java.util.concurrent.atomic.AtomicInteger()
        val observers = java.util.concurrent.atomic.AtomicInteger()
        try {
            assertTrue(NuxieRuntimeBridge.nativeExecutorSubmit(executor,
                BridgeJob({ throw IllegalStateException("Expected JNI callback failure") }, releases)))
            repeat(64) {
                assertTrue(NuxieRuntimeBridge.nativeExecutorSubmit(executor,
                    BridgeJob({ runs.incrementAndGet() }, releases)))
            }
            assertTrue(NuxieRuntimeBridge.nativeExecutorOnTerminated(executor,
                BridgeJob({ observers.incrementAndGet() }, releases)))
            NuxieRuntimeBridge.nativeExecutorClose(executor)
            val rejectedReleases = java.util.concurrent.atomic.AtomicInteger()
            assertFalse(NuxieRuntimeBridge.nativeExecutorSubmit(executor,
                BridgeJob({ runs.incrementAndGet() }, rejectedReleases)))
            assertTrue(NuxieRuntimeBridge.nativeExecutorWait(executor, 5_000))
            assertEquals(64, runs.get())
            assertEquals(66, releases.get())
            assertEquals(0, rejectedReleases.get())
            assertEquals(1, observers.get())
            assertTrue(NuxieRuntimeBridge.nativeExecutorOnTerminated(executor,
                BridgeJob({ observers.incrementAndGet() }, releases)))
            assertEquals(2, observers.get())
            assertEquals(67, releases.get())
        } finally {
            NuxieRuntimeBridge.nativeExecutorClose(executor)
            NuxieRuntimeBridge.nativeExecutorWait(executor, 5_000)
            NuxieRuntimeBridge.nativeExecutorRelease(executor)
        }
    }

    @Test
    fun `calls share one daemon owner and an exception does not kill the queue`() = kotlinx.coroutines.runBlocking {
        val lane = NuxieRuntimeLane()
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
    fun `a callback can enqueue and close its own owner without waiting on itself`() {
        val lane = NuxieRuntimeLane()
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
    fun `independent cleanup owners all run after native work including late registration`() {
        val lane = NuxieRuntimeLane()
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
    fun `enqueue accepts before shutdown and rejects after`() {
        val lane = NuxieRuntimeLane()
        val ran = CountDownLatch(1)
        assertTrue("live lane must accept work", lane.enqueue { ran.countDown() })
        assertTrue("accepted work must run", ran.await(2, TimeUnit.SECONDS))

        lane.shutdown()
        assertFalse("shut-down lane must report rejection", lane.enqueue { })
    }

    @Test
    fun `awaitQuiescence drains work accepted before shutdown`() {
        val lane = NuxieRuntimeLane()
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
    fun `awaitQuiescence times out while accepted work is still running`() {
        val lane = NuxieRuntimeLane()
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
}
