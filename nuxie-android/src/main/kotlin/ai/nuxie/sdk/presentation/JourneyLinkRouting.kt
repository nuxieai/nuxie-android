package ai.nuxie.sdk.presentation

import android.net.Uri

internal data class JourneyOpenedLink(val url: String, val target: String?, val screenId: String, val instanceId: String? = null, val effectId: String? = null)

/** One parsed destination for runtime links and Journey open-link steps. */
internal object JourneyLinkRouting {
    sealed interface Destination {
        val uri: Uri
        data class InApp(override val uri: Uri) : Destination
        data class External(override val uri: Uri) : Destination
    }

    fun destination(url: String, target: String?): Destination? {
        if (url.any(Char::isWhitespace)) return null
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (!scheme.matches(Regex("[a-z][a-z0-9+.-]*"))) return null
        val web = scheme == "http" || scheme == "https"
        if (web && uri.host.isNullOrEmpty()) return null
        return if (web && (target?.lowercase() ?: "_self") in setOf("", "_self", "_parent", "_top", "in_app"))
            Destination.InApp(uri) else Destination.External(uri)
    }

    fun open(url: String, target: String?, inApp: (Uri) -> Boolean, external: (Uri) -> Boolean): Boolean =
        when (val destination = destination(url, target)) {
            is Destination.InApp -> inApp(destination.uri)
            is Destination.External -> external(destination.uri)
            null -> false
        }
}
