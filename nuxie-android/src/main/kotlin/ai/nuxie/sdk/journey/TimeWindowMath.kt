package ai.nuxie.sdk.journey

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone

/** Deterministic local-calendar math backed only by [SignedTimezoneBundle]. */
internal object TimeWindowMath {
    const val CURRENT_DEVICE_TIMEZONE = "__current_device__"

    sealed interface Decision {
        data object Malformed : Decision
        data object Unavailable : Decision
        data object InWindow : Decision
        data class Pause(val untilMilliseconds: Long) : Decision
    }

    fun resolveTimezone(raw: String, currentIdentifier: String, bundle: SignedTimezoneBundle): SignedTimezoneBundle.Timezone? =
        if (raw == CURRENT_DEVICE_TIMEZONE) bundle.resolveDeviceIdentifier(currentIdentifier) else bundle.resolve(raw)

    fun evaluate(
        nowMilliseconds: Long,
        startTime: String,
        endTime: String,
        daysOfWeek: List<Int>?,
        timezone: SignedTimezoneBundle.Timezone,
    ): Decision {
        val start = parseTime(startTime) ?: return Decision.Malformed
        val end = parseTime(endTime) ?: return Decision.Malformed
        val localNow = localParts(nowMilliseconds, timezone) ?: return Decision.Unavailable
        val crossesDate = end.minutes <= start.minutes
        for (offset in listOf(-1, 0)) {
            val day = shiftDate(localNow, offset) ?: continue
            if (!isSelected(day, daysOfWeek)) continue
            val interval = resolvedInterval(day, start, end, crossesDate, timezone) ?: continue
            if (interval.second > interval.first && nowMilliseconds in interval.first until interval.second) return Decision.InWindow
        }
        for (offset in 0..7) {
            val day = shiftDate(localNow, offset) ?: continue
            if (!isSelected(day, daysOfWeek)) continue
            val interval = resolvedInterval(day, start, end, crossesDate, timezone) ?: continue
            if (interval.second > interval.first && interval.first > nowMilliseconds) return Decision.Pause(interval.first)
        }
        return Decision.Unavailable
    }

    private data class LocalParts(
        val year: Int, val month: Int, val day: Int, val hour: Int = 0, val minute: Int = 0,
        val weekday: Int = 0,
    )
    private data class ParsedTime(val hour: Int, val minute: Int) { val minutes = hour * 60 + minute }
    private enum class Disambiguation { Earlier, Later }

    private fun parseTime(value: String): ParsedTime? {
        if (value.length != 5 || value[2] != ':' || value.indices.any { it != 2 && value[it] !in '0'..'9' }) return null
        val hour = value.substring(0, 2).toInt()
        val minute = value.substring(3, 5).toInt()
        return if (hour in 0..23 && minute in 0..59) ParsedTime(hour, minute) else null
    }

    private fun localParts(instant: Long, timezone: SignedTimezoneBundle.Timezone): LocalParts? {
        val offset = timezone.bundle.offsetSeconds(timezone, instant) ?: return null
        val local = runCatching { Math.addExact(instant, Math.multiplyExact(offset.toLong(), 1_000L)) }.getOrNull() ?: return null
        val calendar = utcCalendar(local)
        return LocalParts(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.DAY_OF_WEEK) - 1)
    }

    private fun shiftDate(parts: LocalParts, days: Int): LocalParts? = runCatching {
        val calendar = utcCalendar(parts.year, parts.month, parts.day)
        calendar.add(Calendar.DAY_OF_MONTH, days)
        LocalParts(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
            weekday = calendar.get(Calendar.DAY_OF_WEEK) - 1)
    }.getOrNull()

    private fun isSelected(day: LocalParts, days: List<Int>?) = days.isNullOrEmpty() || day.weekday in days

    private fun resolvedInterval(
        day: LocalParts, start: ParsedTime, end: ParsedTime, crossesDate: Boolean,
        timezone: SignedTimezoneBundle.Timezone,
    ): Pair<Long, Long>? {
        val endDay = shiftDate(day, if (crossesDate) 1 else 0) ?: return null
        val startInstant = localToInstant(day, start, timezone, Disambiguation.Earlier) ?: return null
        val endInstant = localToInstant(endDay, end, timezone, Disambiguation.Later) ?: return null
        return startInstant to endInstant
    }

    private fun localToInstant(
        day: LocalParts, time: ParsedTime, timezone: SignedTimezoneBundle.Timezone,
        disambiguation: Disambiguation,
    ): Long? {
        val local = runCatching { utcCalendar(day.year, day.month, day.day, time.hour, time.minute).timeInMillis }.getOrNull() ?: return null
        exactLocalToInstant(local, timezone, disambiguation)?.let { return it }
        for (delta in 1..2_880) {
            val shifted = runCatching { Math.addExact(local, delta * 60_000L) }.getOrNull() ?: return null
            exactLocalToInstant(shifted, timezone, Disambiguation.Earlier)?.let { return it }
        }
        return null
    }

    private fun exactLocalToInstant(
        localAsUtc: Long, timezone: SignedTimezoneBundle.Timezone, disambiguation: Disambiguation,
    ): Long? {
        val expected = utcCalendar(localAsUtc)
        val candidates = timezone.bundle.nearbyOffsets(timezone, localAsUtc).mapNotNull { offset ->
            val instant = runCatching { Math.subtractExact(localAsUtc, Math.multiplyExact(offset.toLong(), 1_000L)) }.getOrNull()
                ?: return@mapNotNull null
            val observed = localParts(instant, timezone) ?: return@mapNotNull null
            if (observed.year == expected.get(Calendar.YEAR) && observed.month == expected.get(Calendar.MONTH) + 1 &&
                observed.day == expected.get(Calendar.DAY_OF_MONTH) && observed.hour == expected.get(Calendar.HOUR_OF_DAY) &&
                observed.minute == expected.get(Calendar.MINUTE)) instant else null
        }.sorted()
        return if (disambiguation == Disambiguation.Later) candidates.lastOrNull() else candidates.firstOrNull()
    }

    private fun utcCalendar(milliseconds: Long) = GregorianCalendar(UTC).apply {
        isLenient = false; gregorianChange = Date(Long.MIN_VALUE); timeInMillis = milliseconds
    }

    private fun utcCalendar(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) = utcCalendar(0).apply {
        clear(); set(year, month - 1, day, hour, minute, 0); set(Calendar.MILLISECOND, 0); timeInMillis
    }

    private val UTC = TimeZone.getTimeZone("GMT")
}
