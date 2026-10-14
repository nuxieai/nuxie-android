package ai.nuxie.sdk.presentation

import kotlinx.serialization.json.*

/** Declare the published scene's authored default so mounted screens bind it. */
internal fun videoLegWithBoundValues(leg: JsonObject, modelName: String = "Runtime"): JsonObject = JsonObject(leg + (
    "screens" to JsonArray(leg.getValue("screens").jsonArray.map { value ->
        JsonObject(value.jsonObject + ("defaultViewModelName" to JsonPrimitive(modelName)))
    })
))

/** Await the lane's write result so a missing path or missing binding cannot pass silently. */
internal fun ExperienceSurfaceHost.setVideoValuesChecked(values: Map<String, ai.nuxie.sdk.runtime.NuxieViewModelScalarValue>) {
    kotlinx.coroutines.runBlocking {
        org.junit.Assert.assertTrue("Bound video paths must accept the write: ${values.keys}",
            kotlinx.coroutines.withTimeout(10_000) { updateRuntimeValues(values).await() })
    }
}
