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
    /** Capture-time name retained only for forwarding classification. */
    val forwardingName: String,
    /** Admission time when forwarding was enabled; null means do not replay. */
    val forwardingReceivedAtMillis: Long?,
) {
    private val encodedProperties = encodedProperties.copyOf()

    val properties: JsonObject by lazy {
        Json.parseToJsonElement(this.encodedProperties.decodeToString()).jsonObject
    }

    val origin: String
        get() = if (properties["\$nuxie_event_origin"] == JsonPrimitive("server")) "server" else "device"

    constructor(
        id: String,
        name: String,
        properties: JsonObject = JsonObject(emptyMap()),
        timestampMillis: Long = System.currentTimeMillis(),
        distinctId: String,
        forwardingName: String = name,
        forwardingReceivedAtMillis: Long? = null,
    ) : this(
        id = id,
        name = name,
        encodedProperties = CanonicalJson.encodeToByteArray(properties),
        timestampMillis = timestampMillis,
        distinctId = distinctId,
        sessionId = (properties[SESSION_ID_PROPERTY] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull,
        forwardingName = forwardingName,
        forwardingReceivedAtMillis = forwardingReceivedAtMillis,
    )

    fun encodedProperties(): ByteArray = encodedProperties.copyOf()

    fun withForwardingAdmission(receivedAtMillis: Long?): StoredEvent = StoredEvent(
        id = id,
        name = name,
        encodedProperties = encodedProperties,
        timestampMillis = timestampMillis,
        distinctId = distinctId,
        sessionId = sessionId,
        forwardingName = forwardingName,
        forwardingReceivedAtMillis = receivedAtMillis,
    )

    internal companion object {
        private const val SESSION_ID_PROPERTY = "\$session_id"

        fun from(
            event: NuxieEvent,
            forwardingName: String = event.name,
            forwardingReceivedAtMillis: Long? = null,
        ): StoredEvent = StoredEvent(
            id = event.id,
            name = event.name,
            properties = JsonValueConverter.fromMap(event.properties),
            timestampMillis = event.timestampMillis,
            distinctId = event.distinctId,
            forwardingName = forwardingName,
            forwardingReceivedAtMillis = forwardingReceivedAtMillis,
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
            forwardingName = name,
            forwardingReceivedAtMillis = null,
        )
    }
}
