package ai.nuxie.sdk

import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import ai.nuxie.sdk.billing.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.billing.PurchaseEvidence
import ai.nuxie.sdk.billing.StoredFeatureAllowance
import ai.nuxie.sdk.billing.StoredProductMapping
import ai.nuxie.sdk.billing.StoredPurchaseState
import ai.nuxie.sdk.billing.purchaseAuthorityScope
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import ai.nuxie.sdk.testsupport.canonicalJourneyProfileResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class NuxieMeteredUseTest {
    private class RecordingDeliveredStore : EventStore {
        val delivered = CopyOnWriteArrayList<StoredEvent>()

        override suspend fun insertPending(event: StoredEvent) = Unit
        override suspend fun insertDeliveredIfAbsent(event: StoredEvent): Boolean {
            delivered += event
            return true
        }
        override suspend fun markDelivered(ids: List<String>) = Unit
        override suspend fun hasEvent(name: String, distinctId: String, sinceMillis: Long?) = false
        override suspend fun countEvents(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ) = 0
        override suspend fun getFirstEventTime(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ): Long? = null
        override suspend fun getLastEventTime(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ): Long? = null
        override suspend fun querySessionEvents(sessionId: String) = emptyList<StoredEvent>()
        override suspend fun reassignEvents(from: String, to: String) = 0
        override suspend fun deleteOldestDeliveredEvents(keeping: Int) = 0
        override suspend fun recordStableDrop(eventId: String, recordedAtMillis: Long) = true
        override suspend fun pendingBatch(limit: Int) = emptyList<StoredEvent>()
        override suspend fun close() = Unit
    }

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    @Test
    fun useFeatureWarnsAndDoesNothingBeforeSetupWhileWaitingThrows() {
        Nuxie.useFeature("credits")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { Nuxie.useFeatureAndWait("credits") }
        }
    }

    @Test
    fun publicUseFeatureAndWaitUsesTheImmediateOrdinaryPathWhenNoPurchaseMatches() = runBlocking {
        val transport = usageTransport()
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = transport,
            registerLifecycle = false,
                requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
        )
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_metered").apply {
                environment = NuxieEnvironment.DEVELOPMENT
                logLevel = LogLevel.NONE
            },
        )

        val result = Nuxie.useFeatureAndWait(
            featureId = "exports",
            amount = 2.0,
            entityId = "workspace-1",
            metadata = mapOf("source" to "pdf"),
        )

        assertTrue(result.success)
        assertEquals(8.0, result.usage!!.remaining!!, 0.0)
        assertEquals(null, result.authoritativeAccess)
        val body = Json.parseToJsonElement(
            transport.requests.single { it.url.path == "/event" }.body.decodeToString(),
        ).jsonObject
        assertEquals("\$feature_used", body.getValue("event").jsonPrimitive.content)
        assertEquals(2.0, body.getValue("value").jsonPrimitive.double, 0.0)
        assertEquals("workspace-1", body.getValue("entityId").jsonPrimitive.content)
        val properties = body.getValue("properties").jsonObject
        assertEquals("exports", properties.getValue("feature_extId").jsonPrimitive.content)
        assertEquals("pdf", properties.getValue("metadata").jsonObject
            .getValue("source").jsonPrimitive.content)
    }

    @Test
    fun acceptedUseMetadataKeepsNativeScalarTypesInBeforeSendAndDeliveredHistory() = runBlocking {
        val store = RecordingDeliveredStore()
        val beforeSendFeatureUse = AtomicReference<NuxieEvent>()
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            store = store,
            transport = usageTransport(),
            registerLifecycle = false,
                requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
        )
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_metered_metadata").apply {
                environment = NuxieEnvironment.DEVELOPMENT
                logLevel = LogLevel.NONE
                beforeSend = { event ->
                    if (event.name == SystemEventNames.FEATURE_USED) beforeSendFeatureUse.set(event)
                    event
                }
            },
        )

        assertTrue(
            Nuxie.useFeatureAndWait(
                featureId = "exports",
                metadata = mapOf("sample_rate" to 2.5, "is_trial" to true),
            ).success,
        )

        val beforeSendMetadata = beforeSendFeatureUse.get().properties.getValue("metadata")
        assertTrue(beforeSendMetadata is Map<*, *>)
        beforeSendMetadata as Map<*, *>
        assertTrue(beforeSendMetadata["sample_rate"] is Double)
        assertEquals(2.5, beforeSendMetadata["sample_rate"])
        assertTrue(beforeSendMetadata["is_trial"] is Boolean)
        assertEquals(true, beforeSendMetadata["is_trial"])

        val deliveredMetadata = store.delivered.single().properties
            .getValue("metadata").jsonObject
        val deliveredSampleRate = deliveredMetadata.getValue("sample_rate").jsonPrimitive
        assertFalse(deliveredSampleRate.isString)
        assertEquals(2.5, deliveredSampleRate.double, 0.0)
        val deliveredIsTrial = deliveredMetadata.getValue("is_trial").jsonPrimitive
        assertFalse(deliveredIsTrial.isString)
        assertTrue(deliveredIsTrial.boolean)
    }

    @Test
    fun confirmedEntityScopedUseUpdatesGlobalPublicFeatureWithoutEntityCache() = runBlocking {
        val transport = usageTransport(remaining = 0.0)
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_entity_usage_projection",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = transport,
                registerLifecycle = false,
                requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
            ),
        )
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                """{"features":[{"id":"exports","type":"metered","balance":5,"unlimited":false}]}""",
            ).jsonObject,
        )

        val result = core.featureUsage.useFeatureAndWait(
            featureId = "exports",
            amount = 5.0,
            entityId = "workspace-without-cache",
            setUsage = false,
            metadata = null,
        )

        assertTrue(result.success)
        assertEquals(0.0, core.featureInfo.all.value.getValue("exports").balance!!, 0.0)
        assertFalse(core.featureInfo.all.value.getValue("exports").allowed)
        assertFalse(core.featureInfo.isAllowed("exports"))
        assertEquals(0.0, core.featureInfo.balance("exports")!!, 0.0)
        core.stop()
    }

    @Test
    fun fireAndForgetUseFeatureDoesNotSurfaceBackgroundMetadataFailure() {
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = usageTransport(),
            registerLifecycle = false,
                requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
        )
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_metered").apply {
                environment = NuxieEnvironment.DEVELOPMENT
                logLevel = LogLevel.NONE
            },
        )

        Nuxie.useFeature("exports", metadata = mapOf("unsupported" to Any()))
    }

    @Test
    fun waitingUsageNetworkCallNeverExecutesOnTheCallerThread() = runBlocking {
        val callerThread = AtomicReference<Thread>()
        val networkThread = AtomicReference<Thread>()
        val transport = usageTransport().apply {
            val baseRespond = respond
            respond = { request ->
                if (request.url.path == "/event") networkThread.set(Thread.currentThread())
                baseRespond(request)
            }
        }
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = transport,
            registerLifecycle = false,
                requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
        )
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_metered_thread").apply {
                environment = NuxieEnvironment.DEVELOPMENT
                logLevel = LogLevel.NONE
            },
        )

        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "use-feature-caller")
        }.asCoroutineDispatcher().use { callerDispatcher ->
            withContext(callerDispatcher) {
                callerThread.set(Thread.currentThread())
                assertTrue(Nuxie.useFeatureAndWait("exports").success)
            }
        }

        assertTrue(networkThread.get() !== callerThread.get())
    }

    @Test
    fun setUsageOnlyAppliesAcceptedServerBalanceWithoutConsumingProjectedEvidence() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val identity = IdentityService(application).also { it.setDistinctId("customer-a") }
        val store = InMemoryPurchaseEvidenceStore().also {
            it.upsertProductMapping(
                StoredProductMapping(
                    storeProductId = "play-credit-pack",
                    nuxieProductId = "credit-pack",
                    productType = "inapp",
                    consumable = true,
                    featureAllowances = listOf(
                        StoredFeatureAllowance(
                            "credits",
                            FeatureType.CREDIT_SYSTEM.name,
                            false,
                            allowance = 10.0,
                        ),
                    ),
                ),
            )
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
                    firstSeenMillis = 1_784_462_300_000L,
                    catalogResolved = true,
                    nuxieManaged = true,
                    signatureVerified = true,
                    authorityScope = purchaseAuthorityScope(
                        "pk_test_set_usage",
                        NuxieEnvironment.DEVELOPMENT,
                    ),
                ),
            )
        }
        var eventRequests = 0
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/event") {
                    eventRequests += 1
                    val body = if (eventRequests == 1) {
                        """{"status":"rejected","message":"denied","usage":{"current":3,"limit":10,"remaining":7}}"""
                    } else {
                        """{"status":"ok","message":"recorded","usage":{"current":2,"limit":10,"remaining":8}}"""
                    }
                    HttpTransport.Response(200, body.encodeToByteArray())
                } else {
                    canonicalJourneyProfileResponse()
                }
            }
        }
        val core = NuxieCore(
            context = application,
            apiKey = "pk_test_set_usage",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                identity = identity,
                transport = transport,
                purchaseEvidenceStore = store,
                registerLifecycle = false,
                requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
            ),
        )

        withTimeout(5_000) {
            core.featureInfo.all.first { it["credits"]?.balance == 10.0 }
        }
        val rejected = core.featureUsage.useFeatureAndWait(
            featureId = "credits",
            amount = 10.0,
            entityId = null,
            setUsage = true,
            metadata = null,
        )
        assertFalse(rejected.success)
        assertEquals(10.0, core.featureInfo.balance("credits")!!, 0.0)

        val result = core.featureUsage.useFeatureAndWait(
            featureId = "credits",
            amount = 10.0,
            entityId = null,
            setUsage = true,
            metadata = null,
        )

        assertTrue(result.success)
        assertEquals(18.0, core.featureInfo.balance("credits")!!, 0.0)
        assertEquals(2, transport.requests.count { it.url.path == "/event" })
        assertEquals(0, transport.requests.count { it.url.path == "/entitled" })
        val body = Json.parseToJsonElement(
            transport.requests.last { it.url.path == "/event" }.body.decodeToString(),
        ).jsonObject
        assertTrue(body.getValue("properties").jsonObject.getValue("setUsage").jsonPrimitive.boolean)
        assertFalse(store.load().getValue("token-1").synced)
        core.features.applyOptimisticPurchaseProjection("customer-a", null)
        assertEquals(8.0, core.featureInfo.balance("credits")!!, 0.0)
        core.stop()
    }

    private fun usageTransport(remaining: Double = 8.0) = FakeTransport().apply {
        respond = { request ->
            when (request.url.path) {
                "/event" -> HttpTransport.Response(
                    200,
                    """{"status":"ok","message":"recorded","usage":{"current":2,"limit":10,"remaining":$remaining}}"""
                        .encodeToByteArray(),
                )
                else -> canonicalJourneyProfileResponse()
            }
        }
    }
}
