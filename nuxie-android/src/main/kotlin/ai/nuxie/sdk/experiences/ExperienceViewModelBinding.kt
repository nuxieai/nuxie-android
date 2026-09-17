package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.runtime.NuxieViewModelInstanceBinding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Selects the signed Journey declaration, never a schema inferred from the native file. */
internal object ExperienceViewModelBinding {
    fun defaultSchemaName(descriptor: JsonObject, artboardName: String?): String? =
        declaration(descriptor, artboardName).let { screen ->
            if (screen.containsKey("defaultViewModelName")) screen.requiredString("defaultViewModelName") else null
        }

    fun defaultInstanceId(descriptor: JsonObject, artboardName: String?): String? =
        declaration(descriptor, artboardName).let { screen ->
            if (screen.containsKey("defaultInstanceId")) screen.requiredString("defaultInstanceId") else null
        }

    fun instanceBindings(descriptor: JsonObject): List<NuxieViewModelInstanceBinding> {
        val values = (descriptor["viewModelValues"] as? JsonArray).orEmpty().map {
            requireNotNull(it as? JsonObject) { "Signed view-model value is invalid" }
        }
        val models = mutableMapOf<String, String>()
        values.forEach { value ->
            if (value.containsKey("instanceId")) {
                val id = value.requiredString("instanceId")
                val name = value.requiredString("viewModelName")
                require(models.putIfAbsent(id, name).let { it == null || it == name }) {
                    "Signed instance identity names multiple models"
                }
            }
        }
        return values.mapNotNull { value ->
            val path = value.requiredString("path", 4096)
            val envelope = value["value"] as? JsonObject
            val suffix = listOf("/vmInstanceId", "/instanceId").firstOrNull(path::endsWith)
            val target = when {
                suffix != null -> value.requiredString("value")
                envelope?.containsKey("vmInstanceId") == true -> envelope.requiredString("vmInstanceId")
                envelope?.containsKey("instanceId") == true -> envelope.requiredString("instanceId")
                else -> return@mapNotNull null
            }
            if (envelope?.containsKey("vmInstanceId") == true && envelope.containsKey("instanceId")) {
                require(envelope.requiredString("instanceId") == target) { "Signed reference identities conflict" }
            }
            val explicitModel = if (envelope?.containsKey("viewModelId") == true)
                envelope.requiredString("viewModelId") else null
            val declaredModel = models[target]
            require(explicitModel == null || declaredModel == null || explicitModel == declaredModel) {
                "Signed referenced instance model declarations conflict"
            }
            NuxieViewModelInstanceBinding(
                ownerModelName = value.requiredString("viewModelName"),
                ownerInstanceId = if (value.containsKey("instanceId")) value.requiredString("instanceId") else null,
                path = if (suffix == null) path else path.removeSuffix(suffix),
                instanceId = target,
                modelName = explicitModel ?: declaredModel,
            )
        }
    }

    private fun declaration(descriptor: JsonObject, artboardName: String?): JsonObject {
        val renderScreens = screens(descriptor, "render")
        renderScreens.forEach { it.requiredString("artboardName", 256, rejectNul = false) }
        val renderScreen = if (artboardName == null) {
            requireNotNull(renderScreens.singleOrNull()) {
                "Experience default artboard requires exactly one render screen"
            }
        } else {
            requireNotNull(renderScreens.singleOrNull {
                it.requiredString("artboardName", 256, rejectNul = false) == artboardName
            }) { "Experience artboard '$artboardName' must identify exactly one render screen" }
        }
        val screenId = renderScreen.requiredString("id")
        val journeyScreen = requireNotNull(screens(descriptor, "leg").singleOrNull {
            it.requiredString("id") == screenId
        }) { "Experience Journey has no screen '$screenId'" }

        return journeyScreen
    }

    private fun screens(descriptor: JsonObject, section: String): List<JsonObject> {
        val owner = requireNotNull(descriptor[section] as? JsonObject) {
            "Journey release $section is missing"
        }
        val values = requireNotNull(owner["screens"] as? JsonArray) {
            "Journey release $section.screens is missing"
        }
        val records = values.mapIndexed { index, value ->
            requireNotNull(value as? JsonObject) {
                "Journey release $section.screens[$index] is invalid"
            }
        }
        val ids = records.map { it.requiredString("id") }
        require(ids.distinct().size == ids.size) {
            "Journey release $section.screens contains duplicate identities"
        }
        return records
    }

    private fun JsonObject.requiredString(
        key: String,
        maximumUtf16: Int = 128,
        rejectNul: Boolean = true,
    ): String {
        val value = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Match the iOS descriptor's identifier/bounded-string rules; do not
        // trim names or reinterpret defaultInstanceId as an instance selector.
        require(value != null && value.isNotEmpty() && value.length <= maximumUtf16 &&
            (!rejectNul || '\u0000' !in value)
        ) { "Journey release $key is invalid" }
        return value
    }
}
