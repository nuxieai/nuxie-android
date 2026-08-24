package ai.nuxie.sdk.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 UTC parsing/formatting without the JDK time API (banned repo-wide:
 * it forces minSdk<26 hosts into core-library desugaring).
 */
internal object IsoDates {
    private val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    fun parseMillis(iso: String): Long? {
        for (pattern in patterns) {
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            runCatching { return formatter.parse(iso)?.time }
        }
        return null
    }

    fun formatMillis(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
            timeZone = TimeZone.getTimeZone("UTC")
            format(Date(timestampMillis))
        }
}
