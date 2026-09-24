package ai.nuxie.sdk.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Internal execution claim; the server corroborates its pinned action and Journey. */
internal data class JourneyEventOrigin(
    val journeyId: String,
    val experienceId: String,
    val versionId: String,
    val legId: String,
    val generation: Int,
    val stepId: String,
    val occurrenceId: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("journeyId", journeyId); put("experienceId", experienceId); put("versionId", versionId)
        put("legId", legId); put("generation", generation); put("source", "device_action")
        put("stepId", stepId); put("occurrenceId", occurrenceId)
    }

    companion object {
        fun fromJson(value: JsonObject): JourneyEventOrigin {
            require(value.getValue("source").jsonPrimitive.content == "device_action")
            return JourneyEventOrigin(
                journeyId = value.getValue("journeyId").jsonPrimitive.content,
                experienceId = value.getValue("experienceId").jsonPrimitive.content,
                versionId = value.getValue("versionId").jsonPrimitive.content,
                legId = value.getValue("legId").jsonPrimitive.content,
                generation = value.getValue("generation").jsonPrimitive.int,
                stepId = value.getValue("stepId").jsonPrimitive.content,
                occurrenceId = value.getValue("occurrenceId").jsonPrimitive.content,
            )
        }
    }
}
