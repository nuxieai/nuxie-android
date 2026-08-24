package ai.nuxie.sdk.commerce

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

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
    fun disconnectRetriesWithExponentialBackoff() {
        val delays = mutableListOf<Long>()
        val factory = FakeBillingClientFactory()
        val connection = PlayBillingConnection(
            factory = factory,
            scope = testScope(),
            initialRetryDelayMillis = 100,
            maxRetryDelayMillis = 1_000,
            sleepMillis = { delays += it },
        )
        connection.connect()

        factory.client.connectionListener!!.onBillingServiceDisconnected()
        factory.client.connectionListener!!.onBillingServiceDisconnected()

        assertEquals(listOf(100L, 200L), delays)
        assertEquals(3, factory.client.startCount)
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class FakeBillingClientFactory : BillingClientAdapterFactory {
        val client = FakeBillingClientAdapter()
        var createdCount = 0
        var purchaseListener: PurchasesUpdatedListener? = null

        override fun create(listener: PurchasesUpdatedListener): BillingClientAdapter {
            createdCount += 1
            purchaseListener = listener
            return client
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

        override fun endConnection() = Unit

        override fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: ProductDetailsResponseListener,
        ) = Unit
    }
}
