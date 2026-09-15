package ai.nuxie.example.superwall

import ai.nuxie.sdk.billing.PurchaseResult
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.delegate.PurchaseResult as SuperwallPurchaseResult
import com.superwall.sdk.store.abstractions.product.RawStoreProduct
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SuperwallCheckoutTest {
  @Test fun preservesTheResolvedSubscriptionOffer() {
    val raw = subscription()
    val selected = exactSuperwallProduct(raw, "annual", null, "launch", false).rawStoreProduct!!
    assertSame(raw, selected.underlyingProductDetails)
    val offer = selected.selectedOffer as RawStoreProduct.SelectedOfferDetails.Subscription
    assertEquals("annual", offer.underlying.basePlanId)
    assertEquals("launch", offer.underlying.offerId)
    assertEquals("annual-launch", offer.underlying.offerToken)
  }

  @Test fun noOfferDoesNotSelectTheTrial() {
    val selected = exactSuperwallProduct(subscription(), "annual", null, null, false).rawStoreProduct!!
    val offer = selected.selectedOffer as RawStoreProduct.SelectedOfferDetails.Subscription
    assertNull(offer.underlying.offerId)
    assertEquals("annual-base", offer.underlying.offerToken)
  }

  @Test fun missingOfferCannotSilentlyFallBackToBasePlan() {
    assertThrows(IllegalStateException::class.java) {
      exactSuperwallProduct(subscription(), "annual", null, "missing", false)
    }
  }

  @Test fun rejectsPersonalizedTermsBeforeProviderCheckout() {
    assertThrows(IllegalStateException::class.java) {
      exactSuperwallProduct(subscription(), "annual", null, "launch", true)
    }
  }

  @Test fun selectsExactOneTimePurchaseOption() {
    val raw = oneTime()
    assertEquals(2, raw.oneTimePurchaseOfferDetailsList!!.size)
    val selected = exactSuperwallProduct(raw, null, "buy", "launch", false).rawStoreProduct!!
    val offer = selected.selectedOffer as RawStoreProduct.SelectedOfferDetails.OneTime
    assertSame(raw, selected.underlyingProductDetails)
    assertEquals("buy", offer.purchaseOptionId)
    assertEquals("launch", offer.offerId)
    assertEquals("buy-launch", offer.underlying.offerToken)
    assertThrows(IllegalArgumentException::class.java) {
      exactSuperwallProduct(raw, null, "buy", "missing", false)
    }
  }

  @Test fun mapsEveryProviderOutcomeWithoutGrantingOnPending() {
    assertEquals(PurchaseResult.Purchased, mapPurchaseResult(SuperwallPurchaseResult.Purchased()))
    assertEquals(PurchaseResult.Cancelled, mapPurchaseResult(SuperwallPurchaseResult.Cancelled()))
    assertEquals(PurchaseResult.Pending, mapPurchaseResult(SuperwallPurchaseResult.Pending()))
    val failure = mapPurchaseResult(SuperwallPurchaseResult.Failed("Checkout failed")) as PurchaseResult.Failed
    assertEquals("Checkout failed", failure.cause.message)
  }

  private fun subscription(): ProductDetails {
    fun phase(period: String) = """{"billingPeriod":"$period","priceCurrencyCode":"EUR","formattedPrice":"€9.99","priceAmountMicros":9990000,"recurrenceMode":1,"billingCycleCount":0}"""
    return raw("""{"productId":"pro","type":"subs","title":"Pro","name":"Pro","description":"Pro","subscriptionOfferDetails":[
      {"basePlanId":"monthly","offerIdToken":"monthly-base","pricingPhases":[${phase("P1M")}]},
      {"basePlanId":"annual","offerIdToken":"annual-base","pricingPhases":[${phase("P1Y")}]},
      {"basePlanId":"annual","offerId":"launch","offerIdToken":"annual-launch","pricingPhases":[${phase("P1M")},${phase("P1Y")}]}
    ]}""")
  }

  private fun oneTime(): ProductDetails = raw("""{"productId":"coins","type":"inapp","title":"Coins","name":"Coins","description":"Coins","oneTimePurchaseOfferDetailsList":[
    {"purchaseOptionId":"buy","offerIdToken":"buy-base","formattedPrice":"€2.00","priceAmountMicros":2000000,"priceCurrencyCode":"EUR"},
    {"purchaseOptionId":"buy","offerId":"launch","offerIdToken":"buy-launch","formattedPrice":"€1.00","priceAmountMicros":1000000,"priceCurrencyCode":"EUR"}
  ]}""")

  private fun raw(json: String): ProductDetails {
    val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(json)
  }
}
