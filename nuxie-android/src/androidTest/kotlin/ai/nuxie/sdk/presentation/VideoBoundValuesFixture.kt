package ai.nuxie.sdk.presentation

import kotlinx.serialization.json.*

/** Declare the published scene's authored default so mounted screens bind it. */
internal fun videoLegWithBoundValues(leg: JsonObject): JsonObject = JsonObject(leg + (
    "screens" to JsonArray(leg.getValue("screens").jsonArray.map { value ->
        JsonObject(value.jsonObject + ("defaultViewModelName" to JsonPrimitive("screen")))
    })
))
