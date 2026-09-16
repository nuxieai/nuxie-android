package ai.nuxie.example.superwall

import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.StoreProduct
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.delegate.PurchaseResult as SuperwallPurchaseResult
import com.superwall.sdk.models.entitlements.Entitlement
import com.superwall.sdk.store.abstractions.product.StoreProduct as SuperwallStoreProduct
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class SuperwallIdentityTest {
  @Before fun setDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @After fun resetDispatcher() { Dispatchers.resetMain() }

  @Test fun restoreRejectsChangedCustomerAndPreservesStableResult() = runTest {
    for (expected in listOf(null, "alice")) for (active in listOf(false, true)) for (changeCustomer in listOf(false, true)) {
      var customer = "alice"
      lateinit var continuation: Continuation<Result<RestorationResult>>
      val superwall = mock(Superwall::class.java) {
        when {
          it.method.name == "getUserId" -> customer
          it.method.name == "getSubscriptionStatus" -> MutableStateFlow(SubscriptionStatus.Active(
            if (active) setOf(mock(Entitlement::class.java)) else emptySet(),
          ))
          it.method.name.startsWith("restorePurchases-") -> {
            @Suppress("UNCHECKED_CAST")
            continuation = it.rawArguments.last() as Continuation<Result<RestorationResult>>
            COROUTINE_SUSPENDED
          }
          else -> RETURNS_DEFAULTS.answer(it)
        }
      }
      val delegate = NuxieSuperwallPurchaseDelegate(superwall, expectedCustomerId = expected)
      val result = async { delegate.restorePurchases() }
      testScheduler.runCurrent()
      if (changeCustomer) customer = "bob"
      continuation.resume(Result.success(RestorationResult.Restored()))
      if (changeCustomer) assertTrue("Changed provider customer must fail", result.await() is RestoreResult.Failed)
      else assertEquals(if (active) RestoreResult.Restored else RestoreResult.NoPurchases, result.await())
    }
  }

  @Test fun mismatchedExpectedCustomerNeverStartsProviderRestoreOrCheckout() = runTest {
    val superwall = mock(Superwall::class.java)
    `when`(superwall.userId).thenReturn("bob")
    val delegate = NuxieSuperwallPurchaseDelegate(superwall, expectedCustomerId = "alice")
    assertTrue(delegate.restorePurchases() is RestoreResult.Failed)
    assertTrue(delegate.purchase(mock(StoreProduct::class.java)) is PurchaseResult.Failed)
    assertTrue(mockingDetails(superwall).invocations.all { it.method.name == "getUserId" })
  }

  @Test fun purchaseRejectsChangedCustomerAndPreservesStableSuccess() = runTest {
    for (expected in listOf(null, "alice")) for (changeCustomer in listOf(false, true)) {
      val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
      constructor.isAccessible = true
      val raw = constructor.newInstance("""{"productId":"coins","type":"inapp","title":"Coins","name":"Coins","description":"Coins","oneTimePurchaseOfferDetailsList":[{"purchaseOptionId":"buy","offerIdToken":"buy-base","formattedPrice":"€2.00","priceAmountMicros":2000000,"priceCurrencyCode":"EUR"}]}""")
      val product = mock(StoreProduct::class.java)
      `when`(product.rawProduct).thenReturn(raw)
      `when`(product.purchaseOptionId).thenReturn("buy")
      var customer = "alice"
      lateinit var continuation: Continuation<Result<SuperwallPurchaseResult>>
      val superwall = mock(Superwall::class.java) {
        when {
          it.method.name == "getUserId" -> customer
          it.method.name.startsWith("purchase-") -> {
            assertSame(raw, it.getArgument<SuperwallStoreProduct>(0).rawStoreProduct!!.underlyingProductDetails)
            @Suppress("UNCHECKED_CAST")
            continuation = it.rawArguments.last() as Continuation<Result<SuperwallPurchaseResult>>
            COROUTINE_SUSPENDED
          }
          else -> RETURNS_DEFAULTS.answer(it)
        }
      }
      val delegate = NuxieSuperwallPurchaseDelegate(superwall, expectedCustomerId = expected)
      val result = async { delegate.purchase(product) }
      testScheduler.runCurrent()
      if (changeCustomer) customer = "bob"
      continuation.resume(Result.success(SuperwallPurchaseResult.Purchased()))
      if (changeCustomer) assertTrue(result.await() is PurchaseResult.Failed)
      else assertEquals(PurchaseResult.Purchased, result.await())
    }
  }
}
