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
    private data class TimedAccess(
        val access: FeatureAccess,
        val cachedAtMillis: Long,
        val opaqueRequiredBalance: Double? = null,
    )

    private val lock = Any()
    private var cacheDistinctId = identity.distinctId()
    private var durableAccess: Map<String, FeatureAccess> = emptyMap()
    private var durableEntities: Map<String, Map<String, FeatureAccess>> = emptyMap()
    private val realTimeCache = mutableMapOf<CacheKey, TimedAccess>()
    private var scopeGeneration = 0L
    private var nextSeq = 0L
    private val committedSeq = mutableMapOf<CacheKey, Long>()

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
            getExactOpaqueCached(featureId, requiredBalance, entityId)?.let { return it }
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
            committedSeq.clear()
            scopeGeneration += 1
        }
        featureInfo.reset()
    }

    /** Called by ProfileService whenever its raw profile body is applied. */
    suspend fun hydrateProfile(distinctId: String, body: JsonObject) {
        val hydrationGeneration = synchronized(lock) { scopeGeneration }
        if (identity.distinctId() != distinctId) return
        val parsed = parseProfileFeatures(body)
        synchronized(lock) {
            if (identity.distinctId() != distinctId || scopeGeneration != hydrationGeneration) return
            cacheDistinctId = distinctId
            durableAccess = parsed.first
            durableEntities = parsed.second
        }
        publishCurrent(ready = true, expectedGeneration = hydrationGeneration)
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
            when (val cached = realTimeCache[key]?.takeIf(::isFresh)) {
                null -> entityAccess(featureId, entityId)?.forRequiredBalance(requiredBalance)
                else -> if (cached.opaqueRequiredBalance == null) {
                    cached.access.forRequiredBalance(requiredBalance)
                } else if (cached.opaqueRequiredBalance == requiredBalance) {
                    cached.access
                } else {
                    null
                }
            }
        }
    }

    private suspend fun getExactOpaqueCached(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureAccess? {
        synchronizeCustomerScopeIfNeeded()
        return synchronized(lock) {
            realTimeCache[CacheKey(featureId, entityId)]
                ?.takeIf(::isFresh)
                ?.takeIf { it.opaqueRequiredBalance == requiredBalance && it.opaqueRequiredBalance != null }
                ?.access
        }
    }

    private suspend fun performCheck(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureAccess {
        synchronizeCustomerScopeIfNeeded()
        val distinctId = identity.distinctId()
        val key = CacheKey(featureId, entityId)
        val (requestGeneration, seq) = synchronized(lock) {
            scopeGeneration to nextSeq++
        }
        val result = api.checkFeature(distinctId, featureId, requiredBalance, entityId)
        synchronized(lock) {
            if (scopeGeneration != requestGeneration || cacheDistinctId != distinctId) {
                throw kotlinx.coroutines.CancellationException()
            }
            if (seq <= (committedSeq[key] ?: Long.MIN_VALUE)) {
                throw kotlinx.coroutines.CancellationException()
            }
            val opaqueRequiredBalance = result.requiredBalance.takeIf { result.featureId != featureId }
            val access = authoritativeAccess(result, featureId)
            realTimeCache[key] = TimedAccess(access, nowMillis(), opaqueRequiredBalance)
            committedSeq[key] = seq
            featureInfo.update(featureId, access, entityId)
            return access
        }
    }

    private suspend fun synchronizeCustomerScopeIfNeeded() {
        val distinctId = identity.distinctId()
        val changed = synchronized(lock) {
            if (cacheDistinctId == distinctId) false else {
                cacheDistinctId = distinctId
                durableAccess = emptyMap()
                durableEntities = emptyMap()
                realTimeCache.clear()
                committedSeq.clear()
                scopeGeneration += 1
                true
            }
        }
        if (changed) featureInfo.reset()
    }

    private suspend fun publishCurrent(ready: Boolean = false, expectedGeneration: Long? = null) {
        synchronized(lock) {
            if (expectedGeneration != null && scopeGeneration != expectedGeneration) return
            val fresh = realTimeCache
                .filter { (key, timed) -> key.entityId == null && isFresh(timed) }
                .mapValues { it.value.access }
                .mapKeys { it.key.featureId }
            featureInfo.update(durableAccess + fresh, durableEntities, ready)
        }
    }

    private fun entityAccess(featureId: String, entityId: String?): FeatureAccess? = when (entityId) {
        null -> durableAccess[featureId]
        else -> durableEntities[featureId]?.let { entities ->
            entities[entityId] ?: durableAccess[featureId]?.let { FeatureAccess(false, false, null, it.type) }
        } ?: durableAccess[featureId]
    }

    private fun authoritativeAccess(
        result: NuxieApi.FeatureCheckResult,
        requestedFeatureId: String,
    ): FeatureAccess = if (result.featureId == requestedFeatureId) {
        FeatureAccess(result.allowed, result.unlimited, result.balance, result.type)
    } else {
        FeatureAccess(
            allowed = result.allowed,
            unlimited = result.unlimited,
            balance = if (result.balance == 0.0) 0.0 else null,
            type = FeatureType.METERED,
        )
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
