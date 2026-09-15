package ai.nuxie.example.revenuecat

import ai.nuxie.sdk.billing.PurchaseResult
import com.android.billingclient.api.ProductDetails
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.models.GoogleSubscriptionOption
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RevenueCatCheckoutTest {
  @Test fun selectsTheDisplayedOptionAcrossProductsAndPlans() {
    val expected = option()
    val options = listOf(
      option(product = "other"), option(plan = "monthly"), option(offer = null),
      option(token = "changed"), expected,
    )
    assertSame(expected, selectSubscriptionOption("play-pro", "annual", "launch", "token", options))
  }

  @Test fun missingAndAmbiguousOptionsFailInsteadOfFallingBack() {
    for (options in listOf(emptyList(), listOf(option(offer = null)), listOf(option(), option()))) {
      assertThrows(IllegalStateException::class.java) {
        selectSubscriptionOption("play-pro", "annual", "launch", "token", options)
      }
    }
  }

  @Test fun basePlanDoesNotBecomeAnIntroductoryOffer() {
    val base = option(offer = null)
    assertSame(base, selectSubscriptionOption("play-pro", "annual", null, "token", listOf(option(), base)))
  }

  @Test fun changedPricesAreDifferentEvenWithTheSameOfferIdentity() {
    val displayed = raw(9990000).subscriptionOfferDetails!!.single()
    val reloaded = raw(10990000).subscriptionOfferDetails!!.single()
    assertEquals(subscriptionTerms(displayed), subscriptionTerms(raw(9990000).subscriptionOfferDetails!!.single()))
    assertNotEquals(subscriptionTerms(displayed), subscriptionTerms(reloaded))
  }

  @Test fun cancellationPendingAndFailureRemainDistinct() {
    assertEquals(PurchaseResult.Cancelled, mapPurchaseFailure(PurchasesTransactionException(
      PurchasesError(PurchasesErrorCode.UnknownError), true,
    )))
    assertEquals(PurchaseResult.Cancelled, mapPurchaseFailure(PurchasesException(
      PurchasesError(PurchasesErrorCode.PurchaseCancelledError),
    )))
    assertEquals(PurchaseResult.Pending, mapPurchaseFailure(PurchasesException(
      PurchasesError(PurchasesErrorCode.PaymentPendingError),
    )))
    val failure = PurchasesException(PurchasesError(PurchasesErrorCode.NetworkError))
    assertSame(failure, (mapPurchaseFailure(failure) as PurchaseResult.Failed).cause)
  }

  @Test fun oneTimeOfferTokensAreRejectedInsteadOfDiscarded() {
    val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
    constructor.isAccessible = true
    fun details(token: String) = constructor.newInstance("""{"productId":"tip","type":"inapp","title":"Tip","name":"Tip","description":"Tip","oneTimePurchaseOfferDetails":{"formattedPrice":"€1.00","priceAmountMicros":1000000,"priceCurrencyCode":"EUR"$token}}""")
    assertEquals(oneTimeTerms(details("")), oneTimeTerms(details("")))
    assertThrows(IllegalStateException::class.java) {
      oneTimeTerms(details(",\"offerIdToken\":\"exact-token\""))
    }
  }

  private fun option(product: String = "play-pro", plan: String = "annual", offer: String? = "launch", token: String = "token") =
    GoogleSubscriptionOption(product, plan, offer, emptyList(), emptyList(), raw(), token)

  private fun raw(price: Long = 9990000): ProductDetails {
    val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
    constructor.isAccessible = true
    return constructor.newInstance("""{"productId":"play-pro","type":"subs","title":"Pro","name":"Pro","description":"Pro","subscriptionOfferDetails":[{"basePlanId":"annual","offerId":"launch","offerIdToken":"token","pricingPhases":[{"billingPeriod":"P1Y","priceCurrencyCode":"EUR","formattedPrice":"€9.99","priceAmountMicros":$price,"recurrenceMode":1,"billingCycleCount":0}]}]}""")
  }
}
