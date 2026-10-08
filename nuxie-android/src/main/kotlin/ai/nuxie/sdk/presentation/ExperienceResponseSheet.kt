package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue
import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import kotlinx.serialization.json.*

/** Reads native answers once at save time; marking errors never filter a sheet. */
internal object ExperienceResponseSheet {
    fun read(form: String, declaration: JsonObject, snapshot: NativeViewModelSnapshot,
        catalog: NuxieViewModelCatalog): JsonObject {
        val values = snapshot.values.groupBy { it.ownerInstanceId }
        fun value(owner: Long, name: String): NativeViewModelSnapshotValue =
            requireNotNull(values[owner]?.firstOrNull { it.name == name }) { "Native response field is unavailable" }
        fun requireKind(value: NativeViewModelSnapshotValue, kind: NuxieViewModelPropertyKind) {
            require(value.kind == kind.nativeValue) { "Native response kind does not match its release" }
        }
        val link = value(snapshot.rootInstanceId, "responses:$form")
        requireKind(link, NuxieViewModelPropertyKind.VIEW_MODEL)
        val owner = link.referencedInstanceId
        val instance = requireNotNull(snapshot.instances.firstOrNull { it.id == owner })
        require(catalog.schemas.any { it.index.toLong() == instance.schemaIndex &&
            it.name == declaration.getValue("model").jsonPrimitive.content }) { "Native response model does not match its release" }
        val answers = linkedMapOf<String, JsonElement>()
        for (rawField in declaration.getValue("fields").jsonArray) {
            val field = rawField.jsonObject
            val key = field.getValue("key").jsonPrimitive.content
            val type = field.getValue("type").jsonPrimitive.content
            val multiple = field["multiple"]?.jsonPrimitive?.boolean == true
            val raw = value(owner, key)
            if (type in listOf("number", "boolean") || type == "enum" && !multiple) {
                val marker = value(owner, "isset:$key")
                requireKind(marker, NuxieViewModelPropertyKind.BOOLEAN)
                if (!marker.boolValue) continue
            }
            val answer = when (type) {
                "number" -> {
                    requireKind(raw, NuxieViewModelPropertyKind.NUMBER)
                    require(raw.numberValue.isFinite()) { "Native response number is not finite" }
                    JsonPrimitive(raw.numberValue.toDouble())
                }
                "boolean" -> {
                    requireKind(raw, NuxieViewModelPropertyKind.BOOLEAN)
                    JsonPrimitive(raw.boolValue)
                }
                "string", "date" -> {
                    requireKind(raw, NuxieViewModelPropertyKind.STRING)
                    val text = raw.bytesValue.decodeToString(throwOnInvalidSequence = true)
                    if (text.isEmpty()) continue
                    JsonPrimitive(text)
                }
                "enum" -> if (multiple) {
                    requireKind(raw, NuxieViewModelPropertyKind.LIST)
                    val picks = raw.listItemIds.asSequence().mapNotNull { child ->
                        val picked = value(child, "picked")
                        requireKind(picked, NuxieViewModelPropertyKind.BOOLEAN)
                        if (!picked.boolValue) null else {
                            val option = value(child, "value")
                            requireKind(option, NuxieViewModelPropertyKind.STRING)
                            JsonPrimitive(option.bytesValue.decodeToString(throwOnInvalidSequence = true))
                        }
                    }
                    .toList()
                    if (picks.isEmpty()) continue
                    JsonArray(picks)
                } else {
                    requireKind(raw, NuxieViewModelPropertyKind.ENUM)
                    val property = catalog.properties.single { it.schemaIndex.toLong() == instance.schemaIndex && it.name == key }
                    require(raw.integerValue >= 0 && raw.integerValue < property.enumLabels.size.toLong()) { "Native response option is unavailable" }
                    JsonPrimitive(property.enumLabels[raw.integerValue.toInt()])
                }
                else -> error("Native response field kind is unsupported")
            }
            answers[key] = answer
        }
        return JsonObject(answers)
    }
}
