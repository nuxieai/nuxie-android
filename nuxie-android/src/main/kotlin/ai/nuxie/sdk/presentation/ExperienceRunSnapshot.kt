package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelWrite
import ai.nuxie.sdk.runtime.NuxieViewModelMutationKind
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import kotlinx.serialization.json.*

/** A timed wait's durable values, without process-local native identities. */
internal data class ExperienceRunSnapshot(val fields: JsonArray, val lists: ExperienceRunListSnapshot? = null) {
    fun writes(): List<NativeViewModelWrite> = fields.map { entry ->
        val field = entry.jsonObject
        val path = field.getValue("path").jsonPrimitive.content
        val value = field.getValue("value").jsonPrimitive
        when (NuxieViewModelPropertyKind.fromNativeValue(field.getValue("kind").jsonPrimitive.int)) {
            NuxieViewModelPropertyKind.STRING -> NativeViewModelWrite(NuxieViewModelMutationKind.SET_STRING, path,
                bytesValue = value.content.encodeToByteArray())
            NuxieViewModelPropertyKind.NUMBER -> NativeViewModelWrite(NuxieViewModelMutationKind.SET_NUMBER, path,
                numberValue = value.float)
            NuxieViewModelPropertyKind.BOOLEAN -> NativeViewModelWrite(NuxieViewModelMutationKind.SET_BOOLEAN, path,
                boolValue = value.boolean)
            NuxieViewModelPropertyKind.COLOR -> NativeViewModelWrite(NuxieViewModelMutationKind.SET_COLOR, path,
                integerValue = value.long)
            NuxieViewModelPropertyKind.ENUM -> NativeViewModelWrite(NuxieViewModelMutationKind.SET_ENUM, path,
                integerValue = value.long)
            NuxieViewModelPropertyKind.LIST_INDEX -> NativeViewModelWrite(NuxieViewModelMutationKind.SET_LIST_INDEX, path,
                integerValue = value.long)
            NuxieViewModelPropertyKind.IMAGE -> NativeViewModelWrite(NuxieViewModelMutationKind.SET_IMAGE, path,
                integerValue = value.long)
            else -> error("Invalid run snapshot field")
        }
    }

    val journeyValues: JsonObject get() = JsonObject(fields.associate { entry ->
        val field = entry.jsonObject
        field.getValue("path").jsonPrimitive.content to field.getValue("value")
    } + lists?.journeyLists.orEmpty())

    companion object {
        fun capture(snapshot: NativeViewModelSnapshot,
            origins: Map<Long, List<ExperienceRunListSnapshot.OriginStep>> = emptyMap(),
            authoredIds: Set<Long> = emptySet()): ExperienceRunSnapshot = ExperienceRunSnapshot(
                captureFields(snapshot, snapshot.rootInstanceId),
                ExperienceRunListSnapshot.capture(snapshot, origins, authoredIds))

        fun captureFields(snapshot: NativeViewModelSnapshot, root: Long): JsonArray {
            val values = snapshot.values.groupBy { it.ownerInstanceId }
            val fields = mutableListOf<JsonElement>()
            fun visit(id: Long, prefix: String, ancestors: Set<Long>) {
                if (id in ancestors) return
                for (entry in values[id].orEmpty()) {
                    val path = prefix + entry.name
                    val value: JsonPrimitive = when (NuxieViewModelPropertyKind.fromNativeValue(entry.kind)) {
                        NuxieViewModelPropertyKind.VIEW_MODEL -> {
                            visit(entry.referencedInstanceId, "$path/", ancestors + id)
                            continue
                        }
                        NuxieViewModelPropertyKind.STRING -> JsonPrimitive(entry.bytesValue.decodeToString(throwOnInvalidSequence = true))
                        NuxieViewModelPropertyKind.NUMBER -> JsonPrimitive(entry.numberValue)
                        NuxieViewModelPropertyKind.BOOLEAN -> JsonPrimitive(entry.boolValue)
                        NuxieViewModelPropertyKind.COLOR, NuxieViewModelPropertyKind.ENUM,
                        NuxieViewModelPropertyKind.LIST_INDEX, NuxieViewModelPropertyKind.IMAGE -> JsonPrimitive(entry.integerValue)
                        else -> continue
                    }
                    fields += buildJsonObject { put("path", path); put("kind", entry.kind); put("value", value) }
                }
            }
            visit(root, "", emptySet())
            return JsonArray(fields)
        }
    }
}
