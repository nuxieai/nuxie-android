package ai.nuxie.sdk.features

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.billing.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FeatureCommandRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun lostReplyAndJournalReopeningReuseTheAcceptedOperation() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val receipts = mutableMapOf<String, JsonObject>()
        var balance = 1
        var loseReply = true
        val transport = FakeTransport().apply {
            respond = { request ->
                assertEquals("/feature/consume", request.url.path)
                val command = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                requests += command
                val id = command.getValue("operationId").toString()
                val replay = receipts.containsKey(id)
                val receipt = receipts.getOrPut(id) {
                    val accepted = balance > 0
                    if (accepted) balance--
                    JsonObject(command - "apiKey" + mapOf(
                        "accepted" to JsonPrimitive(accepted), "code" to JsonPrimitive(if (accepted) "consumed" else "insufficient"),
                        "active" to JsonPrimitive(false), "balance" to JsonPrimitive(balance), "unlimited" to JsonPrimitive(false),
                        "type" to JsonPrimitive("metered"), "idempotentReplay" to JsonPrimitive(false),
                    ))
                }
                if (loseReply) { loseReply = false; throw IOException("reply lost after commit") }
                HttpTransport.Response(200, JsonObject(receipt + ("idempotentReplay" to JsonPrimitive(replay))).toString().encodeToByteArray())
            }
        }
        val core = NuxieCore(RuntimeEnvironment.getApplication(), "pk_${UUID.randomUUID()}", NuxieEnvironment.DEVELOPMENT,
            LogLevel.NONE, beforeSend = null, overrides = NuxieCore.Overrides(transport = transport,
                registerLifecycle = false, requestInitialProfileRefresh = false,
                billingClientFactory = InertBillingClientAdapter.factory, purchaseEvidenceStore = InMemoryPurchaseEvidenceStore(),
                eventDatabaseFile = File(temporary.root, "events.db"), profileCacheDirectory = File(temporary.root, "profile")))
        try {
            core.purchases.awaitInitialProjection()
            val file = File(temporary.root, "commands.json")
            fun service() = FeatureUsageService(core.api, core.purchases, core.identity, core.features, core.eventLog, core.scope, file)
            try {
                service().consumeFeature("credits", 1.0, "one-export", "project-a")
                fail("The lost response must remain retryable")
            } catch (_: IOException) { }
            assertTrue(file.exists())
            service().recover()
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            assertEquals("[]", file.readText())
            val replay = service().consumeFeature("credits", 1.0, "one-export", "project-a")
            assertTrue(replay.accepted)
            assertFalse(replay.active)
            assertEquals(0.0, replay.balance!!, 0.0)
            assertTrue(replay.idempotentReplay)
            assertEquals(requests[0], requests[1])
            assertEquals(0, balance)
            assertEquals("[]", file.readText())
        } finally { core.stop() }
    }
    @Test
    fun separateImplicitUsesDoNotReplayAPendingLogicalAction() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        var loseReply = true
        val transport = FakeTransport().apply {
            respond = { request ->
                val command = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                requests += command
                if (loseReply) { loseReply = false; throw IOException("lost reply") }
                receipt(command, 8.0)
            }
        }
        val core = testCore(transport)
        try {
            core.purchases.awaitInitialProjection()
            val service = FeatureUsageService(core.api, core.purchases, core.identity, core.features, core.eventLog,
                core.scope, File(temporary.root, "implicit.json"))
            try { service.useFeatureAndWait("credits", 1.0, null, false, null); fail("Expected lost reply") }
            catch (_: IOException) { }
            val second = service.useFeatureAndWait("credits", 1.0, null, false, null)
            assertNotEquals(requests[0]["operationId"], requests[1]["operationId"])
            assertNull(second.usage)
            service.recover()
            assertEquals(requests[0], requests[2])
            assertEquals(3, requests.size)
        } finally { core.stop() }
    }

    @Test
    fun featureListenerCanAwaitAnotherConsumption() = runBlocking {
        var balance = 3.0
        val transport = FakeTransport().apply {
            respond = { request ->
                val command = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                receipt(command, --balance)
            }
        }
        val core = testCore(transport)
        try {
            core.purchases.awaitInitialProjection()
            core.features.hydrateProfile(core.identity.distinctId(), Json.parseToJsonElement(
                """{"features":[{"id":"credits","ext_id":"credits","type":"metered","allowed":true,"unlimited":false,"balance":3}]}"""
            ).jsonObject)
            val service = FeatureUsageService(core.api, core.purchases, core.identity, core.features, core.eventLog,
                core.scope, File(temporary.root, "reentrant.json"))
            val nestedReturned = CompletableDeferred<Unit>()
            val nestedPublished = CompletableDeferred<Unit>()
            core.featureInfo.onFeatureChange = { _, _, access, _ ->
                if (access.balance == 2.0) {
                    val nested = service.consumeFeature("credits", 1.0, "nested", null)
                    assertEquals(1.0, nested.balance!!, 0.0)
                    nestedReturned.complete(Unit)
                } else if (access.balance == 1.0) nestedPublished.complete(Unit)
            }
            withTimeout(5_000) {
                val result = service.consumeFeature("credits", 1.0, "outer", null)
                assertEquals(core.identity.distinctId(), result.customerId)
                assertEquals("credits", result.featureId)
                assertEquals(1789285000000.0, result.occurredAtMs!!, 0.0)
                nestedReturned.await()
                nestedPublished.await()
            }
            assertEquals("[]", File(temporary.root, "reentrant.json").readText())
        } finally { core.stop() }
    }

    private fun receipt(command: JsonObject, balance: Double) = HttpTransport.Response(200,
        JsonObject(command - "apiKey" + mapOf(
            "accepted" to JsonPrimitive(true), "code" to JsonPrimitive("consumed"),
            "active" to JsonPrimitive(balance > 0), "balance" to JsonPrimitive(balance),
            "unlimited" to JsonPrimitive(false), "type" to JsonPrimitive("metered"),
            "occurredAtMs" to JsonPrimitive(1789285000000L), "idempotentReplay" to JsonPrimitive(false),
        )).toString().encodeToByteArray())

    private fun testCore(transport: FakeTransport) = NuxieCore(RuntimeEnvironment.getApplication(),
        "pk_${UUID.randomUUID()}", NuxieEnvironment.DEVELOPMENT, LogLevel.NONE, beforeSend = null,
        overrides = NuxieCore.Overrides(transport = transport, registerLifecycle = false,
            requestInitialProfileRefresh = false, billingClientFactory = InertBillingClientAdapter.factory,
            purchaseEvidenceStore = InMemoryPurchaseEvidenceStore(),
            eventDatabaseFile = File(temporary.root, "events.db"), profileCacheDirectory = File(temporary.root, "profile")))

}
