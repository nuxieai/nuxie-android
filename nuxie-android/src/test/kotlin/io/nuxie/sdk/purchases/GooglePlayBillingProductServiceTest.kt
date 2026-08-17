package io.nuxie.sdk.purchases

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import io.nuxie.sdk.flows.FlowProduct
import io.nuxie.sdk.flows.FlowProductFetchException
import io.nuxie.sdk.flows.ProductPeriod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePlayBillingProductServiceTest {
  private class FakeBillingClient(
    private val setupResult: PlayBillingResult = PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"),
    private val productsByType: Map<PlayStoreProductType, List<PlayBillingProductDetailsSnapshot>> = emptyMap(),
    private val queryResultsByType: Map<PlayStoreProductType, PlayBillingResult> = emptyMap(),
    private val autoCompleteSetup: Boolean = true,
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
    private var pendingSetupFinished: ((PlayBillingResult) -> Unit)? = null

    override fun setPurchasesUpdatedListener(
      listener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)?,
    ) {
    }

    override fun startConnection(
      onSetupFinished: (PlayBillingResult) -> Unit,
      onDisconnected: () -> Unit,
    ) {
      startConnectionCalls += 1
      if (autoCompleteSetup) {
        isReady = setupResult.responseCode == BillingClient.BillingResponseCode.OK
        onSetupFinished(setupResult)
      } else {
        pendingSetupFinished = onSetupFinished
      }
    }

    fun completeSetup(result: PlayBillingResult = setupResult) {
      isReady = result.responseCode == BillingClient.BillingResponseCode.OK
      pendingSetupFinished?.invoke(result)
      pendingSetupFinished = null
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
  fun fetchProducts_exposes_the_store_eligible_offer_and_purchase_token() = runTest {
    val subscription = PlayBillingProductDetailsSnapshot(
      productId = "pro_monthly",
      productType = PlayStoreProductType.SUBSCRIPTION,
      name = "Nuxie Pro",
      title = "Nuxie Pro (Nuxie)",
      subscriptionOffers = listOf(
        PlayBillingSubscriptionOfferSnapshot(
          basePlanId = "monthly",
          offerId = "exit-discount",
          offerToken = "eligible-token",
          pricingPhases = listOf(
            pricingPhase(
              formattedPrice = "\$1.99",
              priceAmountMicros = 1_990_000,
              billingPeriod = "P1M",
              recurrenceMode = ProductDetails.RecurrenceMode.FINITE_RECURRING,
              billingCycleCount = 3,
            ),
            pricingPhase(
              formattedPrice = "\$9.99",
              priceAmountMicros = 9_990_000,
              billingPeriod = "P1M",
              recurrenceMode = ProductDetails.RecurrenceMode.INFINITE_RECURRING,
            ),
          ),
        ),
      ),
    )
    val service = GooglePlayBillingProductService(
      FakeBillingClient(
        productsByType = mapOf(
          PlayStoreProductType.SUBSCRIPTION to listOf(subscription),
        ),
      ),
    )

    val product = service.fetchProducts(setOf("pro_monthly")).single()

    assertEquals("\$9.99", product.price)
    assertEquals("exit-discount", product.offer?.id)
    assertEquals("\$1.99", product.offer?.price)
    assertEquals(3, product.offer?.periodCount)
    assertEquals("\$1.99 for 3 months", product.offer?.label)
    assertEquals("eligible-token", product.offer?.offerToken)
  }

  @Test
  fun fetchProducts_pairs_offer_with_renewal_phase_from_the_same_base_plan() = runTest {
    val subscription = PlayBillingProductDetailsSnapshot(
      productId = "pro",
      productType = PlayStoreProductType.SUBSCRIPTION,
      name = "Pro",
      title = "Pro",
      subscriptionOffers = listOf(
        PlayBillingSubscriptionOfferSnapshot(
          basePlanId = "annual",
          offerId = null,
          pricingPhases = listOf(pricingPhase("\$99.99", 99_990_000, "P1Y", ProductDetails.RecurrenceMode.INFINITE_RECURRING)),
        ),
        PlayBillingSubscriptionOfferSnapshot(
          basePlanId = "monthly",
          offerId = "monthly-discount",
          offerToken = "monthly-token",
          pricingPhases = listOf(
            pricingPhase("\$1.99", 1_990_000, "P1M", ProductDetails.RecurrenceMode.FINITE_RECURRING, 3),
            pricingPhase("\$9.99", 9_990_000, "P1M", ProductDetails.RecurrenceMode.INFINITE_RECURRING),
          ),
        ),
      ),
    )
    val service = GooglePlayBillingProductService(FakeBillingClient(productsByType = mapOf(PlayStoreProductType.SUBSCRIPTION to listOf(subscription))))

    val product = service.fetchProducts(setOf("pro")).single()

    assertEquals("\$9.99", product.price)
    assertEquals(ProductPeriod.MONTH, product.period)
    assertEquals("monthly-token", product.offer?.offerToken)
  }

  @Test
  fun fetchProducts_multiplies_billing_period_magnitude_by_cycles() = runTest {
    val subscription = PlayBillingProductDetailsSnapshot(
      productId = "pro",
      productType = PlayStoreProductType.SUBSCRIPTION,
      name = "Pro",
      title = "Pro",
      subscriptionOffers = listOf(
        PlayBillingSubscriptionOfferSnapshot(
          basePlanId = "monthly",
          offerId = "six-month-discount",
          offerToken = "token",
          pricingPhases = listOf(
            pricingPhase("\$4.99", 4_990_000, "P3M", ProductDetails.RecurrenceMode.FINITE_RECURRING, 2),
            pricingPhase("\$9.99", 9_990_000, "P1M", ProductDetails.RecurrenceMode.INFINITE_RECURRING),
          ),
        ),
      ),
    )
    val service = GooglePlayBillingProductService(FakeBillingClient(productsByType = mapOf(PlayStoreProductType.SUBSCRIPTION to listOf(subscription))))

    val offer = service.fetchProducts(setOf("pro")).single().offer

    assertEquals(6, offer?.periodCount)
    assertEquals("\$4.99 for 6 months", offer?.label)
  }

  @Test
  fun fetchProducts_exposes_only_a_real_discounted_one_time_offer() = runTest {
    val details = PlayBillingProductDetailsSnapshot(
      productId = "coins",
      productType = PlayStoreProductType.ONE_TIME,
      name = "Coins",
      title = "Coins",
      oneTimeOffers = listOf(
        PlayBillingOneTimeOfferSnapshot(
          formattedPrice = "\$4.99",
          priceAmountMicros = 4_990_000,
          purchaseOptionId = "buy",
          offerToken = "base-token",
        ),
        PlayBillingOneTimeOfferSnapshot(
          formattedPrice = "\$1.99",
          priceAmountMicros = 1_990_000,
          offerId = "promo-offer",
          purchaseOptionId = "buy-promo",
          offerToken = "one-time-token",
          fullPriceMicros = 4_990_000,
        ),
      ),
    )
    val service = GooglePlayBillingProductService(FakeBillingClient(productsByType = mapOf(PlayStoreProductType.ONE_TIME to listOf(details))))

    val product = service.fetchProducts(setOf("coins")).single()
    val offer = product.offer

    assertEquals("\$4.99", product.price)
    assertEquals("promo-offer", offer?.id)
    assertEquals("one-time-token", offer?.offerToken)
  }

  @Test
  fun fetchProducts_does_not_render_a_base_purchase_option_as_an_offer() = runTest {
    val details = oneTimeDetails("coins", "Coins", "\$1.99")
    val service = GooglePlayBillingProductService(FakeBillingClient(productsByType = mapOf(PlayStoreProductType.ONE_TIME to listOf(details))))

    val product = service.fetchProducts(setOf("coins")).single()

    assertEquals(null, product.offer)
    assertEquals(emptyList<Any>(), product.offers)
  }

  @Test
  fun fetchProducts_keeps_day_offer_units_exact() = runTest {
    val subscription = PlayBillingProductDetailsSnapshot(
      productId = "pro",
      productType = PlayStoreProductType.SUBSCRIPTION,
      name = "Pro",
      title = "Pro",
      subscriptionOffers = listOf(
        PlayBillingSubscriptionOfferSnapshot(
          basePlanId = "monthly",
          offerId = "three-day-discount",
          offerToken = "token",
          pricingPhases = listOf(
            pricingPhase("\$0.99", 990_000, "P3D", ProductDetails.RecurrenceMode.FINITE_RECURRING),
            pricingPhase("\$9.99", 9_990_000, "P1M", ProductDetails.RecurrenceMode.INFINITE_RECURRING),
          ),
        ),
      ),
    )
    val service = GooglePlayBillingProductService(FakeBillingClient(productsByType = mapOf(PlayStoreProductType.SUBSCRIPTION to listOf(subscription))))

    val offer = service.fetchProducts(setOf("pro")).single().offer

    assertEquals(ProductPeriod.DAY, offer?.period)
    assertEquals(3, offer?.periodCount)
    assertEquals("\$0.99 for 3 days", offer?.label)
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
  fun fetchProducts_chunks_product_detail_queries_at_play_billing_limit() = runTest {
    val productIds = (1..21).map { "pro_$it" }
    val subscriptions = productIds.mapIndexed { index, productId ->
      subscriptionDetails(
        productId = productId,
        name = "Pro $index",
        phases = listOf(
          pricingPhase(
            formattedPrice = "\$${index + 1}.99",
            priceAmountMicros = (index + 1) * 1_000_000L,
            billingPeriod = "P1M",
            recurrenceMode = ProductDetails.RecurrenceMode.INFINITE_RECURRING,
          )
        ),
      )
    }
    val client = FakeBillingClient(
      productsByType = mapOf(PlayStoreProductType.SUBSCRIPTION to subscriptions)
    )
    val service = GooglePlayBillingProductService(client)

    val products = service.fetchProducts(linkedSetOf(*productIds.toTypedArray()))

    assertEquals(
      listOf(
        FakeBillingClient.ProductQuery(
          productType = PlayStoreProductType.SUBSCRIPTION,
          productIds = productIds.take(20),
        ),
        FakeBillingClient.ProductQuery(
          productType = PlayStoreProductType.SUBSCRIPTION,
          productIds = productIds.drop(20),
        ),
        FakeBillingClient.ProductQuery(
          productType = PlayStoreProductType.ONE_TIME,
          productIds = productIds.take(20),
        ),
        FakeBillingClient.ProductQuery(
          productType = PlayStoreProductType.ONE_TIME,
          productIds = productIds.drop(20),
        ),
      ),
      client.productQueries,
    )
    assertEquals(productIds, products.map { it.id })
  }

  @Test
  fun fetchProducts_throws_when_billing_setup_fails() = runTest {
    val client = FakeBillingClient(
      setupResult = PlayBillingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "offline")
    )
    val service = GooglePlayBillingProductService(client)

    try {
      service.fetchProducts(setOf("pro_monthly"))
      fail("expected FlowProductFetchException")
    } catch (_: FlowProductFetchException) {
    }

    assertEquals(emptyList<FakeBillingClient.ProductQuery>(), client.productQueries)
  }

  @Test
  fun fetchProducts_waits_for_in_flight_billing_setup_before_querying() = runTest {
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
    val client = FakeBillingClient(
      productsByType = mapOf(PlayStoreProductType.SUBSCRIPTION to listOf(subscription)),
      autoCompleteSetup = false,
    )
    val service = GooglePlayBillingProductService(client)

    val first = async { service.fetchProducts(setOf("pro_monthly")) }
    val second = async { service.fetchProducts(setOf("pro_monthly")) }
    runCurrent()

    assertEquals(1, client.startConnectionCalls)
    assertEquals(emptyList<FakeBillingClient.ProductQuery>(), client.productQueries)

    client.completeSetup()

    assertEquals("pro_monthly", first.await().single().id)
    assertEquals("pro_monthly", second.await().single().id)
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
    billingCycleCount: Int = 1,
  ): PlayBillingPricingPhaseSnapshot {
    return PlayBillingPricingPhaseSnapshot(
      formattedPrice = formattedPrice,
      priceAmountMicros = priceAmountMicros,
      billingPeriod = billingPeriod,
      recurrenceMode = recurrenceMode,
      billingCycleCount = billingCycleCount,
    )
  }
}
