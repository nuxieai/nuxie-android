package ai.nuxie.sdk

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ForwardedActivityJourneyReleaseDeliveryTest {
    @After
    fun tearDown() {
        Nuxie.listener = null
    }

    @Test
    fun activitiesArriveOnTheMainThreadInCommitOrder() {
        val deliveries = mutableListOf<Pair<String, Thread>>()
        val listener = object : NuxieListener {
            override fun onAppActionRequested(sdk: Nuxie, action: AppAction) = Unit

            override fun onActivityEmitted(sdk: Nuxie, info: NuxieActivityInfo) {
                assertSame(Nuxie, sdk)
                deliveries += info.id to Thread.currentThread()
            }
        }
        Nuxie.listener = listener
        val workerFinished = CountDownLatch(1)

        Thread {
            runBlocking {
                Nuxie.deliverActivity(info("first"))
                Nuxie.deliverActivity(info("second"))
            }
            workerFinished.countDown()
        }.start()

        assertFalse(workerFinished.await(100, TimeUnit.MILLISECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (workerFinished.count != 0L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }

        assertTrue(workerFinished.await(100, TimeUnit.MILLISECONDS))
        assertEquals(listOf("first", "second"), deliveries.map { it.first })
        assertTrue(deliveries.all { it.second === Looper.getMainLooper().thread })
    }

    private fun info(id: String) = NuxieActivityInfo(id, 1L, 2L, NuxieActivity.AppOpened)
}
