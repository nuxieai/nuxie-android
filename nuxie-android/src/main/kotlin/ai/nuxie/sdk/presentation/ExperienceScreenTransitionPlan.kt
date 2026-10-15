package ai.nuxie.sdk.presentation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long

/** Resolves navigation intent against declarations from an already verified release. */
internal data class ExperienceScreenTransitionPlan(
    val kind: Kind,
    val custom: Custom? = null,
) {
    enum class Kind { NONE, PUSH, MODAL, FADE, CUSTOM }

    data class Custom(
        val id: String,
        val durationMs: Long,
        val incomingOnTop: Boolean,
        val outgoingCompletionEvent: String,
        val incomingCompletionEvent: String,
    ) {
        val watchdogMs: Long get() = durationMs + 250
    }

    fun shouldAnimate(reduceMotion: Boolean): Boolean = kind != Kind.NONE && !reduceMotion

    companion object {
        fun resolve(
            intent: JsonObject?,
            declarations: JsonArray,
            sourceScreenId: String,
            destinationScreenId: String,
        ): ExperienceScreenTransitionPlan {
            val kind = when (intent?.string("type")?.trim()?.lowercase(java.util.Locale.ROOT)) {
                "push" -> Kind.PUSH
                "modal" -> Kind.MODAL
                "fade" -> Kind.FADE
                "custom" -> Kind.CUSTOM
                else -> Kind.NONE
            }
            if (kind != Kind.CUSTOM) return ExperienceScreenTransitionPlan(kind)
            // Preserve the authored identifier; trimming is only an emptiness check.
            val id = intent?.string("transitionId")?.takeIf { it.isNotBlank() }
                ?: return ExperienceScreenTransitionPlan(Kind.NONE)
            val declaration = declarations.filterIsInstance<JsonObject>().firstOrNull { it.string("id") == id }
                ?: return ExperienceScreenTransitionPlan(Kind.NONE)
            val forward = declaration.string("sourceScreenId") == sourceScreenId &&
                declaration.string("destinationScreenId") == destinationScreenId
            val reverse = declaration.string("destinationScreenId") == sourceScreenId &&
                declaration.string("sourceScreenId") == destinationScreenId
            val edge = when {
                forward -> declaration
                reverse -> declaration["reverse"] as? JsonObject
                else -> null
            } ?: return ExperienceScreenTransitionPlan(Kind.NONE)
            val duration = ((edge["durationMs"] ?: declaration.getValue("durationMs")) as JsonPrimitive).long
            val incomingOnTop = ((edge["incomingOnTop"] ?: declaration.getValue("incomingOnTop")) as JsonPrimitive).boolean
            return ExperienceScreenTransitionPlan(Kind.CUSTOM, Custom(
                id = id,
                durationMs = duration.coerceAtLeast(0),
                incomingOnTop = incomingOnTop,
                outgoingCompletionEvent = (edge.getValue("source") as JsonObject).string("completeEventName")!!,
                incomingCompletionEvent = (edge.getValue("destination") as JsonObject).string("completeEventName")!!,
            ))
        }

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
