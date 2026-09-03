package ai.nuxie.sdk

import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class AppActionJourneyReleaseDeliveryTest {
    @After
    fun tearDown() {
        Nuxie.listener = null
    }

    @Test
    fun appActionsArriveOnTheMainThreadInRequestOrder() {
        val deliveries = mutableListOf<Pair<String, Thread>>()
        val listener = NuxieListener { sdk, action ->
            assertSame(Nuxie, sdk)
            deliveries += action.name to Thread.currentThread()
        }
        Nuxie.listener = listener
        val workerFinished = CountDownLatch(1)

        Thread {
            runBlocking {
                Nuxie.deliverAppAction(action("first"))
                Nuxie.deliverAppAction(action("second"))
            }
            workerFinished.countDown()
        }.start()

        assertFalse("delivery must wait for the main-thread callback", workerFinished.await(100, TimeUnit.MILLISECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (workerFinished.count != 0L && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue(workerFinished.await(100, TimeUnit.MILLISECONDS))

        assertEquals(listOf("first", "second"), deliveries.map { it.first })
        assertTrue(deliveries.all { (_, thread) -> thread === Looper.getMainLooper().thread })
    }

    @Test
    fun anAbsentListenerDropsTheActionWithoutReplayingItLater() = runBlocking {
        Nuxie.deliverAppAction(action("dropped"))
        val deliveries = mutableListOf<String>()
        val listener = NuxieListener { sdk, action ->
            assertSame(Nuxie, sdk)
            deliveries += action.name
        }
        Nuxie.listener = listener

        Nuxie.deliverAppAction(action("subsequent"))

        assertEquals(listOf("subsequent"), deliveries)
    }

    private fun action(name: String) = AppAction(
        name = name,
        payload = null,
        experience = ExperienceRef("experience-1", "version-1", "journey-1"),
    )
}
