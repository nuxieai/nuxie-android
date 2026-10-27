package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import ai.nuxie.sdk.runtime.TestRuntimeLane
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderCapabilityProbeTest {
    @Test
    fun successfulNativeRendererCreationIsFreedOnItsWorkerAndCached() {
        val native = RecordingNative()
        val lane = TestRuntimeLane()
        val terminated = AtomicBoolean(false)
        lane.afterTermination { terminated.set(true) }
        val probe = RenderCapabilityProbe(
            libraryAvailable = { true },
            probeRenderer = { probeNativeRenderer(NuxieRuntime(native), lane) },
        )
        try {
            assertTrue(probe.isAvailable())
            assertTrue(terminated.get())
            assertTrue(probe.isAvailable())
            assertEquals(listOf(1 to 1), native.sizes)
            assertEquals(listOf(41L), native.freedHandles)
            assertEquals(native.createdOn, native.freedOn)
            assertTrue(native.createdOn.single() !== Thread.currentThread())
        } finally {
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun missingLibrarySkipsRendererProbeAndCachesFailure() {
        var probeCalls = 0
        val probe = RenderCapabilityProbe(
            libraryAvailable = { false },
            probeRenderer = {
                probeCalls++
                true
            },
        )
        assertFalse(probe.isAvailable())
        assertFalse(probe.isAvailable())
        assertEquals(0, probeCalls)
    }

    @Test
    fun unavailableOrThrowingRendererCreationTerminatesAndCachesFailure() {
        for (create in listOf<() -> Long>({ 0L }, { error("Renderer creation failed") })) {
            val native = RecordingNative(create = create)
            val lane = TestRuntimeLane()
            val terminated = AtomicBoolean(false)
            lane.afterTermination { terminated.set(true) }
            val probe = RenderCapabilityProbe(
                libraryAvailable = { true },
                probeRenderer = { probeNativeRenderer(NuxieRuntime(native), lane) },
            )
            try {
                assertFalse(probe.isAvailable())
                assertTrue(terminated.get())
                assertFalse(probe.isAvailable())
                assertEquals(1, native.createdOn.size)
                assertTrue(native.freedHandles.isEmpty())
            } finally {
                lane.shutdown()
                assertTrue(lane.awaitQuiescence(5_000))
            }
        }
    }

    @Test
    fun rendererCleanupFailureDoesNotReportCapability() {
        val native = RecordingNative(free = { error("Renderer cleanup failed") })
        val lane = TestRuntimeLane()
        try {
            assertFalse(probeNativeRenderer(NuxieRuntime(native), lane))
            assertTrue(lane.awaitQuiescence(5_000))
            assertEquals(listOf(41L), native.freedHandles)
            assertEquals(native.createdOn, native.freedOn)
        } finally {
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun rejectedSubmissionDoesNotCreateRenderer() {
        val native = RecordingNative()
        val lane = TestRuntimeLane()
        lane.shutdown()
        try {
            assertFalse(probeNativeRenderer(NuxieRuntime(native), lane))
            assertTrue(native.createdOn.isEmpty())
            assertTrue(native.freedHandles.isEmpty())
        } finally {
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun finishedRendererWorkIsNotSuccessBeforeTerminationCompletes() {
        val native = RecordingNative()
        val lane = TestRuntimeLane()
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        lane.afterTermination {
            cleanupEntered.countDown()
            check(releaseCleanup.await(5, TimeUnit.SECONDS))
        }
        try {
            assertFalse(probeNativeRenderer(NuxieRuntime(native), lane, timeoutMillis = 50))
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(41L), native.freedHandles)
        } finally {
            releaseCleanup.countDown()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun timeoutLeavesAcceptedRendererCreationAndCleanupOwnedByTheLane() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = RecordingNative(create = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            41L
        })
        val lane = TestRuntimeLane()
        val probe = RenderCapabilityProbe(
            libraryAvailable = { true },
            probeRenderer = { probeNativeRenderer(NuxieRuntime(native), lane, timeoutMillis = 50) },
        )
        try {
            assertFalse(probe.isAvailable())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(native.freedHandles.isEmpty())
        } finally {
            release.countDown()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
        assertEquals(listOf(41L), native.freedHandles)
        assertEquals(native.createdOn, native.freedOn)
        assertFalse(probe.isAvailable())
        assertEquals(1, native.createdOn.size)
    }

    @Test
    fun interruptionPreservesTheCallerFlagAndStillDrainsAcceptedWork() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = RecordingNative(create = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            41L
        })
        val lane = TestRuntimeLane()
        val probe = RenderCapabilityProbe(
            libraryAvailable = { true },
            probeRenderer = { probeNativeRenderer(NuxieRuntime(native), lane) },
        )
        val result = AtomicBoolean(true)
        val interrupted = AtomicBoolean(false)
        val returned = CountDownLatch(1)
        val caller = Thread {
            try {
                result.set(probe.isAvailable())
                interrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                returned.countDown()
            }
        }
        caller.start()
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertFalse(result.get())
            assertTrue(interrupted.get())
            assertTrue(native.freedHandles.isEmpty())
        } finally {
            release.countDown()
            caller.join(5_000)
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
        assertEquals(listOf(41L), native.freedHandles)
        assertEquals(native.createdOn, native.freedOn)
    }

    private class RecordingNative(
        private val create: () -> Long = { 41L },
        private val free: () -> Unit = {},
    ) : NuxieTypedRuntimeNative {
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        val createdOn = CopyOnWriteArrayList<Thread>()
        val freedOn = CopyOnWriteArrayList<Thread>()
        val freedHandles = CopyOnWriteArrayList<Long>()

        override fun newAndroidVulkanRenderer(pixelWidth: Int, pixelHeight: Int): Long {
            sizes += pixelWidth to pixelHeight
            createdOn += Thread.currentThread()
            return create()
        }

        override fun freeRenderer(handle: Long) {
            freedOn += Thread.currentThread()
            freedHandles += handle
            free()
        }
    }
}
