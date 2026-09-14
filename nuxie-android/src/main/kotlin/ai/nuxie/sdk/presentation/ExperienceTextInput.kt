package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

/** An editable control selected from the authenticated render descriptor. */
internal data class ExperienceTextInput(
    val id: String,
    val runName: String,
    val value: String,
    val responseField: String?,
    val placeholder: String?,
    val keyboardType: String?,
    val secure: Boolean,
    val multiline: Boolean,
    val maxLength: Int?,
    val geometryPaths: Map<String, String>,
    val style: Style,
) {
    data class Style(
        val fontFamily: String,
        val fontWeight: String,
        val italic: Boolean,
        val fontSize: Float,
        val lineHeight: Float,
        val letterSpacing: Float,
        val color: Int,
        val fontAssetName: String,
        val textAlign: String?,
    )

    data class Geometry(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val rotation: Float,
        val scaleX: Float,
        val scaleY: Float,
    )

    fun geometry(snapshot: NuxieViewModelSnapshot): Geometry? {
        fun number(key: String) = geometryPaths[key]?.let(snapshot::resolveGeometryNumber)
        return Geometry(
            number("xPath") ?: return null,
            number("yPath") ?: return null,
            number("widthPath")?.takeIf { it > 0f } ?: return null,
            number("heightPath")?.takeIf { it > 0f } ?: return null,
            number("rotationPath") ?: return null,
            number("scaleXPath") ?: return null,
            number("scaleYPath") ?: return null,
        )
    }

    companion object {
        /** Schema validation precedes this projection; ambiguous IDs are never activated. */
        fun forScreen(descriptor: JsonObject?, screenId: String?): List<ExperienceTextInput> {
            val render = descriptor?.get("render") as? JsonObject ?: return emptyList()
            val records = (render["textInputs"] as? JsonArray).orEmpty()
                .map { it as JsonObject }
                .filter { it.text("screenId") == screenId && it.primitive("editable").boolean }
            val counts = records.groupingBy { it.text("id") }.eachCount()
            return records.filter { counts[it.text("id")] == 1 }.map { input ->
                val style = input.getValue("style") as JsonObject
                ExperienceTextInput(
                    id = input.text("id"),
                    runName = input.text("riveTextRunName"),
                    value = input.text("value"),
                    responseField = input.optionalText("responseFieldKey"),
                    placeholder = input.optionalText("placeholder"),
                    keyboardType = input.optionalText("keyboardType"),
                    secure = input.primitive("secureTextEntry").boolean,
                    multiline = input.primitive("multiline").boolean,
                    maxLength = (input["maxLength"] as? JsonPrimitive)?.int,
                    geometryPaths = (input.getValue("geometry") as JsonObject)
                        .mapValues { (_, value) -> (value as JsonPrimitive).content },
                    style = Style(
                        fontFamily = style.text("fontFamily"),
                        fontWeight = style.text("fontWeight"),
                        italic = style.text("fontStyle") == "italic",
                        fontSize = style.primitive("fontSize").float,
                        lineHeight = style.primitive("lineHeight").float,
                        letterSpacing = style.primitive("letterSpacing").float,
                        color = style.primitive("color").long.toInt(),
                        fontAssetName = style.text("fontAssetRiveUniqueName"),
                        textAlign = style.optionalText("textAlign"),
                    ),
                )
            }
        }

        private fun JsonObject.primitive(key: String) = getValue(key) as JsonPrimitive
        private fun JsonObject.text(key: String) = primitive(key).content
        private fun JsonObject.optionalText(key: String) = (get(key) as? JsonPrimitive)?.content
    }
}
