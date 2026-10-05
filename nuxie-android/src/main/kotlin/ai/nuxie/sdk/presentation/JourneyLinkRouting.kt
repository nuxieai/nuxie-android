package ai.nuxie.sdk.presentation

import java.net.URI

/** One destination policy for runtime links and Journey open-link steps. */
internal object JourneyLinkRouting {
    fun destination(url: String, target: String?): String? {
        if (url.any(Char::isWhitespace)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val web = scheme == "http" || scheme == "https"
        if (web && uri.host.isNullOrEmpty()) return null
        return if (web && (target?.lowercase() ?: "_self") in setOf("", "_self", "_parent", "_top", "in_app"))
            "in_app" else "external"
    }

    fun open(url: String, target: String?, inApp: (String) -> Boolean, external: (String) -> Boolean): Boolean =
        when (destination(url, target)) {
            "in_app" -> inApp(url)
            "external" -> external(url)
            else -> false
        }
}
