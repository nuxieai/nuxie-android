package ai.nuxie.sdk.experiences

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Selects the signed Journey declaration, never a schema inferred from the native file. */
internal object ExperienceViewModelBinding {
    fun defaultSchemaName(descriptor: JsonObject, artboardName: String?): String? {
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
        val journeyScreen = requireNotNull(screens(descriptor, "journey").singleOrNull {
            it.requiredString("id") == screenId
        }) { "Experience Journey has no screen '$screenId'" }

        // iOS opens a bound session only when this field is present. A declared
        // default that native code cannot create/bind must fail at the caller;
        // absence does not authorize activating another schema from the file.
        if (!journeyScreen.containsKey("defaultViewModelName")) return null
        return journeyScreen.requiredString("defaultViewModelName")
    }

    private fun screens(descriptor: JsonObject, section: String): List<JsonObject> {
        val owner = requireNotNull(descriptor[section] as? JsonObject) {
            "Experience release $section is missing"
        }
        val values = requireNotNull(owner["screens"] as? JsonArray) {
            "Experience release $section.screens is missing"
        }
        val records = values.mapIndexed { index, value ->
            requireNotNull(value as? JsonObject) {
                "Experience release $section.screens[$index] is invalid"
            }
        }
        val ids = records.map { it.requiredString("id") }
        require(ids.distinct().size == ids.size) {
            "Experience release $section.screens contains duplicate identities"
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
        ) { "Experience release $key is invalid" }
        return value
    }
}
