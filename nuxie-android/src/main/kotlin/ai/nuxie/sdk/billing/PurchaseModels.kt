package ai.nuxie.sdk.billing

import ai.nuxie.sdk.features.FeatureAllowance
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import java.math.BigDecimal

/** The exact Nuxie Product, Placement, and live Play details retained for checkout. */
class StoreProduct internal constructor(
    val productId: String,
    val storeProductId: String,
    val basePlanId: String?,
    val purchaseOptionId: String? = null,
    val offerId: String?,
    val placementId: String?,
    val rawProduct: ProductDetails?,
    internal val offerToken: String?,
    /** Signed Placement flag; purchase delegates must forward it to their checkout provider. */
    val isOfferPersonalized: Boolean,
    internal val productType: String,
    internal val consumable: Boolean = false,
    internal val featureAllowances: List<FeatureAllowance> = emptyList(),
    internal val licensingPublicKey: String? = null,
    internal val purchaseContext: PurchaseContext? = null,
    /** Signed preview used only by Test Store; never native pricing or verification evidence. */
    internal val testStorePreview: kotlinx.serialization.json.JsonObject? = null,
)

internal data class StorePrice(
    val amount: BigDecimal,
    val display: String,
)

internal fun StoreProduct.storePrice(): StorePrice? {
    val details = rawProduct ?: return null
    val amountMicros: Long
    val displayPrice: String
    when (productType) {
        BillingClient.ProductType.INAPP -> {
            val offer = details.oneTimePurchaseOfferDetailsList
                ?.firstOrNull { it.offerToken == offerToken }
                ?: details.oneTimePurchaseOfferDetails
                ?: return null
            amountMicros = offer.priceAmountMicros
            displayPrice = offer.formattedPrice
        }
        BillingClient.ProductType.SUBS -> {
            val phase = details.subscriptionOfferDetails
                ?.firstOrNull { it.offerToken == offerToken }
                ?.pricingPhases
                ?.pricingPhaseList
                ?.lastOrNull()
                ?: return null
            amountMicros = phase.priceAmountMicros
            displayPrice = phase.formattedPrice
        }
        else -> return null
    }
    return StorePrice(BigDecimal.valueOf(amountMicros, PLAY_PRICE_SCALE), displayPrice)
}

private const val PLAY_PRICE_SCALE = 6

/** A configured Play subscription upgrade or downgrade. */
data class SubscriptionReplacement(
    val oldPurchaseToken: String,
    val replacementMode: ReplacementMode,
)

/** Play's supported subscription replacement policies. Nuxie never guesses one. */
enum class ReplacementMode {
    WITH_TIME_PRORATION,
    CHARGE_PRORATED_PRICE,
    WITHOUT_PRORATION,
    CHARGE_FULL_PRICE,
    DEFERRED,
}

/** Checkout found an active subscription but no replacement policy was configured. */
class SubscriptionReplacementRequiredException internal constructor() :
    IllegalStateException("An active subscription requires an explicit SubscriptionReplacement.")

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

internal data class PurchaseContext(
    val experienceId: String?,
    val experienceVersion: String?,
    val journeyId: String? = null,
)

/** A checkout-owned copy; shared catalog products never retain Journey ownership. */
internal fun StoreProduct.forJourneyCheckout(journeyId: String?): StoreProduct = StoreProduct(
    productId = productId, storeProductId = storeProductId, basePlanId = basePlanId,
    purchaseOptionId = purchaseOptionId, offerId = offerId, placementId = placementId,
    rawProduct = rawProduct, offerToken = offerToken, isOfferPersonalized = isOfferPersonalized,
    productType = productType, consumable = consumable, featureAllowances = featureAllowances,
    licensingPublicKey = licensingPublicKey,
    purchaseContext = purchaseContext?.copy(journeyId = journeyId),
    testStorePreview = testStorePreview,
)
