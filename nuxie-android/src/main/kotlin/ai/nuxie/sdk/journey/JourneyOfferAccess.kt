package ai.nuxie.sdk.journey

import ai.nuxie.sdk.features.FeatureAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads the existing identity-scoped purchase projection and server access. */
internal object JourneyOfferAccess {
    enum class Decision { ELIGIBLE, ALREADY_ENTITLED, UNKNOWN }

    suspend fun evaluate(
        placementIds: List<String>,
        products: JsonArray,
        placements: JsonArray,
        featureAccess: suspend (String) -> FeatureAccess?,
    ): Decision {
        if (placementIds.isEmpty()) return Decision.UNKNOWN
        var unresolved = false
        for (placementId in placementIds) {
            val placement = placements.filterIsInstance<JsonObject>().firstOrNull { it.text("id") == placementId }
            val product = products.filterIsInstance<JsonObject>().firstOrNull { it.text("id") == placement?.text("productId") }
            if (product == null) { unresolved = true; continue }
            if (product.text("type") == "consumable") return Decision.ELIGIBLE
            val grants = product["entitlements"] as? JsonArray
            val features = grants?.filterIsInstance<JsonObject>()?.mapNotNull { it.text("featureExternalId") ?: it.text("featureId") }?.toSet().orEmpty()
            if (features.isEmpty() || grants?.any { it !is JsonObject || (it.text("featureId") == null && it.text("featureExternalId") == null) } == true) {
                unresolved = true
                continue
            }
            var missing = false
            var unknown = false
            for (feature in features) {
                val access = featureAccess(feature)
                if (access == null) unknown = true else missing = missing || !access.allowed
            }
            if (missing) return Decision.ELIGIBLE
            unresolved = unresolved || unknown
        }
        return if (unresolved) Decision.UNKNOWN else Decision.ALREADY_ENTITLED
    }

    fun forScreen(leg: JsonObject, screenId: String): JsonObject? =
        (leg["offers"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull { it.text("screenId") == screenId }

    fun forPurchaseStep(leg: JsonObject, stepId: String): JsonObject? {
        val steps = (leg["steps"] as? JsonArray)?.filterIsInstance<JsonObject>()?.associateBy { it.text("id") }.orEmpty()
        for (route in (leg["routes"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()) {
            val host = route["host"] as? JsonObject ?: continue
            if (host.text("kind") != "screen") continue
            val pending = mutableListOf(route.text("entryStepId") ?: continue)
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val cursor = pending.removeAt(pending.lastIndex)
                if (!visited.add(cursor)) continue
                if (cursor == stepId) return host.text("screenId")?.let { forScreen(leg, it) }
                val step = steps[cursor] ?: continue
                val action = step["action"] as? JsonObject ?: continue
                if (action.text("type") in setOf("navigate", "back", "dismiss", "exit")) continue
                pending.addAll((step["outlets"] as? JsonObject)?.values?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.orEmpty())
            }
        }
        return null
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
