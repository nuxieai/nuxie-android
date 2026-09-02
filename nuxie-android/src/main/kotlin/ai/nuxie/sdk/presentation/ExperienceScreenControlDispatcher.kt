package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves renderer-generated control envelopes only through the authenticated
 * declarative controls signed for the current screen.
 */
internal class ExperienceScreenControlDispatcher(
    descriptor: JsonObject?,
    screenId: String?,
) {
    private val emitProgramsByActionId = signedEmitPrograms(descriptor, screenId)

    fun dispatch(event: NuxieRuntimeEvent): List<NuxieRuntimeEvent> {
        if (event.name != GENERATED_INTERACTION_EVENT) return listOf(event)
        val actionId = generatedActionId(event) ?: return emptyList()
        val eventNames = emitProgramsByActionId[actionId] ?: return emptyList()
        return eventNames.map { eventName ->
            event.copy(
                coreType = 0,
                name = eventName,
                url = "",
                target = "",
                properties = emptyList(),
            )
        }
    }

    private fun generatedActionId(event: NuxieRuntimeEvent): String? {
        if (event.coreType != GENERATED_INTERACTION_CORE_TYPE ||
            event.url.isNotEmpty() ||
            event.target.isNotEmpty()
        ) {
            return null
        }
        val propertyNames = event.properties.map { it.name }
        if (propertyNames.toSet().size != propertyNames.size) return null
        val properties = event.properties.associate { it.name to it.value }
        for (name in REQUIRED_GENERATED_IDENTITIES) {
            if (properties[name].identityString()?.isBlank() != false) return null
        }
        if ("instanceId" in properties && properties["instanceId"].identityString()?.isBlank() != false) {
            return null
        }
        return properties.getValue("actionId").identityString()
    }

    private fun NuxieRuntimeEventPropertyValue?.identityString(): String? {
        val bytes = (this as? NuxieRuntimeEventPropertyValue.Bytes)?.value ?: return null
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private companion object {
        const val GENERATED_INTERACTION_EVENT = "Nuxie Interaction"
        const val GENERATED_INTERACTION_CORE_TYPE = 128
        val REQUIRED_GENERATED_IDENTITIES = listOf("nuxieTrigger", "actionId", "componentId")

        fun signedEmitPrograms(
            descriptor: JsonObject?,
            screenId: String?,
        ): Map<String, List<String>> {
            if (descriptor == null || screenId == null) return emptyMap()
            val screenBehaviors = descriptor["screenBehaviors"] as? JsonArray ?: return emptyMap()
            val matching = screenBehaviors.mapNotNull { it as? JsonObject }
                .filter { it.string("screenId") == screenId }
            if (matching.size != 1) return emptyMap()
            val controls = matching.single()["controls"] as? JsonArray ?: return emptyMap()
            val seenActionIds = mutableSetOf<String>()
            val programs = linkedMapOf<String, List<String>>()
            for (element in controls) {
                val control = element as? JsonObject ?: return emptyMap()
                val actionId = control.string("actionId")
                    ?.takeIf { it.isNotBlank() }
                    ?: return emptyMap()
                if (!seenActionIds.add(actionId)) return emptyMap()
                parseEmitProgram(control["behavior"] as? JsonObject)?.let { program ->
                    programs[actionId] = program
                }
            }
            return programs
        }

        fun parseEmitProgram(behavior: JsonObject?): List<String>? {
            if (behavior?.string("kind") != "declarative") return null
            val program = behavior["program"] as? JsonArray ?: return null
            if (program.isEmpty()) return null
            return buildList {
                for (element in program) {
                    val action = element as? JsonObject ?: return null
                    if (action.string("type") != "emit") return null
                    val eventName = action.string("eventName")
                        ?.takeIf { it.isNotBlank() && !it.startsWith('$') }
                        ?: return null
                    val payload = action["payload"]
                    if (payload != null && (payload !is JsonObject || payload.isNotEmpty())) return null
                    add(eventName)
                }
            }
        }

        fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
