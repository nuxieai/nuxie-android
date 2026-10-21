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
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject

/**
 * A connected billing adapter whose purchase queries report [purchases], as
 * Play does at launch. It supports nothing else: checkout, product details
 * and completion report BILLING_UNAVAILABLE.
 */
internal class ReportingBillingClientAdapter(private val purchases: List<Purchase>) : BillingClientAdapter {
    private val ok: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()
    private val unavailable: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
        .build()

    override val isReady: Boolean = true

    override fun startConnection(listener: BillingClientStateListener) {
        listener.onBillingSetupFinished(ok)
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
        listener.onQueryPurchasesResponse(ok, purchases)
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

    companion object {
        fun factory(vararg purchases: Purchase) =
            BillingClientAdapterFactory { ReportingBillingClientAdapter(purchases.toList()) }

        /** An active Play purchase of [productId] that names [obfuscatedAccountId]. */
        fun purchase(
            purchaseToken: String,
            productId: String,
            obfuscatedAccountId: String,
            packageName: String = "com.example.app",
        ): Purchase = Purchase(
            JSONObject()
                .put("orderId", "GPA.$purchaseToken")
                .put("packageName", packageName)
                .put("productId", productId)
                .put("purchaseTime", 1L)
                .put("purchaseState", 0)
                .put("purchaseToken", purchaseToken)
                .put("acknowledged", false)
                .put("obfuscatedAccountId", obfuscatedAccountId)
                .toString(),
            "",
        )
    }
}
