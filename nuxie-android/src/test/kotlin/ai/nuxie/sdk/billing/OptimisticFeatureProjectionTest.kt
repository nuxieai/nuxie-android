package ai.nuxie.sdk.billing

import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.billing.ProjectionFixtureAdapters.unlessNull
import ai.nuxie.sdk.fixtures.FixtureRunner
import java.io.File
import kotlinx.serialization.json.Json
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
            // The declarations input is deliberately consumed and never fed
            // into derivation: external billing produces no overlay.
            check(
                case["externalPurchaseDeclarations"].unlessNull()?.jsonArray
                    ?.all { it.jsonObject.containsKey("productId") } != false,
            )
            var currentEvidence = ProjectionFixtureAdapters.evidence(case["evidence"])
            var currentDescriptors = ProjectionFixtureAdapters.descriptors(case["descriptors"])

            assertEquals(
                case.getValue("name").jsonPrimitive.content,
                ProjectionFixtureAdapters.expectedProjection(case["expectedOverlay"]),
                optimisticFeatureProjection(
                    distinctId = case.getValue("distinctId").jsonPrimitive.content,
                    authorityScope = AUTHORITY_SCOPE,
                    evidence = currentEvidence,
                    descriptors = currentDescriptors,
                ),
            )

            case["transitions"].unlessNull()?.jsonArray.orEmpty().forEach { transitionElement ->
                val transition = transitionElement.jsonObject
                transition["evidence"].unlessNull()?.let {
                    currentEvidence = ProjectionFixtureAdapters.evidence(it)
                }
                transition["descriptors"].unlessNull()?.let {
                    currentDescriptors = ProjectionFixtureAdapters.descriptors(it)
                }
                assertEquals(
                    transition.getValue("name").jsonPrimitive.content,
                    ProjectionFixtureAdapters.expectedProjection(transition["expectedOverlay"]),
                    optimisticFeatureProjection(
                        distinctId = transition.getValue("distinctId").jsonPrimitive.content,
                        authorityScope = AUTHORITY_SCOPE,
                        evidence = currentEvidence,
                        descriptors = currentDescriptors,
                    ),
                )
            }
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
    fun allowanceResolutionDistinguishesNoSourceFromAnExplicitlyEmptySource() {
        val evidence = evidence(owner = "customer-a")

        assertNull(
            resolvedFeatureAllowancesForEvidence(
                evidence = evidence,
                descriptors = emptyList(),
            ),
        )
        assertEquals(
            emptyList<StoredFeatureAllowance>(),
            resolvedFeatureAllowancesForEvidence(
                evidence = evidence,
                descriptors = listOf(descriptor()),
            ),
        )
    }

    @Test
    fun aSingleCatalogMappingResolvesEvidenceBeforeItsNuxieProductIdentityArrives() {
        val unresolvedEvidence = evidence(owner = "customer-a").copy(nuxieProductId = null)

        val resolved = resolvedFeatureAllowancesForEvidence(
            evidence = unresolvedEvidence,
            descriptors = listOf(
                descriptor(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            ),
        )

        assertEquals("pro", resolved?.single()?.featureId)
    }

    @Test
    fun ambiguousCatalogMappingsDoNotResolveEvidenceWithoutANuxieProductIdentity() {
        val unresolvedEvidence = evidence(owner = "customer-a").copy(nuxieProductId = null)

        val resolved = resolvedFeatureAllowancesForEvidence(
            evidence = unresolvedEvidence,
            descriptors = listOf(
                descriptor(FeatureAllowance("pro", FeatureType.BOOLEAN)),
                descriptor(FeatureAllowance("other", FeatureType.BOOLEAN)).copy(
                    nuxieProductId = "other-product",
                ),
            ),
        )

        assertNull(resolved)
    }

    @Test
    fun purchasedWithoutRequiredSignatureIsEligibleWhenUnverified() {
        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(
                evidence(owner = "customer-a").copy(
                    signatureVerificationRequired = false,
                    signatureVerified = false,
                ),
            ),
            descriptors = listOf(descriptor(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )

        assertEquals(
            OptimisticFeatureOverlay(FeatureType.BOOLEAN, unlimited = false, balanceIncrease = null),
            projection?.get("pro"),
        )
    }

    @Test
    fun purchasedWithRequiredButUnverifiedSignatureIsNotEligible() {
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

    @Test
    fun meteredAndCreditSystemJoinAsCreditSystemRegardlessOfAllowanceOrder() {
        val metered = FeatureAllowance("balance", FeatureType.METERED, allowance = 2.0)
        val creditSystem = FeatureAllowance("balance", FeatureType.CREDIT_SYSTEM, allowance = 3.0)
        val expected = OptimisticFeatureOverlay(
            type = FeatureType.CREDIT_SYSTEM,
            unlimited = false,
            balanceIncrease = 5.0,
        )

        listOf(
            arrayOf(metered, creditSystem),
            arrayOf(creditSystem, metered),
        ).forEach { allowances ->
            val projection = optimisticFeatureProjection(
                distinctId = "customer-a",
                authorityScope = AUTHORITY_SCOPE,
                evidence = listOf(evidence(owner = "customer-a")),
                descriptors = listOf(descriptor(*allowances)),
            )

            assertEquals(expected, projection?.get("balance"))
        }
    }

    @Test
    fun unlimitedJoinUsesHighestRankedTypeRegardlessOfAllowanceOrder() {
        val unlimitedMetered = FeatureAllowance(
            "balance",
            FeatureType.METERED,
            unlimited = true,
        )
        val creditSystem = FeatureAllowance("balance", FeatureType.CREDIT_SYSTEM, allowance = 3.0)
        val expected = OptimisticFeatureOverlay(
            type = FeatureType.CREDIT_SYSTEM,
            unlimited = true,
            balanceIncrease = null,
        )

        listOf(
            arrayOf(unlimitedMetered, creditSystem),
            arrayOf(creditSystem, unlimitedMetered),
        ).forEach { allowances ->
            val projection = optimisticFeatureProjection(
                distinctId = "customer-a",
                authorityScope = AUTHORITY_SCOPE,
                evidence = listOf(evidence(owner = "customer-a")),
                descriptors = listOf(descriptor(*allowances)),
            )

            assertEquals(expected, projection?.get("balance"))
        }
    }

    @Test
    fun booleanAndMeteredJoinRetainsMeteredAllowanceRegardlessOfAllowanceOrder() {
        val boolean = FeatureAllowance("balance", FeatureType.BOOLEAN)
        val metered = FeatureAllowance("balance", FeatureType.METERED, allowance = 4.0)
        val expected = OptimisticFeatureOverlay(
            type = FeatureType.METERED,
            unlimited = false,
            balanceIncrease = 4.0,
        )

        listOf(
            arrayOf(boolean, metered),
            arrayOf(metered, boolean),
        ).forEach { allowances ->
            val projection = optimisticFeatureProjection(
                distinctId = "customer-a",
                authorityScope = AUTHORITY_SCOPE,
                evidence = listOf(evidence(owner = "customer-a")),
                descriptors = listOf(descriptor(*allowances)),
            )

            assertEquals(expected, projection?.get("balance"))
        }
    }

    @Test
    fun blankFeatureIdsAreExcludedFromProjection() {
        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(evidence(owner = "customer-a")),
            descriptors = listOf(
                descriptor(
                    FeatureAllowance(" ", FeatureType.BOOLEAN),
                    FeatureAllowance("pro", FeatureType.BOOLEAN),
                ),
            ),
        )

        assertEquals(setOf("pro"), projection?.keys)
    }

    @Test
    fun blankFeatureIdsAloneDoNotCreateAProjection() {
        assertNull(
            optimisticFeatureProjection(
                distinctId = "customer-a",
                authorityScope = AUTHORITY_SCOPE,
                evidence = listOf(evidence(owner = "customer-a")),
                descriptors = listOf(descriptor(FeatureAllowance("", FeatureType.BOOLEAN))),
            ),
        )
    }

    @Test
    fun descriptorAllowanceMatchesTheFullProductIdentity() {
        val unrelated = descriptor(FeatureAllowance("wrong-feature", FeatureType.BOOLEAN)).copy(
            nuxieProductId = "other-nuxie-product",
            basePlanId = "monthly",
            offerId = "standard",
        )
        val purchased = descriptor(FeatureAllowance("right-feature", FeatureType.BOOLEAN)).copy(
            basePlanId = "annual",
            offerId = "launch",
        )
        val purchaseEvidence = evidence(owner = "customer-a").copy(
            basePlanId = "annual",
            offerId = "launch",
        )

        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(purchaseEvidence),
            descriptors = listOf(unrelated, purchased),
        )

        assertEquals(setOf("right-feature"), projection?.keys)
    }

    @Test
    fun bindingAllowanceMatchesAccountAndFullProductIdentity() {
        val purchaseEvidence = evidence(owner = "customer-a").copy(
            obfuscatedAccountId = "account-a",
            basePlanId = "annual",
            offerId = "launch",
        )
        val unrelated = StoredPurchaseBinding(
            obfuscatedAccountId = "account-a",
            distinctId = "customer-a",
            storeProductId = "play-product",
            nuxieProductId = "other-nuxie-product",
            basePlanId = "monthly",
            offerId = "standard",
            productType = "subs",
            consumable = false,
            featureAllowances = listOf(StoredFeatureAllowance("wrong-feature", "BOOLEAN", false)),
            nuxieManaged = true,
        )
        val purchased = unrelated.copy(
            nuxieProductId = "nuxie-product",
            basePlanId = "annual",
            offerId = "launch",
            featureAllowances = listOf(StoredFeatureAllowance("right-feature", "BOOLEAN", false)),
        )

        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(purchaseEvidence),
            descriptors = emptyList(),
            bindings = listOf(unrelated, purchased),
        )

        assertEquals(setOf("right-feature"), projection?.keys)
    }

    @Test
    fun purchaseTimeBindingAllowanceWinsOverALaterDescriptorUpdate() {
        val purchaseEvidence = evidence(owner = "customer-a").copy(
            obfuscatedAccountId = "account-a",
        )
        val purchaseTimeBinding = StoredPurchaseBinding(
            obfuscatedAccountId = "account-a",
            distinctId = "customer-a",
            storeProductId = "play-product",
            nuxieProductId = "nuxie-product",
            productType = "inapp",
            consumable = false,
            featureAllowances = listOf(
                StoredFeatureAllowance("credits", FeatureType.METERED.name, false, 2.0),
            ),
            nuxieManaged = true,
        )
        val updatedDescriptor = descriptor(
            FeatureAllowance("credits", FeatureType.METERED, allowance = 99.0),
        )

        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(purchaseEvidence),
            descriptors = listOf(updatedDescriptor),
            bindings = listOf(purchaseTimeBinding),
        )

        assertEquals(2.0, projection?.get("credits")?.balanceIncrease!!, 0.0)
    }

    @Test
    fun tokenPinnedAllowanceWinsOverLaterBindingAndDescriptorValues() {
        val purchaseEvidence = evidence(owner = "customer-a").copy(
            obfuscatedAccountId = "account-a",
            pinnedFeatureAllowances = listOf(
                StoredFeatureAllowance("credits", FeatureType.METERED.name, false, 1.0),
            ),
        )
        val laterBinding = StoredPurchaseBinding(
            obfuscatedAccountId = "account-a",
            distinctId = "customer-a",
            storeProductId = "play-product",
            nuxieProductId = "nuxie-product",
            productType = "inapp",
            consumable = false,
            featureAllowances = listOf(
                StoredFeatureAllowance("credits", FeatureType.METERED.name, false, 2.0),
            ),
            nuxieManaged = true,
        )

        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(purchaseEvidence),
            descriptors = listOf(
                descriptor(FeatureAllowance("credits", FeatureType.METERED, allowance = 99.0)),
            ),
            bindings = listOf(laterBinding),
        )

        assertEquals(1.0, projection?.get("credits")?.balanceIncrease!!, 0.0)
    }

    @Test
    fun emptyTokenPinDoesNotGainLaterBindingOrDescriptorAllowances() {
        val purchaseEvidence = evidence(owner = "customer-a").copy(
            obfuscatedAccountId = "account-a",
            pinnedFeatureAllowances = emptyList(),
        )
        val laterBinding = StoredPurchaseBinding(
            obfuscatedAccountId = "account-a",
            distinctId = "customer-a",
            storeProductId = "play-product",
            nuxieProductId = "nuxie-product",
            productType = "inapp",
            consumable = false,
            featureAllowances = listOf(
                StoredFeatureAllowance("pro", FeatureType.BOOLEAN.name, false),
            ),
            nuxieManaged = true,
        )

        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(purchaseEvidence),
            descriptors = listOf(descriptor(FeatureAllowance("pro", FeatureType.BOOLEAN))),
            bindings = listOf(laterBinding),
        )

        assertNull(projection)
    }

    @Test
    fun emptyPurchaseTimeBindingDoesNotGainALaterDescriptorAllowance() {
        val purchaseEvidence = evidence(owner = "customer-a").copy(
            obfuscatedAccountId = "account-a",
        )
        val purchaseTimeBinding = StoredPurchaseBinding(
            obfuscatedAccountId = "account-a",
            distinctId = "customer-a",
            storeProductId = "play-product",
            nuxieProductId = "nuxie-product",
            productType = "inapp",
            consumable = false,
            featureAllowances = emptyList(),
            nuxieManaged = true,
        )

        val projection = optimisticFeatureProjection(
            distinctId = "customer-a",
            authorityScope = AUTHORITY_SCOPE,
            evidence = listOf(purchaseEvidence),
            descriptors = listOf(
                descriptor(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            ),
            bindings = listOf(purchaseTimeBinding),
        )

        assertNull(projection)
    }

    private fun evidence(owner: String) = PurchaseEvidence(
        purchaseToken = "token-1",
        packageName = "com.example.app",
        storeProductIds = listOf("play-product"),
        nuxieProductId = "nuxie-product",
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
        const val AUTHORITY_SCOPE = ProjectionFixtureAdapters.AUTHORITY_SCOPE
    }
}
