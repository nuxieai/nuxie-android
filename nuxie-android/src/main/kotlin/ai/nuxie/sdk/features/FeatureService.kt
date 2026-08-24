package ai.nuxie.sdk.features

import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.network.NuxieApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Profile-backed Feature access and the short-lived results of real-time
 * checks. Purchase projections and their revision protocol deliberately do
 * not live here; commerce owns those layers when it lands.
 */
internal class FeatureService(
    private val api: NuxieApi,
    private val identity: IdentityProvider,
    private val featureInfo: FeatureInfo,
    private val cacheTtlMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class CacheKey(val featureId: String, val entityId: String?)
    private data class TimedAccess(val access: FeatureAccess, val cachedAtMillis: Long)

    private val lock = Any()
    private var cacheDistinctId = identity.distinctId()
    private var durableAccess: Map<String, FeatureAccess> = emptyMap()
    private var durableEntities: Map<String, Map<String, FeatureAccess>> = emptyMap()
    private val realTimeCache = mutableMapOf<CacheKey, TimedAccess>()

    suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? =
        getCached(featureId, requiredBalance = null, entityId = entityId)

    suspend fun getAllCached(): Map<String, FeatureAccess> {
        synchronizeCustomerScopeIfNeeded()
        return synchronized(lock) {
            durableAccess + realTimeCache
                .filter { (key, timed) -> key.entityId == null && isFresh(timed) }
                .mapValues { it.value.access }
                .mapKeys { it.key.featureId }
        }
    }

    suspend fun check(
        featureId: String,
        requiredBalance: Double? = null,
        entityId: String? = null,
    ): FeatureAccess = performCheck(featureId, requiredBalance, entityId)

    suspend fun checkWithCache(
        featureId: String,
        requiredBalance: Double? = null,
        entityId: String? = null,
        forceRefresh: Boolean = false,
    ): FeatureAccess {
        if (!forceRefresh) {
            getCached(featureId, requiredBalance, entityId)?.let { cached ->
                if (cached.type == FeatureType.BOOLEAN) return cached
                if (cached.unlimited || (cached.balance ?: 0.0) >= (requiredBalance ?: 1.0)) {
                    return cached
                }
            }
        }
        return performCheck(featureId, requiredBalance, entityId)
    }

    suspend fun clearCache() {
        synchronizeCustomerScopeIfNeeded()
        synchronized(lock) { realTimeCache.clear() }
        // Profile remains the durable source for future cache reads. Clearing
        // the reactive projection matches iOS: a later profile hydration is
        // what republishes that durable snapshot.
        featureInfo.clear()
    }

    suspend fun handleUserChange(from: String, to: String) {
        synchronized(lock) {
            // A new customer must never inherit the prior customer's durable
            // profile snapshot or short-lived check results.
            cacheDistinctId = to
            durableAccess = emptyMap()
            durableEntities = emptyMap()
            realTimeCache.clear()
        }
        featureInfo.reset()
    }

    /** Called by ProfileService whenever its raw profile body is applied. */
    suspend fun hydrateProfile(distinctId: String, body: JsonObject) {
        if (identity.distinctId() != distinctId) return
        val parsed = parseProfileFeatures(body)
        synchronized(lock) {
            if (identity.distinctId() != distinctId) return
            cacheDistinctId = distinctId
            durableAccess = parsed.first
            durableEntities = parsed.second
        }
        publishCurrent(ready = true)
    }

    suspend fun syncFeatureInfo() = publishCurrent(ready = true)

    private suspend fun getCached(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureAccess? {
        synchronizeCustomerScopeIfNeeded()
        return synchronized(lock) {
            val key = CacheKey(featureId, entityId)
            realTimeCache[key]?.takeIf(::isFresh)?.access?.forRequiredBalance(requiredBalance)
                ?: entityAccess(featureId, entityId)?.forRequiredBalance(requiredBalance)
        }
    }

    private suspend fun performCheck(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureAccess {
        synchronizeCustomerScopeIfNeeded()
        val distinctId = identity.distinctId()
        val result = api.checkFeature(distinctId, featureId, requiredBalance, entityId)
        if (identity.distinctId() != distinctId) throw kotlinx.coroutines.CancellationException()

        val access = FeatureAccess(result.allowed, result.unlimited, result.balance, result.type)
        synchronized(lock) {
            if (cacheDistinctId != distinctId) throw kotlinx.coroutines.CancellationException()
            realTimeCache[CacheKey(featureId, entityId)] = TimedAccess(access, nowMillis())
        }
        featureInfo.update(featureId, access, entityId)
        return access
    }

    private suspend fun synchronizeCustomerScopeIfNeeded() {
        val distinctId = identity.distinctId()
        val changed = synchronized(lock) {
            if (cacheDistinctId == distinctId) false else {
                cacheDistinctId = distinctId
                durableAccess = emptyMap()
                durableEntities = emptyMap()
                realTimeCache.clear()
                true
            }
        }
        if (changed) featureInfo.reset()
    }

    private suspend fun publishCurrent(ready: Boolean = false) {
        val current = synchronized(lock) {
            val fresh = realTimeCache
                .filter { (key, timed) -> key.entityId == null && isFresh(timed) }
                .mapValues { it.value.access }
                .mapKeys { it.key.featureId }
            (durableAccess + fresh) to durableEntities
        }
        featureInfo.update(current.first, current.second, ready)
    }

    private fun entityAccess(featureId: String, entityId: String?): FeatureAccess? = when (entityId) {
        null -> durableAccess[featureId]
        else -> durableEntities[featureId]?.get(entityId)
            ?: durableAccess[featureId]?.let { FeatureAccess(false, false, null, it.type) }
    }

    private fun isFresh(timed: TimedAccess): Boolean = nowMillis() - timed.cachedAtMillis < cacheTtlMillis

    private fun FeatureAccess.forRequiredBalance(requiredBalance: Double?): FeatureAccess = when (type) {
        FeatureType.BOOLEAN -> this
        FeatureType.METERED, FeatureType.CREDIT_SYSTEM -> copy(
            allowed = unlimited || (balance ?: 0.0) >= (requiredBalance ?: 1.0),
        )
    }

    private fun parseProfileFeatures(
        body: JsonObject,
    ): Pair<Map<String, FeatureAccess>, Map<String, Map<String, FeatureAccess>>> {
        val features = body["features"] as? JsonArray ?: return emptyMap<String, FeatureAccess>() to emptyMap()
        val all = linkedMapOf<String, FeatureAccess>()
        val entities = linkedMapOf<String, Map<String, FeatureAccess>>()
        features.forEach { element ->
            val feature = element as? JsonObject ?: return@forEach
            val id = feature.string("id") ?: return@forEach
            val type = feature.string("type")?.toFeatureType() ?: return@forEach
            val unlimited = feature.boolean("unlimited") ?: false
            val balance = feature.double("balance")
            val access = profileAccess(type, unlimited, balance)
            all[id] = access
            (feature["entities"] as? JsonObject)?.let { rawEntities ->
                entities[id] = rawEntities.mapNotNull { (entityId, rawValue) ->
                    val entity = rawValue as? JsonObject ?: return@mapNotNull null
                    val entityBalance = entity.double("balance") ?: return@mapNotNull null
                    entityId to profileAccess(type, unlimited, entityBalance)
                }.toMap()
            }
        }
        return all to entities
    }

    private fun profileAccess(type: FeatureType, unlimited: Boolean, balance: Double?): FeatureAccess =
        FeatureAccess(
            allowed = type == FeatureType.BOOLEAN || unlimited || (balance ?: 0.0) >= 1.0,
            unlimited = unlimited,
            balance = balance,
            type = type,
        )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun String.toFeatureType(): FeatureType? = when (this) {
        "boolean" -> FeatureType.BOOLEAN
        "metered" -> FeatureType.METERED
        "creditSystem" -> FeatureType.CREDIT_SYSTEM
        else -> null
    }
}
