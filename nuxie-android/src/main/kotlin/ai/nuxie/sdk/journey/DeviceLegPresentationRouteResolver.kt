package ai.nuxie.sdk.journey

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Resolves authenticated screen events through the owning Journey's routes. */
internal object DeviceLegPresentationRouteResolver {
    fun resolve(
        leg: JsonObject,
        eventName: String,
        screenId: String,
    ): String? {
        val routes = leg.getValue("routes").jsonArray.map(JsonElement::jsonObject)
        fun route(kind: String, expectedScreenId: String? = null): String? = routes
            .firstOrNull { candidate ->
                if (candidate.text("eventName") != eventName) return@firstOrNull false
                val host = candidate.getValue("host").jsonObject
                host.text("kind") == kind &&
                    (expectedScreenId == null || host.text("screenId") == expectedScreenId)
            }
            ?.text("entryStepId")
        return route("screen", screenId) ?: route("journey")
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
