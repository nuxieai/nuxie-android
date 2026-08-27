package ai.nuxie.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Canonical wrapper projection pinned by fixtures/encodings/app-action.json. */
internal object AppActionWireEncoder {
    fun encode(action: AppAction): JsonObject = JsonObject(
        linkedMapOf(
            "name" to JsonPrimitive(action.name),
            "payload" to (action.payload?.let(::encodePayload) ?: JsonNull),
            "experience" to JsonObject(
                linkedMapOf(
                    "experienceId" to JsonPrimitive(action.experience.experienceId),
                    "experienceVersion" to action.experience.experienceVersion.toJsonPrimitive(),
                    "journeyId" to action.experience.journeyId.toJsonPrimitive(),
                ),
            ),
        ),
    )

    private fun encodePayload(payload: Map<String, AppActionValue>): JsonObject = JsonObject(
        payload.mapValues { (_, value) -> value.toJsonPrimitive() },
    )

    private fun AppActionValue.toJsonPrimitive(): JsonElement = when (this) {
        is AppActionValue.String -> JsonPrimitive(value)
        is AppActionValue.Int -> JsonPrimitive(value)
        is AppActionValue.Double -> JsonPrimitive(value)
        is AppActionValue.Bool -> JsonPrimitive(value)
    }

    private fun String?.toJsonPrimitive(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
}
