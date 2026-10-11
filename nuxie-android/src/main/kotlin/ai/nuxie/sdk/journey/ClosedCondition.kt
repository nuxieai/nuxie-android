package ai.nuxie.sdk.journey

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.serialization.json.*

internal data class ClosedConditionFacts(val snapshot: JsonObject, val nowMilliseconds: Long, val zone: String?)
internal sealed interface ClosedConditionResult {
    val jsonValue: JsonElement
    data class Answer(val value: Boolean) : ClosedConditionResult { override val jsonValue get() = JsonPrimitive(value) }
    data object Unavailable : ClosedConditionResult { override val jsonValue = JsonPrimitive("unavailable") }
}

/** The bare Experience tree. Stored segment IR keeps its own interpreter. */
internal class ClosedCondition(val json: JsonObject) {
    init { validate(json) }

    fun nodes(): List<JsonObject> = buildList {
        fun visit(node: JsonObject) { add(node); children(node).forEach(::visit) }
        visit(json)
    }
    fun featureIds() = nodes().filter { it.kind == "Feature" }.map { it.getValue("id").jsonPrimitive.content }.distinct()

    fun evaluate(facts: ClosedConditionFacts): ClosedConditionResult {
        var unavailable = false
        fun failed(): JsonElement { unavailable = true; return JsonNull }
        fun field(snapshot: JsonElement?, key: String): JsonElement =
            if (snapshot == JsonNull) failed() else (snapshot as? JsonObject)?.get(key) ?: JsonNull
        fun read(node: JsonObject): JsonElement {
            fun child(key: String) = read(node.getValue(key).jsonObject)
            return when (node.kind) {
                "Null" -> JsonNull
                "String", "Number", "Bool" -> node.getValue("value")
                "List" -> JsonArray(node.getValue("value").jsonArray.map { read(it.jsonObject) })
                "Event.Field" -> field(facts.snapshot["event"], node.getValue("key").jsonPrimitive.content)
                "Customer.Field" -> field(facts.snapshot["customer"], node.getValue("key").jsonPrimitive.content)
                "Feature" -> field(facts.snapshot["access"], node.getValue("id").jsonPrimitive.content)
                "Segment" -> (facts.snapshot["memberships"] as? JsonObject)?.get(node.getValue("id").jsonPrimitive.content) ?: failed()
                "Run.Value" -> {
                    if (facts.snapshot["run"] == JsonNull) failed() else {
                        var value = facts.snapshot["run"] ?: JsonNull
                        node.getValue("path").jsonPrimitive.content.split('.').forEach { key -> value = (value as? JsonObject)?.get(key) ?: JsonNull }
                        value
                    }
                }
                "Events.Count" -> {
                    val seconds = (node["within"] as? JsonObject)?.get("value") ?: JsonNull
                    val count = (facts.snapshot["counts"] as? JsonArray)?.lastOrNull {
                        it is JsonObject && equal(it["name"] ?: JsonNull, node.getValue("name")) && equal(it["withinSeconds"] ?: JsonNull, seconds)
                    }?.jsonObject?.get("count")
                    val number = count.number()
                    if (number != null && number.isFinite() && number >= 0 && number <= 9_007_199_254_740_991.0 && number % 1.0 == 0.0) count!! else failed()
                }
                "Env.Platform" -> facts.snapshot["platform"].takeIf { it.text() in listOf("ios", "android", "macos", "web") } ?: failed()
                "Time.Hour", "Time.Minute", "Time.Weekday", "Time.Date" -> clock(facts.nowMilliseconds, facts.zone)?.get(node.kind) ?: failed()
                "Not" -> JsonPrimitive(child("arg") != JsonPrimitive(true))
                "And", "Or" -> {
                    val answers = node.getValue("args").jsonArray.map { read(it.jsonObject) }
                    JsonPrimitive(if (node.kind == "And") answers.all { it == JsonPrimitive(true) } else answers.any { it == JsonPrimitive(true) })
                }
                "Contains" -> {
                    val collection = child("collection"); val value = child("value")
                    JsonPrimitive(when {
                        collection == JsonNull || value == JsonNull -> false
                        collection is JsonArray -> collection.any { equal(it, value) }
                        collection.text() != null && value.text() != null -> collection.text()!!.contains(value.text()!!)
                        else -> false
                    })
                }
                "Compare" -> {
                    val left = child("left"); val right = child("right"); val op = node.getValue("op").jsonPrimitive.content
                    val explicitNull = node.getValue("left").jsonObject.kind == "Null" || node.getValue("right").jsonObject.kind == "Null"
                    val result = when {
                        op in listOf("==", "!=") && explicitNull -> (left == JsonNull && right == JsonNull).let { if (op == "==") it else !it }
                        left == JsonNull || right == JsonNull -> false
                        op == "==" -> equal(left, right)
                        op == "!=" -> !equal(left, right)
                        else -> {
                            val order = if (left.number() != null && right.number() != null) left.number()!!.compareTo(right.number()!!)
                                else if (left.text() != null && right.text() != null) left.text()!!.compareTo(right.text()!!) else null
                            order != null && when (op) { "<" -> order < 0; "<=" -> order <= 0; ">" -> order > 0; else -> order >= 0 }
                        }
                    }
                    JsonPrimitive(result)
                }
                else -> failed()
            }
        }
        val answer = read(json)
        return if (unavailable) ClosedConditionResult.Unavailable else ClosedConditionResult.Answer(answer == JsonPrimitive(true))
    }

