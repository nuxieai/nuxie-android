package ai.nuxie.sdk.commerce

import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.features.FeatureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimisticFeatureProjectionTest {
    @Test
    fun derivesBooleanAndMeteredOverlayFromEligibleEvidenceAndCachedAllowances() {
        val evidence = evidence(owner = "customer-a")
        val descriptor = descriptor(
            FeatureAllowance("pro", FeatureType.BOOLEAN),
            FeatureAllowance("exports", FeatureType.METERED, allowance = 3.0),
            FeatureAllowance("credits", FeatureType.CREDIT_SYSTEM, allowance = 2.5),
        )

        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(evidence),
            descriptors = listOf(descriptor),
        )

        assertEquals(
            OptimisticFeatureOverlay(FeatureType.BOOLEAN, unlimited = false, balanceIncrease = null),
            projection?.get("pro"),
        )
        assertEquals(
            OptimisticFeatureOverlay(FeatureType.METERED, unlimited = false, balanceIncrease = 3.0),
            projection?.get("exports"),
        )
        assertEquals(
            OptimisticFeatureOverlay(FeatureType.CREDIT_SYSTEM, unlimited = false, balanceIncrease = 2.5),
            projection?.get("credits"),
        )
    }

    @Test
    fun noEligibleEvidenceIsAbsentRatherThanEmpty() {
        assertNull(
            optimisticFeatureProjection(
                distinctId = "customer-a",
                authorityScope = AUTHORITY_SCOPE,
                evidence = emptyList(),
                descriptors = listOf(descriptor(FeatureAllowance("pro", FeatureType.BOOLEAN))),
            ),
        )
    }

    @Test
    fun eligibleEvidenceWithoutCachedDescriptorIsAbsentRatherThanEmpty() {
        assertNull(
            optimisticFeatureProjection(
                distinctId = "customer-a",
                authorityScope = AUTHORITY_SCOPE,
                evidence = listOf(evidence(owner = "customer-a")),
                descriptors = emptyList(),
            ),
        )
    }

    @Test
    fun purchasedButUnverifiedPlayEvidenceIsNotEligible() {
        assertNull(
            optimisticFeatureProjection(
                distinctId = "customer-a",
                authorityScope = AUTHORITY_SCOPE,
                evidence = listOf(evidence(owner = "customer-a").copy(signatureVerified = false)),
                descriptors = listOf(descriptor(FeatureAllowance("pro", FeatureType.BOOLEAN))),
            ),
        )
    }

    @Test
    fun overlaysAreIdentityScopedAndEndAtAcknowledgementOrRevocation() {
        val descriptor = descriptor(FeatureAllowance("pro", FeatureType.BOOLEAN))
        val candidates = listOf(
            evidence(owner = "customer-b").copy(purchaseToken = "foreign"),
            evidence(owner = "customer-a").copy(purchaseToken = "synced", backendSyncedAtMillis = 2L),
            evidence(owner = "customer-a").copy(purchaseToken = "revoked", revoked = true),
        )

        assertNull(
            optimisticFeatureProjection(
                distinctId = "customer-a",
                authorityScope = AUTHORITY_SCOPE,
                evidence = candidates,
                descriptors = listOf(descriptor),
            ),
        )
    }

    @Test
    fun multipleUnreconciledPurchasesJoinByWidening() {
        val descriptor = descriptor(
            FeatureAllowance("exports", FeatureType.METERED, allowance = 2.0),
        )

        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(
                evidence(owner = "customer-a").copy(purchaseToken = "token-1"),
                evidence(owner = "customer-a").copy(purchaseToken = "token-2"),
            ),
            descriptors = listOf(descriptor),
        )

        assertEquals(4.0, projection?.get("exports")?.balanceIncrease!!, 0.0)
    }

    private fun evidence(owner: String) = PurchaseEvidence(
        purchaseToken = "token-1",
        packageName = "com.example.app",
        storeProductIds = listOf("play-product"),
        purchaseState = StoredPurchaseState.PURCHASED,
        syncAttributionDistinctId = owner,
        ownerDistinctId = owner,
        acknowledged = false,
        firstSeenMillis = 1L,
        catalogResolved = true,
        signatureVerificationRequired = true,
        signatureVerified = true,
        authorityScope = AUTHORITY_SCOPE,
    )

    private fun descriptor(vararg allowances: FeatureAllowance) = StoredProductMapping(
        storeProductId = "play-product",
        nuxieProductId = "nuxie-product",
        productType = "inapp",
        consumable = false,
        featureAllowances = allowances.map {
            StoredFeatureAllowance(it.featureId, it.type.name, it.unlimited, it.allowance)
        },
    )

    private companion object {
        const val AUTHORITY_SCOPE = "scope-a"
    }
}
