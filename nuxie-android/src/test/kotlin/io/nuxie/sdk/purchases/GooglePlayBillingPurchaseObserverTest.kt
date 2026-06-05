package io.nuxie.sdk.purchases

import com.android.billingclient.api.BillingClient
import io.nuxie.sdk.features.FeatureAccess
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.FeatureType
import io.nuxie.sdk.features.PurchaseFeature
import io.nuxie.sdk.features.PurchaseResponse
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.PlayStorePurchaseRequest
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePlayBillingPurchaseObserverTest {
  private class FakeBillingClient(
    private val subscriptionPurchases: List<PlayBillingPurchaseSnapshot> = emptyList(),
    private val oneTimePurchases: List<PlayBillingPurchaseSnapshot> = emptyList(),
    private val autoCompleteQueries: Boolean = true,
  ) : PlayBillingClient {
    data class QueryCall(
      val productType: PlayStoreProductType,
      val includeSuspendedSubscriptions: Boolean,
    )
    private data class PendingQuery(
      val productType: PlayStoreProductType,
      val listener: (PlayBillingResult, List<PlayBillingPurchaseSnapshot>) -> Unit,
    )

    val queries = mutableListOf<QueryCall>()
    var ended = false
    var startConnectionCalls = 0
    override var isReady: Boolean = false
      private set
    private var purchasesUpdatedListener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)? = null
    private var disconnect: (() -> Unit)? = null
    private val pendingQueries = mutableListOf<PendingQuery>()

    override fun setPurchasesUpdatedListener(
      listener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)?,
    ) {
      purchasesUpdatedListener = listener
    }

    override fun startConnection(
      onSetupFinished: (PlayBillingResult) -> Unit,
      onDisconnected: () -> Unit,
    ) {
      startConnectionCalls += 1
      disconnect = onDisconnected
      isReady = true
      onSetupFinished(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"))
    }

    fun disconnect() {
      isReady = false
      disconnect?.invoke()
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
      queries += QueryCall(productType, includeSuspendedSubscriptions)
      if (!autoCompleteQueries) {
        pendingQueries += PendingQuery(productType, listener)
        return
      }
      completeQuery(productType, listener)
    }

    fun completePendingQueries() {
      val queries = pendingQueries.toList()
      pendingQueries.clear()
      for (query in queries) {
        completeQuery(query.productType, query.listener)
      }
    }

    private fun completeQuery(
      productType: PlayStoreProductType,
      listener: (PlayBillingResult, List<PlayBillingPurchaseSnapshot>) -> Unit,
    ) {
      val purchases = when (productType) {
        PlayStoreProductType.SUBSCRIPTION -> subscriptionPurchases
        PlayStoreProductType.ONE_TIME -> oneTimePurchases
      }
      listener(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"), purchases)
    }

    override fun queryProductDetails(
      productType: PlayStoreProductType,
      productIds: List<String>,
      listener: (PlayBillingResult, List<PlayBillingProductDetailsSnapshot>) -> Unit,
    ) {
      listener(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"), emptyList())
    }

    fun emitPurchaseUpdate(
      result: PlayBillingResult,
      purchases: List<PlayBillingPurchaseSnapshot>?,
    ) {
      purchasesUpdatedListener?.invoke(result, purchases)
    }
  }

  private class FakeApi : NuxieApiProtocol {
    val requests = mutableListOf<PlayStorePurchaseRequest>()

    override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun trackEvent(
      event: String,
      distinctId: String,
      anonDistinctId: String?,
      properties: JsonObject?,
      uuid: String,
      value: Double?,
      entityId: String?,
      timestamp: String,
    ): EventResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun fetchFlow(flowId: String): RemoteFlow {
      throw UnsupportedOperationException()
    }

    override suspend fun checkFeature(
      customerId: String,
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
    ): FeatureCheckResult {
      throw UnsupportedOperationException()
    }

    override suspend fun syncPlayStorePurchase(request: PlayStorePurchaseRequest): PurchaseResponse {
      requests += request
      return PurchaseResponse(
        success = true,
        customerId = "cus_123",
        features = listOf(
          PurchaseFeature(
            id = "pro",
            extId = "pro",
            type = FeatureType.BOOLEAN,
            allowed = true,
            balance = null,
            unlimited = false,
          )
        ),
      )
    }
  }

  private class FakeFeatureService : FeatureService {
    val purchaseUpdates = mutableListOf<List<PurchaseFeature>>()

    override suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? = null
    override suspend fun getAllCached(): Map<String, FeatureAccess> = emptyMap()
    override suspend fun check(featureId: String, requiredBalance: Int?, entityId: String?): FeatureCheckResult {
      throw UnsupportedOperationException()
    }

    override suspend fun checkWithCache(
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
      forceRefresh: Boolean,
    ): FeatureAccess {
      throw UnsupportedOperationException()
    }

    override suspend fun clearCache() {}
    override suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {}
    override suspend fun syncFeatureInfo() {}
    override suspend fun updateFromPurchase(features: List<PurchaseFeature>) {
      purchaseUpdates += features
    }
  }

  @Test
  fun start_queries_owned_subscription_and_one_time_purchases() = runTest {
    val subscription = PlayBillingPurchaseSnapshot(
      purchaseToken = "token_sub_001",
      productIds = listOf("pro_monthly"),
      packageName = "io.nuxie.example",
      orderId = "GPA.1111-2222-3333-44444",
      purchaseState = PlayStorePurchaseState.PURCHASED,
    )
    val coins = PlayBillingPurchaseSnapshot(
      purchaseToken = "token_coin_001",
      productIds = listOf("coins_100"),
      packageName = "io.nuxie.example",
      orderId = "GPA.5555-6666-7777-88888",
      purchaseState = PlayStorePurchaseState.PURCHASED,
    )
    val client = FakeBillingClient(
      subscriptionPurchases = listOf(subscription),
      oneTimePurchases = listOf(coins),
    )
    val api = FakeApi()
    val observer = newObserver(
      scope = this,
      client = client,
      api = api,
      consumables = setOf("coins_100"),
    )

    observer.start()
    advanceUntilIdle()

    assertEquals(
      listOf(
        FakeBillingClient.QueryCall(PlayStoreProductType.SUBSCRIPTION, true),
        FakeBillingClient.QueryCall(PlayStoreProductType.ONE_TIME, false),
      ),
      client.queries,
    )
    assertEquals(2, api.requests.size)
    assertEquals(PlayStoreProductType.SUBSCRIPTION, api.requests[0].productType)
    assertEquals("pro_monthly", api.requests[0].productId)
    assertNull(api.requests[0].consumePurchase)
    assertEquals(PlayStoreProductType.ONE_TIME, api.requests[1].productType)
    assertEquals("coins_100", api.requests[1].productId)
    assertEquals(true, api.requests[1].consumePurchase)
  }

  @Test
  fun one_time_purchase_with_mixed_consumable_and_non_consumable_products_is_not_consumed() = runTest {
    val mixedPurchase = PlayBillingPurchaseSnapshot(
      purchaseToken = "token_mixed_cart_001",
      productIds = listOf("coins_100", "lifetime_unlock"),
      packageName = "io.nuxie.example",
      orderId = "GPA.5555-6666-7777-88888",
      purchaseState = PlayStorePurchaseState.PURCHASED,
    )
    val client = FakeBillingClient(oneTimePurchases = listOf(mixedPurchase))
    val api = FakeApi()
    val observer = newObserver(
      scope = this,
      client = client,
      api = api,
      consumables = setOf("coins_100"),
    )

    observer.start()
    advanceUntilIdle()

    val request = api.requests.single()
    assertNull(request.productId)
    assertEquals(PlayStoreProductType.ONE_TIME, request.productType)
    assertNull(request.consumePurchase)
  }

  @Test
  fun query_callbacks_after_stop_do_not_sync_purchases() = runTest {
    val purchase = PlayBillingPurchaseSnapshot(
      purchaseToken = "token_sub_001",
      productIds = listOf("pro_monthly"),
      packageName = "io.nuxie.example",
      orderId = "GPA.1111-2222-3333-44444",
      purchaseState = PlayStorePurchaseState.PURCHASED,
    )
    val client = FakeBillingClient(
      subscriptionPurchases = listOf(purchase),
      autoCompleteQueries = false,
    )
    val api = FakeApi()
    val observer = newObserver(
      scope = this,
      client = client,
      api = api,
      consumables = emptySet(),
    )

    observer.start()
    observer.stop()
    client.completePendingQueries()
    advanceUntilIdle()

    assertEquals(0, api.requests.size)
  }

  @Test
  fun refreshPurchases_queries_owned_purchases_again_without_reconnecting_when_connected() = runTest {
    val subscription = PlayBillingPurchaseSnapshot(
      purchaseToken = "token_sub_001",
      productIds = listOf("pro_monthly"),
      packageName = "io.nuxie.example",
      orderId = "GPA.1111-2222-3333-44444",
      purchaseState = PlayStorePurchaseState.PURCHASED,
    )
    val client = FakeBillingClient(subscriptionPurchases = listOf(subscription))
    val api = FakeApi()
    val observer = newObserver(
      scope = this,
      client = client,
      api = api,
      consumables = emptySet(),
    )

    observer.start()
    observer.refreshPurchases()
    advanceUntilIdle()

    assertEquals(1, client.startConnectionCalls)
    assertEquals(
      listOf(
        FakeBillingClient.QueryCall(PlayStoreProductType.SUBSCRIPTION, true),
        FakeBillingClient.QueryCall(PlayStoreProductType.ONE_TIME, false),
        FakeBillingClient.QueryCall(PlayStoreProductType.SUBSCRIPTION, true),
        FakeBillingClient.QueryCall(PlayStoreProductType.ONE_TIME, false),
      ),
      client.queries,
    )
    assertEquals(1, api.requests.size)
    assertEquals("token_sub_001", api.requests.single().purchaseToken)
  }

  @Test
  fun refreshPurchases_reconnects_after_billing_service_disconnect() = runTest {
    val subscription = PlayBillingPurchaseSnapshot(
      purchaseToken = "token_sub_001",
      productIds = listOf("pro_monthly"),
      packageName = "io.nuxie.example",
      orderId = "GPA.1111-2222-3333-44444",
      purchaseState = PlayStorePurchaseState.PURCHASED,
    )
    val client = FakeBillingClient(subscriptionPurchases = listOf(subscription))
    val api = FakeApi()
    val observer = newObserver(
      scope = this,
      client = client,
      api = api,
      consumables = emptySet(),
    )

    observer.start()
    client.disconnect()
    observer.refreshPurchases()
    advanceUntilIdle()

    assertEquals(2, client.startConnectionCalls)
    assertEquals(
      listOf(
        FakeBillingClient.QueryCall(PlayStoreProductType.SUBSCRIPTION, true),
        FakeBillingClient.QueryCall(PlayStoreProductType.ONE_TIME, false),
        FakeBillingClient.QueryCall(PlayStoreProductType.SUBSCRIPTION, true),
        FakeBillingClient.QueryCall(PlayStoreProductType.ONE_TIME, false),
      ),
      client.queries,
    )
    assertEquals(1, api.requests.size)
  }

  @Test
  fun purchase_update_does_not_sync_pending_purchase() = runTest {
    val api = FakeApi()
    val observer = newObserver(
      scope = this,
      client = FakeBillingClient(),
      api = api,
      consumables = emptySet(),
    )

    observer.start()
    observer.onPurchasesUpdated(
      PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"),
      listOf(
        PlayBillingPurchaseSnapshot(
          purchaseToken = "token_pending_001",
          productIds = listOf("coins_100"),
          packageName = "io.nuxie.example",
          orderId = null,
          purchaseState = PlayStorePurchaseState.PENDING,
        )
      ),
    )
    advanceUntilIdle()

    assertEquals(0, api.requests.size)
  }

  @Test
  fun purchase_update_uses_backend_inference_when_product_type_unknown() = runTest {
    val api = FakeApi()
    val observer = newObserver(
      scope = this,
      client = FakeBillingClient(),
      api = api,
      consumables = emptySet(),
    )

    observer.start()
    observer.onPurchasesUpdated(
      PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"),
      listOf(
        PlayBillingPurchaseSnapshot(
          purchaseToken = "token_listener_001",
          productIds = listOf("pro_monthly"),
          packageName = "io.nuxie.example",
          orderId = "GPA.9999-8888-7777-66666",
          purchaseState = PlayStorePurchaseState.PURCHASED,
        )
      ),
    )
    advanceUntilIdle()

    assertEquals(1, api.requests.size)
    assertEquals("token_listener_001", api.requests.single().purchaseToken)
    assertEquals("pro_monthly", api.requests.single().productId)
    assertNull(api.requests.single().productType)
    assertNull(api.requests.single().consumePurchase)
  }

  @Test
  fun purchase_update_syncs_same_token_again_after_identity_changes() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_123") }
    val api = FakeApi()
    val observer = newObserver(
      scope = this,
      client = FakeBillingClient(),
      api = api,
      consumables = emptySet(),
      identity = identity,
    )
    val purchase = PlayBillingPurchaseSnapshot(
      purchaseToken = "token_sub_001",
      productIds = listOf("pro_monthly"),
      packageName = "io.nuxie.example",
      orderId = "GPA.1111-2222-3333-44444",
      purchaseState = PlayStorePurchaseState.PURCHASED,
    )

    observer.start()
    observer.onPurchasesUpdated(
      PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"),
      listOf(purchase),
    )
    advanceUntilIdle()

    identity.setDistinctId("user_456")
    observer.onPurchasesUpdated(
      PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"),
      listOf(purchase),
    )
    advanceUntilIdle()

    assertEquals(listOf("user_123", "user_456"), api.requests.map { it.distinctId })
  }

  private fun newObserver(
    scope: CoroutineScope,
    client: PlayBillingClient,
    api: FakeApi,
    consumables: Set<String>,
    identity: DefaultIdentityService = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_123") },
  ): GooglePlayBillingPurchaseObserver {
    val syncService = PurchaseSyncService(
      api = api,
      identityService = identity,
      featureService = FakeFeatureService(),
    )
    return GooglePlayBillingPurchaseObserver(
      scope = scope,
      syncService = syncService,
      client = client,
      distinctIdProvider = { identity.getDistinctId() },
      consumableProductIds = { consumables },
    )
  }
}
