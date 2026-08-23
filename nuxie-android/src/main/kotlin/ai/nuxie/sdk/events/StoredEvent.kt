package ai.nuxie.sdk.events

import ai.nuxie.sdk.NuxieEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Immutable event row with its properties retained as encoded JSON bytes. */
internal class StoredEvent private constructor(
    val id: String,
    val name: String,
    encodedProperties: ByteArray,
    val timestampMillis: Long,
    val distinctId: String,
    val sessionId: String?,
) {
    private val encodedProperties = encodedProperties.copyOf()

    val properties: JsonObject by lazy {
        Json.parseToJsonElement(this.encodedProperties.decodeToString()).jsonObject
    }

    constructor(
        id: String,
        name: String,
        properties: JsonObject = JsonObject(emptyMap()),
        timestampMillis: Long = System.currentTimeMillis(),
        distinctId: String,
    ) : this(
        id = id,
        name = name,
        encodedProperties = CanonicalJson.encodeToByteArray(properties),
        timestampMillis = timestampMillis,
        distinctId = distinctId,
        sessionId = (properties[SESSION_ID_PROPERTY] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull,
    )

    fun encodedProperties(): ByteArray = encodedProperties.copyOf()

    internal companion object {
        private const val SESSION_ID_PROPERTY = "\$session_id"

        fun from(event: NuxieEvent): StoredEvent = StoredEvent(
            id = event.id,
            name = event.name,
            properties = JsonValueConverter.fromMap(event.properties),
            timestampMillis = event.timestampMillis,
            distinctId = event.distinctId,
        )

        fun fromStorage(
            id: String,
            name: String,
            encodedProperties: ByteArray,
            timestampMillis: Long,
            distinctId: String,
            sessionId: String?,
        ): StoredEvent = StoredEvent(
            id = id,
            name = name,
            encodedProperties = encodedProperties,
            timestampMillis = timestampMillis,
            distinctId = distinctId,
            sessionId = sessionId,
        )
    }
}
