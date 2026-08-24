package ai.nuxie.sdk.commerce

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetailsResponseListener
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

        override fun endConnection() = Unit

        override fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: ProductDetailsResponseListener,
        ) = Unit
    }
}
