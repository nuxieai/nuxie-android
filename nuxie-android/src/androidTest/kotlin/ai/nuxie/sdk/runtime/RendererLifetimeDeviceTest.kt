package ai.nuxie.sdk.runtime

import android.os.Process
import androidx.test.filters.SdkSuppress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each handle stays on its creator's lane; only independent renderer lifetimes overlap. */
@SdkSuppress(minSdkVersion = 26)
class RendererLifetimeDeviceTest {
    @Test fun serialRendererLifetimesComplete() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val lane = NuxieRuntimeLane()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        lane.enqueue {
            try {
                repeat(40) { checkNotNull(NuxieRuntime.shared.newAndroidVulkanRenderer(64, 64)).close() }
            } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            assertTrue("Serial renderer lifetimes must drain", finished.await(30, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
        } finally { lane.shutdown() }
    }

    @Test fun independentRendererCreationAndDestructionCanOverlap() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val lanes = List(2) { NuxieRuntimeLane() }
        val rendezvous = CyclicBarrier(2)
        val finished = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val created = AtomicInteger()
        lanes.forEachIndexed { index, lane ->
            lane.enqueue {
                var renderer: NuxieAndroidVulkanRenderer? = null
                try {
                    if (index == 0) {
                        renderer = checkNotNull(NuxieRuntime.shared.newAndroidVulkanRenderer(64, 64))
                        created.incrementAndGet()
                    }
                    repeat(40) {
                        rendezvous.await(30, TimeUnit.SECONDS)
                        if (renderer == null) {
                            renderer = checkNotNull(NuxieRuntime.shared.newAndroidVulkanRenderer(64, 64))
                            created.incrementAndGet()
                        } else {
                            renderer?.close()
                            renderer = null
                        }
                        rendezvous.await(30, TimeUnit.SECONDS)
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally {
                    renderer?.close()
                    finished.countDown()
                }
            }
        }
        try {
            val completed = finished.await(30, TimeUnit.SECONDS)
            if (!completed) Process.sendSignal(Process.myPid(), 3)
            assertTrue("Independent renderer lifetimes must drain; created=${created.get()} failure=${failure.get()}", completed)
            assertEquals(null, failure.get())
            assertEquals(41, created.get())
        } finally { lanes.forEach { it.shutdown() } }
    }
}
