package ai.nuxie.sdk.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lane semantics the surface teardown path relies on: enqueue reports
 * shutdown rejection, and awaitQuiescence drains work accepted before
 * shutdown so surfaceDestroyed can guarantee no frame outlives the
 * platform surface.
 */
class NuxieRuntimeLaneTest {
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
        val release = CountDownLatch(1)
        assertTrue(lane.enqueue { release.await(5, TimeUnit.SECONDS) })
        lane.shutdown()
        assertFalse(
            "quiescence must not be reported while work is in flight",
            lane.awaitQuiescence(timeoutMs = 50),
        )
        release.countDown()
        assertTrue(lane.awaitQuiescence(timeoutMs = 2_000))
    }
}
