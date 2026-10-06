package ai.nuxie.sdk.journey

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal data class JourneyResponseSave(
    val distinctId: String,
    val journeyId: String,
    val experienceId: String,
    val experienceVersionId: String,
    val formName: String,
    val sequence: Long,
    val answers: JsonObject,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("distinct_id", JsonPrimitive(distinctId))
        put("journey_id", JsonPrimitive(journeyId))
        put("experience_id", JsonPrimitive(experienceId))
        put("experience_version_id", JsonPrimitive(experienceVersionId))
        put("form_name", JsonPrimitive(formName))
        put("sequence", JsonPrimitive(sequence))
        put("answers", answers)
    }

    companion object {
        const val MAXIMUM_SEQUENCE = 9_007_199_254_740_991L
        fun fromJson(value: JsonObject) = JourneyResponseSave(
            value.getValue("distinct_id").jsonPrimitive.content,
            value.getValue("journey_id").jsonPrimitive.content,
            value.getValue("experience_id").jsonPrimitive.content,
            value.getValue("experience_version_id").jsonPrimitive.content,
            value.getValue("form_name").jsonPrimitive.content,
            value.getValue("sequence").jsonPrimitive.long,
            value.getValue("answers").jsonObject,
        )
    }
}

internal data class JourneyResponseSaveLane(
    var sequence: Long = 0,
    var pending: JourneyResponseSave? = null,
) {
    fun toJson() = buildJsonObject {
        put("sequence", JsonPrimitive(sequence))
        pending?.let { put("pending", it.toJson()) }
    }
    companion object {
        fun fromJson(value: JsonObject) = JourneyResponseSaveLane(
            value.getValue("sequence").jsonPrimitive.long,
            value["pending"]?.jsonObject?.let(JourneyResponseSave::fromJson),
        )
    }
}

internal data class JourneyResponseSaveState(
    val namespace: String,
    val distinctId: String,
    val journeys: MutableMap<String, MutableMap<String, JourneyResponseSaveLane>> = linkedMapOf(),
) {
    fun toJson() = buildJsonObject {
        put("namespace", JsonPrimitive(namespace))
        put("distinctId", JsonPrimitive(distinctId))
        put("journeys", JsonObject(journeys.mapValues { (_, forms) -> JsonObject(forms.mapValues { it.value.toJson() }) }))
    }
    companion object {
        fun fromJson(value: JsonObject) = JourneyResponseSaveState(
            value.getValue("namespace").jsonPrimitive.content,
            value.getValue("distinctId").jsonPrimitive.content,
            value.getValue("journeys").jsonObject.mapValues { (_, forms) ->
                forms.jsonObject.mapValues { JourneyResponseSaveLane.fromJson(it.value.jsonObject) }.toMutableMap()
            }.toMutableMap(),
        )
    }
}
