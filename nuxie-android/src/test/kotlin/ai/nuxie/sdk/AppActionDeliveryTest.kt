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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout

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
    fun cancelledConditionalDeliveryCannotPublishIntoAReplacementListener() = runBlocking {
        val deliveries = mutableListOf<String>()
        val first = NuxieListener { _, action -> deliveries += action.name }
        Nuxie.listener = first
        var publications = 0
        val delivery = async(Dispatchers.Default) {
            Nuxie.deliverAppAction(action("cancelled")) { publish ->
                publications++; publish(); true
            }
        }
        awaitQueuedCallback()
        delivery.cancelAndJoin()
        val replacement = NuxieListener { _, action -> deliveries += action.name }
        Nuxie.listener = replacement
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, publications)
        assertTrue(deliveries.isEmpty())
    }

    @Test
    fun clearingListenerSettlesConditionalDeliveryWithoutWaitingForTheMainLooper() = runBlocking {
        val deliveries = mutableListOf<String>()
        val listener = NuxieListener { _, action -> deliveries += action.name }
        Nuxie.listener = listener
        var publications = 0
        val delivery = async(Dispatchers.Default) {
            Nuxie.deliverAppAction(action("withdrawn")) { publish ->
                publications++; publish(); true
            }
        }
        try {
            awaitQueuedCallback()
            Nuxie.listener = null
            withTimeout(2_000) { assertTrue(delivery.await()) }
            Nuxie.listener = listener
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, publications)
            assertTrue(deliveries.isEmpty())
        } finally { delivery.cancelAndJoin() }
    }

    private fun awaitQueuedCallback() {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (looper.isIdle && System.nanoTime() < deadline) Thread.yield()
        assertFalse("conditional callback was not queued", looper.isIdle)
    }

    @Test
    fun withdrawingAConditionalCallbackStillRejectsARevokedJourney() = runBlocking {
        val listener = NuxieListener { _, _ -> error("Revoked action reached the listener") }
        Nuxie.listener = listener
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val delivery = async(Dispatchers.Default) {
            Nuxie.deliverAppAction(action("revoked")) { publish ->
                if (current.get()) { publish(); true } else false
            }
        }
        try {
            awaitQueuedCallback()
            current.set(false)
            Nuxie.listener = null
            withTimeout(2_000) { assertFalse(delivery.await()) }
            shadowOf(Looper.getMainLooper()).idle()
        } finally { delivery.cancelAndJoin() }
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
