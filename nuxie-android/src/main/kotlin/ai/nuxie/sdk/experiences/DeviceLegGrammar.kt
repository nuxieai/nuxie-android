package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.ReleaseJson.array
import ai.nuxie.sdk.experiences.ReleaseJson.boolean
import ai.nuxie.sdk.experiences.ReleaseJson.exact
import ai.nuxie.sdk.experiences.ReleaseJson.fail
import ai.nuxie.sdk.experiences.ReleaseJson.id
import ai.nuxie.sdk.experiences.ReleaseJson.ids
import ai.nuxie.sdk.experiences.ReleaseJson.integer
import ai.nuxie.sdk.experiences.ReleaseJson.journeyId
import ai.nuxie.sdk.experiences.ReleaseJson.number
import ai.nuxie.sdk.experiences.ReleaseJson.oneOf
import ai.nuxie.sdk.experiences.ReleaseJson.record
import ai.nuxie.sdk.experiences.ReleaseJson.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Lowered actions carry selectors and local outlets, never nested programs. */
internal object DeviceLegGrammar {
    val presenting = setOf("navigate", "back", "purchase", "restore", "request_notifications",
        "request_permission", "request_tracking", "open_link")

    fun action(input: JsonElement?, screens: Set<String>, placements: Set<String>) {
        val action = record(input)
        fun shape(vararg required: String, optional: Set<String> = emptySet()) =
            exact(action, setOf("type", *required), optional)
        when (text(action["type"])) {
            "navigate" -> {
                shape("screenId", optional = setOf("transition"))
                if (journeyId(action["screenId"]) !in screens) fail("screen closure")
                action["transition"]?.let(::transition)
            }
            "back" -> {
                shape(optional = setOf("steps", "transition"))
                action["steps"]?.let { integer(it, 1, 256) }
                action["transition"]?.let(::transition)
            }
            "delay" -> { shape("durationMs"); integer(action["durationMs"], maximum = 366L * 24 * 60 * 60 * 1000) }
            "condition" -> {
                shape("branches")
                for (value in array(action["branches"])) {
                    val branch = exact(value, setOf("id", "condition"))
                    id(branch["id"]); condition(branch["condition"])
                }
            }
            "experiment" -> {
                shape("experimentId", "variants"); id(action["experimentId"])
                for (value in array(action["variants"])) {
                    val variant = exact(value, setOf("id", "isHoldout"))
                    id(variant["id"]); boolean(variant["isHoldout"])
                }
            }
            "time_window" -> {
                shape("startTime", "endTime", "timezone", "daysOfWeek")
                text(action["startTime"]); text(action["endTime"])
                val timezone = record(action["timezone"])
                when (text(timezone["kind"])) {
                    "device", "app_default" -> exact(timezone, setOf("kind"))
                    "iana" -> { exact(timezone, setOf("kind", "identifier")); journeyId(timezone["identifier"]) }
                    else -> fail("timezone")
                }
                for (day in array(action["daysOfWeek"])) integer(day, 0, 6)
            }
            "wait_until" -> {
                shape("trigger", "condition", "maxTimeMs")
                val trigger = record(action["trigger"])
                when (text(trigger["kind"])) {
                    "response_change" -> exact(trigger, setOf("kind"))
                    "event", "event_or_response_change" -> {
                        exact(trigger, setOf("kind", "eventName"), setOf("payloadSchema")); journeyId(trigger["eventName"])
                        trigger["payloadSchema"]?.let { input ->
                            val payload = exact(input, setOf("type", "fields", "additionalProperties"))
                            oneOf(payload["type"], "object"); boolean(payload["additionalProperties"])
                            fields(payload["fields"], false)
                            val names = array(payload["fields"], 256).map { id(record(it)["key"]) }
                            if (names != names.sortedWith { left, right -> compareUtf8(left, right) }) fail("payload field ordering")
                        }
                    }
                    else -> fail("wait trigger")
                }
                condition(action["condition"]); integer(action["maxTimeMs"])
            }
            "purchase" -> {
                shape("placementId")
                val placement = action["placementId"]
                val literal = if (placement is JsonPrimitive) id(placement) else {
                    val value = record(placement)
                    if (value.containsKey("literal")) {
                        exact(value, setOf("literal")); id(value["literal"])
                    } else {
                        exact(value, setOf("ref"))
                        val ref = exact(value["ref"], setOf("kind", "path"), setOf("viewModelName", "isRelative"))
                        oneOf(ref["kind"], "path"); id(ref["path"])
                        ref["viewModelName"]?.let { id(it) }; ref["isRelative"]?.let { boolean(it) }
                        null
                    }
                }
                if (literal != null && literal !in placements) fail("purchase placement")
            }
            "restore", "submit_response", "request_notifications", "request_tracking" -> shape()
            "request_permission" -> { shape("permissionType"); journeyId(action["permissionType"]) }
            "milestone" -> { shape("milestoneId"); journeyId(action["milestoneId"]) }
            "send_event", "app_action" -> {
                val key = if (text(action["type"]) == "send_event") "eventName" else "name"
                shape(key, optional = setOf("payload"))
                val name = journeyId(action[key])
                if (key == "eventName" && name.startsWith('$')) fail("reserved event")
                action["payload"]?.let(::values)
            }
            "update_customer" -> { shape("attributes"); values(action["attributes"]) }
            "open_link" -> {
                shape("url", "target"); oneOf(action["target"], "external", "in_app")
                value(action["url"])
                oneOf(record(action["url"])["type"], "String", "Event.Field", "Response.Field")
            }
            "dismiss", "exit" -> { shape(optional = setOf("reason")); action["reason"]?.let { text(it) } }
            else -> fail("unknown or server action")
        }
    }

