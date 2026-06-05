package io.nuxie.sdk.purchases

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import io.nuxie.sdk.flows.FlowProduct
import io.nuxie.sdk.flows.ProductPeriod
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GooglePlayBillingProductServiceTest {
  private class FakeBillingClient(
    private val setupResult: PlayBillingResult = PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"),
    private val productsByType: Map<PlayStoreProductType, List<PlayBillingProductDetailsSnapshot>> = emptyMap(),
    private val queryResultsByType: Map<PlayStoreProductType, PlayBillingResult> = emptyMap(),
  ) : PlayBillingClient {
    data class ProductQuery(
      val productType: PlayStoreProductType,
      val productIds: List<String>,
    )

    override var isReady: Boolean = false
      private set

    val productQueries = mutableListOf<ProductQuery>()
    var startConnectionCalls = 0
    var ended = false

    override fun setPurchasesUpdatedListener(
      listener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)?,
    ) {
    }

    override fun startConnection(
      onSetupFinished: (PlayBillingResult) -> Unit,
      onDisconnected: () -> Unit,
    ) {
      startConnectionCalls += 1
      isReady = setupResult.responseCode == BillingClient.BillingResponseCode.OK
      onSetupFinished(setupResult)
    }

    override fun endConnection() {
      ended = true
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
      productQueries += ProductQuery(productType, productIds)
      listener(
        queryResultsByType[productType] ?: PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"),
        productsByType[productType].orEmpty(),
      )
    }
  }

  @Test
  fun fetchProducts_queries_subscriptions_and_one_time_products_and_preserves_requested_order() = runTest {
    val subscription = subscriptionDetails(
      productId = "pro_monthly",
      name = "Nuxie Pro",
      phases = listOf(
        pricingPhase(
          formattedPrice = "\$9.99",
          priceAmountMicros = 9_990_000,
          billingPeriod = "P1M",
          recurrenceMode = ProductDetails.RecurrenceMode.INFINITE_RECURRING,
        )
      ),
    )
    val oneTime = oneTimeDetails(
      productId = "lifetime_unlock",
      name = "Lifetime",
      formattedPrice = "\$99.99",
    )
    val client = FakeBillingClient(
      productsByType = mapOf(
        PlayStoreProductType.SUBSCRIPTION to listOf(subscription),
        PlayStoreProductType.ONE_TIME to listOf(oneTime),
      )
    )
    val service = GooglePlayBillingProductService(client)

    val products = service.fetchProducts(linkedSetOf("lifetime_unlock", "pro_monthly"))

    assertEquals(1, client.startConnectionCalls)
    assertEquals(
      listOf(
        FakeBillingClient.ProductQuery(
          productType = PlayStoreProductType.SUBSCRIPTION,
          productIds = listOf("lifetime_unlock", "pro_monthly"),
        ),
        FakeBillingClient.ProductQuery(
          productType = PlayStoreProductType.ONE_TIME,
          productIds = listOf("lifetime_unlock", "pro_monthly"),
        ),
      ),
      client.productQueries,
    )
    assertEquals(
      listOf(
        FlowProduct(
          id = "lifetime_unlock",
          name = "Lifetime",
          price = "\$99.99",
          period = null,
        ),
        FlowProduct(
          id = "pro_monthly",
          name = "Nuxie Pro",
          price = "\$9.99",
          period = ProductPeriod.MONTH,
        ),
      ),
      products,
    )
  }

  @Test
  fun fetchProducts_uses_recurring_paid_subscription_phase_over_free_trial_phase() = runTest {
    val subscription = subscriptionDetails(
      productId = "pro_monthly",
      name = "Nuxie Pro",
      phases = listOf(
        pricingPhase(
          formattedPrice = "Free",
          priceAmountMicros = 0,
          billingPeriod = "P1W",
          recurrenceMode = ProductDetails.RecurrenceMode.FINITE_RECURRING,
        ),
        pricingPhase(
          formattedPrice = "\$9.99",
          priceAmountMicros = 9_990_000,
          billingPeriod = "P1M",
          recurrenceMode = ProductDetails.RecurrenceMode.INFINITE_RECURRING,
        ),
      ),
    )
    val client = FakeBillingClient(
      productsByType = mapOf(PlayStoreProductType.SUBSCRIPTION to listOf(subscription))
    )
    val service = GooglePlayBillingProductService(client)

    val product = service.fetchProducts(setOf("pro_monthly")).single()

    assertEquals("\$9.99", product.price)
    assertEquals(ProductPeriod.MONTH, product.period)
  }

  @Test
  fun fetchProducts_dedupes_and_trims_product_ids_before_querying() = runTest {
    val subscription = subscriptionDetails(
      productId = "pro_annual",
      name = "Annual",
      phases = listOf(
        pricingPhase(
          formattedPrice = "\$79.99",
          priceAmountMicros = 79_990_000,
          billingPeriod = "P1Y",
          recurrenceMode = ProductDetails.RecurrenceMode.INFINITE_RECURRING,
        )
      ),
    )
    val client = FakeBillingClient(
      productsByType = mapOf(PlayStoreProductType.SUBSCRIPTION to listOf(subscription))
    )
    val service = GooglePlayBillingProductService(client)

    val products = service.fetchProducts(linkedSetOf(" pro_annual ", "", "pro_annual"))

    assertEquals(
      listOf(
        FakeBillingClient.ProductQuery(
          productType = PlayStoreProductType.SUBSCRIPTION,
          productIds = listOf("pro_annual"),
        ),
        FakeBillingClient.ProductQuery(
          productType = PlayStoreProductType.ONE_TIME,
          productIds = listOf("pro_annual"),
        ),
      ),
      client.productQueries,
    )
    assertEquals("pro_annual", products.single().id)
    assertEquals(ProductPeriod.YEAR, products.single().period)
  }

  @Test
  fun fetchProducts_returns_empty_when_billing_setup_fails() = runTest {
    val client = FakeBillingClient(
      setupResult = PlayBillingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "offline")
    )
    val service = GooglePlayBillingProductService(client)

    val products = service.fetchProducts(setOf("pro_monthly"))

    assertEquals(emptyList<FlowProduct>(), products)
    assertEquals(emptyList<FakeBillingClient.ProductQuery>(), client.productQueries)
  }

  @Test
  fun fetchProducts_keeps_partial_results_when_one_product_type_query_fails() = runTest {
    val oneTime = oneTimeDetails(
      productId = "coins_100",
      name = "100 coins",
      formattedPrice = "\$1.99",
    )
    val client = FakeBillingClient(
      productsByType = mapOf(PlayStoreProductType.ONE_TIME to listOf(oneTime)),
      queryResultsByType = mapOf(
        PlayStoreProductType.SUBSCRIPTION to PlayBillingResult(
          BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
          "subscription catalog unavailable",
        )
      ),
    )
    val service = GooglePlayBillingProductService(client)

    val products = service.fetchProducts(setOf("coins_100"))

    assertEquals(
      listOf(
        FlowProduct(
          id = "coins_100",
          name = "100 coins",
          price = "\$1.99",
          period = null,
        )
      ),
      products,
    )
  }

  private fun subscriptionDetails(
    productId: String,
    name: String,
    phases: List<PlayBillingPricingPhaseSnapshot>,
  ): PlayBillingProductDetailsSnapshot {
    return PlayBillingProductDetailsSnapshot(
      productId = productId,
      productType = PlayStoreProductType.SUBSCRIPTION,
      name = name,
      title = "$name (Nuxie)",
      subscriptionOffers = listOf(
        PlayBillingSubscriptionOfferSnapshot(
          basePlanId = "base_monthly",
          offerId = null,
          pricingPhases = phases,
        )
      ),
    )
  }

  private fun oneTimeDetails(
    productId: String,
    name: String,
    formattedPrice: String,
  ): PlayBillingProductDetailsSnapshot {
    return PlayBillingProductDetailsSnapshot(
      productId = productId,
      productType = PlayStoreProductType.ONE_TIME,
      name = name,
      title = "$name (Nuxie)",
      oneTimeOffers = listOf(
        PlayBillingOneTimeOfferSnapshot(
          formattedPrice = formattedPrice,
          priceAmountMicros = 1_990_000,
        )
      ),
    )
  }

  private fun pricingPhase(
    formattedPrice: String,
    priceAmountMicros: Long,
    billingPeriod: String,
    recurrenceMode: Int,
  ): PlayBillingPricingPhaseSnapshot {
    return PlayBillingPricingPhaseSnapshot(
      formattedPrice = formattedPrice,
      priceAmountMicros = priceAmountMicros,
      billingPeriod = billingPeriod,
      recurrenceMode = recurrenceMode,
    )
  }
}
