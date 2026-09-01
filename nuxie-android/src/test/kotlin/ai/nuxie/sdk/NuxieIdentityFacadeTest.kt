package ai.nuxie.sdk

import ai.nuxie.sdk.billing.OptimisticFeatureOverlay
import ai.nuxie.sdk.billing.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.billing.PurchaseEvidence
import ai.nuxie.sdk.billing.StoredFeatureAllowance
import ai.nuxie.sdk.billing.StoredProductMapping
import ai.nuxie.sdk.billing.StoredPurchaseState
import ai.nuxie.sdk.billing.purchaseAuthorityScope
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieIdentityFacadeTest {
    @Before
    fun setUp() {
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(),
            requestInitialProfileRefresh = false,
        )
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_identity"),
        )
    }

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    private fun pendingNames(): List<String> = runBlocking {
        val core = requireNotNull(Nuxie.core)
        core.eventLog.awaitBarrier()
        core.userTransitions.drain()
        core.store.pendingBatch(limit = 50).map { it.name }
    }

    @Test
    fun sameIdIdentifyWithoutPropertiesIsAFullNoOp() = runBlocking {
        Nuxie.identify("user-1")
        val core = requireNotNull(Nuxie.core)
        core.eventLog.awaitBarrier()
        val sessionAfterFirst = core.sessions.getSessionId(readOnly = true)
        val identifyCount = pendingNames().count { it == "\$identify" }

        Nuxie.identify("user-1")
        core.eventLog.awaitBarrier()

        assertEquals(identifyCount, pendingNames().count { it == "\$identify" })
        assertEquals(sessionAfterFirst, core.sessions.getSessionId(readOnly = true))
    }

    @Test
    fun anonymousToIdentifiedMigratesEventsAndCarriesAnonDistinctId() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        val anonId = Nuxie.anonymousId

        // Launch lifecycle events were captured under the anonymous id.
        core.eventLog.awaitBarrier()

        Nuxie.identify("user-1")
        core.eventLog.awaitBarrier()
        core.userTransitions.drain()

        assertTrue(Nuxie.isIdentified)
        assertEquals("user-1", Nuxie.distinctId)

        val events = core.store.pendingBatch(limit = 50)
        // Migration: prior anonymous events now belong to user-1.
        assertTrue(events.all { it.distinctId == "user-1" })

        val identify = events.single { it.name == "\$identify" }
        assertEquals(
            JsonPrimitive(anonId),
            identify.properties["\$anon_distinct_id"],
        )
        assertEquals(JsonPrimitive("user-1"), identify.properties["distinct_id"])
    }

    @Test
    fun identifiedToIdentifiedDoesNotMigrateHistory() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        Nuxie.identify("user-1")
        core.eventLog.awaitBarrier()
        core.userTransitions.drain()

        Nuxie.identify("user-2")
        core.eventLog.awaitBarrier()
        core.userTransitions.drain()

        val events = core.store.pendingBatch(limit = 50)
        // user-1's history stays with user-1; only user-2's $identify is new.
        assertTrue(events.any { it.distinctId == "user-1" })
        val secondIdentify = events.filter { it.name == "\$identify" }
            .single { it.distinctId == "user-2" }
        assertNull(secondIdentify.properties["\$anon_distinct_id"])
    }

    @Test
    fun identifyRotatesTheSessionOnlyOnUserChange() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        val before = core.sessions.getSessionId()
        Nuxie.identify("user-1")
        val afterIdentify = core.sessions.getSessionId(readOnly = true)
        assertNotEquals(before, afterIdentify)
    }

    @Test
    fun resetReturnsToAFreshAnonymousIdentity() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        Nuxie.identify("user-1")
        core.userTransitions.drain()
        val anonBefore = Nuxie.anonymousId

        Nuxie.reset()
        core.userTransitions.drain()

        assertFalse(Nuxie.isIdentified)
        assertNotEquals("user-1", Nuxie.distinctId)
        assertNotEquals(anonBefore, Nuxie.anonymousId)
    }

    @Test
    fun identifyAndResetHideThePreviousCustomersOverlayBeforeReturning() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        val anonymous = Nuxie.distinctId
        core.features.applyOptimisticPurchaseProjection(
            anonymous,
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )
        assertTrue(Nuxie.features.isAllowed("pro"))

        Nuxie.identify("user-1")

        assertFalse(Nuxie.features.isAllowed("pro"))
        core.userTransitions.drain()
        core.features.applyOptimisticPurchaseProjection(
            "user-1",
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )
        assertTrue(Nuxie.features.isAllowed("pro"))

        Nuxie.reset()

        assertFalse(Nuxie.features.isAllowed("pro"))
    }

    @Test
    fun identifyFromAFeatureListenerInvalidatesThePublishingMutationWithoutDeadlock() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        val anonymous = Nuxie.distinctId
        core.featureInfo.onFeatureChange = { featureId, _, _, _ ->
            if (featureId == "pro") Nuxie.identify("listener-user")
        }

        core.features.applyOptimisticPurchaseProjection(
            anonymous,
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )

        assertEquals("listener-user", Nuxie.distinctId)
        assertFalse(Nuxie.features.isAllowed("pro"))
    }

    @Test
    fun inlineFeatureCollectorCanSupersedeIdentifyWithoutDroppingEitherTransition() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        val initialDistinctId = Nuxie.distinctId
        val outerDistinctId = "outer-${System.nanoTime()}"
        val nestedDistinctId = "nested-${System.nanoTime()}"
        val outerProperty = "outer-property-${System.nanoTime()}"
        val migrationEventName = "identity-migration-${System.nanoTime()}"
        val transitions = mutableListOf<Pair<String, String>>()
        core.userTransitions.addObserver { _, from, to -> transitions += from to to }
        core.eventLog.capture(migrationEventName)
        core.eventLog.awaitBarrier()
        core.features.applyOptimisticPurchaseProjection(
            initialDistinctId,
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )
        assertTrue(Nuxie.features.isAllowed("pro"))

        var maySupersede = true
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            Nuxie.features.all.collect { features ->
                if (maySupersede && features.isEmpty()) {
                    maySupersede = false
                    Nuxie.identify(nestedDistinctId)
                }
            }
        }

        Nuxie.identify(
            outerDistinctId,
            userProperties = mapOf(outerProperty to "must-not-leak"),
        )
        collector.cancelAndJoin()
        core.userTransitions.drain()
        core.eventLog.awaitBarrier()

        assertEquals(nestedDistinctId, Nuxie.distinctId)
        assertNull(core.identity.userProperty(outerProperty))
        assertEquals(
            listOf(
                initialDistinctId to outerDistinctId,
                outerDistinctId to nestedDistinctId,
            ),
            transitions,
        )
        val identifyCustomers = core.store.pendingBatch(limit = 50)
            .filter { it.name == "\$identify" }
            .map { it.distinctId }
        assertEquals(listOf(outerDistinctId, nestedDistinctId), identifyCustomers)
        assertEquals(
            outerDistinctId,
            core.store.pendingBatch(limit = 50).single { it.name == migrationEventName }.distinctId,
        )
    }

    @Test
    fun returningToEvidenceOwnerPublishesItsOverlayBeforeIdentifyReturns() = runBlocking {
        Nuxie.resetForTesting()
        val application = RuntimeEnvironment.getApplication()
        val apiKey = "pk_test_identity_projection_${System.nanoTime()}"
        val owner = "owner-${System.nanoTime()}"
        val identity = IdentityService(application).also { it.setDistinctId(owner) }
        val evidenceStore = InMemoryPurchaseEvidenceStore().also { store ->
            store.upsertProductMapping(
                StoredProductMapping(
                    storeProductId = "play-pro",
                    nuxieProductId = "pro-product",
                    productType = "inapp",
                    consumable = false,
                    featureAllowances = listOf(
                        StoredFeatureAllowance(
                            featureId = "pro",
                            type = FeatureType.BOOLEAN.name,
                            unlimited = false,
                        ),
                    ),
                ),
            )
            store.upsert(
                PurchaseEvidence(
                    purchaseToken = "token-owner",
                    packageName = "com.example.app",
                    storeProductIds = listOf("play-pro"),
                    nuxieProductId = "pro-product",
                    purchaseState = StoredPurchaseState.PURCHASED,
                    syncAttributionDistinctId = owner,
                    ownerDistinctId = owner,
                    acknowledged = false,
                    firstSeenMillis = 1L,
                    catalogResolved = true,
                    signatureVerified = true,
                    authorityScope = purchaseAuthorityScope(
                        apiKey,
                        NuxieEnvironment.PRODUCTION,
                    ),
                ),
            )
        }
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            identity = identity,
            transport = FakeTransport(),
            purchaseEvidenceStore = evidenceStore,
            registerLifecycle = false,
            requestInitialProfileRefresh = false,
        )
        Nuxie.setup(
            application,
            NuxieConfiguration(apiKey).apply { logLevel = LogLevel.NONE },
        )
        val core = requireNotNull(Nuxie.core)
        core.featureInfo.publish(core.stageFeatureUserChange(owner, owner))
        assertTrue(Nuxie.features.isAllowed("pro"))

        Nuxie.identify("other-${System.nanoTime()}")
        assertFalse(Nuxie.features.isAllowed("pro"))

        Nuxie.identify(owner)

        assertTrue(Nuxie.features.isAllowed("pro"))
        assertEquals(FeatureInfo.State.Unknown, Nuxie.features.state.value)
    }

    @Test
    fun capturedEventsCarryASessionId() = runBlocking {
        val core = requireNotNull(Nuxie.core)
        core.eventLog.capture("session_stamped")
        core.eventLog.awaitBarrier()

        val event = core.store.pendingBatch(limit = 50).single { it.name == "session_stamped" }
        assertTrue(event.sessionId != null)
        assertEquals(
            JsonPrimitive(event.sessionId),
            event.properties["\$session_id"],
        )
    }
}