    private fun transition(input: JsonElement) {
        val value = record(input)
        when (text(value["type"])) {
            "none", "push", "modal", "fade" -> exact(value, setOf("type"))
            "custom" -> { exact(value, setOf("type", "transitionId")); journeyId(value["transitionId"]) }
            else -> fail("transition")
        }
    }

    private fun values(input: JsonElement?) {
        for ((key, child) in record(input)) { journeyId(JsonPrimitive(key)); value(child) }
    }

    private fun value(input: JsonElement?) {
        val value = record(input)
        when (text(value["type"])) {
            "Null" -> exact(value, setOf("type"))
            "Boolean" -> { exact(value, setOf("type", "value")); boolean(value["value"]) }
            "Number" -> { exact(value, setOf("type", "value")); number(value["value"]) }
            "String" -> { exact(value, setOf("type", "value")); text(value["value"]) }
            "Array" -> { exact(value, setOf("type", "items")); array(value["items"], 256).forEach(::value) }
            "Object" -> { exact(value, setOf("type", "fields")); values(value["fields"]) }
            "Event.Field", "Response.Field" -> { exact(value, setOf("type", "key")); journeyId(value["key"]) }
            else -> fail("value expression")
        }
    }

    private fun condition(input: JsonElement?) {
        val condition = record(input)
        when (text(condition["type"])) {
            "Truthy" -> { exact(condition, setOf("type", "value")); value(condition["value"]) }
            "Compare" -> {
                exact(condition, setOf("type", "op", "left", "right"))
                oneOf(condition["op"], "==", "!=", "<", "<=", ">", ">=")
                value(condition["left"]); value(condition["right"])
            }
            "Contains" -> { exact(condition, setOf("type", "collection", "value")); value(condition["collection"]); value(condition["value"]) }
            "All", "Any" -> {
                exact(condition, setOf("type", "conditions"))
                val values = array(condition["conditions"], 64)
                if (values.isEmpty()) fail("empty condition")
                values.forEach(::condition)
            }
            "Not" -> { exact(condition, setOf("type", "condition")); condition(condition["condition"]) }
            else -> fail("condition expression")
        }
    }

    fun boundary(input: JsonElement?) {
        val boundary = exact(input, setOf("eventFields", "responseFields"))
        fields(boundary["eventFields"], false); fields(boundary["responseFields"], true)
    }

    fun fields(input: JsonElement?, response: Boolean) {
        val names = mutableSetOf<String>()
        for (item in array(input)) {
            val field = record(item)
            val type = text(field["type"])
            val optional = when (type) {
                "number" -> setOf("min", "max")
                "string" -> if (!response) setOf("enum") else fail("response type")
                "text", "date" -> if (response) emptySet() else fail("event field type")
                "enum", "multi_enum" -> if (response) setOf("options") else fail("event field type")
                "null", "json" -> if (!response) emptySet() else fail("response type")
                "boolean" -> emptySet()
                else -> fail("boundary field type")
            }
            exact(field, setOf("key", "type", "required"), optional)
            val key = id(field["key"])
            if (!response) journeyId(field["key"])
            if (!names.add(key) || key.encodeToByteArray().size > if (response) 128 else 256) fail("field key")
            boolean(field["required"])
            if (type == "number") {
                val min = field["min"]?.let { number(it) }
                val max = field["max"]?.let { number(it) }
                if (min != null && max != null && min > max) fail("field bounds")
            }
            val enumKey = if (response) "options" else "enum"
            if (field.containsKey(enumKey) || type in setOf("enum", "multi_enum")) {
                val options = ids(field[enumKey], 256)
                if (!response) array(field[enumKey]).forEach { journeyId(it) }
                if (options.isEmpty() || options.size != options.toSet().size) fail("field options")
            }
        }
    }

    private fun compareUtf8(left: String, right: String): Int {
        val a = left.encodeToByteArray(); val b = right.encodeToByteArray()
        for (i in 0 until minOf(a.size, b.size)) {
            val comparison = (a[i].toInt() and 255) - (b[i].toInt() and 255)
            if (comparison != 0) return comparison
        }
        return a.size - b.size
    }
}
