package ai.nuxie.sdk.commerce

import ai.nuxie.sdk.features.FeatureType

/** A widening-only delta derived from retained Play evidence and a signed descriptor. */
internal data class OptimisticFeatureOverlay(
    val type: FeatureType,
    val unlimited: Boolean,
    val balanceIncrease: Double?,
)

/**
 * Pure optimistic projection. A missing evidence/descriptor pair is absence,
 * never an authoritative empty Feature snapshot.
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
        val allowances = featureAllowancesForEvidence(purchase, descriptors, bindings)
        if (allowances.isEmpty()) return null
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
): List<StoredFeatureAllowance> {
    descriptors.firstOrNull { descriptor ->
        evidence.matchesProductIdentity(descriptor.productIdentity)
    }
        ?.let { return it.featureAllowances }
    return bindings.firstOrNull { binding ->
        binding.obfuscatedAccountId == evidence.obfuscatedAccountId &&
            evidence.matchesProductIdentity(binding.productIdentity)
    }?.featureAllowances.orEmpty()
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
    if (unlimited || other.unlimited) return copy(unlimited = true, balanceIncrease = null)
    if (type == FeatureType.BOOLEAN || other.type == FeatureType.BOOLEAN) {
        return OptimisticFeatureOverlay(FeatureType.BOOLEAN, unlimited = false, balanceIncrease = null)
    }
    return copy(balanceIncrease = (balanceIncrease ?: 0.0) + (other.balanceIncrease ?: 0.0))
}
