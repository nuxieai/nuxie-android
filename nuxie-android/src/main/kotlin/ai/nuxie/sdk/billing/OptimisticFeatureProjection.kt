package ai.nuxie.sdk.billing

import ai.nuxie.sdk.features.FeatureType

/** A widening-only delta derived from retained verified Play evidence and resolved Feature allowances. */
internal data class OptimisticFeatureOverlay(
    val type: FeatureType,
    val unlimited: Boolean,
    val balanceIncrease: Double?,
)

/**
 * Pure optimistic projection. Missing eligible evidence or an allowance source
 * is absence, never an authoritative empty Feature snapshot.
 */
internal fun optimisticFeatureProjection(
    distinctId: String,
    authorityScope: String,
    evidence: Collection<PurchaseEvidence>,
    descriptors: Collection<StoredProductMapping>,
    bindings: Collection<StoredPurchaseBinding> = emptyList(),
): Map<String, OptimisticFeatureOverlay>? {
    val projected = linkedMapOf<String, OptimisticFeatureOverlay>()
    val accountId = playAccountId(distinctId)
    val eligible = evidence.filter { it.isEligibleProjectionEvidence(distinctId, accountId, authorityScope) }
    for (purchase in eligible) {
        // A purchase whose descriptor is missing or grants nothing (an empty
        // Feature allowance list is schema-valid) contributes no overlay, but must
        // not suppress another purchase's derivable overlay. When nothing
        // derives at all the projection stays absent below.
        val allowances = featureAllowancesForEvidence(purchase, descriptors, bindings)
        for (allowance in allowances) {
            if (allowance.featureId.isBlank()) continue
            val type = runCatching { FeatureType.valueOf(allowance.type) }.getOrNull() ?: continue
            val increase = allowance.allowance?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
            val next = OptimisticFeatureOverlay(
                type = type,
                unlimited = allowance.unlimited,
                balanceIncrease = increase.takeUnless { type == FeatureType.BOOLEAN || allowance.unlimited },
            )
            projected[allowance.featureId] = projected[allowance.featureId]?.widen(next) ?: next
        }
    }
    return projected.takeIf { it.isNotEmpty() }
}

internal fun featureAllowancesForEvidence(
    evidence: PurchaseEvidence,
    descriptors: Collection<StoredProductMapping>,
    bindings: Collection<StoredPurchaseBinding> = emptyList(),
): List<StoredFeatureAllowance> = resolvedFeatureAllowancesForEvidence(
    evidence,
    descriptors,
    bindings,
).orEmpty()

/** Returns null only when no pinned, checkout-binding, or catalog allowance source exists. */
internal fun resolvedFeatureAllowancesForEvidence(
    evidence: PurchaseEvidence,
    descriptors: Collection<StoredProductMapping>,
    bindings: Collection<StoredPurchaseBinding> = emptyList(),
): List<StoredFeatureAllowance>? {
    // A token snapshot is immutable purchase-time evidence, including when it
    // explicitly pins an empty list. Legacy evidence falls back to its checkout
    // binding, then to a catalog descriptor that may arrive during recovery.
    evidence.pinnedFeatureAllowances?.let { return it }
    evidence.selectAllowanceSource(
        bindings.filter { it.obfuscatedAccountId == evidence.obfuscatedAccountId },
        { it.productIdentity },
    )?.let { return it.featureAllowances }
    return evidence.selectAllowanceSource(
        descriptors,
        { it.productIdentity },
    )?.featureAllowances
}

private fun <T> PurchaseEvidence.selectAllowanceSource(
    candidates: Collection<T>,
    identity: (T) -> StoredProductIdentity,
): T? {
    val storeMatches = candidates.filter { identity(it).storeProductId in storeProductIds }
    return if (nuxieProductId == null) {
        storeMatches.singleOrNull()
    } else {
        storeMatches.filter { matchesProductIdentity(identity(it)) }.singleOrNull()
    }
}

/**
 * The early unlock: only the buyer's own purchase or sync entry still waiting
 * for the server projects. Play must name the account the SDK derives for this
 * customer, and the owed send must be theirs. A Restore claim waits for the
 * server, as Restore itself does; so does a purchase the server has answered.
 * Nothing else decides it, Play's queries and in-memory state included: a
 * refund or cancellation before the server answers keeps the early unlock until
 * that answer, seconds online or until the device is back online, the same as
 * the offline case. An answer ends it by being saved on the entry; if saving
 * fails, the entry stays owed and is sent again until an answer is saved.
 */
internal fun PurchaseEvidence.isBuyersOwnUnsyncedPurchase(
    distinctId: String,
    accountId: String = playAccountId(distinctId),
): Boolean {
    val send = pendingSend ?: return false
    return !synced &&
        send.distinctId == distinctId &&
        send.reason != PurchaseSyncReason.RESTORE &&
        obfuscatedAccountId == accountId &&
        purchaseState == StoredPurchaseState.PURCHASED &&
        hasEligiblePurchaseSignature
}

private fun PurchaseEvidence.isEligibleProjectionEvidence(
    distinctId: String,
    accountId: String,
    expectedAuthorityScope: String,
): Boolean =
    authorityScope == expectedAuthorityScope && isBuyersOwnUnsyncedPurchase(distinctId, accountId)

internal val PurchaseEvidence.hasEligiblePurchaseSignature: Boolean
    get() = !signatureVerificationRequired || signatureVerified

private fun OptimisticFeatureOverlay.widen(other: OptimisticFeatureOverlay): OptimisticFeatureOverlay {
    val joinedType = deterministicFeatureType(type, other.type)
    if (unlimited || other.unlimited) {
        return OptimisticFeatureOverlay(joinedType, unlimited = true, balanceIncrease = null)
    }
    if (joinedType == FeatureType.BOOLEAN) {
        return OptimisticFeatureOverlay(FeatureType.BOOLEAN, unlimited = false, balanceIncrease = null)
    }
    return OptimisticFeatureOverlay(
        type = joinedType,
        unlimited = false,
        balanceIncrease = (balanceIncrease ?: 0.0) + (other.balanceIncrease ?: 0.0),
    )
}

private fun deterministicFeatureType(left: FeatureType, right: FeatureType): FeatureType =
    if (left.projectionRank >= right.projectionRank) left else right

private val FeatureType.projectionRank: Int
    get() = when (this) {
        FeatureType.BOOLEAN -> 0
        FeatureType.METERED -> 1
        FeatureType.CREDIT_SYSTEM -> 2
    }
