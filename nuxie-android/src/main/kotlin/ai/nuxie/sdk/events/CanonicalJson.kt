package ai.nuxie.sdk.events

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Compact JSON with object keys sorted recursively and array order retained. */
internal object CanonicalJson {
    fun encode(value: JsonElement): String = canonicalize(value).toString()

    fun encodeToByteArray(value: JsonElement): ByteArray = encode(value).encodeToByteArray()

    private fun canonicalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries
                .sortedBy(Map.Entry<String, JsonElement>::key)
                .associateTo(linkedMapOf()) { (key, child) -> key to canonicalize(child) },
        )
        is JsonArray -> JsonArray(value.map(::canonicalize))
        else -> value
    }
}
