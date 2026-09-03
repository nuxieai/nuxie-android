package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.JourneyReleaseJson.array
import ai.nuxie.sdk.experiences.JourneyReleaseJson.boolean
import ai.nuxie.sdk.experiences.JourneyReleaseJson.fail
import ai.nuxie.sdk.experiences.JourneyReleaseJson.number
import ai.nuxie.sdk.experiences.JourneyReleaseJson.oneOf
import ai.nuxie.sdk.experiences.JourneyReleaseJson.record
import ai.nuxie.sdk.experiences.JourneyReleaseJson.text
import kotlinx.serialization.json.JsonElement

/** Mirrors the canonical IR node grammar. Availability and evaluation remain
 * separate: a well-formed expression can still evaluate unknown at start. */
internal object DeviceEntryIrSchema {
    private val predicateOps = arrayOf("eq", "neq", "icontains", "regex", "gt", "gte", "lt", "lte", "is_set", "is_not_set",
        "in", "not_in", "is_date_exact", "is_date_after", "is_date_before")

    fun validate(input: JsonElement?, depth: Int = 0) {
        if (depth > 64) fail("IR nesting")
        val node = record(input)
        fun child(key: String) = validate(node[key], depth + 1)
        fun optional(vararg keys: String) { for (key in keys) if (node.containsKey(key)) child(key) }
        when (text(node["type"])) {
            "Bool" -> boolean(node["value"])
            "Number", "Timestamp", "Duration" -> number(node["value"])
            "String" -> text(node["value"])
            "List" -> array(node["value"]).forEach { validate(it, depth + 1) }
            "And", "Or", "PredAnd", "PredOr" -> array(node["args"]).forEach { validate(it, depth + 1) }
            "Not" -> child("arg")
            "Compare" -> { oneOf(node["op"], "==", "!=", "<", "<=", ">", ">=", "in", "not_in"); child("left"); child("right") }
            "User", "Event", "Pred" -> {
                if (text(node["type"]) == "Pred") oneOf(node["op"], *predicateOps) else oneOf(node["op"], "has", *predicateOps)
                text(node["key"]); optional("value")
            }
            "Segment" -> { oneOf(node["op"], "is_member", "not_member", "entered_within"); text(node["id"]); optional("within") }
            "Feature" -> {
                oneOf(node["op"], "has", "not_has", "is_unlimited", "credits_eq", "credits_neq", "credits_gt", "credits_gte",
                    "credits_lt", "credits_lte", "is_trialing", "is_active", "is_expired", "will_renew", "will_not_renew")
                text(node["id"]); optional("value")
            }
            "Subscription" -> oneOf(node["op"], "active", "not_active", "trialing", "expired", "will_renew", "will_not_renew")
            "Events.Exists", "Events.Count", "Events.Aggregate" -> {
                text(node["name"]); optional("since", "until", "within", "where")
                if (text(node["type"]) == "Events.Aggregate") { oneOf(node["agg"], "sum", "avg", "min", "max", "unique"); text(node["prop"]) }
            }
            "Events.FirstTime", "Events.LastTime", "Events.LastAge" -> { text(node["name"]); optional("where") }
            "Events.InOrder" -> {
                for (value in array(node["steps"])) {
                    val step = record(value); text(step["name"])
                    step["where"]?.let { validate(it, depth + 1) }
                }
                optional("overallWithin", "perStepWithin", "since", "until")
            }
            "Events.ActivePeriods" -> {
                text(node["name"]); oneOf(node["period"], "day", "week", "month", "year")
                number(node["totalPeriods"]); number(node["minPeriods"]); optional("where")
            }
            "Events.Stopped", "Events.Restarted" -> {
                text(node["name"]); child("inactiveFor"); optional("where")
                if (text(node["type"]) == "Events.Restarted") child("within")
            }
            "Time.Now", "Journey.Id" -> Unit
            "Response.Field" -> if (!text(node["key"]).matches(Regex("^[A-Za-z][A-Za-z0-9_]*$"))) fail("response field")
            "Time.Ago" -> child("duration")
            "Time.Window" -> { number(node["value"]); oneOf(node["interval"], "day", "week", "month", "year") }
            else -> fail("IR expression")
        }
    }
}
