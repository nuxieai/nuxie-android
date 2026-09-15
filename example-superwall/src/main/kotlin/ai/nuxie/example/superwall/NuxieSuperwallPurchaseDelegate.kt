package ai.nuxie.example.superwall

import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.PurchaseResult as SuperwallPurchaseResult
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import com.superwall.sdk.store.abstractions.product.BasePlanType
import com.superwall.sdk.store.abstractions.product.OfferType
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import com.superwall.sdk.store.abstractions.product.StoreProduct as SuperwallStoreProduct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Example source; Superwall must be configured with its automatic purchase controller. */
class NuxieSuperwallPurchaseDelegate(
  private val superwall: Superwall = Superwall.instance,
) : NuxiePurchaseDelegate {
  override suspend fun purchase(product: StoreProduct): PurchaseResult = withContext(Dispatchers.Main.immediate) {
    try {
      val exact = exactSuperwallProduct(
        requireNotNull(product.rawProduct) { "Native Play product required." },
        product.basePlanId, product.purchaseOptionId, product.offerId, product.isOfferPersonalized,
      )
      mapPurchaseResult(superwall.purchase(exact).getOrThrow())
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      PurchaseResult.Failed(failure)
    }
  }

  override suspend fun restorePurchases(): RestoreResult = withContext(Dispatchers.Main.immediate) {
    try {
      when (val result = superwall.restorePurchases().getOrThrow()) {
        is RestorationResult.Restored -> {
          val status = superwall.subscriptionStatus.value
          if (status is SubscriptionStatus.Active && status.entitlements.isNotEmpty()) {
            RestoreResult.Restored
          } else RestoreResult.NoPurchases
        }
        is RestorationResult.Failed -> RestoreResult.Failed(
          result.error ?: IllegalStateException("Superwall restore failed without an error."),
        )
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      RestoreResult.Failed(failure)
    }
  }
}

internal fun exactSuperwallProduct(
  raw: ProductDetails,
  basePlanId: String?,
  purchaseOptionId: String?,
  offerId: String?,
  personalized: Boolean,
): SuperwallStoreProduct {
  check(!personalized) { "Superwall 2.8.3 cannot forward personalized-price terms." }
  val plan = when (raw.productType) {
    BillingClient.ProductType.SUBS -> requireNotNull(basePlanId) { "Subscription base plan required." }
    BillingClient.ProductType.INAPP -> purchaseOptionId
    else -> error("Unsupported Play product type.")
  }
  val identifier = if (plan != null) "${raw.productId}:$plan:${offerId ?: "sw-none"}" else raw.productId
  val selected = RawStoreProduct(
    raw, identifier, plan?.let(BasePlanType::Specific) ?: BasePlanType.Auto,
    offerId?.let(OfferType::Specific) ?: OfferType.None,
  )
  when (val offer = selected.selectedOffer) {
    is RawStoreProduct.SelectedOfferDetails.Subscription -> {
      val expected = raw.subscriptionOfferDetails.orEmpty().singleOrNull {
        it.basePlanId == basePlanId && it.offerId == offerId
      } ?: error("Displayed subscription offer unavailable or ambiguous.")
      check(offer.underlying.basePlanId == expected.basePlanId &&
        offer.underlying.offerId == expected.offerId && offer.underlying.offerToken == expected.offerToken) {
        "Superwall selected a different subscription offer."
      }
    }
    is RawStoreProduct.SelectedOfferDetails.OneTime -> {
      val expected = if (purchaseOptionId != null) {
        raw.oneTimePurchaseOfferDetailsList.orEmpty().singleOrNull {
          it.purchaseOptionId == purchaseOptionId && it.offerId == offerId
        }
      } else raw.oneTimePurchaseOfferDetails
      requireNotNull(expected) { "Displayed one-time purchase option unavailable or ambiguous." }
      check(offer.underlying.offerToken == expected.offerToken &&
        offer.underlying.purchaseOptionId == expected.purchaseOptionId &&
        offer.underlying.offerId == expected.offerId) { "Superwall selected different one-time terms." }
    }
    null -> error("Superwall could not resolve the displayed product.")
  }
  return SuperwallStoreProduct(selected)
}

internal fun mapPurchaseResult(result: SuperwallPurchaseResult): PurchaseResult = when (result) {
  is SuperwallPurchaseResult.Purchased -> PurchaseResult.Purchased
  is SuperwallPurchaseResult.Cancelled -> PurchaseResult.Cancelled
  is SuperwallPurchaseResult.Pending -> PurchaseResult.Pending
  is SuperwallPurchaseResult.Failed -> PurchaseResult.Failed(IllegalStateException(result.errorMessage))
}
