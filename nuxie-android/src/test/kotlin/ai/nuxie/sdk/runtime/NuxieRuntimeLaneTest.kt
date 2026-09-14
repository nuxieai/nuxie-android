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
class NuxieRuntimeLaneTest {
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
