package ai.nuxie.sdk.commerce

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayBillingFlowParamsTest {
    @Suppress("DEPRECATION")
    @Test
    fun upgradeLaunchBuildsTheExactBillingFlowParams() = runTest {
        val adapter = CapturingAdapter()
        val connection = PlayBillingConnection(
            factory = BillingClientAdapterFactory { adapter },
            scope = this,
        )
        connection.connect()
        adapter.finishSetup()
        val rawProduct = productDetails()
        val product = StoreProduct(
            productId = "pro",
            storeProductId = "play-pro",
            basePlanId = "annual",
            offerId = "launch",
            placementId = "primary",
            rawProduct = rawProduct,
            offerToken = "offer-token",
            isOfferPersonalized = true,
            productType = BillingClient.ProductType.SUBS,
        )

        connection.launch(
            Robolectric.buildActivity(Activity::class.java).get(),
            CheckoutRequest(
                product,
                "account-hash",
                SubscriptionReplacement("old-token", ReplacementMode.DEFERRED),
            ),
        )

        val params = checkNotNull(adapter.launchedParams)
        val productParams = params.zzk().single() as BillingFlowParams.ProductDetailsParams
        assertSame(rawProduct, productParams.zza())
        assertEquals("offer-token", productParams.zzb())
        assertEquals("account-hash", params.zze())
        assertTrue(params.zzt())
        assertEquals("old-token", params.zzh())
        assertEquals(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED, params.zzb())
        connection.close()
    }

    private fun productDetails(): ProductDetails = ProductDetails::class.java
        .getDeclaredConstructor(String::class.java)
        .apply { isAccessible = true }
        .newInstance(
            """{"productId":"play-pro","type":"subs","title":"Pro","name":"Pro","description":"Pro","packageName":"com.example.app"}""",
        )

    private class CapturingAdapter : BillingClientAdapter {
        override var isReady = false
        private lateinit var listener: BillingClientStateListener
        var launchedParams: BillingFlowParams? = null

        override fun startConnection(listener: BillingClientStateListener) {
            this.listener = listener
        }

        fun finishSetup() {
            isReady = true
            listener.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))
        }

        override fun endConnection() = Unit
        override fun queryProductDetailsAsync(
            params: QueryProductDetailsParams,
            listener: ProductDetailsResponseListener,
        ) = Unit

        override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
            launchedParams = params
            return result(BillingClient.BillingResponseCode.OK)
        }

        override fun queryPurchasesAsync(
            params: QueryPurchasesParams,
            listener: PurchasesResponseListener,
        ) = Unit

        override fun acknowledgePurchase(
            params: AcknowledgePurchaseParams,
            listener: AcknowledgePurchaseResponseListener,
        ) = Unit

        override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) = Unit

        private fun result(code: Int) = BillingResult.newBuilder()
            .setResponseCode(code)
            .setDebugMessage("test")
            .build()
    }
}
