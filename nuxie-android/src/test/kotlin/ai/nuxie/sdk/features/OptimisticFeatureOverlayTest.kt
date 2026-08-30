package ai.nuxie.sdk.features

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.commerce.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.commerce.OptimisticFeatureOverlay
import ai.nuxie.sdk.commerce.PurchaseEvidence
import ai.nuxie.sdk.commerce.StoredFeatureAllowance
import ai.nuxie.sdk.commerce.StoredProductMapping
import ai.nuxie.sdk.commerce.StoredPurchaseState
import ai.nuxie.sdk.commerce.purchaseAuthorityScope
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        core.features.handleUserChange(owner, "customer-b")
        core.features.applyOptimisticPurchaseProjection(owner, projection)
        assertFalse(core.featureInfo.isAllowed("pro"))

        core.identity.setDistinctId(owner)
        core.features.handleUserChange("customer-b", owner)
        core.features.applyOptimisticPurchaseProjection(owner, projection)
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun cachedDescriptorChangeRecomputesProjectionWithoutAnotherBillingUpdate() = runBlocking {
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
        core.stop()
    }

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
}
