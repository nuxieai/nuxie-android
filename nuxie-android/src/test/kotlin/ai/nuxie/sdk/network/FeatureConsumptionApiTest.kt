package ai.nuxie.sdk.network

import ai.nuxie.sdk.identity.CustomerKind
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Test

class FeatureConsumptionApiTest {
    @Test
    fun sharedCommandReceiptsUseTheDedicatedEndpoint() {
        FixtureRunner.run("encodings/feature-consumption.json", "encodings/feature-consumption") { vector ->
            val command = vector.body.getValue("request").jsonObject
            val receipt = vector.body.getValue("response").jsonObject
            val purchase = command["purchase"] as? JsonObject
            // Shared evidence uses the iOS arm. Exercise Android's real encoder
            // with equivalent Play evidence while retaining every command field.
            val expected = if (purchase == null) command else JsonObject(command + ("purchase" to JsonObject(mapOf(
                "type" to JsonPrimitive("playstore"),
                "purchaseToken" to purchase.getValue("signedTransaction"),
                "productId" to JsonPrimitive("credit-pack"),
            ))))
            val transport = HttpTransport { request ->
                assertEquals("/feature/consume", request.url.path)
                val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                assertEquals(JsonPrimitive("test-key"), body["apiKey"])
                assertEquals(expected, JsonObject(body - "apiKey"))
                assertFalse(body.containsKey("customer_kind"))
                if (purchase == null) assertNull(body["customerKind"])
                HttpTransport.Response(200, receipt.toString().encodeToByteArray())
            }
            runBlocking {
                val api = NuxieApi("test-key", NuxieEnvironment.DEVELOPMENT, transport)
                if (purchase == null) {
                    assertEquals(receipt, api.consumeFeature(command))
                } else {
                    val result = api.useFeatureWithPurchase(NuxieApi.PurchaseBackedFeatureUseReport(
                        customerId = command.getValue("customerId").jsonPrimitive.content,
                        featureId = command.getValue("featureId").jsonPrimitive.content,
                        requiredBalance = command.getValue("quantity").jsonPrimitive.double,
                        eventData = NuxieApi.FeatureUseEventData(command.getValue("quantity").jsonPrimitive.double, null),
                        entityId = command["entityId"]?.jsonPrimitive?.content,
                        purchase = NuxieApi.PlayPurchaseUseReport(
                            packageName = "com.example.app", productId = "credit-pack",
                            purchaseToken = purchase.getValue("signedTransaction").jsonPrimitive.content,
                            basePlanId = null, offerId = null, obfuscatedAccountId = null,
                            eventId = command.getValue("operationId").jsonPrimitive.content,
                        ),
                        customerKind = command["customerKind"]?.jsonPrimitive?.content?.let { wire ->
                            CustomerKind.entries.single { it.wireValue == wire }
                        },
                    ))
                    assertEquals(command.getValue("customerId").jsonPrimitive.content, result.customerId)
                    assertEquals(command.getValue("featureId").jsonPrimitive.content, result.featureId)
                    assertEquals(receipt.getValue("balance").jsonPrimitive.double, result.balance!!, 0.0)
                }
            }
        }
    }
    @Test
    fun olderSavedPurchaseCommandKeepsOmittingCustomerKind(): Unit = runBlocking {
        val saved = """{"customerId":"customer","featureId":"credits","operationId":"older-use","quantity":1,"purchase":{"type":"playstore","purchaseToken":"saved-token","productId":"credit-pack"}}"""
        val command = Json.parseToJsonElement(saved).jsonObject
        val transport = HttpTransport { request ->
            val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
            assertNull(body["customerKind"])
            assertNull(body["customer_kind"])
            assertEquals(command, JsonObject(body - "apiKey"))
            ai.nuxie.sdk.testsupport.featureCommandResponse(request, balance = 0.0)
        }
        NuxieApi("test-key", NuxieEnvironment.DEVELOPMENT, transport).consumeFeature(command)
    }
}
