package ai.nuxie.sdk.events

import kotlinx.serialization.json.JsonObject

internal data class DecisionEventCapture(
    val event: StoredEvent?,
    val response: JsonObject?,
)

/** Durable capture followed by the synchronous `/event` decision round trip. */
internal interface DecisionEventCapturing {
    suspend fun capture(
        name: String,
        properties: Map<String, Any?>,
        distinctId: String,
        eventId: String? = null,
        applyBeforeSend: Boolean = true,
    ): DecisionEventCapture?
}
