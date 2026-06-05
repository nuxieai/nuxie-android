package io.nuxie.sdk.features

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import io.nuxie.sdk.util.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFeatureServiceTest {

  private class FakeClock(var now: Long) : Clock {
    override fun nowEpochMillis(): Long = now
  }

  private class FakeApi(
    private val checkResult: () -> FeatureCheckResult,
  ) : NuxieApiProtocol {
    var checkCalls: Int = 0

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
      checkCalls += 1
      return checkResult()
    }
  }

  private class FakeProfileService(
    var profile: ProfileResponse?,
  ) : ProfileService {
    override suspend fun fetchProfile(distinctId: String): ProfileResponse = throw UnsupportedOperationException()
    override suspend fun getCachedProfile(distinctId: String): ProfileResponse? = profile
    override suspend fun clearCache(distinctId: String) = Unit
    override suspend fun clearAllCache() = Unit
    override suspend fun cleanupExpired(): Int = 0
    override suspend fun getCacheStats(): Map<String, Any?> = emptyMap()
    override suspend fun refetchProfile(): ProfileResponse = throw UnsupportedOperationException()
    override suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) = Unit
    override suspend fun onAppBecameActive() = Unit
    override fun shutdown() {}
  }

  @Test
  fun getCached_returns_feature_from_profile() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val profile = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
      features = listOf(
        Feature(id = "pro", type = FeatureType.BOOLEAN, unlimited = false),
      ),
    )
    val profileService = FakeProfileService(profile)
    val api = FakeApi { throw AssertionError("should not call network") }
    val config = NuxieConfiguration(apiKey = "k")
    val info = FeatureInfo()

    val service = DefaultFeatureService(
      api = api,
      identityService = identity,
      profileService = profileService,
      configuration = config,
      featureInfo = info,
      clock = FakeClock(0),
    )

    val cached = service.getCached("pro")
    assertNotNull(cached)
    assertTrue(cached!!.allowed)
    assertEquals(0, api.checkCalls)
  }

  @Test
  fun getCached_entity_balance_overrides_profile_balance() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val profile = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
      features = listOf(
        Feature(
          id = "credits",
          type = FeatureType.METERED,
          balance = 0,
          unlimited = false,
          entities = mapOf("project_1" to EntityBalance(balance = 3)),
        ),
      ),
    )
    val service = DefaultFeatureService(
      api = FakeApi { throw AssertionError("should not call network") },
      identityService = identity,
      profileService = FakeProfileService(profile),
      configuration = NuxieConfiguration(apiKey = "k"),
      featureInfo = FeatureInfo(),
      clock = FakeClock(0),
    )

    val cached = service.getCached(featureId = "credits", entityId = "project_1")
    assertNotNull(cached)
    assertEquals(3, cached!!.balance)
    assertTrue(cached.allowed)
  }

  @Test
  fun getCached_missing_entity_returns_notFound_sentinel() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val profile = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
      features = listOf(
        Feature(
          id = "credits",
          type = FeatureType.METERED,
          balance = 0,
          unlimited = false,
          entities = mapOf("project_1" to EntityBalance(balance = 3)),
        ),
      ),
    )
    val service = DefaultFeatureService(
      api = FakeApi { throw AssertionError("should not call network") },
      identityService = identity,
      profileService = FakeProfileService(profile),
      configuration = NuxieConfiguration(apiKey = "k"),
      featureInfo = FeatureInfo(),
      clock = FakeClock(0),
    )

    val cached = service.getCached(featureId = "credits", entityId = "missing")
    assertNotNull(cached)
    assertEquals(false, cached!!.allowed)
  }

  @Test
  fun check_caches_result_and_updates_feature_info() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val api = FakeApi {
      FeatureCheckResult(
        customerId = "u",
        featureId = "pro",
        requiredBalance = 1,
        code = "feature_found",
        allowed = true,
        unlimited = false,
        balance = 1,
        type = FeatureType.BOOLEAN,
      )
    }
    val info = FeatureInfo()
    var callbackCount = 0
    info.onFeatureChange = { _, _, _ -> callbackCount += 1 }

    val service = DefaultFeatureService(
      api = api,
      identityService = identity,
      profileService = FakeProfileService(null),
      configuration = NuxieConfiguration(apiKey = "k"),
      featureInfo = info,
      clock = FakeClock(0),
    )

    val res = service.check("pro")
    assertTrue(res.allowed)
    assertEquals(1, api.checkCalls)
    assertTrue(info.feature("pro")!!.allowed)
    assertEquals(1, callbackCount)
  }

  @Test
  fun getCached_uses_real_time_cache_until_ttl() = runTest {
    val clock = FakeClock(0)
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val api = FakeApi {
      FeatureCheckResult(
        customerId = "u",
        featureId = "pro",
        requiredBalance = 1,
        code = "feature_found",
        allowed = true,
        unlimited = false,
        balance = 1,
        type = FeatureType.BOOLEAN,
      )
    }
    val config = NuxieConfiguration(apiKey = "k").also { it.featureCacheTtlSeconds = 5 }
    val service = DefaultFeatureService(
      api = api,
      identityService = identity,
      profileService = FakeProfileService(null),
      configuration = config,
      featureInfo = FeatureInfo(),
      clock = clock,
    )

    service.check("pro")
    clock.now = 4_000
    assertNotNull(service.getCached("pro"))
    clock.now = 6_000
    assertNull(service.getCached("pro"))
  }

  @Test
  fun syncFeatureInfo_updates_from_profile_cache() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val profile = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
      features = listOf(
        Feature(id = "pro", type = FeatureType.BOOLEAN, unlimited = false),
      ),
    )
    val info = FeatureInfo()

    val service = DefaultFeatureService(
      api = FakeApi { throw AssertionError("should not call network") },
      identityService = identity,
      profileService = FakeProfileService(profile),
      configuration = NuxieConfiguration(apiKey = "k"),
      featureInfo = info,
      clock = FakeClock(0),
    )

    service.syncFeatureInfo()
    assertTrue(info.feature("pro")!!.allowed)
  }

  @Test
  fun updateFromPurchase_merges_purchase_features_without_dropping_existing_features() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val info = FeatureInfo()
    info.update(
      mapOf(
        "pro" to FeatureAccess(
          allowed = false,
          unlimited = false,
          balance = null,
          type = FeatureType.BOOLEAN,
        ),
        "credits" to FeatureAccess(
          allowed = true,
          unlimited = false,
          balance = 10,
          type = FeatureType.METERED,
        ),
      )
    )

    val service = DefaultFeatureService(
      api = FakeApi { throw AssertionError("should not call network") },
      identityService = identity,
      profileService = FakeProfileService(null),
      configuration = NuxieConfiguration(apiKey = "k"),
      featureInfo = info,
      clock = FakeClock(0),
    )

    service.updateFromPurchase(
      listOf(
        PurchaseFeature(
          id = "feature_internal_pro",
          extId = "pro",
          type = FeatureType.BOOLEAN,
          allowed = true,
          balance = null,
          unlimited = false,
        )
      )
    )

    assertTrue(info.feature("pro")!!.allowed)
    assertNull(info.feature("feature_internal_pro"))
    assertTrue(service.getCached("pro")!!.allowed)
    assertNull(service.getCached("feature_internal_pro"))
    assertEquals(10, info.feature("credits")!!.balance)
  }

  @Test
  fun updateFromPurchase_overrides_stale_profile_and_realtime_feature_caches() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val staleProfile = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
      features = listOf(
        Feature(
          id = "credits",
          type = FeatureType.METERED,
          balance = 0,
          unlimited = false,
        ),
      ),
    )
    val profileService = FakeProfileService(staleProfile)
    val api = FakeApi {
      FeatureCheckResult(
        customerId = "u",
        featureId = "credits",
        requiredBalance = 1,
        code = "feature_denied",
        allowed = false,
        unlimited = false,
        balance = 0,
        type = FeatureType.METERED,
      )
    }
    val info = FeatureInfo()
    val service = DefaultFeatureService(
      api = api,
      identityService = identity,
      profileService = profileService,
      configuration = NuxieConfiguration(apiKey = "k"),
      featureInfo = info,
      clock = FakeClock(0),
    )

    val denied = service.checkWithCache("credits", requiredBalance = 1)
    assertFalse(denied.allowed)
    assertEquals(1, api.checkCalls)

    service.updateFromPurchase(
      listOf(
        PurchaseFeature(
          id = "credits",
          extId = "credits",
          type = FeatureType.METERED,
          allowed = true,
          balance = 10,
          unlimited = false,
        )
      )
    )

    val purchased = service.checkWithCache("credits", requiredBalance = 1)
    assertTrue(purchased.allowed)
    assertEquals(10, purchased.balance)
    assertEquals(1, api.checkCalls)

    service.syncFeatureInfo()
    assertTrue(info.feature("credits")!!.allowed)
    assertEquals(10, info.feature("credits")!!.balance)

    val stillPurchased = service.checkWithCache("credits", requiredBalance = 1)
    assertTrue(stillPurchased.allowed)
    assertEquals(1, api.checkCalls)

    profileService.profile = staleProfile.copy(
      features = listOf(
        Feature(
          id = "credits",
          type = FeatureType.METERED,
          balance = 10,
          unlimited = false,
        )
      )
    )
    service.syncFeatureInfo()
    assertTrue(info.feature("credits")!!.allowed)
    assertEquals(10, info.feature("credits")!!.balance)

    profileService.profile = staleProfile
    service.syncFeatureInfo()
    assertFalse(info.feature("credits")!!.allowed)
    assertEquals(0, info.feature("credits")!!.balance)
  }

  @Test
  fun syncFeatureInfoFromProfileRefresh_replaces_purchase_override_with_lower_metered_balance() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val profileService = FakeProfileService(
      ProfileResponse(
        campaigns = emptyList(),
        segments = emptyList(),
        flows = emptyList(),
        features = listOf(
          Feature(
            id = "credits",
            type = FeatureType.METERED,
            balance = 0,
            unlimited = false,
          ),
        ),
      )
    )
    val info = FeatureInfo()
    val service = DefaultFeatureService(
      api = FakeApi { throw AssertionError("should not call network") },
      identityService = identity,
      profileService = profileService,
      configuration = NuxieConfiguration(apiKey = "k"),
      featureInfo = info,
      clock = FakeClock(0),
    )

    service.updateFromPurchase(
      listOf(
        PurchaseFeature(
          id = "credits",
          extId = "credits",
          type = FeatureType.METERED,
          allowed = true,
          balance = 10,
          unlimited = false,
        )
      )
    )

    service.syncFeatureInfo()
    assertTrue(info.feature("credits")!!.allowed)
    assertEquals(10, info.feature("credits")!!.balance)

    profileService.profile = profileService.profile!!.copy(
      features = listOf(
        Feature(
          id = "credits",
          type = FeatureType.METERED,
          balance = 4,
          unlimited = false,
        )
      )
    )

    service.syncFeatureInfoFromProfileRefresh()

    assertTrue(info.feature("credits")!!.allowed)
    assertEquals(4, info.feature("credits")!!.balance)
    assertEquals(4, service.getCached("credits")!!.balance)
  }

  @Test
  fun syncFeatureInfoFromProfileRefresh_clears_purchase_override_when_feature_is_missing() = runTest {
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("u") }
    val profileService = FakeProfileService(
      ProfileResponse(
        campaigns = emptyList(),
        segments = emptyList(),
        flows = emptyList(),
        features = emptyList(),
      )
    )
    val info = FeatureInfo()
    val service = DefaultFeatureService(
      api = FakeApi { throw AssertionError("should not call network") },
      identityService = identity,
      profileService = profileService,
      configuration = NuxieConfiguration(apiKey = "k"),
      featureInfo = info,
      clock = FakeClock(0),
    )

    service.updateFromPurchase(
      listOf(
        PurchaseFeature(
          id = "feature_internal_pro",
          extId = "pro",
          type = FeatureType.BOOLEAN,
          allowed = true,
          balance = null,
          unlimited = false,
        )
      )
    )

    service.syncFeatureInfo()
    assertTrue(info.feature("pro")!!.allowed)
    assertTrue(service.getCached("pro")!!.allowed)

    service.syncFeatureInfoFromProfileRefresh()

    assertNull(info.feature("pro"))
    assertNull(service.getCached("pro"))
  }
}
