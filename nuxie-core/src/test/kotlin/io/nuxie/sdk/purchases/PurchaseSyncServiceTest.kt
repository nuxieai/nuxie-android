package io.nuxie.sdk.purchases

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.config.NuxiePropertiesSanitizer
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.queue.InMemoryEventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
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
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseSyncServiceTest {
  private class FakeApi(
    private val response: PurchaseResponse = PurchaseResponse(
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
    ),
    private val beforeResponse: ((PlayStorePurchaseRequest) -> Unit)? = null,
  ) : NuxieApiProtocol {
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
      beforeResponse?.invoke(request)
      return response
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
  fun purchaseOutcome_keeps_legacy_positional_constructor_order() {
    val outcome = PurchaseOutcome(
      PurchaseResult.Success,
      "pro_monthly",
      "token_sub_2026",
      "GPA.3344-5566-7788-99001",
    )

    assertEquals(PurchaseResult.Success, outcome.result)
    assertEquals("pro_monthly", outcome.productId)
    assertEquals("token_sub_2026", outcome.purchaseToken)
    assertEquals("GPA.3344-5566-7788-99001", outcome.orderId)
    assertNull(outcome.playStorePurchase)
  }

  @Test
  fun purchaseOutcome_accepts_play_store_purchase_without_changing_primary_constructor_order() {
    val playStorePurchase = PlayStorePurchase(
      purchaseToken = "token_sub_2026",
      productIds = listOf("pro_monthly"),
      packageName = "io.nuxie.example",
      productType = PlayStoreProductType.SUBSCRIPTION,
    )

    val outcome = PurchaseOutcome(
      result = PurchaseResult.Success,
      productId = "pro_monthly",
      playStorePurchase = playStorePurchase,
    )

    assertEquals(PurchaseResult.Success, outcome.result)
    assertEquals("pro_monthly", outcome.productId)
    assertNull(outcome.purchaseToken)
    assertNull(outcome.orderId)
    assertEquals(playStorePurchase, outcome.playStorePurchase)
  }

  @Test
  fun syncPlayStorePurchase_sends_completed_subscription_and_updates_features() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_123") }
    val api = FakeApi()
    val features = FakeFeatureService()
    val service = PurchaseSyncService(
      api = api,
      identityService = identity,
      featureService = features,
    )

    val response = service.syncPlayStorePurchase(
      PlayStorePurchase(
        purchaseToken = " token_sub_2026 ",
        productIds = listOf("pro_monthly"),
        packageName = " io.nuxie.example ",
        basePlanId = " monthly ",
        productType = PlayStoreProductType.SUBSCRIPTION,
        orderId = "GPA.3344-5566-7788-99001",
      )
    )

    assertTrue(response.success)
    assertEquals(1, api.requests.size)
    val request = api.requests.single()
    assertEquals("playstore", request.type)
    assertEquals("token_sub_2026", request.purchaseToken)
    assertEquals("pro_monthly", request.productId)
    assertEquals("io.nuxie.example", request.packageName)
    assertEquals("monthly", request.basePlanId)
    assertEquals("user_123", request.distinctId)
    assertEquals(PlayStoreProductType.SUBSCRIPTION, request.productType)
    assertNull(request.consumePurchase)
    assertEquals("pro", features.purchaseUpdates.single().single().id)
  }

  @Test
  fun syncPlayStorePurchase_skips_local_feature_update_when_identity_changes_before_response() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_123") }
    val api = FakeApi(
      beforeResponse = {
        identity.setDistinctId("user_456")
      },
    )
    val features = FakeFeatureService()
    val service = PurchaseSyncService(
      api = api,
      identityService = identity,
      featureService = features,
    )

    val response = service.syncPlayStorePurchase(
      PlayStorePurchase(
        purchaseToken = "token_sub_2026",
        productIds = listOf("pro_monthly"),
        packageName = "io.nuxie.example",
        productType = PlayStoreProductType.SUBSCRIPTION,
        distinctId = "user_123",
      )
    )

    assertTrue(response.success)
    assertEquals("user_123", api.requests.single().distinctId)
    assertEquals(0, features.purchaseUpdates.size)
  }

  @Test
  fun syncPlayStorePurchase_keeps_success_when_purchaseSynced_tracking_hook_throws() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_123") }
    val api = FakeApi()
    val features = FakeFeatureService()
    val eventStore = InMemoryEventQueueStore()
    val eventService = EventService(
      identityService = identity,
      sessionService = DefaultSessionService(),
      configuration = NuxieConfiguration(apiKey = "k").also {
        it.propertiesSanitizer = NuxiePropertiesSanitizer {
          throw IllegalStateException("analytics_blocked")
        }
      },
      api = api,
      store = eventStore,
      historyStore = InMemoryEventHistoryStore(),
      networkQueue = NuxieNetworkQueue(
        store = eventStore,
        api = api,
        scope = this,
        flushAt = 999,
        flushIntervalSeconds = 999,
        maxQueueSize = 1000,
        maxBatchSize = 50,
        maxRetries = 0,
        baseRetryDelaySeconds = 1,
      ),
      scope = this,
    )
    val service = PurchaseSyncService(
      api = api,
      identityService = identity,
      featureService = features,
      eventService = eventService,
    )

    val response = service.syncPlayStorePurchase(
      PlayStorePurchase(
        purchaseToken = "token_sub_2026",
        productIds = listOf("pro_monthly"),
        packageName = "io.nuxie.example",
        productType = PlayStoreProductType.SUBSCRIPTION,
      )
    )

    assertTrue(response.success)
    assertEquals(1, api.requests.size)
    assertEquals("pro", features.purchaseUpdates.single().single().id)
  }

  @Test
  fun syncPlayStorePurchase_omits_product_id_for_multi_product_one_time_purchase() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_123") }
    val api = FakeApi()
    val service = PurchaseSyncService(
      api = api,
      identityService = identity,
      featureService = FakeFeatureService(),
    )

    service.syncPlayStorePurchase(
      PlayStorePurchase(
        purchaseToken = "token_cart_001",
        productIds = listOf("coins_100", "gems_25"),
        packageName = "io.nuxie.example",
        productType = PlayStoreProductType.ONE_TIME,
        consumePurchase = true,
      )
    )

    val request = api.requests.single()
    assertNull(request.productId)
    assertEquals(PlayStoreProductType.ONE_TIME, request.productType)
    assertEquals(true, request.consumePurchase)
  }

  @Test
  fun syncPlayStorePurchase_does_not_sync_pending_purchase() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_123") }
    val api = FakeApi()
    val features = FakeFeatureService()
    val service = PurchaseSyncService(
      api = api,
      identityService = identity,
      featureService = features,
    )

    val response = service.syncPlayStorePurchase(
      PlayStorePurchase(
        purchaseToken = "token_pending_cash_001",
        productIds = listOf("coins_100"),
        packageName = "io.nuxie.example",
        productType = PlayStoreProductType.ONE_TIME,
        purchaseState = PlayStorePurchaseState.PENDING,
      )
    )

    assertFalse(response.success)
    assertEquals("purchase_pending", response.error)
    assertEquals(0, api.requests.size)
    assertEquals(0, features.purchaseUpdates.size)
  }
}
