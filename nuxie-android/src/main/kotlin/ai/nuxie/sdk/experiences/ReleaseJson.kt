package ai.nuxie.sdk.experiences

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Strict primitives used at the signed descriptor boundary. */
internal object ReleaseJson {
    fun record(value: JsonElement?): JsonObject = value as? JsonObject ?: fail("object")
    fun array(value: JsonElement?, maximum: Int = Int.MAX_VALUE): JsonArray =
        (value as? JsonArray)?.takeIf { it.size <= maximum } ?: fail("array")
    fun text(value: JsonElement?): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("string")
    fun id(value: JsonElement?, maximum: Int = 256): String = text(value).also {
        if (it.isEmpty() || it.length > maximum) fail("identifier")
    }
    fun journeyId(value: JsonElement?): String = id(value).also {
        if (it.encodeToByteArray().size > 256) fail("identifier byte limit")
        var index = 0
        while (index < it.length) {
            val char = it[index++]
            if (char.isHighSurrogate()) {
                if (index >= it.length || !it[index++].isLowSurrogate()) fail("identifier Unicode")
            } else if (char.isLowSurrogate()) fail("identifier Unicode")
        }
    }
    fun timestamp(value: JsonElement?): String = text(value).also { timestamp ->
        // Identity timestamps are opaque signed strings, not millisecond values.
        // Validate the Gregorian date without rounding its fractional precision.
        val match = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(?:Z|([+-])(\d{2}):(\d{2}))$""")
            .matchEntire(timestamp) ?: fail("timestamp")
        val parts = match.groupValues
        val year = parts[1].toInt(); val month = parts[2].toInt(); val day = parts[3].toInt()
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (month !in 1..12 || day !in 1..days[month - 1] || parts[4].toInt() > 23 ||
            parts[5].toInt() > 59 || parts[6].toInt() > 59 ||
            (parts[7].isNotEmpty() && (parts[8].toInt() > 23 || parts[9].toInt() > 59))) fail("timestamp")
    }
    fun ids(value: JsonElement?, maximum: Int = Int.MAX_VALUE): List<String> = array(value, maximum).map { id(it) }
    fun boolean(value: JsonElement?): Boolean =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: fail("boolean")
    fun number(value: JsonElement?, minimum: Double = -Double.MAX_VALUE, maximum: Double = Double.MAX_VALUE): Double =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() && it in minimum..maximum } ?: fail("number")
    fun integer(value: JsonElement?, minimum: Long = 0, maximum: Long = 9_007_199_254_740_991): Long =
        number(value, minimum.toDouble(), maximum.toDouble()).let {
            if (it != kotlin.math.floor(it)) fail("integer")
            it.toLong()
        }
    fun hash(value: JsonElement?): String = text(value).also {
        if (!it.matches(Regex("^[a-f0-9]{64}$"))) fail("digest")
    }
    fun oneOf(value: JsonElement?, vararg allowed: String): String = text(value).also { if (it !in allowed) fail("enum") }
    fun exact(value: JsonElement?, required: Set<String>, optional: Set<String> = emptySet()): JsonObject = record(value).also {
        if (!it.keys.containsAll(required) || (it.keys - required - optional).isNotEmpty()) fail("unknown or missing fields")
    }
    fun sortedUnique(values: List<String>) {
        if (values != values.sorted() || values.toSet().size != values.size) fail("ordering or duplicate")
    }
    fun fail(message: String): Nothing = throw ReleaseAuthenticationException(message)
}
