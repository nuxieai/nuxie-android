package ai.nuxie.sdk.network

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureConsumptionApiTest {
    @Test
    fun sharedCommandReceiptsUseTheDedicatedEndpoint() {
        FixtureRunner.run("encodings/feature-consumption.json", "encodings/feature-consumption") { vector ->
            val command = vector.body.getValue("request").jsonObject
            val receipt = vector.body.getValue("response").jsonObject
            val transport = HttpTransport { request ->
                assertEquals("/feature/consume", request.url.path)
                val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                assertEquals(JsonPrimitive("test-key"), body["apiKey"])
                assertEquals(command, JsonObject(body - "apiKey"))
                HttpTransport.Response(200, receipt.toString().encodeToByteArray())
            }
            assertEquals(receipt, NuxieApi("test-key", NuxieEnvironment.DEVELOPMENT, transport).consumeFeature(command))
        }
    }
}
