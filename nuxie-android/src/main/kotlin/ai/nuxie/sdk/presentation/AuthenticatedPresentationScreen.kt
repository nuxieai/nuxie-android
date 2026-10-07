package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shell and screen geometry resolved from authenticated metadata without acquiring render bytes. */
internal class AuthenticatedPresentationScreen private constructor(
    val screenId: String,
    val artboardName: String,
    val artboardSize: ExperienceArtboardSize?,
    val clearColor: Int,
    val shell: PresentationShell,
) {
    companion object {
        fun resolve(release: AuthenticatedJourneyRelease, screenId: String, transition: JsonObject? = null): AuthenticatedPresentationScreen {
            val descriptor = release.descriptor
            val artboardName = descriptor.artboardName(screenId)
                ?: throw ExperiencePresentationException(
                    ExperiencePresentationException.Reason.PREPARATION_FAILED,
                    "Authenticated journey screen is not renderable: $screenId",
                )
            return AuthenticatedPresentationScreen(screenId, artboardName, descriptor.artboardSize(screenId),
                descriptor.presentationClearColor(), PresentationShell.fromTransition(transition))
        }
    }
}

private fun JsonObject.artboardName(screenId: String): String? {
    val render = this["render"] as? JsonObject ?: return null
    val screen = (render["screens"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.singleOrNull { it.string("id") == screenId }
        ?: return null
    return (screen["artboardName"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
}

private fun JsonObject.artboardSize(screenId: String): ExperienceArtboardSize? {
    val render = this["render"] as? JsonObject ?: return null
    val screen = (render["screens"] as? JsonArray)
        ?.filterIsInstance<JsonObject>()
        ?.singleOrNull { it.string("id") == screenId }
        ?: return null
    return screen.artboardSize()
}

private fun JsonObject.artboardSize(): ExperienceArtboardSize? {
    val width = float("width") ?: return null
    val height = float("height") ?: return null
    return runCatching { ExperienceArtboardSize(width, height) }.getOrNull()
}

private fun JsonObject.presentationClearColor(): Int {
    val presentation = this["presentation"] as? JsonObject ?: return OPAQUE_BLACK
    val value = (presentation["backgroundColor"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: return OPAQUE_BLACK
    val hex = value.removePrefix("#")
    return runCatching {
        when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> {
                val rgba = hex.toLong(16)
                ((rgba and 0xFF) shl 24 or (rgba ushr 8)).toInt()
            }
            else -> OPAQUE_BLACK
        }
    }.getOrDefault(OPAQUE_BLACK)
}

private fun JsonObject.float(key: String): Float? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toFloatOrNull()

private const val OPAQUE_BLACK = 0xFF000000.toInt()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
