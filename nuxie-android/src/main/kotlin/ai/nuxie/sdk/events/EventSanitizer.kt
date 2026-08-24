package ai.nuxie.sdk.events

import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Stage-1 data-type sanitization, ported from the iOS `EventSanitizer`:
 * platform types become JSON-serializable values before enrichment.
 *
 * Rules (iOS parity): strings truncate to 1000 chars and lose NUL characters;
 * nesting truncates beyond depth 10; Date becomes an ISO-8601 string;
 * ByteArray becomes base64 capped at 1024 bytes; unknown types fall back to
 * their toString(), and nulls are preserved as JSON null.
 */
internal object EventSanitizer {
    private const val MAX_STRING_LENGTH = 1000
    private const val MAX_NESTING_DEPTH = 10
    private const val MAX_DATA_BYTES = 1024

    fun sanitizeDataTypes(properties: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return sanitizeValue(properties, depth = 0) as? Map<String, Any?> ?: emptyMap()
    }

    private fun sanitizeValue(value: Any?, depth: Int): Any? {
        if (depth > MAX_NESTING_DEPTH + 1) return SanitizeDrop
        return when (value) {
            null -> null
            is String -> sanitizeString(value)
            is Boolean, is Int, is Long, is Short, is Byte, is Float, is Double -> value
            is Char -> value.toString()
            is Date -> isoFormatter().format(value)
            is ByteArray -> {
                val bounded = if (value.size > MAX_DATA_BYTES) value.copyOf(MAX_DATA_BYTES) else value
                Base64.encodeToString(bounded, Base64.NO_WRAP)
            }
            is Map<*, *> -> sanitizeMap(value, depth)
            is Iterable<*> -> value.mapNotNull { child ->
                sanitizeValue(child, depth + 1).takeUnless { it === SanitizeDrop }
            }
            is Array<*> -> value.mapNotNull { child ->
                sanitizeValue(child, depth + 1).takeUnless { it === SanitizeDrop }
            }
            else -> sanitizeString(value.toString())
        }
    }

    private fun sanitizeMap(value: Map<*, *>, depth: Int): Map<String, Any?> {
        val sanitized = linkedMapOf<String, Any?>()
        value.forEach { (key, child) ->
            if (key !is String) return@forEach
            val sanitizedChild = sanitizeValue(child, depth + 1)
            if (sanitizedChild !== SanitizeDrop) {
                sanitized[sanitizeString(key)] = sanitizedChild
            }
        }
        return sanitized
    }

    private fun sanitizeString(string: String): String {
        val truncated = if (string.length > MAX_STRING_LENGTH) string.take(MAX_STRING_LENGTH) else string
        return truncated.replace("\u0000", "")
    }

    private fun isoFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** Sentinel distinguishing "drop this entry" from a legitimate null value. */
    private object SanitizeDrop
}
