package ai.nuxie.sdk.journey

import java.security.MessageDigest
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The verified projection of IANA timezone data used by Journey time windows.
 * Android's platform timezone rules never participate in route evaluation. */
internal class SignedTimezoneBundle private constructor(
    private val startYear: Int,
    private val endYear: Int,
    private val aliases: Map<String, String>,
    private val zones: Map<String, Zone>,
) {
    class Timezone internal constructor(val identifier: String, internal val bundle: SignedTimezoneBundle)
    private data class Transition(val atMilliseconds: Long, val offsetSeconds: Int)
    private data class Zone(val initialOffsetSeconds: Int, val transitions: List<Transition>)

    fun resolve(identifier: String): Timezone? = identifier.takeIf {
        it.isNotEmpty() && it in zones && it !in aliases
    }?.let { Timezone(it, this) }

    fun resolveDeviceIdentifier(identifier: String): Timezone? = resolve(aliases[identifier] ?: identifier)

    fun offsetSeconds(timezone: Timezone, atMilliseconds: Long): Int? {
        if (timezone.bundle !== this) return null
        val year = runCatching { utcCalendar(atMilliseconds).get(Calendar.YEAR) }.getOrNull() ?: return null
        val zone = zones[timezone.identifier] ?: return null
        if (year !in startYear..endYear) return null
        var low = 0
        var high = zone.transitions.size
        while (low < high) {
            val mid = (low + high).ushr(1)
            if (zone.transitions[mid].atMilliseconds <= atMilliseconds) low = mid + 1 else high = mid
        }
        return if (low == 0) zone.initialOffsetSeconds else zone.transitions[low - 1].offsetSeconds
    }

    fun nearbyOffsets(timezone: Timezone, aroundMilliseconds: Long): Set<Int> {
        if (timezone.bundle !== this) return emptySet()
        val zone = zones[timezone.identifier] ?: return emptySet()
        val result = mutableSetOf(zone.initialOffsetSeconds)
        offsetSeconds(timezone, aroundMilliseconds)?.let(result::add)
        val lower = saturatingAdd(aroundMilliseconds, -NEARBY_MILLISECONDS)
        val upper = saturatingAdd(aroundMilliseconds, NEARBY_MILLISECONDS)
        var index = zone.transitions.binarySearchBy(lower) { it.atMilliseconds }
        if (index < 0) index = -index - 1
        while (index < zone.transitions.size && zone.transitions[index].atMilliseconds <= upper) {
            result += zone.transitions[index].offsetSeconds
            result += if (index == 0) zone.initialOffsetSeconds else zone.transitions[index - 1].offsetSeconds
            index++
        }
        return result
    }

    companion object {
        const val REVISION = "2026c"
        const val SHA256 = "d4ad5c12a6be491076f333c9b4f96f60cb8ab552495bbfae0d8cdc9730ecb198"
        private const val NEARBY_MILLISECONDS = 172_800_000L
        private const val RESOURCE = "/ai/nuxie/sdk/journey/timezone-bundle.json"

        fun load(): SignedTimezoneBundle = load(checkNotNull(SignedTimezoneBundle::class.java.getResourceAsStream(RESOURCE)) {
            "missing pinned timezone bundle"
        }.use { it.readBytes() })

        fun load(bytes: ByteArray): SignedTimezoneBundle {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(digest == SHA256) { "pinned timezone bundle digest mismatch" }
            val resource = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(resource.getValue("format").jsonPrimitive.content == "iana-tzdb")
            require(resource.getValue("revision").jsonPrimitive.content == REVISION)
            val startYear = resource.getValue("startYear").jsonPrimitive.int
            val endYear = resource.getValue("endYear").jsonPrimitive.int
            require(startYear < endYear)
            val aliases = resource.getValue("aliases").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val zones = resource.getValue("zones").jsonObject.mapValues { (_, value) -> parseZone(value.jsonObject) }
            require(zones.isNotEmpty() && aliases.values.all { it in zones })
            return SignedTimezoneBundle(startYear, endYear, aliases, zones)
        }

        private fun parseZone(value: JsonObject): Zone {
            val transitions = value.getValue("transitions").jsonArray.map {
                val pair = it.jsonArray
                require(pair.size == 2)
                Transition(pair[0].jsonPrimitive.long, pair[1].jsonPrimitive.int)
            }
            require(transitions.zipWithNext().all { (left, right) -> left.atMilliseconds < right.atMilliseconds })
            return Zone(value.getValue("initialOffsetSeconds").jsonPrimitive.int, transitions)
        }

        private fun utcCalendar(milliseconds: Long) = GregorianCalendar(UTC).apply {
            isLenient = false
            gregorianChange = Date(Long.MIN_VALUE)
            timeInMillis = milliseconds
        }

        private val UTC = TimeZone.getTimeZone("GMT")
        private fun saturatingAdd(left: Long, right: Long): Long = runCatching { Math.addExact(left, right) }
            .getOrElse { if (right < 0) Long.MIN_VALUE else Long.MAX_VALUE }
    }
}
