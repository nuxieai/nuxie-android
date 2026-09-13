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
}
