package ai.nuxie.example.revenuecat

import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.models.GoogleStoreProduct
import com.revenuecat.purchases.models.GoogleSubscriptionOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Example source, not part of the Nuxie SDK artifact. Configure RevenueCat before use. */
class NuxieRevenueCatPurchaseDelegate(
  private val currentActivity: () -> Activity?,
  private val purchases: Purchases = Purchases.sharedInstance,
  // The host supplies explicit upgrade/downgrade policy here when needed.
  private val configureReplacement: (StoreProduct, PurchaseParams.Builder) -> Unit = { _, _ -> },
  private val expectedCustomerId: String? = null,
) : NuxiePurchaseDelegate {
  override suspend fun purchase(product: StoreProduct): PurchaseResult = withContext(Dispatchers.Main.immediate) {
    try {
      val owner = currentCustomer()
      val raw = requireNotNull(product.rawProduct) { "Native Play product required." }
      val type = when (raw.productType) {
        BillingClient.ProductType.SUBS -> ProductType.SUBS
        BillingClient.ProductType.INAPP -> ProductType.INAPP
        else -> error("Unsupported Play product type.")
      }
      val candidates = purchases.awaitGetProducts(listOf(product.storeProductId), type)
        .filterIsInstance<GoogleStoreProduct>()
      check(purchases.appUserID == owner) { "RevenueCat identity changed during product lookup." }
      val activity = requireNotNull(currentActivity()) { "A resumed host Activity is required." }
      check(!activity.isFinishing && !activity.isDestroyed) { "Host Activity is closing." }
      val builder = if (type == ProductType.SUBS) {
        val expected = raw.subscriptionOfferDetails.orEmpty().singleOrNull {
          it.basePlanId == product.basePlanId && it.offerId == product.offerId
        } ?: error("The displayed subscription offer is unavailable.")
        val option = selectSubscriptionOption(
          product.storeProductId, expected.basePlanId, expected.offerId, expected.offerToken,
          candidates.flatMap { it.subscriptionOptions.orEmpty() }.filterIsInstance<GoogleSubscriptionOption>(),
        )
        val providerOffer = option.productDetails.subscriptionOfferDetails.orEmpty().singleOrNull {
          it.basePlanId == expected.basePlanId && it.offerId == expected.offerId
        } ?: error("Provider offer details are unavailable.")
        check(subscriptionTerms(expected) == subscriptionTerms(providerOffer)) {
          "The subscription price changed; refresh the Experience."
        }
        PurchaseParams.Builder(activity, option)
      } else {
        // This provider version has no explicit one-time purchase-option API.
        check(product.purchaseOptionId == null && product.offerId == null) {
          "RevenueCat 10.21.1 cannot preserve an explicit one-time purchase option."
        }
        val candidate = candidates.singleOrNull { it.productId == product.storeProductId && it.type == type }
          ?: error("The displayed one-time product is unavailable or ambiguous.")
        check(oneTimeTerms(raw) == oneTimeTerms(candidate.productDetails)) {
          "The one-time purchase terms changed; refresh the Experience."
        }
        PurchaseParams.Builder(activity, candidate)
      }
      configureReplacement(product, builder)
      builder.isPersonalizedPrice(product.isOfferPersonalized)
      check(purchases.appUserID == owner) { "RevenueCat identity changed before checkout." }
      purchases.awaitPurchase(builder.build())
      check(purchases.appUserID == owner) { "RevenueCat identity changed during checkout." }
      PurchaseResult.Purchased
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      mapPurchaseFailure(failure)
    }
  }

  override suspend fun restorePurchases(): RestoreResult = try {
    val owner = currentCustomer()
    val customer = purchases.awaitRestore()
    check(purchases.appUserID == owner) { "RevenueCat identity changed during restore." }
    if (customer.entitlements.active.isEmpty()) RestoreResult.NoPurchases else RestoreResult.Restored
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (failure: Exception) {
    RestoreResult.Failed(failure)
  }

  private fun currentCustomer(): String = purchases.appUserID.also {
    check(expectedCustomerId == null || it == expectedCustomerId) {
      "RevenueCat has not resolved the expected customer identity."
    }
  }
}

internal fun subscriptionTerms(offer: ProductDetails.SubscriptionOfferDetails): List<List<Any>> =
  offer.pricingPhases.pricingPhaseList.map {
    listOf(it.priceAmountMicros, it.priceCurrencyCode, it.formattedPrice,
      it.billingPeriod, it.billingCycleCount, it.recurrenceMode)
  }

internal fun oneTimeTerms(product: ProductDetails): List<Any?> {
  val offer = requireNotNull(product.oneTimePurchaseOfferDetails) { "One-time price unavailable." }
  check(offer.offerToken.isNullOrEmpty()) {
    "RevenueCat 10.21.1 cannot forward a one-time offer token."
  }
  return listOf(offer.priceAmountMicros, offer.priceCurrencyCode, offer.formattedPrice, offer.offerToken)
}

internal fun selectSubscriptionOption(
  productId: String,
  basePlanId: String,
  offerId: String?,
  offerToken: String,
  options: List<GoogleSubscriptionOption>,
): GoogleSubscriptionOption = options.singleOrNull {
  it.productId == productId && it.basePlanId == basePlanId &&
    it.offerId == offerId && it.offerToken == offerToken
} ?: error("RevenueCat cannot purchase the exact displayed subscription offer.")

internal fun mapPurchaseFailure(failure: Exception): PurchaseResult = when {
  failure is PurchasesTransactionException && failure.userCancelled -> PurchaseResult.Cancelled
  failure is PurchasesException && failure.code == PurchasesErrorCode.PurchaseCancelledError -> PurchaseResult.Cancelled
  failure is PurchasesException && failure.code == PurchasesErrorCode.PaymentPendingError -> PurchaseResult.Pending
  else -> PurchaseResult.Failed(failure)
}
