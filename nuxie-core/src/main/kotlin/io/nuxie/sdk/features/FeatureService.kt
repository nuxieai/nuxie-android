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
  suspend fun syncFeatureInfoFromProfileRefresh() {
    syncFeatureInfo()
  }

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
  private val purchaseFeatureOverrides: MutableMap<String, FeatureAccess> = mutableMapOf()

  private val ttlMillis: Long get() = configuration.featureCacheTtlSeconds * 1000L

  override suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? {
    val distinctId = identityService.getDistinctId()

    // 1) purchase sync cache. Purchase responses are fresher than the profile
    // cache until a refreshed profile demonstrably includes the purchased access.
    if (entityId == null) {
      val purchaseAccess = mutex.withLock { purchaseFeatureOverrides[featureId] }
      if (purchaseAccess != null) return purchaseAccess
    }

    // 2) profile cache
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

    // 3) real-time cache
    val cacheKey = makeCacheKey(featureId, entityId)
    val cached = mutex.withLock { realTimeCache[cacheKey] } ?: return null
    val age = clock.nowEpochMillis() - cached.cachedAtEpochMillis
    if (age < ttlMillis) {
      return FeatureAccess.fromCheckResult(cached.result)
    }

    return null
  }

  override suspend fun getAllCached(): Map<String, FeatureAccess> {
    val profileFeatures = getProfileFeatureAccess()
    val purchaseFeatures = mutex.withLock { purchaseFeatureOverrides.toMap() }
    return profileFeatures + purchaseFeatures
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
      if (entityId == null) {
        purchaseFeatureOverrides.remove(featureId)
      }
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
    mutex.withLock {
      realTimeCache.clear()
      purchaseFeatureOverrides.clear()
    }
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
    syncFeatureInfo(profileWasRefreshed = false)
  }

  override suspend fun syncFeatureInfoFromProfileRefresh() {
    syncFeatureInfo(profileWasRefreshed = true)
  }

  private suspend fun syncFeatureInfo(profileWasRefreshed: Boolean) {
    val profileFeatures = getProfileFeatureAccess()
    val effectiveFeatures = mutex.withLock {
      val coveredFeatureIds = purchaseFeatureOverrides
        .filter { (featureId, purchaseAccess) ->
          if (profileWasRefreshed) {
            true
          } else {
            profileFeatures[featureId]?.coversPurchaseOverride(purchaseAccess) == true
          }
        }
        .keys
      for (featureId in coveredFeatureIds) {
        purchaseFeatureOverrides.remove(featureId)
      }
      profileFeatures + purchaseFeatureOverrides
    }
    featureInfo.update(effectiveFeatures)
  }

  override suspend fun updateFromPurchase(features: List<PurchaseFeature>) {
    val purchaseFeatures = features.associate { it.publicFeatureKey to it.toFeatureAccess }
    mutex.withLock {
      realTimeCache.clear()
      purchaseFeatureOverrides.putAll(purchaseFeatures)
    }

    for (feature in features) {
      featureInfo.update(feature.publicFeatureKey, feature.toFeatureAccess)
    }
    NuxieLogger.info("Updated FeatureInfo from purchase response (${features.size} features)")
  }

  private fun makeCacheKey(featureId: String, entityId: String?): String {
    return if (entityId != null) "$featureId:$entityId" else featureId
  }

  private suspend fun getProfileFeatureAccess(): Map<String, FeatureAccess> {
    val distinctId = identityService.getDistinctId()
    val profile = profileService.getCachedProfile(distinctId)
    return profile?.features
      ?.associate { it.id to FeatureAccess.fromFeature(it) }
      .orEmpty()
  }

  private fun FeatureAccess.coversPurchaseOverride(purchaseAccess: FeatureAccess): Boolean {
    if (type != purchaseAccess.type) return false
    if (purchaseAccess.allowed && !allowed) return false
    if (purchaseAccess.unlimited) return unlimited
    if (unlimited) return true

    val requiredBalance = purchaseAccess.balance
    return requiredBalance == null || (balance ?: Int.MIN_VALUE) >= requiredBalance
  }
}

private val PurchaseFeature.publicFeatureKey: String
  get() = extId?.trim()?.takeIf(String::isNotEmpty) ?: id
