package ai.nuxie.sdk.events

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Encodes a stored event as the canonical batch-item wire representation. */
internal object BatchItemWireEncoder {
    fun encode(event: StoredEvent): String {
        val properties = event.properties
        val item = linkedMapOf<String, JsonElement>(
            "event" to JsonPrimitive(event.name),
            "distinct_id" to JsonPrimitive(event.distinctId),
            "timestamp" to JsonPrimitive(formatTimestamp(event.timestampMillis)),
            "properties" to properties,
            "idempotency_key" to JsonPrimitive(event.id),
        )

        properties.stringProperty("\$anon_distinct_id")?.let {
            item["\$anon_distinct_id"] = JsonPrimitive(it)
        }
        properties.numericProperty("value")?.let { item["value"] = it }
        // The iOS reference encoder emits camel-cased `entityId` (RequestModels
        // CodingKeys has no snake mapping for it); the fixture's `entity_id`
        // expectation key is an adapter label, like `anon_distinct_id`.
        properties.stringProperty("entityId")?.let { item["entityId"] = JsonPrimitive(it) }

        return CanonicalJson.encode(JsonObject(item))
    }

    private fun formatTimestamp(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
            timeZone = TimeZone.getTimeZone("UTC")
            format(Date(timestampMillis))
        }

    private fun JsonObject.stringProperty(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull

    private fun JsonObject.numericProperty(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString && it.booleanOrNull == null && it.content != "null" }
        ?.takeIf { it.content.toDoubleOrNull() != null }

}
