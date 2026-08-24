package ai.nuxie.sdk.commerce

import com.android.billingclient.api.ProductDetails

/** The exact Nuxie Product, Placement, and live Play details retained for checkout. */
class StoreProduct internal constructor(
    val productId: String,
    val storeProductId: String,
    val basePlanId: String?,
    val offerId: String?,
    val placementId: String?,
    val rawProduct: ProductDetails?,
    internal val offerToken: String?,
    internal val isOfferPersonalized: Boolean,
)

/** The result of launching checkout for the [StoreProduct] shown to the customer. */
sealed interface PurchaseResult {
    data object Purchased : PurchaseResult
    data object Cancelled : PurchaseResult
    data object Pending : PurchaseResult
    data class Failed(val cause: Throwable) : PurchaseResult
}

/** The result of asking the configured purchase system to restore purchases. */
sealed interface RestoreResult {
    data object Restored : RestoreResult
    data object NoPurchases : RestoreResult
    data class Failed(val cause: Throwable) : RestoreResult
}

/** Whether Nuxie or the host app owns Play purchase completion. */
enum class PurchaseHandlingMode {
    NUXIE_MANAGED,
    APP_MANAGED,
}

/** Optional checkout seam for a provider SDK or custom billing stack. */
interface NuxiePurchaseDelegate {
    suspend fun purchase(product: StoreProduct): PurchaseResult

    suspend fun restorePurchases(): RestoreResult
}
