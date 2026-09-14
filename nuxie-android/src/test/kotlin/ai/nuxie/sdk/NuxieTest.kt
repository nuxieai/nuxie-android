package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.billing.BillingClientAdapter
import ai.nuxie.sdk.billing.BillingClientAdapterFactory
import ai.nuxie.sdk.billing.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.events.SystemEventNames
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.boolean
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieTest {
    @get:Rule val temporary = TemporaryFolder()

    @org.junit.Before
    fun installFakeTransport() {
        Nuxie.overridesForTesting = NuxieCore.Overrides(transport = FakeTransport())
    }

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    @Test
    fun activeConfigurationOwnsLogLevelIncludingIgnoredSetup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(), registerLifecycle = false, requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
        )
        val disabled = NuxieConfiguration("pk_test_logging").apply { logLevel = LogLevel.NONE }
        Nuxie.setup(context, disabled)
        org.robolectric.shadows.ShadowLog.clear()
        disabled.logLevel = LogLevel.DEBUG
        Nuxie.setup(context, NuxieConfiguration("pk_test_ignored").apply { logLevel = LogLevel.DEBUG })
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("Nuxie").isEmpty())
        Nuxie.shutdownAndAwait()
        Nuxie.setup(context, NuxieConfiguration("pk_test_logging").apply { logLevel = LogLevel.WARN })
        org.robolectric.shadows.ShadowLog.clear()
        Nuxie.setup(context, NuxieConfiguration("pk_test_ignored").apply { logLevel = LogLevel.NONE })
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("Nuxie").any {
            it.msg == "SDK setup ignored while a graph is active or changing."
        })
    }

    @Test
    fun setupCapturesRedactionAndIgnoredSetupCannotChangeIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(), registerLifecycle = false, requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
        )
        val configuration = NuxieConfiguration("pk_test_logging")
        Nuxie.setup(context, configuration)
        configuration.redactSensitiveData = false
        Nuxie.setup(context, NuxieConfiguration("pk_test_ignored").apply { redactSensitiveData = false })
        fun diagnostic(): String {
            org.robolectric.shadows.ShadowLog.clear()
            ai.nuxie.sdk.logging.NuxieLog.w("PrivacyProbe", "Customer", null,
                ai.nuxie.sdk.logging.NuxieLog.sensitive("customer", "setup-secret"))
            return org.robolectric.shadows.ShadowLog.getLogsForTag("PrivacyProbe").single().msg
        }
        assertFalse(diagnostic().contains("setup-secret"))
        Nuxie.shutdownAndAwait()
        Nuxie.setup(context, configuration)
        configuration.redactSensitiveData = true
        Nuxie.setup(context, NuxieConfiguration("pk_test_ignored"))
        assertTrue(diagnostic().contains("setup-secret"))
        Nuxie.shutdownAndAwait()
        Nuxie.setup(context, NuxieConfiguration("pk_test_default"))
        assertFalse(diagnostic().contains("setup-secret"))
    }

    @Test
    fun testStoreSetupMatchesSharedAndroidAdmissionVectors() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = context.applicationInfo.flags
        val fixture = Json.parseToJsonElement(java.io.File(
            ai.nuxie.sdk.fixtures.FixtureRunner.fixturesRoot(), "purchases/test-store-configuration.json",
        ).readText()).jsonObject
        try {
            Nuxie.overridesForTesting = NuxieCore.Overrides(
                transport = FakeTransport(), registerLifecycle = false, requestInitialProfileRefresh = false,
                billingClientFactory = InertBillingClientAdapter.factory,
            )
            for (raw in fixture.getValue("cases").jsonArray) {
                val case = raw.jsonObject
                val flag = android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
                context.applicationInfo.flags = if (case.getValue("debuggable").jsonPrimitive.boolean) original or flag
                    else original and flag.inv()
                val configuration = NuxieConfiguration(case.getValue("apiKey").jsonPrimitive.content).apply {
                    environment = NuxieEnvironment.valueOf(case.getValue("environment").jsonPrimitive.content)
                    testStoreEnabled = case.getValue("enabled").jsonPrimitive.boolean
                }
                if (case.getValue("allowed").jsonPrimitive.boolean) {
                    Nuxie.setup(context, configuration)
                    assertTrue(Nuxie.isSetup)
                    Nuxie.shutdownAndAwait()
                } else {
                    assertThrows(IllegalArgumentException::class.java) { Nuxie.setup(context, configuration) }
                }
                assertFalse(Nuxie.isSetup)
            }
        } finally { context.applicationInfo.flags = original }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun configuredTestStoreNeverConstructsBillingAndCapturesItsModeAtSetup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        val original = context.applicationInfo.flags
        context.applicationInfo.flags = original or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        try {
            val billingConstructions = java.util.concurrent.atomic.AtomicInteger()
            Nuxie.overridesForTesting = NuxieCore.Overrides(
                transport = FakeTransport(), registerLifecycle = false, requestInitialProfileRefresh = false,
                billingClientFactory = BillingClientAdapterFactory {
                    billingConstructions.incrementAndGet()
                    error("Test Store constructed Billing")
                },
            )
            val config = NuxieConfiguration("pk_test_store_config_${System.nanoTime()}").apply {
                environment = NuxieEnvironment.DEVELOPMENT
                testStoreEnabled = true
                purchaseDelegate = object : NuxiePurchaseDelegate {
                    override suspend fun purchase(product: StoreProduct): PurchaseResult = error("Delegate called")
                    override suspend fun restorePurchases(): RestoreResult = error("Delegate called")
                }
            }
            Nuxie.setup(context, config)
            assertTrue(Nuxie.isSetup)
            val testDirectory = ai.nuxie.sdk.billing.purchaseEvidenceDirectory(
                context.filesDir, config.apiKey, config.environment, testStore = true,
            )
            assertTrue(testDirectory.isDirectory)
            assertFalse(ai.nuxie.sdk.billing.purchaseEvidenceDirectory(
                context.filesDir, config.apiKey, config.environment, testStore = false,
            ).exists())
            config.testStoreEnabled = false
            // No visible Activity: Test Store fails presentation instead of entering delegate or Play.
            assertTrue(Nuxie.restorePurchases() is RestoreResult.Failed)
            Nuxie.shutdownAndAwait()
            assertEquals(0, billingConstructions.get())
        } finally {
            Nuxie.resetForTesting()
            context.applicationInfo.flags = original
            Dispatchers.resetMain()
        }
    }

    @Test
    fun setupRejectsABlankKeyWithoutInitializing() {
        assertFalse(Nuxie.isSetup)
        assertThrows(IllegalArgumentException::class.java) {
            Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("   "))
        }
        assertFalse(Nuxie.isSetup)
    }

    @Test
    fun failedCoreConstructionClosesAlreadyAllocatedStorageBeforeAllowingSetupAgain() = runBlocking {
        val actualStore = SQLiteEventStore(RuntimeEnvironment.getApplication(),
            databaseFile = temporary.newFile("failed-construction.db"))
        var closeCount = 0
        val store = object : EventStore by actualStore {
            override suspend fun close() { closeCount++; actualStore.close() }
        }
        val failure = IllegalStateException("Presentation construction failed")
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(), store = store, registerLifecycle = false,
            requestInitialProfileRefresh = false, billingClientFactory = InertBillingClientAdapter.factory,
            presentationFactory = NuxieCore.PresentationFactory { throw failure },
        )
        try {
            val thrown = runCatching {
                Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_failed_construction"))
            }.exceptionOrNull()
            assertTrue(thrown === failure)
            assertFalse(Nuxie.isSetup)
            assertEquals(1, closeCount)
            Nuxie.shutdownAndAwait()
            assertEquals(1, closeCount)
            Nuxie.overridesForTesting = NuxieCore.Overrides(transport = FakeTransport())
            Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_recovered_construction"))
            assertTrue(Nuxie.isSetup)
        } finally { if (closeCount == 0) actualStore.close() }
    }

    @Test
    fun shutdownDuringSetupPreventsPublicationAndWaitsForTheStartingGraph() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val actualStore = SQLiteEventStore(RuntimeEnvironment.getApplication(),
            databaseFile = temporary.newFile("starting-shutdown.db"))
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(), registerLifecycle = false, requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
            store = object : EventStore by actualStore {
                override suspend fun close() { actualStore.close(); closed.incrementAndGet() }
            },
            appVersion = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "1.0"
            },
        )
        withTimeout(10_000) {
            val setup = async(Dispatchers.Default) {
                Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_starting_shutdown"))
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                Nuxie.shutdown()
                Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_overlapping_start"))
                assertFalse(Nuxie.isSetup)
                val completion = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    Nuxie.shutdownAndAwait()
                }
                assertFalse(completion.isCompleted)
                assertEquals(0, closed.get())
                release.countDown()
                setup.await()
                completion.await()
                assertFalse(Nuxie.isSetup)
                assertEquals(1, closed.get())
                Nuxie.overridesForTesting = NuxieCore.Overrides(transport = FakeTransport())
                Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_after_starting_shutdown"))
                assertTrue(Nuxie.isSetup)
            } finally { release.countDown() }
        }
    }

    @Test
    fun setupInitializesOnceAndIgnoresRepeatedCalls() {
        Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_first"))
        assertTrue(Nuxie.isSetup)
        val core = Nuxie.core

        // A repeated call is a warning no-op — even with an invalid key.
        Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("   "))
        assertTrue(Nuxie.isSetup)
        assertTrue(core === Nuxie.core)
    }

    @Test(timeout = 5_000)
    fun shutdownReleasesTheActiveGraphAndAllowsFreshSetup() {
        val listener = NuxieListener { _, _ -> }
        Nuxie.listener = listener
        Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_first"))
        val firstCore = requireNotNull(Nuxie.core)

        runBlocking { Nuxie.shutdownAndAwait() }

        assertFalse(Nuxie.isSetup)
        assertTrue(Nuxie.listener == null)
        Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_second"))
        assertTrue(Nuxie.isSetup)
        assertTrue(firstCore !== Nuxie.core)
    }

    @Test
    fun shutdownClosesAdmissionImmediatelyAndDrainsAnAcceptedConsumption() =
        assertShutdownDrainsConsumption(fireAndForget = false)

    @Test
    fun shutdownDrainsFeatureUseAcceptedImmediatelyBeforeIt() =
        assertShutdownDrainsConsumption(fireAndForget = true)

    private fun assertShutdownDrainsConsumption(fireAndForget: Boolean) = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receivedCommand = CompletableDeferred<JsonObject>()
        val database = temporary.newFile("facade-shutdown.db")
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path != "/feature/consume") HttpTransport.Response(503, ByteArray(0))
                else {
                    val command = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                    receivedCommand.complete(command)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(200, JsonObject(command - "apiKey" + mapOf(
                        "accepted" to JsonPrimitive(true), "code" to JsonPrimitive("consumed"),
                        "active" to JsonPrimitive(true), "balance" to JsonPrimitive(8.0),
                        "unlimited" to JsonPrimitive(false), "type" to JsonPrimitive("metered"),
                        "occurredAtMs" to JsonPrimitive(1789285000000L), "idempotentReplay" to JsonPrimitive(false),
                    )).toString().encodeToByteArray())
                }
            }
        }
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = transport, registerLifecycle = false, requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory, eventDatabaseFile = database,
        )
        Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_facade_shutdown"))
        val original = requireNotNull(Nuxie.core)
        original.purchases.awaitInitialProjection()
        try {
            val command = if (fireAndForget) {
                Nuxie.useFeature("credits")
                null
            } else {
                async(Dispatchers.Default) { Nuxie.consumeFeature("credits", 1.0, "held-command") }
                    .also { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
            }
            Nuxie.shutdown()
            assertFalse(Nuxie.isSetup)
            Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_overlapping"))
            assertFalse(Nuxie.isSetup)
            val completion = async(Dispatchers.Default) { Nuxie.shutdownAndAwait() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            delay(50)
            assertFalse(completion.isCompleted)
            release.countDown()
            command?.let { assertEquals(8.0, it.await().balance!!, 0.0) }
            completion.await()
            val received = receivedCommand.await()
            val eventId = original.featureUsage.localEventId(
                received.getValue("customerId").jsonPrimitive.content,
                received.getValue("operationId").jsonPrimitive.content,
            )
            val reopened = SQLiteEventStore(RuntimeEnvironment.getApplication(), databaseFile = database)
            try { assertTrue(reopened.hasStableOutcome(eventId)) } finally { reopened.close() }
            Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_after_completion"))
            assertTrue(Nuxie.isSetup)
        } finally { release.countDown() }
    }

    @Test
    fun shutdownCancelsPendingPlayCheckoutAndRetainsItsRecoveryBinding() = runBlocking {
        val launched = CompletableDeferred<Unit>()
        val endCount = java.util.concurrent.atomic.AtomicInteger()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val evidence = InMemoryPurchaseEvidenceStore()
        val ok = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()
        val adapter = object : BillingClientAdapter by InertBillingClientAdapter {
            override val isReady = true
            override fun startConnection(listener: BillingClientStateListener) { listener.onBillingSetupFinished(ok) }
            override fun endConnection() { endCount.incrementAndGet() }
            override fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) {
                listener.onQueryPurchasesResponse(ok, emptyList())
            }
            override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult {
                launched.complete(Unit)
                return ok
            }
        }
        val details = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
            .apply { isAccessible = true }.newInstance(
                """{"productId":"play-pro","type":"inapp","title":"Pro","name":"Pro","description":"Pro","oneTimePurchaseOfferDetails":{"formattedPrice":"1.00","priceAmountMicros":1000000,"priceCurrencyCode":"USD"}}""",
            )
        val product = StoreProduct(
            productId = "pro", storeProductId = "play-pro", basePlanId = null, offerId = null,
            placementId = "primary", rawProduct = details, offerToken = null,
            isOfferPersonalized = false, productType = BillingClient.ProductType.INAPP,
        )
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(), registerLifecycle = false, requestInitialProfileRefresh = false,
            billingClientFactory = BillingClientAdapterFactory { adapter }, purchaseEvidenceStore = evidence,
        )
        val configuration = NuxieConfiguration("pk_test_play_shutdown").apply {
            beforeSend = { event -> events += event.name; event }
        }
        Nuxie.setup(RuntimeEnvironment.getApplication(), configuration)
        val activity = org.robolectric.Robolectric.buildActivity(Activity::class.java).setup()
        try {
            withTimeout(5_000) {
                val purchase = async { runCatching { Nuxie.purchase(activity.get(), product) } }
                launched.await()
                val bindings = evidence.loadBindings()
                assertTrue(bindings.isNotEmpty())
                Nuxie.shutdown()
                Nuxie.shutdownAndAwait()
                val failure = runCatching { purchase.await().getOrThrow() }.exceptionOrNull()
                assertTrue(failure is kotlinx.coroutines.CancellationException)
                assertEquals(bindings, evidence.loadBindings())
                assertEquals(1, endCount.get())
                assertFalse(events.contains(SystemEventNames.PURCHASE_CANCELLED))
                assertFalse(events.contains(SystemEventNames.PURCHASE_FAILED))
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test
    fun shutdownCancelsAHostRestoreAndWaitsForItsCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaningUp = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val configuration = NuxieConfiguration("pk_test_restore_shutdown").apply {
            purchaseDelegate = object : NuxiePurchaseDelegate {
                override suspend fun purchase(product: StoreProduct): PurchaseResult = error("Unexpected checkout")
                override suspend fun restorePurchases(): RestoreResult {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            cleaningUp.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
            }
        }
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(), registerLifecycle = false, requestInitialProfileRefresh = false,
            billingClientFactory = InertBillingClientAdapter.factory,
        )
        Nuxie.setup(RuntimeEnvironment.getApplication(), configuration)
        withTimeout(5_000) {
            val restore = async { runCatching { Nuxie.restorePurchases() } }
            try {
                entered.await()
                Nuxie.shutdown()
                val completion = async { Nuxie.shutdownAndAwait() }
                cleaningUp.await()
                assertFalse(Nuxie.isSetup)
                assertFalse(completion.isCompleted)
                releaseCleanup.complete(Unit)
                val failure = runCatching { restore.await().getOrThrow() }.exceptionOrNull()
                assertTrue(failure is kotlinx.coroutines.CancellationException)
                completion.await()
                Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_after_restore"))
                assertTrue(Nuxie.isSetup)
            } finally { releaseCleanup.complete(Unit) }
        }
    }

    @Test
    fun beforeSendCanInitiateShutdownAndSelfAwaitFailsWithoutDeadlocking() = runBlocking {
        val callbackResult = CompletableDeferred<Boolean>()
        val config = NuxieConfiguration("pk_test_callback_shutdown").apply {
            beforeSend = { event ->
                if (event.name == "callback-stop") {
                    val failure = runCatching { runBlocking { Nuxie.shutdownAndAwait() } }.exceptionOrNull()
                    Nuxie.shutdown()
                    callbackResult.complete(failure is IllegalStateException)
                }
                event
            }
        }
        Nuxie.setup(RuntimeEnvironment.getApplication(), config)
        Nuxie.trigger("callback-stop")
        kotlinx.coroutines.withTimeout(5_000) { assertTrue(callbackResult.await()); Nuxie.shutdownAndAwait() }
        assertFalse(Nuxie.isSetup)
    }

    @Test
    fun versionIsExposed() {
        assertTrue(Nuxie.version.isNotBlank())
    }

    @Test
    fun hasFeatureBeforeSetupThrows() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { Nuxie.hasFeature("premium") }
        }
    }

    @Test
    fun dismissBeforeSetupIsANoop() = runBlocking {
        Nuxie.dismiss()
    }

    @Test
    fun runtimeLocaleChangesWaitForTheNextProfileSyncPoint() = runBlocking {
        val transport = FakeTransport()
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = transport,
            registerLifecycle = false,
            deviceLocaleIdentifier = { "device_TEST" },
        )
        val configuration = NuxieConfiguration("pk_test_locale").apply {
            localeIdentifier = "en_US"
        }
        Nuxie.setup(RuntimeEnvironment.getApplication(), configuration)
        val core = requireNotNull(Nuxie.core)

        // Queue behind setup's initial refresh so every inspected request is complete.
        assertTrue(core.profile.refreshAndWait())
        assertEquals("en_US", lastProfileLocale(transport))

        val initialRequestCount = profileRequestCount(transport)
        Nuxie.setLocaleIdentifier("fr_FR")
        assertEquals(initialRequestCount, profileRequestCount(transport))
        assertTrue(core.profile.refreshAndWait())
        assertEquals("fr_FR", lastProfileLocale(transport))

        val frenchRequestCount = profileRequestCount(transport)
        Nuxie.setLocaleIdentifier(null)
        assertEquals(frenchRequestCount, profileRequestCount(transport))
        assertTrue(core.profile.refreshAndWait())
        assertEquals("device_TEST", lastProfileLocale(transport))
    }

    private fun profileRequestCount(transport: FakeTransport): Int =
        transport.requests.count { it.url.path == "/profile" }

    private fun lastProfileLocale(transport: FakeTransport): String {
        val body = transport.requests.last { it.url.path == "/profile" }.body.decodeToString()
        return Regex("\\\"locale\\\":\\\"([^\\\"]+)\\\"")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: error("Profile request did not contain a locale: $body")
    }
}
