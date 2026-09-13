package ai.nuxie.sdk.network

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Validated per-item acceptance. HTTP success alone never retires a durable event. */
internal data class BatchAcknowledgment(val acceptedIndexes: List<Int>) {
    companion object {
        fun decode(bytes: ByteArray, submittedCount: Int): BatchAcknowledgment {
            try {
                val body = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                    ?: error("Expected batch response object")
                fun integer(value: kotlinx.serialization.json.JsonElement?): Int {
                    val number = value as? JsonPrimitive ?: error("Expected integer")
                    check(!number.isString)
                    return number.intOrNull ?: error("Expected integer")
                }
                fun string(value: kotlinx.serialization.json.JsonElement?) {
                    check((value as? JsonPrimitive)?.isString == true)
                }
                string(body["status"])
                val total = integer(body["total"])
                val processed = integer(body["processed"])
                val failed = integer(body["failed"])
                check(total == submittedCount && processed in 0..total && failed == total - processed)
                val errors = when (val value = body["errors"]) {
                    null, JsonNull -> emptyList()
                    is JsonArray -> value
                    else -> error("Expected error array")
                }
                check(errors.size == failed)
                val failedIndexes = errors.map { value ->
                    val failure = value as? JsonObject ?: error("Expected error object")
                    string(failure["event"])
                    string(failure["error"])
                    integer(failure["index"]).also { check(it in 0 until total) }
                }.toSet()
                check(failedIndexes.size == failed)
                return BatchAcknowledgment((0 until total).filterNot(failedIndexes::contains))
            } catch (error: Exception) {
                throw IOException("Invalid batch acknowledgment; retaining submitted events", error)
            }
        }
    }
}
