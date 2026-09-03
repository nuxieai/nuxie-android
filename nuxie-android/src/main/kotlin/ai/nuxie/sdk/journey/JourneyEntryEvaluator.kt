package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.util.IsoDates
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Current facts are read at start/wake. Ordinary events are edges; the caller
 * owns the foreground latch. Unknown is never represented by Boolean false,
 * so a missing fact or unsupported operation cannot become true under Not. */
internal object JourneyEntryEvaluator {
    suspend fun matches(
        entry: JsonObject,
        facts: JsonObject,
        references: JsonObject,
        foreground: Boolean,
        event: JsonObject?,
        nowMillis: Long = System.currentTimeMillis(),
        events: EventStore? = null,
        distinctId: String? = null,
        featureAccess: (suspend (String) -> FeatureAccess?)? = null,
    ): Boolean {
        val properties = facts["properties"] as? JsonObject ?: return false
        val memberships = facts["memberships"] as? JsonObject ?: return false
        for ((reference, table) in listOf("propertyKeys" to properties, "segmentIds" to memberships)) {
            val keys = references[reference] as? JsonArray ?: return false
            if (!keys.all { key -> key.string()?.let(table::containsKey) == true }) return false
        }
        when (entry["type"].string()) {
            "app_foregrounded" -> if (!foreground) return false
            "event" -> {
                val name = entry["eventName"].string() ?: return false
                if (event?.get("name").string() != name) return false
            }
            "segment" -> {
                val id = entry["segmentId"].string() ?: return false
                val wanted = entry["member"].boolean() ?: return false
                if (memberships[id].boolean() != wanted) return false
            }
            else -> return false
        }
        if (!entry.containsKey("condition")) return true
        val envelope = entry["condition"] as? JsonObject ?: return false
        if (envelope["ir_version"].number() != 1.0) return false
        val minimumEngine = envelope["engine_min"].string()?.substringBefore('.')?.toIntOrNull()
        if (minimumEngine != null && minimumEngine > 1) return false
        val context = Context(
            properties,
            memberships,
            event,
            nowMillis,
            events,
            distinctId,
            featureAccess,
        )
        if (!context.available(envelope["expr"])) return false
        return try {
            context.evaluate(envelope["expr"])?.truthy() == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** Null return means unknown; JsonNull is a known absent/null value. */
    private class Context(
        val properties: JsonObject,
        val memberships: JsonObject,
        val event: JsonObject?,
        val nowMillis: Long,
        val events: EventStore?,
        val distinctId: String?,
        val featureAccess: (suspend (String) -> FeatureAccess?)?,
    ) {
        fun available(value: JsonElement?, hasEvent: Boolean = event != null, depth: Int = 0): Boolean {
            if (depth > 64) return false
            val expression = value as? JsonObject ?: return false
            fun child(key: String): Boolean = expression[key]?.let { available(it, hasEvent, depth + 1) } ?: true
            fun children(key: String): Boolean = (expression[key] as? JsonArray)?.all { available(it, hasEvent, depth + 1) } == true
            return when (expression["type"].string()) {
                "Bool", "String", "Number", "Timestamp", "Duration", "Time.Now", "Time.Window" -> true
                "Time.Ago" -> child("duration")
                "List" -> children("value")
                "Not" -> child("arg")
                "And", "Or" -> children("args")
                "PredAnd", "PredOr" -> hasEvent && children("args")
                "Compare" -> expression["op"].string() in compareOps && child("left") && child("right")
                "User" -> expression["op"].string() in propertyOps &&
                    expression["key"].string()?.let(properties::containsKey) == true && child("value")
                "Event", "Pred" -> hasEvent && expression["op"].string() in propertyOps && child("value")
                "Segment" -> expression["op"].string() in setOf("is_member", "not_member", "in", "not_in") &&
                    expression["id"].string()?.let(memberships::containsKey) == true
                "Feature" -> featureAccess != null && expression["op"].string() in featureOps && child("value")
                "Events.Exists", "Events.Count", "Events.Aggregate" -> events != null && distinctId != null &&
                    child("since") && child("until") && child("within") &&
                    (expression["where"]?.let { predicateAvailable(it, depth + 1) } ?: true) &&
                    (expression["type"].string() != "Events.Aggregate" || expression["agg"].string() in aggregateOps)
                // First/last/stopped/restarted need complete lifetime history,
                // which this device's retention-bounded store cannot promise.
                else -> false
            }
        }

        private fun predicateAvailable(value: JsonElement, depth: Int): Boolean {
            if (depth > 64) return false
            val node = value as? JsonObject ?: return false
            return when (node["type"].string()) {
                "Pred" -> node["op"].string() != "has" && available(node, hasEvent = true, depth = depth + 1)
                "PredAnd", "PredOr" -> (node["args"] as? JsonArray)?.all { predicateAvailable(it, depth + 1) } == true
                else -> false
            }
        }

        suspend fun evaluate(value: JsonElement?): JsonElement? {
            val expression = value as? JsonObject ?: return null
            return when (expression["type"].string()) {
                "Bool" -> expression["value"].boolean()?.let(::JsonPrimitive)
                "String" -> expression["value"].string()?.let(::JsonPrimitive)
                "Number", "Timestamp", "Duration" -> expression["value"].number()?.let(::JsonPrimitive)
                "List" -> {
                    val children = expression["value"] as? JsonArray ?: return null
                    JsonArray(children.map { evaluate(it) ?: return null })
                }
                "Time.Now" -> JsonPrimitive(nowMillis / 1000.0)
                "Time.Ago" -> evaluate(expression["duration"])?.number()?.let { JsonPrimitive(nowMillis / 1000.0 - it) }
                "Time.Window" -> expression["value"].number()?.let {
                    JsonPrimitive(it * (windowSeconds[expression["interval"].string()] ?: 0))
                }
                "Not" -> evaluate(expression["arg"])?.truthy()?.let { JsonPrimitive(!it) }
                "And", "Or", "PredAnd", "PredOr" -> {
                    val type = expression["type"].string()
                    if (type in setOf("PredAnd", "PredOr") && event == null) return null
                    val children = expression["args"] as? JsonArray ?: return null
                    val conjunction = type in setOf("And", "PredAnd")
                    for (child in children) {
                        val result = evaluate(child)?.truthy() ?: return null
                        if (result != conjunction) return JsonPrimitive(result)
                    }
                    JsonPrimitive(conjunction)
                }
                "User" -> {
                    val key = expression["key"].string() ?: return null
                    val fact = properties[key] as? JsonObject ?: return null
                    val present = fact["present"].boolean() ?: return null
                    if (present != fact.containsKey("value")) return null
                    propertyOperation(expression, if (present) fact.getValue("value") else JsonNull)
                }
                "Event", "Pred" -> {
                    val key = expression["key"].string() ?: return null
                    val current = event ?: return null
                    propertyOperation(expression, eventValue(current, key))
                }
                "Segment" -> {
                    val id = expression["id"].string() ?: return null
                    val member = memberships[id].boolean() ?: return null
                    when (expression["op"].string()) {
                        "is_member", "in" -> JsonPrimitive(member)
                        "not_member", "not_in" -> JsonPrimitive(!member)
                        else -> null // Transition queries are exclusively server-owned.
                    }
                }
                "Feature" -> featureOperation(expression)
                "Compare" -> {
                    val left = evaluate(expression["left"]) ?: return null
                    val right = evaluate(expression["right"]) ?: return null
                    compare(expression["op"].string(), left, right)?.let(::JsonPrimitive)
                }
                "Events.Exists", "Events.Count", "Events.Aggregate" -> occurrence(expression)
                // Other operators stay unknown until their adapters exist.
                else -> null
            }
        }

        private suspend fun featureOperation(expression: JsonObject): JsonElement? {
            val access = featureAccess?.invoke(expression["id"].string() ?: return null)
            val result = when (expression["op"].string()) {
                "has" -> access?.allowed ?: false
                "not_has" -> access?.allowed != true
                "is_unlimited" -> access?.unlimited ?: false
                "credits_eq", "credits_neq", "credits_gt", "credits_gte", "credits_lt", "credits_lte" -> {
                    val target = evaluate(expression["value"])?.number() ?: return null
                    val balance = access?.balance ?: return JsonPrimitive(false)
                    when (expression["op"].string()) {
                        "credits_eq" -> balance == target
                        "credits_neq" -> balance != target
                        "credits_gt" -> balance > target
                        "credits_gte" -> balance >= target
                        "credits_lt" -> balance < target
                        else -> balance <= target
                    }
                }
                else -> return null
            }
            return JsonPrimitive(result)
        }

        private suspend fun propertyOperation(expression: JsonObject, actual: JsonElement): JsonElement? {
            val expected = if (expression.containsKey("value")) evaluate(expression["value"]) ?: return null else JsonNull
            return propertyResult(expression, actual, expected)
        }

        private fun propertyResult(expression: JsonObject, actual: JsonElement, expected: JsonElement): JsonElement? {
            val op = expression["op"].string() ?: return null
            val result = when (op) {
                "has", "is_set" -> actual != JsonNull
                "is_not_set" -> actual == JsonNull
                "eq" -> compare("==", actual, expected)
                "neq" -> compare("!=", actual, expected)
                "gt" -> compare(">", actual, expected)
                "gte" -> compare(">=", actual, expected)
                "lt" -> compare("<", actual, expected)
                "lte" -> compare("<=", actual, expected)
                "in", "not_in" -> compare(op, actual, expected)
                "icontains" -> {
                    val search = expected.coerceString() ?: ""
                    val values = if (actual is JsonArray) actual else listOf(actual)
                    values.any { it.coerceString()?.contains(search, ignoreCase = true) == true }
                }
                "regex" -> {
                    val text = actual.coerceString()
                    val pattern = expected.coerceString() ?: ""
                    text != null && runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
                }
                "is_date_exact", "is_date_after", "is_date_before" -> {
                    val timestamp = actual.coerceNumber() ?: actual.string()?.let(::timestampSeconds)
                    val target = expected.number()
                    if (timestamp == null || target == null || !isTimestamp(expression["value"])) false
                    else when (op) {
                        "is_date_after" -> timestamp > target
                        "is_date_before" -> timestamp < target
                        else -> day(timestamp) == day(target)
                    }
                }
                else -> null
            }
            return result?.let(::JsonPrimitive)
        }

        private suspend fun occurrence(expression: JsonObject): JsonElement? {
            val store = events ?: return null
            val person = distinctId ?: return null
            val name = expression["name"].string() ?: return null
            var since: Double? = null
            var until: Double? = null
            expression["since"]?.let {
                val value = evaluate(it) ?: return null
                if (isTimestamp(it)) since = value.number()
            }
            expression["until"]?.let {
                val value = evaluate(it) ?: return null
                if (isTimestamp(it)) until = value.number()
            }
            expression["within"]?.let {
                val duration = evaluate(it)?.number() ?: return null
                if ((it as? JsonObject)?.get("type").string() in setOf("Duration", "Time.Window")) {
                    since = maxOf(since ?: Double.NEGATIVE_INFINITY, nowMillis / 1000.0 - duration)
                }
            }
            val lower = since?.let { queryMilliseconds(it, lower = true) ?: return null }
            val upper = until?.let { queryMilliseconds(it, lower = false) ?: return null }
            // Bind predicate inputs once, before inspecting rows. An empty
            // result must not conceal an unknown nested occurrence query.
            val predicate = expression["where"]?.let { compilePredicate(it) ?: return null }
            val rows = store.queryHistory(name, person, lower, upper) ?: return null
            val matches = if (predicate == null) rows else rows.filter { predicate(it.properties) }
            // Recheck the durable fence before consuming this history snapshot.
            val coverage = store.historyCoverageStartingAt() ?: return null
            if (lower == null || lower < coverage) return null
            return when (expression["type"].string()) {
                "Events.Exists" -> JsonPrimitive(matches.isNotEmpty())
                "Events.Count" -> JsonPrimitive(matches.size)
                "Events.Aggregate" -> {
                    val property = expression["prop"].string() ?: return null
                    val values = matches.mapNotNull { it.properties[property].coerceNumber() }
                    val result = if (values.isEmpty()) 0.0 else when (expression["agg"].string()) {
                        "sum" -> values.sum()
                        "avg" -> values.average()
                        "min" -> values.min()
                        "max" -> values.max()
                        "unique" -> values.toSet().size.toDouble()
                        else -> return null
                    }
                    result.takeIf { it.isFinite() }?.let(::JsonPrimitive)
                }
                else -> null
            }
        }

        private suspend fun compilePredicate(value: JsonElement): ((JsonObject) -> Boolean)? {
            val node = value as? JsonObject ?: return null
            return when (node["type"].string()) {
                "Pred" -> {
                    val key = node["key"].string() ?: return null
                    val expected = if (node.containsKey("value")) evaluate(node["value"]) ?: return null else JsonNull
                    // History predicates read literal property keys. The iOS
                    // predicate grammar has no `has` alias or event metadata.
                    val match: (JsonObject) -> Boolean = { properties ->
                        node["op"].string() != "has" &&
                            propertyResult(node, properties[key] ?: JsonNull, expected)?.truthy() == true
                    }
                    match
                }
                "PredAnd", "PredOr" -> {
                    val children = (node["args"] as? JsonArray ?: return null).map { compilePredicate(it) ?: return null }
                    val match: (JsonObject) -> Boolean = { properties ->
                        if (node["type"].string() == "PredAnd") children.all { it(properties) }
                        else children.any { it(properties) }
                    }
                    match
                }
                else -> null
            }
        }

        private fun eventValue(event: JsonObject, key: String): JsonElement {
            val normalized = key.removePrefix("$")
            val properties = event["properties"] as? JsonObject ?: return JsonNull
            return when (normalized) {
                "name", "event" -> event["name"] ?: JsonNull
                "timestamp" -> event["timestamp"] ?: JsonNull
                "distinctId", "distinct_id" -> event["distinctId"] ?: distinctId?.let(::JsonPrimitive) ?: JsonNull
                else -> if (normalized.startsWith("properties.")) {
                    normalized.removePrefix("properties.").split('.').fold(properties as JsonElement) { value, part ->
                        (value as? JsonObject)?.get(part) ?: JsonNull
                    }
                } else properties[normalized] ?: JsonNull
            }
        }
    }

    private fun compare(op: String?, left: JsonElement, right: JsonElement): Boolean? = when (op) {
        "in", "not_in" -> {
            val text = left.coerceString()
            val member = text != null && (right as? JsonArray)?.any { it.coerceString() == text } == true
            if (op == "in") member else !member
        }
        "==", "!=", "<", "<=", ">", ">=" -> {
            val a = left.coerceNumber()
            val b = right.coerceNumber()
            val x = left.coerceString()
            val y = right.coerceString()
            val ordering = when {
                a != null && b != null -> a.compareTo(b)
                x != null && y != null -> x.compareTo(y)
                else -> null
            }
            if (ordering == null) {
                if (left == JsonNull || right == JsonNull) when (op) {
                    "==" -> left == JsonNull && right == JsonNull
                    "!=" -> left != JsonNull || right != JsonNull
                    else -> false
                } else false
            } else when (op) {
                "==" -> ordering == 0
                "!=" -> ordering != 0
                "<" -> ordering < 0
                "<=" -> ordering <= 0
                ">" -> ordering > 0
                else -> ordering >= 0
            }
        }
        else -> null
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonElement.truthy(): Boolean = when (this) {
        JsonNull -> false
        is JsonArray -> isNotEmpty()
        else -> boolean() ?: number()?.let { it != 0.0 } ?: string()?.isNotEmpty() ?: false
    }
    private fun JsonElement?.coerceNumber(): Double? = number() ?: boolean()?.let { if (it) 1.0 else 0.0 }
        ?: string()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun JsonElement?.coerceString(): String? = string() ?: boolean()?.let { if (it) "1" else "0" }
        ?: number()?.let { if (it >= Long.MIN_VALUE && it < Long.MAX_VALUE && it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
    private fun isTimestamp(value: JsonElement?): Boolean = (value as? JsonObject)?.get("type").string() in
        setOf("Timestamp", "Time.Now", "Time.Ago", "Events.FirstTime", "Events.LastTime")
    private fun milliseconds(seconds: Double): Long? = (seconds * 1000).takeIf {
        it.isFinite() && it >= Long.MIN_VALUE && it < Long.MAX_VALUE
    }?.toLong()
    private fun queryMilliseconds(seconds: Double, lower: Boolean): Long? {
        val raw = seconds * 1000
        val nearest = kotlin.math.round(raw)
        val tolerance = Math.ulp(seconds) * 1000 + Math.ulp(raw)
        val normalized = if (kotlin.math.abs(raw - nearest) <= tolerance) nearest else raw
        val boundary = if (lower) kotlin.math.ceil(normalized) else kotlin.math.floor(normalized)
        return boundary.takeIf { it.isFinite() && it >= Long.MIN_VALUE && it < Long.MAX_VALUE }?.toLong()
    }
    private fun day(seconds: Double): List<Int> {
        val calendar = Calendar.getInstance().apply { timeInMillis = milliseconds(seconds) ?: return emptyList() }
        return listOf(calendar.get(Calendar.ERA), calendar.get(Calendar.YEAR), calendar.get(Calendar.DAY_OF_YEAR))
    }
    private fun timestampSeconds(value: String): Double? {
        val parts = isoTimestamp.matchEntire(value) ?: return null
        val whole = IsoDates.parseMillis(parts.groupValues[1] + parts.groupValues[3]) ?: return null
        val fraction = parts.groupValues[2].takeIf { it.isNotEmpty() }?.let { "0.$it".toDoubleOrNull() } ?: 0.0
        return whole / 1000.0 + fraction
    }
    // SimpleDateFormat's SSS means an integer millisecond field: `.5` would
    // mean 5 ms. Parse the fraction as decimal seconds, as ISO 8601 requires.
    private val isoTimestamp = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$""")
    private val propertyOps = setOf("has", "eq", "neq", "icontains", "regex", "gt", "gte", "lt", "lte",
        "is_set", "is_not_set", "is_date_exact", "is_date_after", "is_date_before", "in", "not_in")
    private val compareOps = setOf("==", "!=", "<", "<=", ">", ">=", "in", "not_in")
    private val featureOps = setOf(
        "has",
        "not_has",
        "is_unlimited",
        "credits_eq",
        "credits_neq",
        "credits_gt",
        "credits_gte",
        "credits_lt",
        "credits_lte",
    )
    private val aggregateOps = setOf("sum", "avg", "min", "max", "unique")
    private val windowSeconds = mapOf("hour" to 3600, "day" to 86400, "week" to 604800, "month" to 2592000, "year" to 31536000)
}
