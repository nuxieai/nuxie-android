package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JNI ownership checks plus the lifecycle contracts on the real native lane. */
abstract class RuntimeExecutorContract : RuntimeLaneContract() {
    internal override fun createLane(): NuxieRuntimeLane = NuxieRuntimeLane()

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
}
