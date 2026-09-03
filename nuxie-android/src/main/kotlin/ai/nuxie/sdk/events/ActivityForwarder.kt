package ai.nuxie.sdk.events

import ai.nuxie.sdk.NuxieActivityInfo
import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.util.IsoDates
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One post-commit forwarding path shared by every event origin. */
internal class ActivityForwarder(
    private val deliver: suspend (NuxieActivityInfo) -> Unit,
) {
    suspend fun onCommitted(event: StoredEvent) {
        val receivedAtMillis = event.forwardingReceivedAtMillis ?: return
        if (event.forwardingName !in ActivityCuration.curatedNames) return
        val activity = ActivityCuration.activity(event.forwardingName, event.properties) ?: return
        deliver(
            NuxieActivityInfo(
                id = event.id,
                timestampMillis = occurrenceTime(event),
                receivedAtMillis = receivedAtMillis,
                activity = activity,
            ),
        )
    }

    private fun occurrenceTime(event: StoredEvent): Long {
        val key = when (event.forwardingName) {
            JourneyEventNames.LEG_STARTED -> "started_at"
            JourneyEventNames.LEG_COMPLETED -> "completed_at"
            else -> return event.timestampMillis
        }
        return event.properties.string(key)?.let(IsoDates::parseMillis) ?: event.timestampMillis
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

}
