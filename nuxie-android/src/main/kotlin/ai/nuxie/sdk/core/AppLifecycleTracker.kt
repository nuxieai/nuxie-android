package ai.nuxie.sdk.core

import ai.nuxie.sdk.events.SystemEventNames
import android.content.SharedPreferences

/**
 * Emits the automatic lifecycle moments ($app_installed, $app_updated,
 * $app_opened, $app_backgrounded), ported from the iOS `AppLifecycleTracker`.
 * Always on — there is no public switch (audit decision); `beforeSend` is the
 * escape hatch. Property names and epoch-second values match iOS.
 */
internal class AppLifecycleTracker(
    private val preferences: SharedPreferences,
    private val appVersionProvider: () -> String,
    private val nowMillis: () -> Long,
    private val emit: (name: String, properties: Map<String, Any?>) -> Unit,
) {
    /** $app_installed on first launch, $app_updated on version change, $app_opened always. */
    fun trackAppLaunchEvents() {
        val currentVersion = appVersionProvider()
        val hasLaunchedBefore = preferences.getBoolean(HAS_LAUNCHED_BEFORE_KEY, false)
        val lastVersion = preferences.getString(LAST_VERSION_KEY, null)

        // iOS parity: the property map accumulates across the launch sequence,
        // so first-launch $app_opened also carries install_date, and
        // post-update $app_opened carries previous_version/update_date.
        val properties = linkedMapOf<String, Any?>(
            "source" to "app_lifecycle",
            "app_version" to currentVersion,
        )

        if (!hasLaunchedBefore) {
            properties["install_date"] = epochSeconds()
            emit(SystemEventNames.APP_INSTALLED, properties.toMap())
            preferences.edit()
                .putBoolean(HAS_LAUNCHED_BEFORE_KEY, true)
                .putString(LAST_VERSION_KEY, currentVersion)
                .apply()
        } else if (lastVersion != null && lastVersion != currentVersion) {
            properties["previous_version"] = lastVersion
            properties["update_date"] = epochSeconds()
            emit(SystemEventNames.APP_UPDATED, properties.toMap())
            preferences.edit().putString(LAST_VERSION_KEY, currentVersion).apply()
        }

        properties["open_date"] = epochSeconds()
        emit(SystemEventNames.APP_OPENED, properties.toMap())
    }

    fun trackAppForegrounded() {
        emit(
            SystemEventNames.APP_OPENED,
            mapOf(
                "source" to "app_lifecycle",
                "foreground_date" to epochSeconds(),
                "app_version" to appVersionProvider(),
            ),
        )
    }

    fun trackAppBackgrounded() {
        emit(
            SystemEventNames.APP_BACKGROUNDED,
            mapOf("source" to "app_lifecycle", "background_date" to epochSeconds()),
        )
    }

    private fun epochSeconds(): Double = nowMillis() / 1000.0

    internal companion object {
        // Keys match the iOS tracker so semantics stay recognizable in review.
        const val HAS_LAUNCHED_BEFORE_KEY = "nuxie_has_launched_before"
        const val LAST_VERSION_KEY = "nuxie_last_version"
    }
}
