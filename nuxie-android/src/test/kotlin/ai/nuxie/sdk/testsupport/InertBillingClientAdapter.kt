package ai.nuxie.sdk.testsupport

import ai.nuxie.sdk.billing.BillingClientAdapter
import ai.nuxie.sdk.billing.BillingClientAdapterFactory
import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams

/**
 * A billing adapter that reports BILLING_UNAVAILABLE immediately to every
 * call, mirroring a Play-less device. The code is a PERMANENT setup failure
 * (PlayBillingConnection does not retry it), so connection-driven recovery
 * can never fire, and anything awaiting the client fails fast instead of
 * hanging. Unit suites that exercise billing behavior use their own
 * purpose-built fakes instead.
 */
internal object InertBillingClientAdapter : BillingClientAdapter {
    val factory = BillingClientAdapterFactory { InertBillingClientAdapter }

    private val unavailable: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
        .build()

    override val isReady: Boolean = false

    override fun startConnection(listener: BillingClientStateListener) {
        listener.onBillingSetupFinished(unavailable)
    }

    override fun endConnection() = Unit

    override fun queryProductDetailsAsync(
        params: QueryProductDetailsParams,
        listener: ProductDetailsResponseListener,
    ) {
        listener.onProductDetailsResponse(
            unavailable,
            QueryProductDetailsResult.create(emptyList(), emptyList()),
        )
    }

    override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult =
        unavailable

    override fun queryPurchasesAsync(
        params: QueryPurchasesParams,
        listener: PurchasesResponseListener,
    ) {
        listener.onQueryPurchasesResponse(unavailable, emptyList())
    }

    override fun acknowledgePurchase(
        params: AcknowledgePurchaseParams,
        listener: AcknowledgePurchaseResponseListener,
    ) {
        listener.onAcknowledgePurchaseResponse(unavailable)
    }

    override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) {
        listener.onConsumeResponse(unavailable, "")
    }
}
