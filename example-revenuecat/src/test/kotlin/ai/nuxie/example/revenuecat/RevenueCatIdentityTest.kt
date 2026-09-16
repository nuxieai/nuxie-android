package ai.nuxie.example.revenuecat

import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.StoreProduct
import android.app.Activity
import com.android.billingclient.api.ProductDetails
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.interfaces.GetStoreProductsCallback
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.models.GoogleStoreProduct
import com.revenuecat.purchases.models.GooglePurchasingData
import com.revenuecat.purchases.models.StoreTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Before
import org.junit.After
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class RevenueCatIdentityTest {
  @Before fun setDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
  @After fun resetDispatcher() { Dispatchers.resetMain() }

  @Test fun mismatchedExpectedCustomerNeverStartsProviderRestoreOrCheckout() = runTest {
    val purchases = mock(Purchases::class.java)
    `when`(purchases.appUserID).thenReturn("bob")
    val delegate = NuxieRevenueCatPurchaseDelegate({ null }, purchases, expectedCustomerId = "alice")
    assertTrue(delegate.restorePurchases() is RestoreResult.Failed)
    assertTrue(delegate.purchase(mock(StoreProduct::class.java)) is PurchaseResult.Failed)
    assertTrue(mockingDetails(purchases).invocations.all { it.method.name == "getAppUserID" })
  }

  @Test fun purchaseRejectsChangedCustomerAndPreservesStableSuccess() = runTest {
    for (changeCustomer in listOf(false, true)) {
      var customer = "alice"
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
      val purchases = mock(Purchases::class.java) {
        when (it.method.name) {
          "getAppUserID" -> customer
          "getProducts" -> { it.getArgument<GetStoreProductsCallback>(2).onReceived(listOf(candidate)); null }
          "purchase" -> { callback = it.getArgument(1); null }
          else -> RETURNS_DEFAULTS.answer(it)
        }
      }
      val delegate = NuxieRevenueCatPurchaseDelegate({ mock(Activity::class.java) }, purchases)
      val result = async { delegate.purchase(product) }
      testScheduler.runCurrent()
      if (changeCustomer) customer = "bob"
      callback.onCompleted(mock(StoreTransaction::class.java), mock(CustomerInfo::class.java))
      if (changeCustomer) assertTrue(result.await() is PurchaseResult.Failed)
      else assertEquals(PurchaseResult.Purchased, result.await())
    }
  }

  @Test fun restoreRejectsAProviderCustomerChangeWhileItsCallbackIsPending() = runTest {
    var customer = "alice"
    lateinit var callback: ReceiveCustomerInfoCallback
    val purchases = mock(Purchases::class.java) {
      when (it.method.name) {
        "getAppUserID" -> customer
        "restorePurchases" -> { callback = it.getArgument(0); null }
        else -> RETURNS_DEFAULTS.answer(it)
      }
    }
    val info = mock(CustomerInfo::class.java, RETURNS_DEEP_STUBS)
    `when`(info.entitlements.active).thenReturn(emptyMap())
    val delegate = NuxieRevenueCatPurchaseDelegate({ null }, purchases)
    val result = async { delegate.restorePurchases() }
    testScheduler.runCurrent()
    customer = "bob"
    callback.onReceived(info)
    assertTrue("A result from a changed provider session must fail", result.await() is RestoreResult.Failed)
  }

  @Test fun unchangedProviderCustomerPreservesNoPurchases() = runTest {
    val info = mock(CustomerInfo::class.java, RETURNS_DEEP_STUBS)
    `when`(info.entitlements.active).thenReturn(emptyMap())
    val purchases = mock(Purchases::class.java) {
      when (it.method.name) {
        "getAppUserID" -> "alice"
        "restorePurchases" -> { it.getArgument<ReceiveCustomerInfoCallback>(0).onReceived(info); null }
        else -> RETURNS_DEFAULTS.answer(it)
      }
    }
    assertEquals(RestoreResult.NoPurchases, NuxieRevenueCatPurchaseDelegate({ null }, purchases).restorePurchases())
  }
}
