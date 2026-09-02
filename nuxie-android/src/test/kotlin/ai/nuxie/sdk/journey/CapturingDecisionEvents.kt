package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.DecisionEventCapture
import ai.nuxie.sdk.events.DecisionEventCapturing
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.StoredEvent
import kotlinx.serialization.json.JsonObject

/** Test seam that exercises durable capture without making a network call. */
internal class CapturingDecisionEvents(
    private val eventLog: EventLog,
    private val responseFor: (StoredEvent) -> JsonObject? = { null },
    private val applyResponse: suspend (StoredEvent, JsonObject) -> Unit = { _, _ -> },
) : DecisionEventCapturing {
    override suspend fun capture(
        name: String,
        properties: Map<String, Any?>,
        distinctId: String,
        eventId: String?,
        applyBeforeSend: Boolean,
    ): DecisionEventCapture? {
        val stored = if (eventId == null) {
            eventLog.captureForTrigger(name, properties, distinctId) ?: return null
        } else {
            val result = eventLog.captureIdempotentlyWithResult(
                name = name,
                properties = properties,
                eventId = eventId,
                distinctId = distinctId,
                applyBeforeSend = applyBeforeSend,
            )
            if (!result.succeeded) return null
            result.storedEvent
        }
        val response = stored?.let(responseFor)
        if (stored != null && response != null) applyResponse(stored, response)
        return DecisionEventCapture(stored, response)
    }
}
