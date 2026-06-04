package io.nuxie.sdk.features

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.util.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface FeatureService {
  suspend fun getCached(featureId: String, entityId: String? = null): FeatureAccess?
  suspend fun getAllCached(): Map<String, FeatureAccess>
  suspend fun check(featureId: String, requiredBalance: Int? = null, entityId: String? = null): FeatureCheckResult
  suspend fun checkWithCache(
    featureId: String,
    requiredBalance: Int? = null,
    entityId: String? = null,
    forceRefresh: Boolean = false,
  ): FeatureAccess

  suspend fun clearCache()
  suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String)
  suspend fun syncFeatureInfo()
  suspend fun updateFromPurchase(features: List<PurchaseFeature>)
}

class DefaultFeatureService(
  private val api: NuxieApiProtocol,
  private val identityService: IdentityService,
  private val profileService: ProfileService,
  private val configuration: NuxieConfiguration,
  private val featureInfo: FeatureInfo,
  private val clock: Clock = Clock.system(),
) : FeatureService {

  private val mutex = Mutex()

  private data class CachedResult(
    val result: FeatureCheckResult,
    val cachedAtEpochMillis: Long,
  )

  private val realTimeCache: MutableMap<String, CachedResult> = mutableMapOf()

  private val ttlMillis: Long get() = configuration.featureCacheTtlSeconds * 1000L

  override suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? {
    val distinctId = identityService.getDistinctId()

    // 1) profile cache
    val profile = profileService.getCachedProfile(distinctId)
    val feature = profile?.features?.firstOrNull { it.id == featureId }
    if (feature != null) {
      if (entityId != null) {
        val entities = feature.entities
        val entityBalance = entities?.get(entityId)
        if (entityBalance != null) {
          val copy = feature.copy(balance = entityBalance.balance, entities = null)
          return FeatureAccess.fromFeature(copy)
        }
        // Feature is present but entity isn't: return a denied-like sentinel rather than null.
        return FeatureAccess.notFound
      }
      return FeatureAccess.fromFeature(feature)
    }

    // 2) real-time cache
    val cacheKey = makeCacheKey(featureId, entityId)
    val cached = mutex.withLock { realTimeCache[cacheKey] } ?: return null
    val age = clock.nowEpochMillis() - cached.cachedAtEpochMillis
    if (age < ttlMillis) {
      return FeatureAccess.fromCheckResult(cached.result)
    }

    return null
  }

  override suspend fun getAllCached(): Map<String, FeatureAccess> {
    val distinctId = identityService.getDistinctId()
    val profile = profileService.getCachedProfile(distinctId) ?: return emptyMap()
    val features = profile.features ?: return emptyMap()
    return features.associate { it.id to FeatureAccess.fromFeature(it) }
  }

  override suspend fun check(
    featureId: String,
    requiredBalance: Int?,
    entityId: String?,
  ): FeatureCheckResult {
    val customerId = identityService.getDistinctId()
    val result = api.checkFeature(
      customerId = customerId,
      featureId = featureId,
      requiredBalance = requiredBalance,
      entityId = entityId,
    )

    val cacheKey = makeCacheKey(featureId, entityId)
    mutex.withLock {
      realTimeCache[cacheKey] = CachedResult(result = result, cachedAtEpochMillis = clock.nowEpochMillis())
    }

    // Update FeatureInfo for reactivity.
    featureInfo.update(featureId, FeatureAccess.fromCheckResult(result))

    return result
  }

  override suspend fun checkWithCache(
    featureId: String,
    requiredBalance: Int?,
    entityId: String?,
    forceRefresh: Boolean,
  ): FeatureAccess {
    if (!forceRefresh) {
      val cached = getCached(featureId, entityId)
      if (cached != null) {
        if (cached.type == FeatureType.BOOLEAN) {
          return cached
        }

        val required = requiredBalance ?: 1
        if (cached.unlimited || (cached.balance ?: 0) >= required) {
          return cached
        }
      }
    }

    val result = check(featureId, requiredBalance, entityId)
    return FeatureAccess.fromCheckResult(result)
  }

  override suspend fun clearCache() {
    mutex.withLock { realTimeCache.clear() }
    NuxieLogger.info("Feature cache cleared")
  }

  override suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {
    clearCache()
    syncFeatureInfo()
    NuxieLogger.info(
      "Feature cache cleared due to user change (${NuxieLogger.logDistinctId(fromOldDistinctId)} -> ${NuxieLogger.logDistinctId(toNewDistinctId)})"
    )
  }

  override suspend fun syncFeatureInfo() {
    featureInfo.update(getAllCached())
  }

  override suspend fun updateFromPurchase(features: List<PurchaseFeature>) {
    for (feature in features) {
      featureInfo.update(feature.id, feature.toFeatureAccess)
    }
    NuxieLogger.info("Updated FeatureInfo from purchase response (${features.size} features)")
  }

  private fun makeCacheKey(featureId: String, entityId: String?): String {
    return if (entityId != null) "$featureId:$entityId" else featureId
  }
}