    fun nextClockChange(nowMilliseconds: Long, zone: String?): Long? {
        val keys = nodes().map { it.kind }.filter { it in clockTypes }.toSet()
        if (keys.isEmpty()) return null
        val initial = clock(nowMilliseconds, zone) ?: return null
        val first = (nowMilliseconds / 1000L - if (nowMilliseconds < 0 && nowMilliseconds % 1000L != 0L) 1 else 0) * 1000L + (60 - initial.getValue("second").jsonPrimitive.int) * 1000L
        var instant = first
        val end = nowMilliseconds + 172_800_000L
        while (instant <= end) {
            val current = clock(instant, zone) ?: return null
            if (keys.any { initial[it] != current[it] }) return instant
            instant += 60_000L
        }
        return null
    }

    companion object {
        private val bundle by lazy { SignedTimezoneBundle.load() }
        private val clockTypes = setOf("Time.Hour", "Time.Minute", "Time.Weekday", "Time.Date")
        private val JsonObject.kind get() = getValue("type").jsonPrimitive.content
        private fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonElement?.number() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        private fun equal(a: JsonElement, b: JsonElement): Boolean = when {
            a.number() != null && b.number() != null -> a.number() == b.number()
            a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { equal(a[it], b[it]) }
            a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { (key, value) -> equal(value, b.getValue(key)) }
            else -> a == b
        }
        private fun children(node: JsonObject): List<JsonObject> = when (node.kind) {
            "And", "Or" -> node.getValue("args").jsonArray.map { it.jsonObject }
            "List" -> node.getValue("value").jsonArray.map { it.jsonObject }
            "Not" -> listOf(node.getValue("arg").jsonObject)
            "Compare" -> listOf(node.getValue("left").jsonObject, node.getValue("right").jsonObject)
            "Contains" -> listOf(node.getValue("collection").jsonObject, node.getValue("value").jsonObject)
            else -> emptyList()
        }
        private fun validate(raw: JsonElement) {
            require(raw is JsonObject && raw["type"].text() != null) { "invalid condition node" }
            val optional = if (raw.kind == "Events.Count") setOf("within") else emptySet()
            val required = when (raw.kind) {
                "And", "Or" -> { require(raw["args"] is JsonArray); setOf("type", "args") }
                "Not" -> setOf("type", "arg")
                "Compare" -> { require(raw["op"].text() in listOf("==", "!=", "<", "<=", ">", ">=")); setOf("type", "op", "left", "right") }
                "Contains" -> setOf("type", "collection", "value")
                "List" -> { require(raw["value"] is JsonArray); setOf("type", "value") }
                "String" -> { require(raw["value"].text() != null); setOf("type", "value") }
                "Number" -> { require(raw["value"].number()?.isFinite() == true); setOf("type", "value") }
                "Bool" -> { require((raw["value"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != null); setOf("type", "value") }
                "Event.Field", "Customer.Field" -> { require(!raw["key"].text().isNullOrEmpty()); setOf("type", "key") }
                "Run.Value" -> { val path = raw["path"].text(); require(path != null && listOf("experience.", "responses.").any { path.startsWith(it) && path.length > it.length }); setOf("type", "path") }
                "Feature", "Segment" -> { require(!raw["id"].text().isNullOrEmpty() && raw["op"].text() == if (raw.kind == "Feature") "has" else "is_member"); setOf("type", "op", "id") }
                "Events.Count" -> {
                    require(!raw["name"].text().isNullOrEmpty())
                    raw["within"]?.let { duration -> require(duration is JsonObject && duration.keys == setOf("type", "value") && duration["type"].text() == "Duration" && duration["value"].number()?.let { it in 60.0..31_622_400.0 && it % 1.0 == 0.0 } == true) }
                    setOf("type", "name")
                }
                "Null", "Time.Hour", "Time.Minute", "Time.Weekday", "Time.Date", "Env.Platform" -> setOf("type")
                else -> throw IllegalArgumentException("unknown condition node")
            }
            require(raw.keys.containsAll(required) && (required + optional).containsAll(raw.keys)) { "invalid condition keys" }
            try { children(raw).forEach(::validate) } catch (e: IllegalStateException) { throw IllegalArgumentException("invalid condition child", e) }
        }
        private fun clock(milliseconds: Long, identifier: String?): Map<String, JsonElement>? {
            val timezone = identifier?.let(bundle::resolveDeviceIdentifier) ?: return null
            val offset = bundle.offsetSeconds(timezone, milliseconds) ?: return null
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                gregorianChange = Date(Long.MIN_VALUE)
                timeInMillis = milliseconds + offset * 1000L
            }
            return mapOf(
                "Time.Hour" to JsonPrimitive(calendar.get(Calendar.HOUR_OF_DAY)), "Time.Minute" to JsonPrimitive(calendar.get(Calendar.MINUTE)),
                "Time.Weekday" to JsonPrimitive(listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")[calendar.get(Calendar.DAY_OF_WEEK) - 1]),
                "Time.Date" to JsonPrimitive("%04d-%02d-%02d".format(java.util.Locale.US, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))),
                "second" to JsonPrimitive(calendar.get(Calendar.SECOND)),
            )
        }
    }
}
