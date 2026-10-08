package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NuxieHostCommand
import ai.nuxie.sdk.runtime.NuxieHostValue
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Immutable answers from the native frame that requested the save. */
internal data class ExperienceResponseSaveRequest(val form: String, val awaitTrigger: String?, val answers: JsonObject) {
    companion object {
        const val EVENT = "\$nuxie.response.save"

        fun capture(event: NuxieRuntimeEvent, snapshot: NativeViewModelSnapshot,
            catalog: NuxieViewModelCatalog, descriptor: JsonObject): ExperienceResponseSaveRequest? {
            if (event.name != EVENT) return null
            require(event.url.isEmpty() && (event.sourceViewModelInstanceId == 0L ||
                snapshot.instances.any { it.id == event.sourceViewModelInstanceId })) { "Invalid native response save request" }
            val fields = event.properties.map { field ->
                field.name to if (field.name in setOf("form", "awaitTrigger")) {
                    (field.value as? NuxieRuntimeEventPropertyValue.Bytes)?.value?.decodeToString(throwOnInvalidSequence = true)
                } else null
            }
            return capture(fields, snapshot, catalog, descriptor)
        }

        fun capture(command: NuxieHostCommand, snapshot: NativeViewModelSnapshot,
            catalog: NuxieViewModelCatalog, descriptor: JsonObject): ExperienceResponseSaveRequest? {
            if (command.name != EVENT) return null
            val objectValue = requireNotNull(command.value as? NuxieHostValue.Object) { "Invalid native response save request" }
            return capture(objectValue.fields.map { it.key to (it.value as? NuxieHostValue.String)?.value },
                snapshot, catalog, descriptor)
        }

        private fun capture(fields: List<Pair<String, String?>>, snapshot: NativeViewModelSnapshot,
            catalog: NuxieViewModelCatalog, descriptor: JsonObject): ExperienceResponseSaveRequest {
            require(fields.map { it.first }.distinct().size == fields.size) { "Invalid native response save request" }
            val properties = fields.toMap()
            val form = requireNotNull(properties["form"]) { "Invalid native response save request" }
            val declaration = requireNotNull(descriptor["responses"]?.jsonObject?.get(form)?.jsonObject) {
                "Invalid native response save request"
            }
            val trigger = if (properties.containsKey("awaitTrigger")) {
                requireNotNull(properties["awaitTrigger"]?.takeIf { it.isNotEmpty() }) { "Invalid native response save request" }
            } else null
            val shared = snapshot.values.firstOrNull { it.ownerInstanceId == snapshot.rootInstanceId &&
                it.name == "experience" && it.kind == NuxieViewModelPropertyKind.VIEW_MODEL.nativeValue }?.referencedInstanceId
            val answers = ExperienceResponseSheet.read(form, declaration,
                if (shared == null) snapshot else snapshot.copy(rootInstanceId = shared), catalog)
            return ExperienceResponseSaveRequest(form, trigger, answers)
        }
    }
}
