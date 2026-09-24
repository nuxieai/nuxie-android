package ai.nuxie.sdk.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Internal execution claim; the server corroborates its pinned action and Journey. */
internal data class JourneyEventOrigin(
    val journeyId: String,
    val experienceId: String,
    val versionId: String,
    val legId: String,
    val generation: Long,
    val stepId: String?,
    val occurrenceId: String,
    val screenId: String? = null,
    val actionId: String? = null,
    val invocationId: String? = null,
) {
    init {
        require(if (stepId != null) screenId == null && actionId == null && invocationId == null
            else screenId != null && actionId != null && invocationId != null)
    }

    val source: String get() = if (stepId != null) "device_action" else "screen_control"

    fun toJson(): JsonObject = buildJsonObject {
        put("journeyId", journeyId); put("experienceId", experienceId); put("versionId", versionId)
        put("legId", legId); put("generation", generation); put("source", source)
        stepId?.let { put("stepId", it) }
        screenId?.let { put("screenId", it) }
        actionId?.let { put("actionId", it) }
        invocationId?.let { put("invocationId", it) }
        put("occurrenceId", occurrenceId)
    }

    companion object {
        fun fromJson(value: JsonObject): JourneyEventOrigin {
            val source = value.getValue("source").jsonPrimitive.content
            require(source == "device_action" || source == "screen_control")
            return JourneyEventOrigin(
                journeyId = value.getValue("journeyId").jsonPrimitive.content,
                experienceId = value.getValue("experienceId").jsonPrimitive.content,
                versionId = value.getValue("versionId").jsonPrimitive.content,
                legId = value.getValue("legId").jsonPrimitive.content,
                generation = value.getValue("generation").jsonPrimitive.long,
                stepId = if (source == "device_action") value.getValue("stepId").jsonPrimitive.content else null,
                screenId = if (source == "screen_control") value.getValue("screenId").jsonPrimitive.content else null,
                actionId = if (source == "screen_control") value.getValue("actionId").jsonPrimitive.content else null,
                invocationId = if (source == "screen_control") value.getValue("invocationId").jsonPrimitive.content else null,
                occurrenceId = value.getValue("occurrenceId").jsonPrimitive.content,
            )
        }
    }
}
