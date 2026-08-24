package ai.nuxie.sdk.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The server's synchronous decision attached to an /event response
 * (`payload.gate`), ported from the iOS `GatePlan`. Wire names keep the
 * legacy `show_flow`/`flowId` spellings until the server contract revs —
 * they never surface publicly.
 */
internal class GatePlan(
    val decision: Decision,
    val featureId: String?,
    val requiredBalance: Double?,
    val entityId: String?,
    val experienceVersionId: String?,
    val policy: Policy?,
    val timeoutMs: Int?,
) {
    enum class Decision { ALLOW, DENY, SHOW_EXPERIENCE, REQUIRE_FEATURE }
    enum class Policy { HARD, SOFT, CACHE_ONLY }

    companion object {
        /** Parse `payload.gate` from an /event response body; null when absent. */
        fun fromEventResponse(body: JsonObject): GatePlan? {
            val payload = body["payload"] as? JsonObject ?: return null
            val gate = payload["gate"] as? JsonObject ?: return null
            val decision = when (gate.string("decision")) {
                "allow" -> Decision.ALLOW
                "deny" -> Decision.DENY
                "show_flow" -> Decision.SHOW_EXPERIENCE
                "require_feature" -> Decision.REQUIRE_FEATURE
                else -> return null
            }
            return GatePlan(
                decision = decision,
                featureId = gate.string("featureId"),
                requiredBalance = (gate["requiredBalance"] as? JsonPrimitive)?.doubleOrNull,
                entityId = gate.string("entityId"),
                experienceVersionId = gate.string("flowId"),
                policy = when (gate.string("policy")) {
                    "hard" -> Policy.HARD
                    "soft" -> Policy.SOFT
                    "cache_only" -> Policy.CACHE_ONLY
                    else -> null
                },
                timeoutMs = (gate["timeoutMs"] as? JsonPrimitive)?.intOrNull,
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
