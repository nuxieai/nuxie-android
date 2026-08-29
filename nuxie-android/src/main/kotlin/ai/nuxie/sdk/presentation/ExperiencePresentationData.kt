package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieBoundViewModel
import ai.nuxie.sdk.runtime.NuxieRuntimeViewModels
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Applies the signed Journey's initial values to the selected Experience screen. */
internal object ExperiencePresentationData {
    suspend fun apply(
        descriptor: JsonObject,
        viewModels: NuxieRuntimeViewModels,
    ): NuxieBoundViewModel? {
        val selection = descriptor.selectedScreenData() ?: return null
        if (selection.values.isEmpty()) return null

        val catalog = viewModels.viewModelCatalog()
        val rootSchema = catalog.schemas.singleOrNull { it.name == selection.viewModelName }
            ?: error(
                "Experience screen '${selection.screenId}' requests unavailable view model " +
                    "'${selection.viewModelName}'",
            )
        val bound = viewModels.bindDefaultViewModelToPlayer(catalog, rootSchema.index)
        try {
            selection.values.forEach { value ->
                val property = catalog.propertyAtPath(rootSchema.index, value.path)
                when (property.kind) {
                    NuxieViewModelPropertyKind.STRING ->
                        bound.setString(value.path, value.value.requireString(value.path))
                    NuxieViewModelPropertyKind.NUMBER ->
                        bound.setNumber(value.path, value.value.requireNumber(value.path))
                    NuxieViewModelPropertyKind.BOOLEAN ->
                        bound.setBoolean(value.path, value.value.requireBoolean(value.path))
                    NuxieViewModelPropertyKind.COLOR ->
                        bound.setColor(value.path, value.value.requireColor(value.path))
                    NuxieViewModelPropertyKind.ENUM ->
                        bound.setEnum(value.path, value.value.requireString(value.path))
                    else -> error(
                        "Experience initial value '${value.path}' has unsupported kind " +
                            property.kind,
                    )
                }
            }
            return bound
        } catch (error: Throwable) {
            runCatching { bound.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private data class ScreenData(
        val screenId: String,
        val viewModelName: String,
        val values: List<InitialValue>,
    )

    private data class InitialValue(val path: String, val value: JsonElement)

    private fun JsonObject.selectedScreenData(): ScreenData? {
        val render = this["render"] as? JsonObject ?: return null
        val renderScreen = (render["screens"] as? JsonArray)
            ?.firstOrNull() as? JsonObject ?: return null
        val screenId = renderScreen.string("id") ?: return null
        val journey = this["journey"] as? JsonObject ?: return null
        val screen = (journey["screens"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.singleOrNull { it.string("id") == screenId }
            ?: return null
        val viewModelName = screen.string("defaultViewModelName") ?: return null
        val instanceId = screen.string("defaultInstanceId")
        val values = (journey["viewModelValues"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { value ->
                value.string("viewModelName") == viewModelName &&
                    if (instanceId == null) {
                        value["instanceId"] == null
                    } else {
                        value.string("instanceId") == instanceId
                    }
            }
            ?.map { value ->
                InitialValue(
                    path = requireNotNull(value.string("path")) {
                        "Experience initial view-model value has no path"
                    },
                    value = requireNotNull(value["value"]) {
                        "Experience initial view-model value has no value"
                    },
                )
            }
            .orEmpty()
        return ScreenData(screenId, viewModelName, values)
    }

    private fun JsonElement.requireString(path: String): String =
        (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Experience initial value '$path' must be a string")

    private fun JsonElement.requireNumber(path: String): Double =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull
            ?: error("Experience initial value '$path' must be a number")

    private fun JsonElement.requireBoolean(path: String): Boolean =
        (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
            ?: error("Experience initial value '$path' must be a boolean")

    private fun JsonElement.requireColor(path: String): Int {
        val primitive = this as? JsonPrimitive
            ?: error("Experience initial value '$path' must be a color")
        if (!primitive.isString) {
            return primitive.doubleOrNull
                ?.takeIf { it == Math.floor(it) && it >= 0.0 && it <= UInt.MAX_VALUE.toDouble() }
                ?.toLong()?.toInt()
                ?: error("Experience initial value '$path' must be an ARGB color")
        }
        val hex = primitive.content.removePrefix("#")
        return runCatching {
            when (hex.length) {
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                8 -> {
                    val rgba = hex.toLong(16)
                    (((rgba and 0xFF) shl 24) or (rgba ushr 8)).toInt()
                }
                else -> error("invalid color")
            }
        }.getOrElse { error("Experience initial value '$path' must be an RGB or RGBA color") }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
}
