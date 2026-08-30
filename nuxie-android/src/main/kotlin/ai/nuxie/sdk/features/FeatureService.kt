package ai.nuxie.sdk.features

import ai.nuxie.sdk.commerce.OptimisticFeatureOverlay
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
    private data class AuthoritativeAccessUpdate(
        val access: FeatureAccess,
        val mutationRevision: Long,
        val opaqueRequiredBalance: Double? = null,
    )
    private data class PurchaseProjection(
        val access: Map<String, FeatureAccess>,
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
    private var optimisticOverlay: Map<String, OptimisticFeatureOverlay> = emptyMap()
    private var profileAdmitted = false
    private var purchaseMutationRevision = 0L
    private val featurePurchaseMutationRevisions = mutableMapOf<String, Long>()
    private var scopeGeneration = 0L
    private var authoritativeMutationRevision = 0L
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
        reconciledOptimisticProjection: Map<String, OptimisticFeatureOverlay>? = null,
        reconcileOptimisticProjection: Boolean = false,
    ): FeatureAccess {
        synchronizeCustomerScopeIfNeeded()
        if (identity.distinctId() != distinctId || result.customerId != distinctId ||
            expectedScope.distinctId != distinctId
        ) {
            throw kotlinx.coroutines.CancellationException()
        }
        val (access, publication) = synchronized(lock) {
            if (identity.distinctId() != distinctId || cacheDistinctId != distinctId ||
                scopeGeneration != expectedScope.generation
            ) {
                throw kotlinx.coroutines.CancellationException()
            }
            if (reconcileOptimisticProjection) {
                optimisticOverlay = reconciledOptimisticProjection.orEmpty()
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
            val updates = affectedFeatureIds.associateWith { featureId ->
                AuthoritativeAccessUpdate(
                    access = publishedAccess.getValue(featureId),
                    mutationRevision = mutationRevisions.getValue(featureId),
                    opaqueRequiredBalance = result.requiredBalance.takeIf {
                        featureId == requestedFeatureId && result.featureId != requestedFeatureId
                    },
                )
            }
            requestedAccess to commitAuthoritativeAccessLocked(updates, entityId)
        }
        featureInfo.publish(publication)
        return access
    }

    /** Apply an ordinary server-confirmed usage balance without consuming the overlay. */
    suspend fun applyAuthoritativeUsageBalance(featureId: String, balance: Double, entityId: String?) {
        synchronizeCustomerScopeIfNeeded()
        val publication = synchronized(lock) {
            val authoritative = realTimeCache[CacheKey(featureId, entityId)]?.access
                ?: entityAccess(featureId, entityId)
            val visible = visibleAccess(featureId, authoritative, entityId) ?: return
            val updated = FeatureAccess(
                allowed = authoritative?.unlimited == true || balance >= DEFAULT_REQUIRED_BALANCE,
                unlimited = authoritative?.unlimited ?: false,
                balance = balance,
                type = authoritative?.type ?: visible.type,
            )
            commitAuthoritativeAccessLocked(
                mapOf(
                    featureId to AuthoritativeAccessUpdate(
                        access = updated,
                        mutationRevision = advanceFeatureMutationRevision(featureId),
                    ),
                ),
                entityId,
            )
        }
        featureInfo.publish(publication)
    }

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
        val checked = performCheck(featureId, requiredBalance, entityId)
        return if (checked.supersededByMutation) checked.effective else checked.authoritative
    }

    suspend fun clearCache() {
        synchronizeCustomerScopeIfNeeded()
        val publication = synchronized(lock) {
            realTimeCache.clear()
            checkNotNull(stageCurrentLocked())
        }
        // Clearing the short-lived check cache must not erase admitted profile
        // access or the evidence-derived widening overlay.
        featureInfo.publish(publication)
    }

    suspend fun handleUserChange(from: String, to: String) {
        val publication = synchronized(lock) {
            // A new customer must never inherit the prior customer's durable
            // profile snapshot or short-lived check results.
            cacheDistinctId = to
            durableAccess = emptyMap()
            durableEntities = emptyMap()
            realTimeCache.clear()
            purchaseUpdates.clear()
            optimisticOverlay = emptyMap()
            profileAdmitted = false
            featureMutationRevisions.clear()
            committedMutationRevisions.clear()
            scopeGeneration += 1
            featureInfo.stageReset()
        }
        featureInfo.publish(publication)
    }

    /** Called by ProfileService whenever its raw profile body is applied. */
    fun capturePurchaseRevision(): Long = synchronized(lock) { purchaseMutationRevision }

    /** Reserve the ordering token for a profile load before its I/O begins. */
    fun reserveAuthoritativeRevision(): Long = synchronized(lock) {
        authoritativeMutationRevision += 1
        authoritativeMutationRevision
    }

    /**
     * Apply an already available profile using revisions captured at this call site.
     * Keep this three-argument shape stable because Kotlin callers that omit the
     * revision link against its generated default-argument method.
     */
    suspend fun hydrateProfile(
        distinctId: String,
        body: JsonObject,
        snapshotPurchaseRevision: Long = capturePurchaseRevision(),
    ) = hydrateProfile(
        distinctId,
        body,
        snapshotPurchaseRevision,
        reserveAuthoritativeRevision(),
    )

    /** Apply a profile using revisions reserved before profile I/O began. */
    suspend fun hydrateProfile(
        distinctId: String,
        body: JsonObject,
        snapshotPurchaseRevision: Long,
        snapshotAuthoritativeRevision: Long = reserveAuthoritativeRevision(),
    ) {
        val hydrationGeneration = synchronized(lock) { scopeGeneration }
        if (identity.distinctId() != distinctId) return
        val parsed = parseProfileFeatures(body)
        val publication = synchronized(lock) {
            if (identity.distinctId() != distinctId || scopeGeneration != hydrationGeneration) return
            val replacedFeatureIds = durableAccess.keys + durableEntities.keys
            cacheDistinctId = distinctId
            durableAccess = parsed.first
            durableEntities = parsed.second
            profileAdmitted = true
            val affectedFeatureIds = linkedSetOf<String>().apply {
                addAll(replacedFeatureIds)
                addAll(featureMutationRevisions.keys)
                addAll(realTimeCache.keys.map { it.featureId })
                addAll(durableAccess.keys)
                addAll(durableEntities.keys)
            }
            realTimeCache.entries.removeAll { (key, _) ->
                (committedMutationRevisions[key] ?: Long.MIN_VALUE) <= snapshotAuthoritativeRevision
            }
            committedMutationRevisions.entries.removeAll { (_, revision) ->
                revision <= snapshotAuthoritativeRevision
            }
            affectedFeatureIds.forEach { featureId ->
                if ((featureMutationRevisions[featureId] ?: Long.MIN_VALUE) <=
                    snapshotAuthoritativeRevision
                ) {
                    featureMutationRevisions[featureId] = snapshotAuthoritativeRevision
                    committedMutationRevisions[CacheKey(featureId, null)] =
                        snapshotAuthoritativeRevision
                }
            }
            purchaseUpdates.entries.removeAll {
                it.value.committedRevision <= snapshotPurchaseRevision
            }
            stageCurrentLocked(expectedGeneration = hydrationGeneration)
        }
        publication?.let { featureInfo.publish(it) }
    }

    /** Replace the entire pure evidence-derived overlay for [distinctId]. */
    suspend fun applyOptimisticPurchaseProjection(
        distinctId: String,
        projection: Map<String, OptimisticFeatureOverlay>?,
    ) {
        synchronizeCustomerScopeIfNeeded()
        val publication = synchronized(lock) {
            if (identity.distinctId() != distinctId || cacheDistinctId != distinctId) return
            val replacement = projection.orEmpty()
            if (optimisticOverlay == replacement) return
            optimisticOverlay = replacement
            stageCurrentLocked()
        }
        publication?.let { featureInfo.publish(it) }
    }

    /** Remove authority that was accepted for a purchase before its exact owner was known. */
    suspend fun reassignPurchaseAuthority(
        distinctId: String,
        transactionId: String,
        projection: Map<String, OptimisticFeatureOverlay>?,
    ) {
        synchronizeCustomerScopeIfNeeded()
        val publication = synchronized(lock) {
            if (identity.distinctId() != distinctId || cacheDistinctId != distinctId) return
            optimisticOverlay = projection.orEmpty()
            val removed = purchaseUpdates.remove(transactionId)?.access.orEmpty()
            if (removed.isNotEmpty()) advancePurchaseMutationRevision(removed.keys)
            stageCurrentLocked()
        }
        publication?.let { featureInfo.publish(it) }
    }

    /** Merge the incremental Feature access returned by /purchase. */
    suspend fun updateFromPurchase(distinctId: String, body: JsonObject, transactionId: String) {
        updateFromPurchaseAndProjection(distinctId, body, transactionId, null, false)
    }

    /** Commit backend purchase authority and its evidence-derived overlay removal atomically. */
    suspend fun reconcilePurchase(
        distinctId: String,
        body: JsonObject,
        transactionId: String,
        projection: Map<String, OptimisticFeatureOverlay>?,
    ) {
        updateFromPurchaseAndProjection(distinctId, body, transactionId, projection, true)
    }

    private suspend fun updateFromPurchaseAndProjection(
        distinctId: String,
        body: JsonObject,
        transactionId: String,
        projection: Map<String, OptimisticFeatureOverlay>?,
        reconcileProjection: Boolean,
    ) {
        val hydrationGeneration = synchronized(lock) { scopeGeneration }
        if (identity.distinctId() != distinctId) return
        val updates = parsePurchaseFeatures(body)
        val publication = synchronized(lock) {
            if (identity.distinctId() != distinctId || scopeGeneration != hydrationGeneration) return
            if (reconcileProjection) optimisticOverlay = projection.orEmpty()
            val replaced = purchaseUpdates[transactionId]?.access.orEmpty()
            val revision = advancePurchaseMutationRevision(replaced.keys + updates.keys)
            updates.keys.forEach { featureId ->
                realTimeCache.keys.removeAll { it.featureId == featureId }
            }
            purchaseUpdates[transactionId] = PurchaseProjection(updates, revision)
            stageCurrentLocked(expectedGeneration = hydrationGeneration)
        }
        publication?.let { featureInfo.publish(it) }
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
        val authoritative = when (val cached = realTimeCache[key]?.takeIf(::isFresh)) {
            null -> entityAccess(featureId, entityId)
            else -> if (cached.opaqueRequiredBalance == null) {
                cached.access
            } else if (cached.opaqueRequiredBalance == (requiredBalance ?: DEFAULT_REQUIRED_BALANCE)) {
                cached.access
            } else {
                null
            }
        }
        return visibleAccessForRequiredBalance(featureId, authoritative, entityId, requiredBalance)
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
        realTimeCache[CacheKey(featureId, entityId)]
            ?.takeIf(::isFresh)
            ?.let { cached ->
                val opaqueRequiredBalance = cached.opaqueRequiredBalance
                if (opaqueRequiredBalance == null) {
                    return visibleAccessForRequiredBalance(featureId, cached.access, entityId, requiredBalance)
                }
                val matchesRequirement =
                    opaqueRequiredBalance == (requiredBalance ?: DEFAULT_REQUIRED_BALANCE)
                return visibleAccess(featureId, cached.access.copy(
                    allowed = matchesRequirement && cached.access.allowed,
                    unlimited = matchesRequirement && cached.access.unlimited,
                    balance = null,
                ), entityId)
            }
        if (entityId == null) {
            purchaseUpdates.values.mapNotNull { it.access[featureId] }.lastOrNull()
                ?.let { visibleAccessForRequiredBalance(featureId, it, entityId, requiredBalance) }
                ?.let { return it }
            if (featureId in optimisticOverlay) {
                return visibleAccessForRequiredBalance(
                    featureId,
                    durableGlobalAccess()[featureId],
                    entityId,
                    requiredBalance,
                )
            }
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
                ?.let { visibleAccess(featureId, it, entityId) }
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
        return withContext(Dispatchers.IO) {
            val (checked, publication) = synchronized(lock) {
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
                )?.let { return@synchronized it to null }
                val dependencySuperseded = affectedFeatureIds.any { affectedFeatureId ->
                    affectedFeatureId != featureId &&
                        (featureMutationRevisions[affectedFeatureId] ?: Long.MIN_VALUE) >
                        requestMutationRevision
                }
                if (dependencySuperseded) {
                    throw kotlinx.coroutines.CancellationException()
                }
                val applicableFeatureIds = affectedFeatureIds.filterTo(linkedSetOf()) {
                    (featureMutationRevisions[it] ?: Long.MIN_VALUE) <= requestMutationRevision
                }
                applicableFeatureIds.forEach { affectedFeatureId ->
                    featureMutationRevisions[affectedFeatureId] = requestMutationRevision
                }
                val updates = applicableFeatureIds.associateWith { affectedFeatureId ->
                    AuthoritativeAccessUpdate(
                        access = publishedAccess.getValue(affectedFeatureId),
                        mutationRevision = requestMutationRevision,
                        opaqueRequiredBalance = result.requiredBalance.takeIf {
                            affectedFeatureId == featureId && result.featureId != featureId
                        },
                    )
                }
                val effectiveAccess = visibleAccessForRequiredBalance(
                    featureId,
                    requestedAccess,
                    entityId,
                    requiredBalance,
                )
                    ?: requestedAccess
                CheckedAccess(serverAccess, effectiveAccess, supersededByMutation = false) to
                    commitAuthoritativeAccessLocked(updates, entityId)
            }
            publication?.let { featureInfo.publish(it) }
            checked
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
        val committedRevision = maxOf(
            committedMutationRevisions[key] ?: Long.MIN_VALUE,
            committedMutationRevisions[CacheKey(featureId, null)] ?: Long.MIN_VALUE,
        )
        val supersededByMutation =
            (purchaseRevisionChanged && entityId == null) ||
                committedRevision > requestMutationRevision
        val effectiveAccess = if (supersededByMutation) {
            committedCachedAccess(featureId, requiredBalance, entityId) ?: serverAccess
        } else {
            requestedAccess
        }
        return CheckedAccess(serverAccess, effectiveAccess, supersededByMutation)
    }

    private suspend fun synchronizeCustomerScopeIfNeeded() {
        val distinctId = identity.distinctId()
        val publication = synchronized(lock) {
            if (cacheDistinctId == distinctId) null else {
                cacheDistinctId = distinctId
                durableAccess = emptyMap()
                durableEntities = emptyMap()
                realTimeCache.clear()
                purchaseUpdates.clear()
                optimisticOverlay = emptyMap()
                profileAdmitted = false
                featureMutationRevisions.clear()
                committedMutationRevisions.clear()
                scopeGeneration += 1
                featureInfo.stageReset()
            }
        }
        publication?.let { featureInfo.publish(it) }
    }

    /** Must be called while [lock] is held so reservation order equals commit order. */
    private fun stageCurrentLocked(
        expectedGeneration: Long? = null,
        publishedEntityAccess: Map<String, FeatureAccess> = emptyMap(),
    ): FeatureInfo.Mutation? {
        if (expectedGeneration != null && scopeGeneration != expectedGeneration) return null
        val fresh = realTimeCache
            .filter { (key, timed) -> key.entityId == null && isFresh(timed) }
            .mapValues { it.value.access }
            .mapKeys { it.key.featureId }
        return featureInfo.stageUpdate(
            mergeOptimisticOverlay(durableGlobalAccess() + fresh) + publishedEntityAccess,
            durableEntities,
            readinessState(),
        )
    }

    /** Commit authoritative state and reserve its fully recomposed publication atomically. */
    private fun commitAuthoritativeAccessLocked(
        updates: Map<String, AuthoritativeAccessUpdate>,
        entityId: String?,
    ): FeatureInfo.Mutation {
        val cachedAt = nowMillis()
        updates.forEach { (featureId, update) ->
            val key = CacheKey(featureId, entityId)
            realTimeCache[key] = TimedAccess(
                access = update.access,
                cachedAtMillis = cachedAt,
                opaqueRequiredBalance = update.opaqueRequiredBalance,
            )
            committedMutationRevisions[key] = update.mutationRevision
        }
        return checkNotNull(
            stageCurrentLocked(
                publishedEntityAccess = if (entityId == null) {
                    emptyMap()
                } else {
                    updates.mapValues { it.value.access }
                },
            ),
        )
    }

    private fun mergedGlobalAccess(): Map<String, FeatureAccess> {
        val fresh = realTimeCache
            .filter { (key, timed) -> key.entityId == null && isFresh(timed) }
            .mapValues { it.value.access }
            .mapKeys { it.key.featureId }
        return mergeOptimisticOverlay(durableGlobalAccess() + fresh)
    }

    private fun durableGlobalAccess(): Map<String, FeatureAccess> {
        var merged = durableAccess
        purchaseUpdates.values.forEach { merged = merged + it.access }
        return merged
    }

    private fun mergeOptimisticOverlay(base: Map<String, FeatureAccess>): Map<String, FeatureAccess> {
        var merged = base
        optimisticOverlay.forEach { (featureId, overlay) ->
            merged = merged + (featureId to overlay.widen(merged[featureId]))
        }
        return merged
    }

    private fun visibleAccess(
        featureId: String,
        authoritative: FeatureAccess?,
        entityId: String?,
    ): FeatureAccess? = if (entityId == null) {
        optimisticOverlay[featureId]?.widen(authoritative) ?: authoritative
    } else {
        authoritative
    }

    private fun visibleAccessForRequiredBalance(
        featureId: String,
        authoritative: FeatureAccess?,
        entityId: String?,
        requiredBalance: Double?,
    ): FeatureAccess? {
        val visible = visibleAccess(featureId, authoritative, entityId)
        return if (entityId == null && optimisticOverlay[featureId]?.type == FeatureType.BOOLEAN) {
            visible
        } else {
            visible?.forRequiredBalance(requiredBalance)
        }
    }

    private fun OptimisticFeatureOverlay.widen(authoritative: FeatureAccess?): FeatureAccess = when {
        type == FeatureType.BOOLEAN || authoritative?.type == FeatureType.BOOLEAN -> FeatureAccess(
            allowed = true,
            unlimited = authoritative?.unlimited == true || unlimited,
            balance = authoritative?.balance,
            type = authoritative?.type ?: type,
        )
        unlimited -> FeatureAccess(
            allowed = true,
            unlimited = true,
            balance = authoritative?.balance,
            type = authoritative?.type ?: type,
        )
        else -> {
            val visibleBalance = (authoritative?.balance ?: 0.0) + (balanceIncrease ?: 0.0)
            FeatureAccess(
                allowed = authoritative?.allowed == true ||
                    authoritative?.unlimited == true ||
                    visibleBalance >= DEFAULT_REQUIRED_BALANCE,
                unlimited = authoritative?.unlimited ?: false,
                balance = visibleBalance,
                type = authoritative?.type ?: type,
            )
        }
    }

    private fun readinessState(): FeatureInfo.State = when {
        !profileAdmitted -> FeatureInfo.State.Unknown
        optimisticOverlay.isNotEmpty() -> FeatureInfo.State.Reconciling
        else -> FeatureInfo.State.Ready
    }

    private fun advanceFeatureMutationRevision(featureId: String): Long {
        authoritativeMutationRevision += 1
        val revision = authoritativeMutationRevision
        featureMutationRevisions[featureId] = revision
        return revision
    }

    private fun advancePurchaseMutationRevision(featureIds: Set<String>): Long {
        purchaseMutationRevision += 1
        featureIds.forEach { featureId ->
            featurePurchaseMutationRevisions[featureId] = purchaseMutationRevision
            advanceFeatureMutationRevision(featureId)
        }
        return purchaseMutationRevision
    }

    private fun purchaseRevision(featureId: String): Long? =
        featurePurchaseMutationRevisions[featureId]

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
