package ai.nuxie.sdk.commerce

import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.fixtures.FixtureRunner
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptimisticFeatureProjectionTest {
    @Test
    fun matchesCrossSdkOptimisticEntitlementProjectionFixture() {
        val fixture = Json.parseToJsonElement(
            File(
                FixtureRunner.fixturesRoot(),
                "features/optimistic-entitlement-projection.json",
            ).readText(),
        ).jsonObject

        fixture.getValue("cases").jsonArray.forEach { element ->
            val case = element.jsonObject
            val actual = optimisticFeatureProjection(
                distinctId = case.getValue("distinctId").jsonPrimitive.content,
                authorityScope = AUTHORITY_SCOPE,
                evidence = fixtureEvidence(case["evidence"]),
                descriptors = fixtureDescriptors(case["descriptors"]),
            )

            assertEquals(
                case.getValue("name").jsonPrimitive.content,
                fixtureExpectedProjection(case["expected"]),
                actual,
            )
        }
    }

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

    private fun fixtureEvidence(element: JsonElement?): List<PurchaseEvidence> =
        element.unlessNull()?.jsonArray.orEmpty().map { evidenceElement ->
            val evidence = evidenceElement.jsonObject
            val backendSynced = evidence.getValue("backendSynced").jsonPrimitive.content.toBoolean()
            val transactionId = evidence.getValue("transactionId").jsonPrimitive.content
            val owner = evidence.getValue("distinctId").jsonPrimitive.content
            PurchaseEvidence(
                purchaseToken = transactionId,
                packageName = "com.example.fixture",
                storeProductIds = listOf(transactionId),
                purchaseState = StoredPurchaseState.PURCHASED,
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
                signatureVerificationRequired = true,
                signatureVerified = true,
                authorityScope = AUTHORITY_SCOPE,
                revoked = evidence.getValue("revoked").jsonPrimitive.content.toBoolean(),
                backendSyncedAtMillis = 1L.takeIf { backendSynced },
            )
        }

    private fun fixtureDescriptors(element: JsonElement?): List<StoredProductMapping> =
        element.unlessNull()?.jsonObject.orEmpty().map { (transactionId, allowancesElement) ->
            StoredProductMapping(
                storeProductId = transactionId,
                nuxieProductId = transactionId,
                productType = "inapp",
                consumable = false,
                featureAllowances = allowancesElement.jsonArray.map { allowanceElement ->
                    val allowance = allowanceElement.jsonObject
                    StoredFeatureAllowance(
                        featureId = allowance.getValue("featureId").jsonPrimitive.content,
                        type = fixtureFeatureType(allowance.getValue("kind").jsonPrimitive.content).name,
                        unlimited = allowance["unlimited"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                        allowance = allowance["allowance"]?.jsonPrimitive?.content?.toDouble(),
                    )
                },
            )
        }

    private fun fixtureExpectedProjection(
        element: JsonElement?,
    ): Map<String, OptimisticFeatureOverlay>? = element.unlessNull()?.jsonObject?.mapValues { (_, value) ->
        val expected = value.jsonObject
        val type = fixtureFeatureType(expected.getValue("kind").jsonPrimitive.content)
        OptimisticFeatureOverlay(
            type = type,
            unlimited = expected["unlimited"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            balanceIncrease = expected["allowance"]?.jsonPrimitive?.content?.toDouble()
                ?.takeUnless { type == FeatureType.BOOLEAN },
        )
    }

    private fun fixtureFeatureType(kind: String): FeatureType = when (kind) {
        "boolean" -> FeatureType.BOOLEAN
        "metered" -> FeatureType.METERED
        "credit_system", "creditSystem" -> FeatureType.CREDIT_SYSTEM
        else -> error("Unsupported fixture Feature kind: $kind")
    }

    private fun JsonElement?.unlessNull(): JsonElement? = this?.takeUnless { it is JsonNull }

    private companion object {
        const val AUTHORITY_SCOPE = "scope-a"
    }
}
