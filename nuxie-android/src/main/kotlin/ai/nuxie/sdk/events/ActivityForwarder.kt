package ai.nuxie.sdk.events

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.NuxieActivityInfo
import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.util.IsoDates
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One post-commit forwarding path shared by every event origin. */
internal class ActivityForwarder(
    private val resolveExperience: (distinctId: String, journeyId: String) -> ExperienceRef?,
    private val deliver: suspend (NuxieActivityInfo) -> Unit,
) {
    suspend fun onCommitted(event: StoredEvent) {
        val receivedAtMillis = event.forwardingReceivedAtMillis ?: return
        if (event.forwardingName !in ActivityCuration.curatedNames) return
        val properties = if (event.forwardingName == JourneyEventNames.SUPERSEDED) {
            enrichJourneyReference(event)
        } else {
            event.properties
        }
        val activity = ActivityCuration.activity(event.forwardingName, properties) ?: return
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

    private fun enrichJourneyReference(event: StoredEvent): JsonObject {
        if (event.properties.containsKey("experience_id")) return event.properties
        val journeyId = event.properties.string("journey_id") ?: return event.properties
        val ref = resolveExperience(event.distinctId, journeyId) ?: return event.properties
        return buildJsonObject {
            for ((key, value) in event.properties) {
                put(key, value)
            }
            put("experience_id", JsonPrimitive(ref.experienceId))
            ref.experienceVersion?.let { put("experience_version", JsonPrimitive(it)) }
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
}
