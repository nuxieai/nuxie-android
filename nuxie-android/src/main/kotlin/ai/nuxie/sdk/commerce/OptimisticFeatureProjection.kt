package ai.nuxie.sdk.commerce

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
    for (purchase in evidence.filter { it.isEligibleProjectionEvidence(distinctId, authorityScope) }) {
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
    bindings.firstOrNull { binding ->
        binding.obfuscatedAccountId == evidence.obfuscatedAccountId &&
            evidence.matchesProductIdentity(binding.productIdentity)
    }?.let { return it.featureAllowances }
    return descriptors.firstOrNull { descriptor ->
        evidence.matchesProductIdentity(descriptor.productIdentity)
    }?.featureAllowances
}

private fun PurchaseEvidence.isEligibleProjectionEvidence(
    distinctId: String,
    expectedAuthorityScope: String,
): Boolean =
    authorityScope == expectedAuthorityScope &&
        ownerDistinctId == distinctId &&
        purchaseState == StoredPurchaseState.PURCHASED &&
        !revoked &&
        !permanentlyRejected &&
        !synced &&
        backendSyncedAtMillis == null &&
        signatureVerified

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
