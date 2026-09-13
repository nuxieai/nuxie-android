package ai.nuxie.sdk.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayBillingConnectionTest {
    @Test
    fun closingAReadyConnectionSettlesEveryOutstandingRequest() = runTest {
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(factory, this)
        connection.connect()
        factory.client.isReady = true
        factory.client.finishSetup(BillingClient.BillingResponseCode.OK)
        val requests = listOf(
            async { runCatching { connection.query(listOf(ProductQuery("product", BillingClient.ProductType.INAPP))) } },
            async { runCatching { connection.queryActive(BillingClient.ProductType.INAPP) } },
            async { runCatching { connection.acknowledge("ack-token") } },
            async { runCatching { connection.consume("consume-token") } },
        )
        try {
            runCurrent()
            assertEquals(4, factory.client.pendingRequestCount)
            connection.close()
            runCurrent()
            assertTrue("close must settle requests already awaiting a Play callback", requests.all { it.isCompleted })
            requests.forEach { assertTrue(it.await().exceptionOrNull() is IllegalStateException) }
            val ok = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()
            repeat(2) {
                factory.client.purchasesListener!!.onQueryPurchasesResponse(ok, emptyList())
                factory.client.acknowledgeListener!!.onAcknowledgePurchaseResponse(ok)
                factory.client.consumeListener!!.onConsumeResponse(ok, "consume-token")
            }
            connection.close()
            assertEquals(1, factory.client.endCount)
        } finally {
            requests.forEach { it.cancel() }
            connection.close()
        }
    }

    @Test
    fun callerCancellationDoesNotCloseOtherRequestsAndLateCallbacksAreHarmless() = runTest {
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(factory, this)
        connection.connect()
        factory.client.isReady = true
        factory.client.finishSetup(BillingClient.BillingResponseCode.OK)
        val cancelled = async { connection.acknowledge("cancelled") }
        val surviving = async { connection.consume("surviving") }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        val ok = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()
        repeat(2) { factory.client.acknowledgeListener!!.onAcknowledgePurchaseResponse(ok) }
        assertTrue(cancelled.isCancelled)
        assertEquals(0, factory.client.endCount)
        factory.client.consumeListener!!.onConsumeResponse(ok, "surviving")
        runCurrent()
        assertSame(ok, surviving.await())
        connection.close()
    }

    @Test
    fun completedCallbackWinsOverCloseAndDuplicateCallbacks() = runTest {
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(factory, this)
        connection.connect()
        factory.client.isReady = true
        factory.client.finishSetup(BillingClient.BillingResponseCode.OK)
        val request = async { connection.consume("token") }
        runCurrent()
        val ok = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()
        factory.client.consumeListener!!.onConsumeResponse(ok, "token")
        // The caller has not resumed yet; a completed provider response must survive closure.
        connection.close()
        factory.client.consumeListener!!.onConsumeResponse(
            BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.ERROR).build(),
            "token",
        )
        runCurrent()
        assertSame(ok, request.await())
    }

    @Test
    fun disconnectFailureStillSettlesRequestsAndCloseIsIdempotent() = runTest {
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(factory, this)
        connection.connect()
        factory.client.isReady = true
        factory.client.finishSetup(BillingClient.BillingResponseCode.OK)
        val request = async { runCatching { connection.acknowledge("token") } }
        runCurrent()
        val disconnectFailure = IllegalStateException("provider disconnect failed")
        factory.client.endFailure = disconnectFailure
        assertSame(disconnectFailure, runCatching { connection.close() }.exceptionOrNull())
        runCurrent()
        assertTrue(request.isCompleted)
        assertEquals("Play Billing connection closed.", request.await().exceptionOrNull()?.message)
        connection.close()
        assertEquals(1, factory.client.endCount)
    }

    @Test
    fun closeSettlesConnectionSetupWaitersAndRejectsLaterRequests() = runTest {
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(factory, this)
        val request = async { runCatching { connection.acknowledge("token") } }
        runCurrent()
        connection.close()
        factory.client.finishSetup(BillingClient.BillingResponseCode.OK)
        runCurrent()
        assertTrue(request.isCompleted)
        assertTrue(request.await().exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { connection.consume("later") }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, factory.client.pendingRequestCount)
        assertEquals(1, factory.client.startCount)
    }

    @Test
    fun clientIsLazyAndRegistersOneGlobalPurchaseListener() {
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(
            factory = factory,
            scope = testScope(),
        )

        assertEquals(0, factory.createdCount)
        connection.connect()
        connection.connect()

        assertEquals(1, factory.createdCount)
        assertEquals(1, factory.client.startCount)
        assertNotNull(factory.purchaseListener)
    }

    @Test
    fun purchaseListenerStoresAndForwardsTheLastUpdate() {
        val factory = FakeBillingClientFactory()
        var forwarded: PurchaseUpdate? = null
        val connection = PlayBillingConnection(
            factory = factory,
            scope = testScope(),
            onPurchasesUpdated = { forwarded = it },
        )
        connection.connect()
        val result = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .setDebugMessage("ok")
            .build()

        factory.purchaseListener!!.onPurchasesUpdated(result, emptyList())

        assertSame(result, connection.lastUpdate?.billingResult)
        assertSame(connection.lastUpdate, forwarded)
    }

    @Test
    fun transientSetupFailuresCoalesceAndRetryWithCappedExponentialBackoff() = runTest {
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(
            factory = factory,
            scope = this,
            initialRetryDelayMillis = 100,
            maxRetryDelayMillis = 250,
        )
        connection.connect()

        factory.client.finishSetup(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        factory.client.finishSetup(BillingClient.BillingResponseCode.NETWORK_ERROR)
        advanceTimeBy(99)
        runCurrent()
        assertEquals(1, factory.client.startCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, factory.client.startCount)

        factory.client.finishSetup(BillingClient.BillingResponseCode.ERROR)
        advanceTimeBy(199)
        runCurrent()
        assertEquals(2, factory.client.startCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, factory.client.startCount)

        factory.client.finishSetup(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(4, factory.client.startCount)

        factory.client.finishSetup(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(5, factory.client.startCount)

        connection.close()
    }

    @Test
    fun terminalSetupFailureCompletesAllWaitersAndOnlyLaterCallReconnects() = runTest {
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(
            factory = factory,
            scope = this,
            initialRetryDelayMillis = 100,
        )
        val query = listOf(ProductQuery("product", BillingClient.ProductType.INAPP))
        val first = async { runCatching { connection.query(query) } }
        val second = async { runCatching { connection.query(query) } }
        runCurrent()

        assertEquals(1, factory.createdCount)
        assertEquals(1, factory.client.startCount)
        factory.client.finishSetup(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
        factory.client.finishSetup(
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
            "invalid setup",
        )
        runCurrent()

        val firstFailure = first.await().exceptionOrNull()
        val secondFailure = second.await().exceptionOrNull()
        assertTrue(firstFailure is BillingUnavailableException)
        assertTrue(secondFailure is BillingUnavailableException)
        assertEquals(
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
            (firstFailure as BillingUnavailableException).responseCode,
        )
        assertEquals("invalid setup", firstFailure.debugMessage)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, factory.createdCount)

        val later = async { runCatching { connection.query(query) } }
        runCurrent()
        assertEquals(2, factory.createdCount)
        assertEquals(1, factory.client.startCount)

        later.cancel()
        connection.close()
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class FakeBillingClientFactory : BillingClientAdapterFactory {
        private val clients = mutableListOf<FakeBillingClientAdapter>()
        val client: FakeBillingClientAdapter
            get() = clients.last()
        val createdCount: Int
            get() = clients.size
        var purchaseListener: PurchasesUpdatedListener? = null

        override fun create(listener: PurchasesUpdatedListener): BillingClientAdapter {
            purchaseListener = listener
            return FakeBillingClientAdapter().also(clients::add)
        }
    }

    private class FakeBillingClientAdapter : BillingClientAdapter {
        override var isReady: Boolean = false
        var startCount = 0
        var endCount = 0
        var endFailure: Throwable? = null
        var pendingRequestCount = 0
        var purchasesListener: PurchasesResponseListener? = null
        var acknowledgeListener: AcknowledgePurchaseResponseListener? = null
        var consumeListener: ConsumeResponseListener? = null
        var connectionListener: BillingClientStateListener? = null

        override fun startConnection(listener: BillingClientStateListener) {
            startCount += 1
            connectionListener = listener
        }

        fun finishSetup(
            @BillingClient.BillingResponseCode responseCode: Int,
            debugMessage: String = "setup result",
        ) {
            connectionListener!!.onBillingSetupFinished(
                BillingResult.newBuilder()
                    .setResponseCode(responseCode)
                    .setDebugMessage(debugMessage)
                    .build(),
            )
        }

        override fun endConnection() {
            endCount++
            endFailure?.let { throw it }
        }

        override fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: ProductDetailsResponseListener,
        ) { pendingRequestCount++ }

        override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult =
            error("Not used by connection lifecycle tests")

        override fun queryPurchasesAsync(
            params: QueryPurchasesParams,
            listener: PurchasesResponseListener,
        ) {
            pendingRequestCount++
            purchasesListener = listener
        }

        override fun acknowledgePurchase(
            params: AcknowledgePurchaseParams,
            listener: AcknowledgePurchaseResponseListener,
        ) {
            pendingRequestCount++
            acknowledgeListener = listener
        }

        override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) {
            pendingRequestCount++
            consumeListener = listener
        }
    }
}
