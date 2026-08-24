package ai.nuxie.sdk.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object JsonValueConverter {
    fun fromMap(properties: Map<String, Any?>): JsonObject = JsonObject(
        properties.mapValues { (_, value) -> fromAny(value) },
    )

    private fun fromAny(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value.requireValidJson()
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value.toString())
        is Byte -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is UByte -> unsigned(value.toString())
        is UShort -> unsigned(value.toString())
        is UInt -> unsigned(value.toString())
        is ULong -> unsigned(value.toString())
        is Float -> JsonPrimitive(value.requireFinite())
        is Double -> JsonPrimitive(value.requireFinite())
        is Map<*, *> -> map(value)
        is Iterable<*> -> JsonArray(value.map(::fromAny))
        is Array<*> -> JsonArray(value.map(::fromAny))
        is BooleanArray -> JsonArray(value.map(::JsonPrimitive))
        is ByteArray -> JsonArray(value.map(::JsonPrimitive))
        is ShortArray -> JsonArray(value.map(::JsonPrimitive))
        is IntArray -> JsonArray(value.map(::JsonPrimitive))
        is LongArray -> JsonArray(value.map(::JsonPrimitive))
        is FloatArray -> JsonArray(value.map { JsonPrimitive(it.requireFinite()) })
        is DoubleArray -> JsonArray(value.map { JsonPrimitive(it.requireFinite()) })
        else -> throw IllegalArgumentException(
            "Unsupported event property type: ${value::class.qualifiedName}",
        )
    }

    private fun map(value: Map<*, *>): JsonObject {
        val entries = linkedMapOf<String, JsonElement>()
        value.forEach { (key, child) ->
            require(key is String) { "Event property keys must be strings." }
            entries[key] = fromAny(child)
        }
        return JsonObject(entries)
    }

    private fun unsigned(value: String): JsonElement = Json.parseToJsonElement(value)

    /**
     * Caller-supplied JsonElements can smuggle non-JSON text such as
     * JsonPrimitive(Double.NaN); validate every leaf so canonical output is
     * always strict JSON.
     */
    private fun JsonElement.requireValidJson(): JsonElement {
        when (this) {
            is JsonObject -> values.forEach { it.requireValidJson() }
            is JsonArray -> forEach { it.requireValidJson() }
            is JsonPrimitive -> if (!isString && this != JsonNull) {
                val text = content
                val isLiteral = text == "true" || text == "false" || text == "null"
                require(isLiteral || strictNumberPattern.matches(text)) {
                    "Event property JSON primitives must be strict JSON values: $text"
                }
            }
            else -> Unit
        }
        return this
    }

    private val strictNumberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private fun Float.requireFinite(): Float {
        require(isFinite()) { "Event property numbers must be finite." }
        return this
    }

    private fun Double.requireFinite(): Double {
        require(isFinite()) { "Event property numbers must be finite." }
        return this
    }
}
