package ai.nuxie.sdk.events

import ai.nuxie.sdk.network.NuxieApi
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Transport outcomes classified independently of the durable queue. */
internal sealed interface EventDeliveryDisposition {
    data class Retry(val retryAfterMillis: Long? = null) : EventDeliveryDisposition
    data object Split : EventDeliveryDisposition
    data object UnhealthyAuthentication : EventDeliveryDisposition
}

internal object EventDeliveryPolicy {
    fun disposition(error: Throwable, nowMillis: Long): EventDeliveryDisposition {
        val rejection = error as? NuxieApi.BatchRejectedException
            ?: return EventDeliveryDisposition.Retry()
        return when (rejection.statusCode) {
            400, 413, 422 -> EventDeliveryDisposition.Split
            401, 403 -> EventDeliveryDisposition.UnhealthyAuthentication
            408, 425, 429 -> EventDeliveryDisposition.Retry(parseRetryAfter(rejection.retryAfter, nowMillis))
            else -> EventDeliveryDisposition.Retry()
        }
    }

    fun parseRetryAfter(value: String?, nowMillis: Long): Long? {
        val text = value?.trim() ?: return null
        text.toDoubleOrNull()?.let { seconds ->
            if (seconds.isFinite() && seconds >= 0) return (seconds * 1000).toLong()
            return null
        }
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val position = ParsePosition(0)
            val date = format.parse(text, position)
            if (date != null && position.index == text.length) {
                return (date.time.toDouble() - nowMillis.toDouble()).coerceAtLeast(0.0).toLong()
            }
        }
        return null
    }
}
