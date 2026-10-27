package ai.nuxie.sdk.presentation.debug

import ai.nuxie.sdk.runtime.ExpectedFileAsset
import ai.nuxie.sdk.runtime.FileAssetKind
import ai.nuxie.sdk.runtime.NuxieRuntime
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import ai.nuxie.sdk.runtime.TestRuntimeLane
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceRenderProbeTest {
    @Test
    fun returnsImageIdentityAfterWorkerInspectionAndTermination() {
        val native = RecordingNative { listOf(image.copy(kind = FileAssetKind.FONT), image) }
        val lane = TestRuntimeLane()
        val terminated = AtomicBoolean(false)
        lane.afterTermination { terminated.set(true) }
        try {
            val result = inspectImageAssetOnLane(NuxieRuntime(native), lane, scene)
            assertEquals(ExperienceRenderProbe.InspectedImageAsset("hero", 42L), result)
            assertTrue(terminated.get())
            assertEquals(1, native.calls.size)
            assertTrue(native.calls.single() !== Thread.currentThread())
            assertArrayEquals(scene, native.bytes.single())
            assertFalse(lane.enqueue { error("Inspection lane must be closed") })
        } finally {
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun invalidCatalogsReportTheInspectionFailureAfterTermination() {
        val cases = listOf(
            null to "Runtime could not inspect the Experience asset catalog",
            emptyList<ExpectedFileAsset>() to "Expected exactly one image in the Experience asset catalog",
            listOf(image, image) to "Expected exactly one image in the Experience asset catalog",
            listOf(image.copy(authoredId = null)) to "Inspected image has no authored id",
        )
        for ((catalog, message) in cases) {
            val native = RecordingNative { catalog }
            val lane = TestRuntimeLane()
            val terminated = AtomicBoolean(false)
            lane.afterTermination { terminated.set(true) }
            try {
                val failure = assertThrows(IllegalStateException::class.java) {
                    inspectImageAssetOnLane(NuxieRuntime(native), lane, scene)
                }
                assertEquals(message, failure.message)
                assertEquals(message, failure.cause?.message)
                assertTrue(terminated.get())
                assertEquals(1, native.calls.size)
            } finally {
                lane.shutdown()
                assertTrue(lane.awaitQuiescence(5_000))
            }
        }
    }

    @Test
    fun nativeExceptionIsPreservedAsTheCauseAfterTermination() {
        val expected = IllegalArgumentException("Catalog read failed")
        val native = RecordingNative { throw expected }
        val lane = TestRuntimeLane()
        val terminated = AtomicBoolean(false)
        lane.afterTermination { terminated.set(true) }
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                inspectImageAssetOnLane(NuxieRuntime(native), lane, scene)
            }
            assertSame(expected, failure.cause)
            assertEquals(expected.message, failure.message)
            assertTrue(terminated.get())
        } finally {
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun rejectedSubmissionDoesNotInspectTheCatalog() {
        val native = RecordingNative { listOf(image) }
        val lane = TestRuntimeLane()
        lane.shutdown()
        try {
            assertThrows(IllegalStateException::class.java) {
                inspectImageAssetOnLane(NuxieRuntime(native), lane, scene)
            }
            assertTrue(native.calls.isEmpty())
        } finally {
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun finishedInspectionCannotSucceedBeforeTerminationCallback() {
        val native = RecordingNative { listOf(image) }
        val lane = TestRuntimeLane()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lane.afterTermination {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                inspectImageAssetOnLane(NuxieRuntime(native), lane, scene, timeoutMillis = 50)
            }
            assertEquals("Timed out inspecting the Experience asset catalog", failure.message)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(1, native.calls.size)
        } finally {
            release.countDown()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
    }

    @Test
    fun timeoutLeavesAcceptedInspectionOwnedByTheLane() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val native = RecordingNative {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            finished.set(true)
            listOf(image)
        }
        val lane = TestRuntimeLane()
        val terminated = AtomicBoolean(false)
        lane.afterTermination { terminated.set(true) }
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                inspectImageAssetOnLane(NuxieRuntime(native), lane, scene, timeoutMillis = 50)
            }
            assertEquals("Timed out inspecting the Experience asset catalog", failure.message)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(finished.get())
            assertFalse(terminated.get())
        } finally {
            release.countDown()
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
        assertTrue(finished.get())
        assertTrue(terminated.get())
        assertEquals(1, native.calls.size)
    }

    @Test
    fun interruptionPreservesTheCallerFlagAndAcceptedInspectionStillFinishes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val native = RecordingNative {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            finished.set(true)
            listOf(image)
        }
        val lane = TestRuntimeLane()
        val failure = AtomicReference<Throwable?>()
        val interrupted = AtomicBoolean(false)
        val returned = CountDownLatch(1)
        val caller = Thread {
            try {
                inspectImageAssetOnLane(NuxieRuntime(native), lane, scene)
            } catch (error: Throwable) {
                failure.set(error)
                interrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                returned.countDown()
            }
        }
        caller.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get() is IllegalStateException)
            assertTrue(interrupted.get())
            assertFalse(finished.get())
        } finally {
            release.countDown()
            caller.join(5_000)
            lane.shutdown()
            assertTrue(lane.awaitQuiescence(5_000))
        }
        assertTrue(finished.get())
        assertEquals(1, native.calls.size)
    }

    private class RecordingNative(
        private val inspect: () -> List<ExpectedFileAsset>?,
    ) : NuxieTypedRuntimeNative {
        val calls = CopyOnWriteArrayList<Thread>()
        val bytes = CopyOnWriteArrayList<ByteArray>()

        override fun inspectFileAssets(bytes: ByteArray): List<ExpectedFileAsset>? {
            calls += Thread.currentThread()
            this.bytes += bytes.copyOf()
            return inspect()
        }
    }

    private val scene = byteArrayOf(1, 2, 3)
    private val image = ExpectedFileAsset(
        ordinal = 0,
        kind = FileAssetKind.IMAGE,
        authoredId = 42L,
        name = "hero",
        fileExtension = "png",
        isEmbedded = false,
        hasContentsRecord = false,
        requiredProviderFlags = 0,
    )
}
