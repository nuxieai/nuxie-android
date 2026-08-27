package ai.nuxie.sdk

import ai.nuxie.sdk.commerce.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.commerce.PurchaseEvidence
import ai.nuxie.sdk.commerce.StoredLocalPurchaseGrant
import ai.nuxie.sdk.commerce.StoredPurchaseState
import ai.nuxie.sdk.commerce.purchaseAuthorityScope
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
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
    fun fireAndForgetUseFeatureDoesNotSurfaceBackgroundMetadataFailure() {
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = usageTransport(),
            registerLifecycle = false,
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
    fun setUsageAlwaysUsesTheOrdinaryPathEvenWithEligiblePurchaseEvidence() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val identity = IdentityService(application).also { it.setDistinctId("customer-a") }
        val store = InMemoryPurchaseEvidenceStore().also {
            it.upsert(
                PurchaseEvidence(
                    purchaseToken = "token-1",
                    packageName = "com.example.app",
                    storeProductIds = listOf("play-credit-pack"),
                    purchaseState = StoredPurchaseState.PURCHASED,
                    syncAttributionDistinctId = "customer-a",
                    ownerDistinctId = "customer-a",
                    acknowledged = false,
                    firstSeenMillis = 1_784_462_300_000L,
                    localFeatureGrants = listOf(
                        StoredLocalPurchaseGrant("credits", FeatureType.CREDIT_SYSTEM.name, false),
                    ),
                    catalogResolved = true,
                    nuxieManaged = true,
                    authorityScope = purchaseAuthorityScope(
                        "pk_test_set_usage",
                        NuxieEnvironment.DEVELOPMENT,
                    ),
                ),
            )
        }
        val transport = usageTransport()
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
            ),
        )

        val result = core.featureUsage.useFeatureAndWait(
            featureId = "credits",
            amount = 10.0,
            entityId = null,
            setUsage = true,
            metadata = null,
        )

        assertTrue(result.success)
        assertEquals(1, transport.requests.count { it.url.path == "/event" })
        assertEquals(0, transport.requests.count { it.url.path == "/entitled" })
        val body = Json.parseToJsonElement(
            transport.requests.single { it.url.path == "/event" }.body.decodeToString(),
        ).jsonObject
        assertTrue(body.getValue("properties").jsonObject.getValue("setUsage").jsonPrimitive.boolean)
        assertFalse(store.load().getValue("token-1").synced)
        core.stop()
    }

    private fun usageTransport() = FakeTransport().apply {
        respond = { request ->
            when (request.url.path) {
                "/event" -> HttpTransport.Response(
                    200,
                    """{"status":"ok","message":"recorded","usage":{"current":2,"limit":10,"remaining":8}}"""
                        .encodeToByteArray(),
                )
                else -> HttpTransport.Response(200, """{"segments":[]}""".encodeToByteArray())
            }
        }
    }
}
