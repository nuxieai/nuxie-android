package ai.nuxie.sdk.testsupport

import ai.nuxie.sdk.network.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun featureCommandResponse(request: HttpTransport.Request, balance: Double = 1.0, accepted: Boolean = true): HttpTransport.Response {
    val command = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
    val receipt = JsonObject(command.filterKeys { it in setOf("customerId", "featureId", "operationId", "quantity", "entityId") } + mapOf(
        "accepted" to JsonPrimitive(accepted), "active" to JsonPrimitive(balance > 0),
        "balance" to JsonPrimitive(balance), "unlimited" to JsonPrimitive(false),
        "code" to JsonPrimitive(if (accepted) "consumed" else "insufficient"),
        "type" to JsonPrimitive("creditSystem"), "idempotentReplay" to JsonPrimitive(false),
        "occurredAtMs" to JsonPrimitive(100),
    ))
    return HttpTransport.Response(200, receipt.toString().encodeToByteArray())
}
