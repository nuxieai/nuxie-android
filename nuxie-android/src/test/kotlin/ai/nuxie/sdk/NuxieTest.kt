package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
    fun setupRejectsABlankKeyWithoutInitializing() {
        assertFalse(Nuxie.isSetup)
        assertThrows(IllegalArgumentException::class.java) {
            Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("   "))
        }
        assertFalse(Nuxie.isSetup)
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
