package ai.nuxie.sdk.presentation

import android.net.Uri
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent

internal data class JourneyOpenedLink(val url: String, val target: String?, val screenId: String?, val instanceId: String? = null, val effectId: String? = null, val destination: String = "external")

/** One parsed destination for runtime links and Journey open-link steps. */
internal object JourneyLinkRouting {
    sealed interface Destination {
        val uri: Uri
        data class InApp(override val uri: Uri) : Destination
        data class External(override val uri: Uri) : Destination
    }

    fun destination(url: String, target: String?): Destination? {
        if (url.any(Char::isWhitespace)) return null
        val uri = Uri.parse(url).normalizeScheme()
        val scheme = uri.scheme?.lowercase() ?: return null
        if (!scheme.matches(Regex("[a-z][a-z0-9+.-]*"))) return null
        val web = scheme == "http" || scheme == "https"
        if (web && uri.host.isNullOrEmpty()) return null
        return if (web && (target?.lowercase() ?: "_self") in setOf("", "_self", "_parent", "_top", "in_app"))
            Destination.InApp(uri) else Destination.External(uri)
    }

}

internal fun openActivityLink(context: Context, destination: JourneyLinkRouting.Destination, activity: Activity?): Boolean =
    runCatching {
        val owner = activity ?: context.applicationContext ?: context
        when (destination) {
            is JourneyLinkRouting.Destination.InApp -> {
                val tab = CustomTabsIntent.Builder().build()
                if (activity == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                tab.launchUrl(owner, destination.uri)
            }
            is JourneyLinkRouting.Destination.External -> {
                val intent = Intent(Intent.ACTION_VIEW, destination.uri)
                if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                owner.startActivity(intent)
            }
        }
        true
    }.getOrDefault(false)
