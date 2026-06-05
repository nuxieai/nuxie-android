package io.nuxie.sdk

import com.android.billingclient.api.BillingClient
import io.nuxie.sdk.flows.FlowProduct
import io.nuxie.sdk.purchases.PlayBillingClient
import io.nuxie.sdk.purchases.PlayBillingProductDetailsSnapshot
import io.nuxie.sdk.purchases.PlayBillingPurchaseSnapshot
import io.nuxie.sdk.purchases.PlayBillingResult
import io.nuxie.sdk.purchases.PlayStoreProductType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayStoreFlowProductServiceSelectionTest {
  private class RecordingBillingClient : PlayBillingClient {
    var startConnectionCalls = 0
    var productQueryCalls = 0
    override var isReady: Boolean = false
      private set

    override fun setPurchasesUpdatedListener(
      listener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)?,
    ) = Unit

    override fun startConnection(
      onSetupFinished: (PlayBillingResult) -> Unit,
      onDisconnected: () -> Unit,
    ) {
      startConnectionCalls += 1
      isReady = true
      onSetupFinished(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"))
    }

    override fun endConnection() {
      isReady = false
    }

    override fun queryPurchases(
      productType: PlayStoreProductType,
      includeSuspendedSubscriptions: Boolean,
      listener: (PlayBillingResult, List<PlayBillingPurchaseSnapshot>) -> Unit,
    ) {
      listener(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"), emptyList())
    }

    override fun queryProductDetails(
      productType: PlayStoreProductType,
      productIds: List<String>,
      listener: (PlayBillingResult, List<PlayBillingProductDetailsSnapshot>) -> Unit,
    ) {
      productQueryCalls += 1
      listener(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"), emptyList())
    }
  }

  @Test
  fun playStoreFlowProductService_usesNoopWithoutBillingClient() = runTest {
    val products = playStoreFlowProductService(null)
      .fetchProducts(setOf("pro_monthly"))

    assertEquals(emptyList<FlowProduct>(), products)
  }

  @Test
  fun playStoreFlowProductService_usesBillingWhenPlayStoreSyncIsEnabled() = runTest {
    val billingClient = RecordingBillingClient()

    playStoreFlowProductService(billingClient)
      .fetchProducts(setOf("pro_monthly"))

    assertEquals(1, billingClient.startConnectionCalls)
    assertEquals(2, billingClient.productQueryCalls)
  }
}
