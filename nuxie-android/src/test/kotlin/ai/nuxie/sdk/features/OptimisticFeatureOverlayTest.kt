package ai.nuxie.sdk.features

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.billing.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.billing.OptimisticFeatureOverlay
import ai.nuxie.sdk.billing.PurchaseEvidence
import ai.nuxie.sdk.billing.PurchaseEvidenceStore
import ai.nuxie.sdk.billing.StoredFeatureAllowance
import ai.nuxie.sdk.billing.StoredProductMapping
import ai.nuxie.sdk.billing.StoredPurchaseState
import ai.nuxie.sdk.billing.purchaseAuthorityScope
import ai.nuxie.sdk.billing.ProjectionFixtureAdapters
import ai.nuxie.sdk.billing.ProjectionFixtureAdapters.unlessNull
import ai.nuxie.sdk.billing.optimisticFeatureProjection
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OptimisticFeatureOverlayTest {
    @Test
    fun overlayWidensProfileUntilEvidenceReconcilesWithoutSnapshotFlicker() = runBlocking {
        val core = core()
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(customer, profile(exportsBalance = 2.0))
        assertEquals(FeatureInfo.State.Ready, core.featureInfo.state.value)

        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf(
                "pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null),
                "exports" to OptimisticFeatureOverlay(FeatureType.METERED, false, 3.0),
            ),
        )

        assertEquals(FeatureInfo.State.Reconciling, core.featureInfo.state.value)
        assertTrue(core.featureInfo.isAllowed("pro"))
        assertEquals(5.0, core.featureInfo.balance("exports")!!, 0.0)

        core.features.hydrateProfile(customer, profile(exportsBalance = 0.0))
        assertEquals(FeatureInfo.State.Reconciling, core.featureInfo.state.value)
        assertTrue(core.featureInfo.isAllowed("pro"))
        assertEquals(3.0, core.featureInfo.balance("exports")!!, 0.0)

        core.features.applyOptimisticPurchaseProjection(customer, null)
        assertEquals(FeatureInfo.State.Ready, core.featureInfo.state.value)
        assertFalse(core.featureInfo.isAllowed("pro"))
        assertEquals(0.0, core.featureInfo.balance("exports")!!, 0.0)
        core.stop()
    }

    @Test
    fun overlayDoesNotPromoteUnknownWithoutAnAdmittedProfile() = runBlocking {
        val core = core()
        val customer = core.identity.distinctId()

        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )

        assertEquals(FeatureInfo.State.Unknown, core.featureInfo.state.value)
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun finiteOverlayPreservesAuthoritativeUnlimitedAccess() = runBlocking {
        val core = core()
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                """{"segments":[],"features":[{"id":"exports","type":"metered","balance":null,"unlimited":true}]}""",
            ).jsonObject,
        )

        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf("exports" to OptimisticFeatureOverlay(FeatureType.METERED, false, 3.0)),
        )

        val visible = core.featureInfo.all.value.getValue("exports")
        assertTrue(visible.allowed)
        assertTrue(visible.unlimited)
        assertEquals(null, visible.balance)
        core.stop()
    }

    @Test
    fun awaitReadyWaitsForReconciliationToFinish() = runBlocking {
        val core = core()
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(customer, profile(exportsBalance = null))
        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )

        val ready = async { core.featureInfo.awaitReady() }
        yield()
        assertFalse(ready.isCompleted)

        core.features.applyOptimisticPurchaseProjection(customer, null)
        ready.await()
        assertEquals(FeatureInfo.State.Ready, core.featureInfo.state.value)
        core.stop()
    }

    @Test
    fun overlayIsFencedToItsEvidenceOwnerAcrossIdentityChanges() = runBlocking {
        val core = core()
        val owner = core.identity.distinctId()
        core.features.hydrateProfile(owner, profile(exportsBalance = null))
        val projection = mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null))
        core.features.applyOptimisticPurchaseProjection(owner, projection)

        core.identity.setDistinctId("customer-b")
        core.featureInfo.publish(core.features.handleUserChange(owner, "customer-b"))
        core.features.applyOptimisticPurchaseProjection(owner, projection)
        assertFalse(core.featureInfo.isAllowed("pro"))

        core.identity.setDistinctId(owner)
        core.featureInfo.publish(
            core.features.handleUserChange(
                "customer-b",
                owner,
                destinationProjection = projection,
            ),
        )
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun returningIdentitySynchronouslyRederivesProjectionFromRetainedEvidence() = runBlocking {
        val apiKey = "pk_test_identity_projection_${System.nanoTime()}"
        val owner = "customer-a"
        val application = RuntimeEnvironment.getApplication()
        val identity = IdentityService(application).also { it.setDistinctId(owner) }
        val store = InMemoryPurchaseEvidenceStore().also {
            it.upsert(
                PurchaseEvidence(
                    purchaseToken = "identity-return-token",
                    packageName = "com.example.app",
                    storeProductIds = listOf("play-pro"),
                    nuxieProductId = "pro",
                    purchaseState = StoredPurchaseState.PURCHASED,
                    syncAttributionDistinctId = owner,
                    ownerDistinctId = owner,
                    acknowledged = false,
                    firstSeenMillis = 1L,
                    catalogResolved = true,
                    signatureVerified = true,
                    authorityScope = purchaseAuthorityScope(apiKey, NuxieEnvironment.DEVELOPMENT),
                ),
            )
            it.upsertProductMapping(
                StoredProductMapping(
                    storeProductId = "play-pro",
                    nuxieProductId = "pro",
                    productType = "inapp",
                    consumable = false,
                    featureAllowances = listOf(
                        StoredFeatureAllowance("pro", FeatureType.BOOLEAN.name, false),
                    ),
                ),
            )
        }
        val core = NuxieCore(
            context = application,
            apiKey = apiKey,
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = FakeTransport(),
                identity = identity,
                purchaseEvidenceStore = store,
                registerLifecycle = false,
            ),
        )

        identity.setDistinctId("customer-b")
        core.featureInfo.publish(core.stageFeatureUserChange(owner, "customer-b"))
        assertFalse(core.featureInfo.isAllowed("pro"))

        identity.setDistinctId(owner)
        core.featureInfo.publish(core.stageFeatureUserChange("customer-b", owner))

        assertTrue(core.featureInfo.isAllowed("pro"))
        assertEquals(FeatureInfo.State.Unknown, core.featureInfo.state.value)
        core.stop()
    }

    @Test
    fun cachedDescriptorArrivalDerivesProjectionWithoutAnotherBillingUpdate() = runBlocking {
        val apiKey = "pk_test_descriptor_refresh_${System.nanoTime()}"
        val application = RuntimeEnvironment.getApplication()
        val identity = IdentityService(application).also { it.setDistinctId("customer-a") }
        val store = InMemoryPurchaseEvidenceStore().also {
            it.upsert(
                PurchaseEvidence(
                    purchaseToken = "token-1",
                    packageName = "com.example.app",
                    storeProductIds = listOf("play-credit-pack"),
                    nuxieProductId = "credit-pack",
                    purchaseState = StoredPurchaseState.PURCHASED,
                    syncAttributionDistinctId = "customer-a",
                    ownerDistinctId = "customer-a",
                    acknowledged = false,
                    firstSeenMillis = 1L,
                    catalogResolved = true,
                    signatureVerified = true,
                    authorityScope = purchaseAuthorityScope(apiKey, NuxieEnvironment.DEVELOPMENT),
                ),
            )
        }
        val core = NuxieCore(
            context = application,
            apiKey = apiKey,
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = FakeTransport(),
                identity = identity,
                purchaseEvidenceStore = store,
                registerLifecycle = false,
            ),
        )

        store.upsertProductMapping(
            StoredProductMapping(
                storeProductId = "play-credit-pack",
                nuxieProductId = "credit-pack",
                productType = "inapp",
                consumable = false,
                featureAllowances = listOf(
                    StoredFeatureAllowance("credits", FeatureType.METERED.name, false, 10.0),
                ),
            ),
        )

        withTimeout(5_000) {
            core.featureInfo.all.first { it["credits"]?.balance == 10.0 }
        }
        assertEquals(
            10.0,
            store.load().getValue("token-1").pinnedFeatureAllowances
                ?.single()?.allowance!!,
            0.0,
        )

        store.upsertProductMapping(
            StoredProductMapping(
                storeProductId = "play-credit-pack",
                nuxieProductId = "credit-pack",
                productType = "inapp",
                consumable = false,
                featureAllowances = listOf(
                    StoredFeatureAllowance("credits", FeatureType.METERED.name, false, 99.0),
                ),
            ),
        )
        core.featureInfo.publish(core.stageFeatureUserChange("customer-a", "customer-a"))

        assertEquals(10.0, core.featureInfo.balance("credits")!!, 0.0)
        core.stop()
    }

    @Test
    fun failedFirstArrivalPinRetriesTheSameAllowancesAfterDescriptorReplacement() = runBlocking {
        val apiKey = "pk_test_descriptor_pin_failure_${System.nanoTime()}"
        val owner = "customer-a"
        val application = RuntimeEnvironment.getApplication()
        val identity = IdentityService(application).also { it.setDistinctId(owner) }
        val store = FailingPinEvidenceStore()
        assertTrue(
            store.upsert(
                PurchaseEvidence(
                    purchaseToken = "token-1",
                    packageName = "com.example.app",
                    storeProductIds = listOf("play-credit-pack"),
                    nuxieProductId = "credit-pack",
                    purchaseState = StoredPurchaseState.PURCHASED,
                    syncAttributionDistinctId = owner,
                    ownerDistinctId = owner,
                    acknowledged = false,
                    firstSeenMillis = 1L,
                    catalogResolved = true,
                    signatureVerified = true,
                    authorityScope = purchaseAuthorityScope(apiKey, NuxieEnvironment.DEVELOPMENT),
                ),
            ),
        )
        val core = NuxieCore(
            context = application,
            apiKey = apiKey,
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = FakeTransport(),
                identity = identity,
                purchaseEvidenceStore = store,
                registerLifecycle = false,
            ),
        )
        try {
            store.failPinnedEvidenceUpserts = true
            assertTrue(
                store.upsertProductMapping(
                    StoredProductMapping(
                        storeProductId = "play-credit-pack",
                        nuxieProductId = "credit-pack",
                        productType = "inapp",
                        consumable = false,
                        featureAllowances = listOf(
                            StoredFeatureAllowance(
                                "credits",
                                FeatureType.METERED.name,
                                false,
                                10.0,
                            ),
                        ),
                    ),
                ),
            )

            val projection = core.purchases.withOptimisticProjectionSnapshot(owner) { it }

            assertNull(projection)
            assertNull(store.load().getValue("token-1").pinnedFeatureAllowances)
            assertFalse(core.featureInfo.isAllowed("credits"))

            assertTrue(
                store.upsertProductMapping(
                    StoredProductMapping(
                        storeProductId = "play-credit-pack",
                        nuxieProductId = "credit-pack",
                        productType = "inapp",
                        consumable = false,
                        featureAllowances = listOf(
                            StoredFeatureAllowance(
                                "credits",
                                FeatureType.METERED.name,
                                false,
                                99.0,
                            ),
                        ),
                    ),
                ),
            )
            assertNull(core.purchases.withOptimisticProjectionSnapshot(owner) { it })

            store.failPinnedEvidenceUpserts = false
            val retried = core.purchases.withOptimisticProjectionSnapshot(owner) { it }

            assertEquals(10.0, retried?.get("credits")?.balanceIncrease!!, 0.0)
            assertEquals(
                10.0,
                store.load().getValue("token-1").pinnedFeatureAllowances
                    ?.single()?.allowance!!,
                0.0,
            )
        } finally {
            core.stop()
        }
    }

    @Test
    fun descriptorArrivalAfterStopDoesNotStrandFeatureInfoPublication() = runBlocking {
        val apiKey = "pk_test_descriptor_after_stop_${System.nanoTime()}"
        val owner = "customer-a"
        val application = RuntimeEnvironment.getApplication()
        val identity = IdentityService(application).also { it.setDistinctId(owner) }
        val store = InMemoryPurchaseEvidenceStore()
        assertTrue(
            store.upsert(
                PurchaseEvidence(
                    purchaseToken = "token-after-stop",
                    packageName = "com.example.app",
                    storeProductIds = listOf("play-pro"),
                    nuxieProductId = "pro",
                    purchaseState = StoredPurchaseState.PURCHASED,
                    syncAttributionDistinctId = owner,
                    ownerDistinctId = owner,
                    acknowledged = false,
                    firstSeenMillis = 1L,
                    catalogResolved = true,
                    signatureVerified = true,
                    authorityScope = purchaseAuthorityScope(apiKey, NuxieEnvironment.DEVELOPMENT),
                ),
            ),
        )
        val core = NuxieCore(
            context = application,
            apiKey = apiKey,
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = FakeTransport(),
                identity = identity,
                purchaseEvidenceStore = store,
                registerLifecycle = false,
            ),
        )
        core.stop()

        store.upsertProductMapping(
            StoredProductMapping(
                storeProductId = "play-pro",
                nuxieProductId = "pro",
                productType = "inapp",
                consumable = false,
                featureAllowances = listOf(
                    StoredFeatureAllowance("pro", FeatureType.BOOLEAN.name, false),
                ),
            ),
        )

        withTimeout(2_000) {
            core.features.applyOptimisticPurchaseProjection(
                owner,
                mapOf(
                    "publication-probe" to OptimisticFeatureOverlay(
                        FeatureType.BOOLEAN,
                        unlimited = false,
                        balanceIncrease = null,
                    ),
                ),
            )
        }
        assertTrue(core.featureInfo.isAllowed("publication-probe"))
    }

    @Test
    fun visibleJoinAndReadinessMatchTheCrossSdkProjectionFixture() = runBlocking {
        val fixture = Json.parseToJsonElement(
            java.io.File(
                FixtureRunner.fixturesRoot(),
                "features/optimistic-entitlement-projection.json",
            ).readText(),
        ).jsonObject

        fixture.getValue("cases").jsonArray.forEach { element ->
            val case = element.jsonObject
            val core = core()
            try {
                var currentEvidence = ProjectionFixtureAdapters.evidence(case["evidence"])
                var currentDescriptors = ProjectionFixtureAdapters.descriptors(case["descriptors"])
                var currentDistinctId = case.getValue("distinctId").jsonPrimitive.content
                core.identity.setDistinctId(currentDistinctId)

                fun deriveOverlay() = optimisticFeatureProjection(
                    distinctId = currentDistinctId,
                    authorityScope = ProjectionFixtureAdapters.AUTHORITY_SCOPE,
                    evidence = currentEvidence,
                    descriptors = currentDescriptors,
                )

                suspend fun applyStage(stage: JsonObject) {
                    if (stage.boolean("profileAdmitted")) {
                        core.features.hydrateProfile(
                            currentDistinctId,
                            fixtureProfile(stage["authoritative"]),
                        )
                    }
                    core.features.applyOptimisticPurchaseProjection(
                        currentDistinctId,
                        deriveOverlay(),
                    )
                    assertFixtureVisible(core, stage)
                }

                applyStage(case)

                case["transitions"].unlessNull()?.jsonArray.orEmpty().forEach { transitionElement ->
                    val transition = transitionElement.jsonObject
                    transition["evidence"].unlessNull()?.let {
                        currentEvidence = ProjectionFixtureAdapters.evidence(it)
                    }
                    transition["descriptors"].unlessNull()?.let {
                        currentDescriptors = ProjectionFixtureAdapters.descriptors(it)
                    }
                    val nextDistinctId = transition.getValue("distinctId").jsonPrimitive.content
                    if (nextDistinctId != currentDistinctId) {
                        val previous = currentDistinctId
                        currentDistinctId = nextDistinctId
                        core.identity.setDistinctId(nextDistinctId)
                        core.featureInfo.publish(
                            core.features.handleUserChange(
                                previous,
                                nextDistinctId,
                                destinationProjection = deriveOverlay(),
                            ),
                        )
                    }
                    applyStage(transition)
                }
            } finally {
                core.stop()
            }
        }
    }

    /**
     * A profile expresses denial by omission, so only expressible rows are
     * hydrated; [assertFixtureVisible] asserts semantic outcomes rather than
     * row shapes for the same reason.
     */
    private fun fixtureProfile(authoritative: JsonElement?): JsonObject {
        val features = authoritative.unlessNull()?.jsonObject.orEmpty().mapNotNull { (id, value) ->
            val access = value.jsonObject
            val allowed = access.boolean("allowed")
            val balance = access["balance"].unlessNull()?.jsonPrimitive?.content?.toDouble()
            val unlimited = access.boolean("unlimited")
            if (!allowed && balance == null && !unlimited) return@mapNotNull null
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("type", JsonPrimitive(access.getValue("type").jsonPrimitive.content))
                put("balance", balance?.let(::JsonPrimitive) ?: JsonNull)
                put("unlimited", JsonPrimitive(unlimited))
            }
        }
        return buildJsonObject {
            put("segments", JsonArray(emptyList()))
            put("features", JsonArray(features))
        }
    }

    private fun assertFixtureVisible(core: NuxieCore, stage: JsonObject) {
        val label = stage.getValue("name").jsonPrimitive.content
        val expectedVisible = stage.getValue("expectedVisible").jsonObject
        if (expectedVisible.isEmpty()) {
            assertTrue(label, core.featureInfo.all.value.isEmpty())
        }
        expectedVisible.forEach { (featureId, value) ->
            val expected = value.jsonObject
            val allowed = expected.boolean("allowed")
            val balance = expected["balance"].unlessNull()?.jsonPrimitive?.content?.toDouble()
            val unlimited = expected.boolean("unlimited")
            val actual = core.featureInfo.all.value[featureId]
            if (actual == null) {
                // Denial-by-omission is this platform's representation of a
                // fully denied row.
                assertFalse(label, allowed)
                assertEquals(label, null, balance)
                assertFalse(label, unlimited)
                assertFalse(label, core.featureInfo.isAllowed(featureId))
                return@forEach
            }
            assertEquals("$label / $featureId allowed", allowed, actual.allowed)
            assertEquals("$label / $featureId unlimited", unlimited, actual.unlimited)
            assertEquals("$label / $featureId balance", balance, actual.balance)
            assertEquals(
                "$label / $featureId type",
                ProjectionFixtureAdapters.featureType(
                    expected.getValue("type").jsonPrimitive.content,
                ),
                actual.type,
            )
        }
        val expectedState = when (stage.getValue("expectedState").jsonPrimitive.content) {
            "unknown" -> FeatureInfo.State.Unknown
            "reconciling" -> FeatureInfo.State.Reconciling
            "ready" -> FeatureInfo.State.Ready
            else -> error("Unsupported fixture state")
        }
        assertEquals(label, expectedState, core.featureInfo.state.value)
    }

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    private fun core() = NuxieCore(
        context = RuntimeEnvironment.getApplication(),
        apiKey = "pk_test_optimistic_projection_${System.nanoTime()}",
        environment = NuxieEnvironment.DEVELOPMENT,
        logLevel = LogLevel.NONE,
        beforeSend = null,
        overrides = NuxieCore.Overrides(transport = FakeTransport(), registerLifecycle = false),
    )

    private fun profile(exportsBalance: Double?) = Json.parseToJsonElement(
        if (exportsBalance == null) {
            """{"segments":[],"features":[]}"""
        } else {
            """{"segments":[],"features":[{"id":"exports","type":"metered","balance":$exportsBalance,"unlimited":false}]}"""
        },
    ).jsonObject

    private class FailingPinEvidenceStore : PurchaseEvidenceStore {
        private val delegate = InMemoryPurchaseEvidenceStore()
        private val mappingInstallationLock = Any()

        @Volatile
        var failPinnedEvidenceUpserts = false

        @Volatile
        private var productMappingsChangedListener: (() -> Unit)? = null

        override fun load(): Map<String, PurchaseEvidence> = delegate.load()

        override fun upsert(evidence: PurchaseEvidence): Boolean =
            if (failPinnedEvidenceUpserts && evidence.pinnedFeatureAllowances != null) {
                false
            } else {
                delegate.upsert(evidence)
            }

        override fun loadProductMappings(): List<StoredProductMapping> =
            delegate.loadProductMappings()

        override fun upsertProductMapping(mapping: StoredProductMapping): Boolean =
            synchronized(mappingInstallationLock) {
                delegate.upsertProductMapping(mapping).also { persisted ->
                    if (persisted) productMappingsChangedListener?.invoke()
                }
            }

        override fun setProductMappingsChangedListener(listener: (() -> Unit)?) {
            productMappingsChangedListener = listener
        }
    }
}
