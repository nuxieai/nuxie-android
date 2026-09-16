package ai.nuxie.example

import ai.nuxie.example.revenuecat.NuxieRevenueCatPurchaseDelegate
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.billing.PurchaseResult
import android.app.Activity
import com.android.billingclient.api.ProductDetails
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.interfaces.GetStoreProductsCallback
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.models.GoogleStoreProduct
import com.revenuecat.purchases.models.GooglePurchasingData
import com.revenuecat.purchases.models.StoreTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class RevenueCatOperationOwnershipTest {
  @Test fun actualPurchaseCallbackOutlivesWaiterAndHoldsDrain() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    try {
      val raw = mock(ProductDetails::class.java, RETURNS_DEEP_STUBS)
      `when`(raw.productType).thenReturn("inapp")
      `when`(raw.oneTimePurchaseOfferDetails!!.offerToken).thenReturn(null)
      val product = mock(StoreProduct::class.java)
      `when`(product.rawProduct).thenReturn(raw)
      `when`(product.storeProductId).thenReturn("tip")
      val candidate = mock(GoogleStoreProduct::class.java)
      `when`(candidate.productId).thenReturn("tip")
      `when`(candidate.type).thenReturn(ProductType.INAPP)
      `when`(candidate.productDetails).thenReturn(raw)
      `when`(candidate.purchasingData).thenReturn(GooglePurchasingData.InAppProduct("tip", raw))
      lateinit var callback: PurchaseCallback
      var identityReads = 0
      var calls = 0
      val purchases = mock(Purchases::class.java) {
        when (it.method.name) {
          "getAppUserID" -> { identityReads++; "alice" }
          "getProducts" -> { it.getArgument<GetStoreProductsCallback>(2).onReceived(listOf(candidate)); null }
          "purchase" -> { calls++; callback = it.getArgument(1); null }
          else -> RETURNS_DEFAULTS.answer(it)
        }
      }
      val owner = ProviderOperations(NuxieRevenueCatPurchaseDelegate({ mock(Activity::class.java) }, purchases), dispatcher)
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
      callback.onCompleted(mock(StoreTransaction::class.java), mock(CustomerInfo::class.java))
      drain.await()
      assertEquals(beforeCompletion + 1, identityReads)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test fun actualRestoreCallbackOutlivesWaiterAndHoldsDrain() = runTest {
    lateinit var callback: ReceiveCustomerInfoCallback
    var completedIdentityReads = 0
    val purchases = mock(Purchases::class.java) {
      when (it.method.name) {
        "getAppUserID" -> { completedIdentityReads++; "alice" }
        "restorePurchases" -> { callback = it.getArgument(0); null }
        else -> RETURNS_DEFAULTS.answer(it)
      }
    }
    val owner = ProviderOperations(NuxieRevenueCatPurchaseDelegate({ null }, purchases), StandardTestDispatcher(testScheduler))
    val waiter = async { owner.restorePurchases() }
    testScheduler.runCurrent()
    assertEquals(1, completedIdentityReads)
    waiter.cancelAndJoin()
    val drain = async { owner.closeAndAwait() }
    testScheduler.runCurrent()
    assertFalse(drain.isCompleted)
    assertTrue(owner.restorePurchases() is RestoreResult.Failed)
    assertEquals(1, mockingDetails(purchases).invocations.count { it.method.name == "restorePurchases" })
    val info = mock(CustomerInfo::class.java, RETURNS_DEEP_STUBS)
    `when`(info.entitlements.active).thenReturn(emptyMap())
    callback.onReceived(info)
    drain.await()
    assertEquals("Adapter completion identity check still executes after waiter cancellation", 2, completedIdentityReads)
  }
}
