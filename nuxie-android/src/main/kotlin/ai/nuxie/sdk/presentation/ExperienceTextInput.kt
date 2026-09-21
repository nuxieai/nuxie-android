package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
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
    val responseCapture: ResponseCapture = ResponseCapture.TEXT,
    val editableValueName: String? = null,
    val viewNodeId: String = id,
    val actionEvent: ExperienceSemanticTextDraft.EventKind = ExperienceSemanticTextDraft.EventKind.EDITING_ENDED,
    val declarativeActionId: String? = null,
) {
    enum class ResponseCapture { TEXT, BINDING }

    /** Native bindings own conversion; a missing source is not a raw-text fallback. */
    fun captureResponse(text: String, snapshot: NuxieViewModelSnapshot?): JsonPrimitive {
        if (responseCapture == ResponseCapture.TEXT) return JsonPrimitive(text)
        val field = checkNotNull(responseField) { "Converted input $id has no response field" }
        return when (val value = snapshot?.resolveScalar(listOf("response", "values", field))) {
            is NuxieViewModelScalarValue.StringValue -> JsonPrimitive(value.value)
            is NuxieViewModelScalarValue.NumberValue -> JsonPrimitive(value.value)
            is NuxieViewModelScalarValue.BooleanValue -> JsonPrimitive(value.value)
            else -> error("Converted input $id has no valid evaluated response source")
        }
    }

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

    data class EffectiveMetrics(val fontSize: Float, val lineHeight: Float)

    fun effectiveMetrics(snapshot: NuxieViewModelSnapshot): EffectiveMetrics? {
        val authored = EffectiveMetrics(style.fontSize, style.lineHeight)
        val parent = geometryPaths["xPath"]?.substringBeforeLast('/', "").orEmpty()
        val components = parent.split('/').filter(String::isNotEmpty)
        // Only the publisher-owned field object defines these outputs. An unrelated
        // authored property named fontSize must not alter a legacy native control.
        if (components.size !in 2..3 || components[components.size - 2] != "nuxieTextInputs") return authored
        val sizePath = "$parent/fontSize"
        val heightPath = "$parent/lineHeight"
        val hasSize = snapshot.hasGeometryValue(sizePath)
        val hasHeight = snapshot.hasGeometryValue(heightPath)
        if (!hasSize && !hasHeight) return authored
        if (!hasSize || !hasHeight) return null
        val size = snapshot.resolveGeometryNumber(sizePath)?.takeIf { it > 0f } ?: return null
        val height = snapshot.resolveGeometryNumber(heightPath)?.takeIf { it == -1f || it > 0f } ?: return null
        return EffectiveMetrics(size, height)
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
                    runName = input.text("textRunName"),
                    editableValueName = input.optionalText("editableValueName"),
                    viewNodeId = input.optionalText("viewNodeId") ?: input.text("id"),
                    actionEvent = when (input.optionalText("actionEvent")) {
                        null, "editing-ended" -> ExperienceSemanticTextDraft.EventKind.EDITING_ENDED
                        "return" -> ExperienceSemanticTextDraft.EventKind.RETURN
                        else -> error("Unsupported input action event")
                    },
                    declarativeActionId = input.optionalText("declarativeActionId"),
                    value = input.text("value"),
                    responseField = input.optionalText("responseFieldKey"),
                    responseCapture = when (input.optionalText("responseCapture")) {
                        null, "text" -> ResponseCapture.TEXT
                        "binding" -> ResponseCapture.BINDING
                        else -> error("Unsupported text input response capture")
                    },
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
                        fontAssetName = style.text("fontAssetUniqueName"),
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
