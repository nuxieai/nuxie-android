package ai.nuxie.sdk.util

import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 UTC parsing/formatting without the JDK time API (banned repo-wide:
 * it forces minSdk<26 hosts into core-library desugaring).
 */
internal object IsoDates {
    private val timestamp = Regex("^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\\.([0-9]+))?(Z|[+-][0-9]{2}:[0-9]{2})$")

    fun parseMillis(iso: String): Long? {
        val match = timestamp.matchEntire(iso) ?: return null
        val fraction = match.groupValues[2].padEnd(3, '0').take(3)
        val offset = match.groupValues[3].let { if (it == "Z") "+0000" else it.replace(":", "") }
        val normalized = "${match.groupValues[1]}.$fraction$offset"
        return runCatching {
            // RFC 822 Z is supported on API 23; ISO X requires API 24.
            val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val position = ParsePosition(0)
            formatter.parse(normalized, position)?.takeIf { position.index == normalized.length }?.time
        }.getOrNull()
    }

    fun formatMillis(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
            timeZone = TimeZone.getTimeZone("UTC")
            format(Date(timestampMillis))
        }
}
