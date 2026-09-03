package ai.nuxie.sdk.journey

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TimeWindowMathTest {
    private val bundle = SignedTimezoneBundle.load()
    private fun instant(value: String): Long = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }.parse(value)!!.time

    private fun evaluate(now: String, start: String, end: String, zone: String = "Etc/UTC", days: List<Int>? = null) =
        TimeWindowMath.evaluate(instant(now), start, end, days, bundle.resolve(zone)!!)

    @Test fun pinnedBundleRejectsTamperingAndOnlyDeviceIdentifiersResolveAliases() {
        assertNotNull(bundle.resolve("America/New_York"))
        assertNull(bundle.resolve("UTC"))
        assertEquals("Etc/UTC", bundle.resolveDeviceIdentifier("UTC")?.identifier)
        assertNull(bundle.resolveDeviceIdentifier("Not/AZone"))
        val bytes = javaClass.getResourceAsStream("/ai/nuxie/sdk/journey/timezone-bundle.json")!!.use { it.readBytes() }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertNull(runCatching { SignedTimezoneBundle.load(bytes) }.getOrNull())
    }

    @Test fun gapsMoveToFirstValidMinuteAndFoldsCoverBothOccurrences() {
        assertEquals(TimeWindowMath.Decision.Pause(instant("2026-03-08T07:00:00Z")),
            evaluate("2026-03-08T06:30:00Z", "02:30", "04:00", "America/New_York"))
        assertEquals(TimeWindowMath.Decision.InWindow,
            evaluate("2026-03-08T07:30:00Z", "02:30", "04:00", "America/New_York"))
        for (now in listOf("2026-11-01T05:15:00Z", "2026-11-01T06:15:00Z")) {
            assertEquals(TimeWindowMath.Decision.InWindow, evaluate(now, "01:00", "01:30", "America/New_York"))
        }
        assertEquals(TimeWindowMath.Decision.Pause(instant("2026-11-02T06:00:00Z")),
            evaluate("2026-11-01T06:30:00Z", "01:00", "01:30", "America/New_York"))
        assertEquals(TimeWindowMath.Decision.Pause(instant("2026-04-04T15:30:00Z")),
            evaluate("2026-04-04T14:00:00Z", "02:30", "03:00", "Australia/Sydney"))
    }

    @Test fun missingDatesAndSelectedOpeningDaysMatchReferenceCalendar() {
        assertEquals(TimeWindowMath.Decision.Pause(instant("2011-12-30T19:00:00Z")),
            evaluate("2011-12-30T04:00:00Z", "09:00", "17:00", "Pacific/Apia"))
        assertEquals(TimeWindowMath.Decision.Pause(instant("2026-07-17T13:00:00Z")),
            evaluate("2026-07-15T14:00:00Z", "09:00", "17:00", "America/New_York", listOf(5)))
        assertEquals(TimeWindowMath.Decision.InWindow,
            evaluate("2026-07-16T01:00:00Z", "22:00", "02:00", days = listOf(3)))
        assertEquals(TimeWindowMath.Decision.Pause(instant("2026-07-22T22:00:00Z")),
            evaluate("2026-07-16T02:00:00Z", "22:00", "02:00", days = listOf(3)))
        assertEquals(TimeWindowMath.Decision.InWindow, evaluate("2026-07-15T03:00:00Z", "09:00", "09:00"))
    }

    @Test fun unsupportedInstantsFailClosedAndCalendarDoesNotConsultDeviceRules() {
        assertEquals(TimeWindowMath.Decision.Unavailable, evaluate("1799-01-01T12:00:00Z", "09:00", "17:00"))
        assertEquals(TimeWindowMath.Decision.Unavailable, evaluate("2400-01-01T12:00:00Z", "09:00", "17:00"))
        for (now in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(TimeWindowMath.Decision.Unavailable,
                TimeWindowMath.evaluate(now, "09:00", "17:00", null, bundle.resolve("Etc/UTC")!!))
        }
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertEquals(TimeWindowMath.Decision.InWindow,
                evaluate("2026-07-15T14:00:00Z", "09:00", "17:00", "America/New_York"))
        } finally { TimeZone.setDefault(original) }
    }
}
