package ai.nuxie.sdk.features

import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.network.NuxieApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Profile-backed Feature access and the short-lived results of real-time
 * checks, with customer-scoped optimistic purchase projections above them.
 */
internal class FeatureService(
    private val api: NuxieApi,
    private val identity: IdentityProvider,
    private val featureInfo: FeatureInfo,
    private val cacheTtlMillis: Long,
    private val revocationStore: DurableFeatureRevocationStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    internal data class AuthoritativeUseScope(
        val distinctId: String,
        val generation: Long,
    )

    private data class CacheKey(val featureId: String, val entityId: String?)
    private data class TimedAccess(
        val access: FeatureAccess,
        val cachedAtMillis: Long,
        val opaqueRequiredBalance: Double? = null,
    )
    private data class CheckedAccess(
        val authoritative: FeatureAccess,
        val effective: FeatureAccess,
        val supersededByMutation: Boolean,
    )
    private data class PurchaseProjection(
        val grants: Map<String, FeatureAccess>,
        val committedRevision: Long,
    )

    private companion object {
        /** A null requiredBalance means "one unit" everywhere. */
        const val DEFAULT_REQUIRED_BALANCE = 1.0
    }

    private val lock = Any()
    private var cacheDistinctId = identity.distinctId()
    private var durableAccess: Map<String, FeatureAccess> = emptyMap()
    private var durableEntities: Map<String, Map<String, FeatureAccess>> = emptyMap()
    private val realTimeCache = mutableMapOf<CacheKey, TimedAccess>()
    private val purchaseUpdates = mutableMapOf<String, PurchaseProjection>()
    private val localPurchases = mutableMapOf<String, PurchaseProjection>()
    private val revokedPurchases = mutableMapOf<String, PurchaseProjection>()
    private var purchaseMutationRevision = 0L
    private val featurePurchaseMutationRevisions = mutableMapOf<String, Long>()
    private var scopeGeneration = 0L
    private val featureMutationRevisions = mutableMapOf<String, Long>()
    private val committedMutationRevisions = mutableMapOf<CacheKey, Long>()

    suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? =
        getCached(featureId, requiredBalance = null, entityId = entityId)

    suspend fun getAllCached(): Map<String, FeatureAccess> {
        synchronizeCustomerScopeIfNeeded()
        return synchronized(lock) {
            mergedGlobalAccess()
        }
    }

    suspend fun check(
        featureId: String,
        requiredBalance: Double? = null,
        entityId: String? = null,
    ): FeatureAccess {
        val checked = performCheck(featureId, requiredBalance, entityId)
        if (checked.supersededByMutation) throw kotlinx.coroutines.CancellationException()
        return checked.authoritative
    }

    /** Capture the customer scope before starting an authoritative use request. */
    suspend fun captureAuthoritativeUseScope(distinctId: String): AuthoritativeUseScope {
        synchronizeCustomerScopeIfNeeded()
        return synchronized(lock) {
            if (identity.distinctId() != distinctId || cacheDistinctId != distinctId) {
                throw kotlinx.coroutines.CancellationException()
            }
            AuthoritativeUseScope(distinctId, scopeGeneration)
        }
    }

    /** Apply the server-authoritative post-use state returned by an atomic command. */
    suspend fun applyAuthoritativeUse(
        result: NuxieApi.FeatureCheckResult,
        requestedFeatureId: String,
        distinctId: String,
        entityId: String?,
        expectedScope: AuthoritativeUseScope,
    ): FeatureAccess {
        synchronizeCustomerScopeIfNeeded()
        if (identity.distinctId() != distinctId || result.customerId != distinctId ||
            expectedScope.distinctId != distinctId
        ) {
            throw kotlinx.coroutines.CancellationException()
        }
        return synchronized(lock) {
            if (identity.distinctId() != distinctId || cacheDistinctId != distinctId ||
                scopeGeneration != expectedScope.generation
            ) {
                throw kotlinx.coroutines.CancellationException()
            }

            val affectedFeatureIds = linkedSetOf(requestedFeatureId, result.featureId)
            val mutationRevisions = affectedFeatureIds.associateWith { featureId ->
                advanceFeatureMutationRevision(featureId)
            }
            val requestedAccess = authoritativeAccess(result, requestedFeatureId)
            val balanceSourceAccess = resultAccess(result)
            val publishedAccess = affectedFeatureIds.associateWith { featureId ->
                if (featureId == requestedFeatureId) requestedAccess else balanceSourceAccess
            }
            val allowedFeatureIds = publishedAccess
                .filterValues { it.allowed }
                .keys

            reconcileLocalPurchases(affectedFeatureIds)
            if (allowedFeatureIds.isNotEmpty() &&
                !revocationStore.retireRevokedGrants(distinctId, allowedFeatureIds)
            ) {
                return@synchronized requestedAccess
            }
            if (identity.distinctId() != distinctId || cacheDistinctId != distinctId ||
                scopeGeneration != expectedScope.generation
            ) {
                throw kotlinx.coroutines.CancellationException()
            }
            if (allowedFeatureIds.isNotEmpty()) {
                reconcileRevokedPurchases(allowedFeatureIds)
            }

            val cachedAt = nowMillis()
            affectedFeatureIds.forEach { featureId ->
                val access = publishedAccess.getValue(featureId)
                val key = CacheKey(featureId, entityId)
                committedMutationRevisions[key] = mutationRevisions.getValue(featureId)
                realTimeCache[key] = TimedAccess(
                    access = access,
                    cachedAtMillis = cachedAt,
                    opaqueRequiredBalance = result.requiredBalance.takeIf {
                        featureId == requestedFeatureId && result.featureId != requestedFeatureId
                    },
                )
                featureInfo.update(featureId, access, entityId)
            }
            requestedAccess
        }
    }

    suspend fun checkWithCache(
        featureId: String,
        requiredBalance: Double? = null,
        entityId: String? = null,
        forceRefresh: Boolean = false,
    ): FeatureAccess {
        if (!forceRefresh) {
            getPurchaseCached(featureId, requiredBalance, entityId)?.let { return it }
            getExactOpaqueCached(featureId, requiredBalance, entityId)?.let { return it }
            getCached(featureId, requiredBalance, entityId)?.let { cached ->
                if (cached.type == FeatureType.BOOLEAN) return cached
                if (cached.unlimited || (cached.balance ?: 0.0) >= (requiredBalance ?: 1.0)) {
                    return cached
                }
            }
        }
        return performCheck(featureId, requiredBalance, entityId).effective
    }

    private suspend fun getPurchaseCached(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureAccess? {
        synchronizeCustomerScopeIfNeeded()
        return synchronized(lock) {
            purchaseAccess(featureId, entityId)?.forRequiredBalance(requiredBalance)
        }
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
            purchaseUpdates.clear()
            localPurchases.clear()
            revokedPurchases.clear()
            featureMutationRevisions.clear()
            committedMutationRevisions.clear()
            scopeGeneration += 1
        }
        featureInfo.reset()
    }

    /** Called by ProfileService whenever its raw profile body is applied. */
    fun capturePurchaseRevision(): Long = synchronized(lock) { purchaseMutationRevision }

    suspend fun hydrateProfile(
        distinctId: String,
        body: JsonObject,
        snapshotPurchaseRevision: Long = capturePurchaseRevision(),
    ) {
        val hydrationGeneration = synchronized(lock) { scopeGeneration }
        if (identity.distinctId() != distinctId) return
        val parsed = parseProfileFeatures(body)
        synchronized(lock) {
            if (identity.distinctId() != distinctId || scopeGeneration != hydrationGeneration) return
            cacheDistinctId = distinctId
            durableAccess = parsed.first
            durableEntities = parsed.second
            purchaseUpdates.entries.removeAll {
                it.value.committedRevision <= snapshotPurchaseRevision
            }
            // A fresh server snapshot is authoritative over earlier optimistic
            // access and revocation tombstones for the Features it contains.
            val reconciled = parsed.first.keys
            localPurchases.toMap().forEach { (transactionId, projection) ->
                if (projection.committedRevision <= snapshotPurchaseRevision) {
                    localPurchases[transactionId] = projection.copy(grants = projection.grants - reconciled)
                }
            }
            revokedPurchases.toMap().forEach { (transactionId, projection) ->
                if (projection.committedRevision <= snapshotPurchaseRevision) {
                    revokedPurchases[transactionId] = projection.copy(grants = projection.grants - reconciled)
                }
            }
            localPurchases.entries.removeAll { it.value.grants.isEmpty() }
            revokedPurchases.entries.removeAll { it.value.grants.isEmpty() }
        }
        publishCurrent(ready = true, expectedGeneration = hydrationGeneration)
    }

    suspend fun syncFeatureInfo() = publishCurrent(ready = true)

    suspend fun applyLocalPurchase(grants: List<LocalPurchaseGrant>, transactionId: String) {
        synchronizeCustomerScopeIfNeeded()
        val optimistic = grants.filter { it.type == FeatureType.BOOLEAN || it.unlimited }
            .associate { grant ->
                grant.featureId to FeatureAccess(
                    allowed = true,
                    unlimited = grant.unlimited,
                    balance = null,
                    type = grant.type,
                )
            }
        if (optimistic.isEmpty()) return
        synchronized(lock) {
            if (localPurchases.containsKey(transactionId)) return
            val replacedRevocation = revokedPurchases.remove(transactionId)?.grants.orEmpty()
            optimistic.keys.forEach { featureId ->
                realTimeCache.keys.removeAll { it.featureId == featureId }
            }
            val revision = advancePurchaseMutationRevision(optimistic.keys + replacedRevocation.keys)
            localPurchases[transactionId] = PurchaseProjection(optimistic, revision)
            optimistic.keys.forEach { featureId ->
                committedMutationRevisions[CacheKey(featureId, null)] = advanceFeatureMutationRevision(featureId)
            }
        }
        publishCurrent()
    }

    suspend fun removePurchase(transactionId: String) {
        synchronizeCustomerScopeIfNeeded()
        synchronized(lock) {
            val optimistic = localPurchases.remove(transactionId)?.grants.orEmpty()
            val accepted = purchaseUpdates.remove(transactionId)?.grants.orEmpty()
            val removed = optimistic + accepted
            if (removed.isEmpty()) return
            val revision = advancePurchaseMutationRevision(removed.keys)
            // iOS checks revokedPurchaseCache before localPurchaseCache,
            // realTimeCache, and durable profile access (FeatureService.swift).
            val tombstones = removed.mapValues { (_, access) ->
                FeatureAccess(false, false, access.balance, access.type)
            }
            revokedPurchases[transactionId] = PurchaseProjection(tombstones, revision)
        }
        publishCurrent()
    }

    /** Merge the incremental Feature grants returned by /purchase. */
    suspend fun updateFromPurchase(distinctId: String, body: JsonObject, transactionId: String) {
        val hydrationGeneration = synchronized(lock) { scopeGeneration }
        if (identity.distinctId() != distinctId) return
        val updates = parsePurchaseFeatures(body)
        synchronized(lock) {
            if (identity.distinctId() != distinctId || scopeGeneration != hydrationGeneration) return
            val replaced = purchaseUpdates[transactionId]?.grants.orEmpty() +
                localPurchases.remove(transactionId)?.grants.orEmpty() +
                revokedPurchases.remove(transactionId)?.grants.orEmpty()
            val revision = advancePurchaseMutationRevision(replaced.keys + updates.keys)
            updates.keys.forEach { featureId ->
                realTimeCache.keys.removeAll { it.featureId == featureId }
            }
            purchaseUpdates[transactionId] = PurchaseProjection(updates, revision)
        }
        publishCurrent(ready = true, expectedGeneration = hydrationGeneration)
    }

    suspend fun getCached(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureAccess? {
        synchronizeCustomerScopeIfNeeded()
        return synchronized(lock) {
            cachedAccess(featureId, requiredBalance, entityId)
        }
    }

    private fun cachedAccess(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureAccess? {
        val key = CacheKey(featureId, entityId)
        return purchaseAccess(featureId, entityId)?.forRequiredBalance(requiredBalance) ?: run {
            when (val cached = realTimeCache[key]?.takeIf(::isFresh)) {
                null -> entityAccess(featureId, entityId)?.forRequiredBalance(requiredBalance)
                else -> if (cached.opaqueRequiredBalance == null) {
                    cached.access.forRequiredBalance(requiredBalance)
                } else if (cached.opaqueRequiredBalance == (requiredBalance ?: DEFAULT_REQUIRED_BALANCE)) {
                    cached.access
                } else {
                    null
                }
            }
        }
    }

    /**
     * Returns only access committed after startup by a purchase mutation or
     * real-time check. Durable profile access is deliberately excluded: it
     * may predate the request that just completed.
     */
    private fun committedCachedAccess(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureAccess? {
        purchaseAccess(featureId, entityId)
            ?.forRequiredBalance(requiredBalance)
            ?.let { return it }
        realTimeCache[CacheKey(featureId, entityId)]
            ?.takeIf(::isFresh)
            ?.let { cached ->
                val opaqueRequiredBalance = cached.opaqueRequiredBalance
                if (opaqueRequiredBalance == null) {
                    return cached.access.forRequiredBalance(requiredBalance)
                }
                val matchesRequirement =
                    opaqueRequiredBalance == (requiredBalance ?: DEFAULT_REQUIRED_BALANCE)
                return cached.access.copy(
                    allowed = matchesRequirement && cached.access.allowed,
                    unlimited = matchesRequirement && cached.access.unlimited,
                    balance = null,
                )
            }
        if (entityId == null) {
            purchaseUpdates.values.mapNotNull { it.grants[featureId] }.lastOrNull()
                ?.forRequiredBalance(requiredBalance)
                ?.let { return it }
        }
        return null
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
                ?.takeIf {
                    it.opaqueRequiredBalance != null &&
                        it.opaqueRequiredBalance == (requiredBalance ?: DEFAULT_REQUIRED_BALANCE)
                }
                ?.access
        }
    }

    private suspend fun performCheck(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): CheckedAccess {
        synchronizeCustomerScopeIfNeeded()
        val distinctId = identity.distinctId()
        val key = CacheKey(featureId, entityId)
        val (requestGeneration, requestPurchaseRevision, requestMutationRevision) = synchronized(lock) {
            Triple(
                scopeGeneration,
                purchaseRevision(featureId),
                advanceFeatureMutationRevision(featureId),
            )
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { api.checkFeature(distinctId, featureId, requiredBalance, entityId) }
        }.getOrThrow()
        val serverAccess = FeatureAccess(
            result.allowed,
            result.unlimited,
            result.balance,
            result.type,
        )
        val requestedAccess = authoritativeAccess(result, featureId)
        val affectedFeatureIds = linkedSetOf(featureId, result.featureId)
        val balanceSourceAccess = resultAccess(result)
        val publishedAccess = affectedFeatureIds.associateWith { affectedFeatureId ->
            if (affectedFeatureId == featureId) requestedAccess else balanceSourceAccess
        }
        val allowedFeatureIds = publishedAccess.filterValues { it.allowed }.keys

        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                // Identity flips synchronously while handleUserChange (which
                // bumps the generation) is queued behind it, so the live
                // identity is re-read inside the commit lock; a flip landing
                // after this check is cleaned up by the queued handleUserChange
                // reset, bounding any stale publication to that queue delay.
                validateCheckScope(distinctId, requestGeneration)
                supersededCheckAccess(
                    featureId = featureId,
                    requiredBalance = requiredBalance,
                    entityId = entityId,
                    key = key,
                    requestPurchaseRevision = requestPurchaseRevision,
                    requestMutationRevision = requestMutationRevision,
                    serverAccess = serverAccess,
                    requestedAccess = requestedAccess,
                )?.let { return@synchronized it }
                // iOS performs this synchronous ledger write on its actor.
                // Keep it serialized with the supersession guard and commit.
                if (allowedFeatureIds.isNotEmpty() &&
                    !revocationStore.retireRevokedGrants(distinctId, allowedFeatureIds)
                ) {
                    throw kotlinx.coroutines.CancellationException()
                }
                val mutationRevisions = affectedFeatureIds.associateWith { affectedFeatureId ->
                    if (affectedFeatureId == featureId) {
                        requestMutationRevision
                    } else {
                        advanceFeatureMutationRevision(affectedFeatureId)
                    }
                }
                reconcileLocalPurchases(affectedFeatureIds)
                if (allowedFeatureIds.isNotEmpty()) reconcileRevokedPurchases(allowedFeatureIds)

                val cachedAt = nowMillis()
                affectedFeatureIds.forEach { affectedFeatureId ->
                    val access = publishedAccess.getValue(affectedFeatureId)
                    val affectedKey = CacheKey(affectedFeatureId, entityId)
                    realTimeCache[affectedKey] = TimedAccess(
                        access = access,
                        cachedAtMillis = cachedAt,
                        opaqueRequiredBalance = result.requiredBalance.takeIf {
                            affectedFeatureId == featureId && result.featureId != featureId
                        },
                    )
                    committedMutationRevisions[affectedKey] = mutationRevisions.getValue(affectedFeatureId)
                    featureInfo.update(affectedFeatureId, access, entityId)
                }
                val effectiveAccess = purchaseAccess(featureId, entityId)
                    ?.forRequiredBalance(requiredBalance)
                    ?: requestedAccess
                CheckedAccess(serverAccess, effectiveAccess, supersededByMutation = false)
            }
        }
    }

    private fun validateCheckScope(distinctId: String, requestGeneration: Long) {
        if (identity.distinctId() != distinctId) {
            throw kotlinx.coroutines.CancellationException()
        }
        if (scopeGeneration != requestGeneration || cacheDistinctId != distinctId) {
            throw kotlinx.coroutines.CancellationException()
        }
    }

    private fun supersededCheckAccess(
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
        key: CacheKey,
        requestPurchaseRevision: Long?,
        requestMutationRevision: Long,
        serverAccess: FeatureAccess,
        requestedAccess: FeatureAccess,
    ): CheckedAccess? {
        val purchaseRevisionChanged = purchaseRevision(featureId) != requestPurchaseRevision
        val featureRevisionChanged = featureMutationRevisions[featureId] != requestMutationRevision
        if (!purchaseRevisionChanged && !featureRevisionChanged) return null
        val supersededByMutation =
            (purchaseRevisionChanged && entityId == null) ||
                (committedMutationRevisions[key] ?: Long.MIN_VALUE) > requestMutationRevision
        val effectiveAccess = if (supersededByMutation) {
            committedCachedAccess(featureId, requiredBalance, entityId) ?: requestedAccess
        } else {
            requestedAccess
        }
        return CheckedAccess(serverAccess, effectiveAccess, supersededByMutation)
    }

    private suspend fun synchronizeCustomerScopeIfNeeded() {
        val distinctId = identity.distinctId()
        val changed = synchronized(lock) {
            if (cacheDistinctId == distinctId) false else {
                cacheDistinctId = distinctId
                durableAccess = emptyMap()
                durableEntities = emptyMap()
                realTimeCache.clear()
                purchaseUpdates.clear()
                localPurchases.clear()
                revokedPurchases.clear()
                featureMutationRevisions.clear()
                committedMutationRevisions.clear()
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
            featureInfo.update(mergePurchaseAccess(durableGlobalAccess() + fresh), durableEntities, ready)
        }
    }

    private fun mergedGlobalAccess(): Map<String, FeatureAccess> {
        val fresh = realTimeCache
            .filter { (key, timed) -> key.entityId == null && isFresh(timed) }
            .mapValues { it.value.access }
            .mapKeys { it.key.featureId }
        return mergePurchaseAccess(durableGlobalAccess() + fresh)
    }

    private fun durableGlobalAccess(): Map<String, FeatureAccess> {
        var merged = durableAccess
        purchaseUpdates.values.forEach { merged = merged + it.grants }
        return merged
    }

    private fun mergePurchaseAccess(base: Map<String, FeatureAccess>): Map<String, FeatureAccess> {
        var merged = base
        localPurchases.values.forEach { merged = merged + it.grants }
        revokedPurchases.values.forEach { merged = merged + it.grants }
        return merged
    }

    private fun purchaseAccess(featureId: String, entityId: String?): FeatureAccess? {
        if (entityId != null) return null
        revokedPurchases.values.mapNotNull { it.grants[featureId] }.lastOrNull()?.let { return it }
        return localPurchases.values.mapNotNull { it.grants[featureId] }.lastOrNull()
    }

    private fun advanceFeatureMutationRevision(featureId: String): Long {
        val revision = (featureMutationRevisions[featureId] ?: 0L) + 1
        featureMutationRevisions[featureId] = revision
        return revision
    }

    private fun advancePurchaseMutationRevision(featureIds: Set<String>): Long {
        purchaseMutationRevision += 1
        featureIds.forEach { featureId ->
            featurePurchaseMutationRevisions[featureId] = purchaseMutationRevision
        }
        return purchaseMutationRevision
    }

    private fun purchaseRevision(featureId: String): Long? =
        featurePurchaseMutationRevisions[featureId]

    private fun reconcileLocalPurchases(featureIds: Set<String>) {
        localPurchases.toMap().forEach { (transactionId, projection) ->
            localPurchases[transactionId] = projection.copy(grants = projection.grants - featureIds)
        }
        localPurchases.entries.removeAll { it.value.grants.isEmpty() }
    }

    private fun reconcileRevokedPurchases(featureIds: Set<String>) {
        revokedPurchases.toMap().forEach { (transactionId, projection) ->
            revokedPurchases[transactionId] = projection.copy(grants = projection.grants - featureIds)
        }
        revokedPurchases.entries.removeAll { it.value.grants.isEmpty() }
    }

    private fun entityAccess(featureId: String, entityId: String?): FeatureAccess? = when (entityId) {
        null -> durableGlobalAccess()[featureId]
        else -> durableEntities[featureId]?.let { entities ->
            entities[entityId] ?: durableGlobalAccess()[featureId]
                ?.let { FeatureAccess(false, false, null, FeatureType.BOOLEAN) }
        } ?: durableGlobalAccess()[featureId]
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

    private fun resultAccess(result: NuxieApi.FeatureCheckResult): FeatureAccess =
        FeatureAccess(result.allowed, result.unlimited, result.balance, result.type)
            .forRequiredBalance(requiredBalance = null)

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

    private fun parsePurchaseFeatures(body: JsonObject): Map<String, FeatureAccess> {
        val features = body["features"] as? JsonArray ?: return emptyMap()
        return features.mapNotNull { element ->
            val feature = element as? JsonObject ?: return@mapNotNull null
            val id = feature.string("ext_id")?.takeIf(String::isNotBlank)
                ?: feature.string("id")
                ?: return@mapNotNull null
            val type = feature.string("type")?.toFeatureType() ?: return@mapNotNull null
            val allowed = feature.boolean("allowed") ?: return@mapNotNull null
            id to FeatureAccess(
                allowed = allowed,
                unlimited = feature.boolean("unlimited") ?: false,
                balance = feature.double("balance"),
                type = type,
            )
        }.toMap()
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
