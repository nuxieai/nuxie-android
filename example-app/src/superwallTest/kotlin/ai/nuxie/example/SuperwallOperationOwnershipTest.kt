package ai.nuxie.example

import ai.nuxie.example.superwall.NuxieSuperwallPurchaseDelegate
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.billing.PurchaseResult
import com.android.billingclient.api.ProductDetails
import com.superwall.sdk.delegate.PurchaseResult as SuperwallPurchaseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.RestorationResult
import com.superwall.sdk.models.entitlements.SubscriptionStatus
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class SuperwallOperationOwnershipTest {
  @Test fun actualPurchaseContinuationOutlivesWaiterAndHoldsDrain() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    try {
      val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
      constructor.isAccessible = true
      val raw = constructor.newInstance("""{"productId":"coins","type":"inapp","title":"Coins","name":"Coins","description":"Coins","oneTimePurchaseOfferDetailsList":[{"purchaseOptionId":"buy","offerIdToken":"buy-base","formattedPrice":"€2.00","priceAmountMicros":2000000,"priceCurrencyCode":"EUR"}]}""")
      val product = mock(StoreProduct::class.java)
      `when`(product.rawProduct).thenReturn(raw)
      `when`(product.purchaseOptionId).thenReturn("buy")
      lateinit var continuation: Continuation<Result<SuperwallPurchaseResult>>
      var calls = 0
      var identityReads = 0
      val superwall = mock(Superwall::class.java) {
        when {
          it.method.name == "getUserId" -> { identityReads++; "alice" }
          it.method.name.startsWith("purchase-") -> {
            calls++
            @Suppress("UNCHECKED_CAST")
            continuation = it.rawArguments.last() as Continuation<Result<SuperwallPurchaseResult>>
            COROUTINE_SUSPENDED
          }
          else -> RETURNS_DEFAULTS.answer(it)
        }
      }
      val owner = ProviderOperations(NuxieSuperwallPurchaseDelegate(superwall), dispatcher)
      val waiter = async { owner.purchase(product) }
      testScheduler.runCurrent()
      assertEquals(1, calls)
      val beforeCompletion = identityReads
      waiter.cancelAndJoin()
      val drain = async { owner.closeAndAwait() }
      testScheduler.runCurrent()
      assertFalse(drain.isCompleted)
      assertTrue(owner.purchase(product) is PurchaseResult.Failed)
      assertEquals(1, calls)
      continuation.resume(Result.success(SuperwallPurchaseResult.Purchased()))
      drain.await()
      assertEquals(beforeCompletion + 1, identityReads)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test fun actualRestoreContinuationOutlivesWaiterAndHoldsDrain() = runTest {
    lateinit var continuation: Continuation<Result<RestorationResult>>
    var completedIdentityReads = 0
    var calls = 0
    val superwall = mock(Superwall::class.java) {
      when {
        it.method.name == "getUserId" -> { completedIdentityReads++; "alice" }
        it.method.name == "getSubscriptionStatus" -> MutableStateFlow(SubscriptionStatus.Active(emptySet()))
        it.method.name.startsWith("restorePurchases-") -> {
          calls++
          @Suppress("UNCHECKED_CAST")
          continuation = it.rawArguments.last() as Continuation<Result<RestorationResult>>
          COROUTINE_SUSPENDED
        }
        else -> RETURNS_DEFAULTS.answer(it)
      }
    }
    val owner = ProviderOperations(NuxieSuperwallPurchaseDelegate(superwall), StandardTestDispatcher(testScheduler))
    val waiter = async { owner.restorePurchases() }
    testScheduler.runCurrent()
    waiter.cancelAndJoin()
    val drain = async { owner.closeAndAwait() }
    testScheduler.runCurrent()
    assertFalse(drain.isCompleted)
    assertTrue(owner.restorePurchases() is RestoreResult.Failed)
    assertEquals(1, calls)
    assertEquals(1, completedIdentityReads)
    continuation.resume(Result.success(RestorationResult.Restored()))
    drain.await()
    assertEquals("Adapter completes after cancellation of its original waiter", 2, completedIdentityReads)
  }
}
