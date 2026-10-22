package ai.nuxie.sdk.presentation

import android.content.res.Configuration

/** Names both the env enum label and the Theme global's authored instance. */
internal enum class ExperienceColorScheme(val label: String) {
    LIGHT("light"),
    DARK("dark");

    companion object {
        /** An unspecified night mode draws light, just like an unspecified iOS style. */
        fun fromUiMode(uiMode: Int): ExperienceColorScheme =
            if (uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
    }
}
