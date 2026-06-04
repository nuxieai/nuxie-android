package io.nuxie.sdk.purchases

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
