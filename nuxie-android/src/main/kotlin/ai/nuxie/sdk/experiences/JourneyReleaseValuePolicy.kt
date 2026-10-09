package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.JourneyReleaseJson.array
import ai.nuxie.sdk.experiences.JourneyReleaseJson.boolean
import ai.nuxie.sdk.experiences.JourneyReleaseJson.exact
import ai.nuxie.sdk.experiences.JourneyReleaseJson.fail
import ai.nuxie.sdk.experiences.JourneyReleaseJson.integer
import ai.nuxie.sdk.experiences.JourneyReleaseJson.number
import ai.nuxie.sdk.experiences.JourneyReleaseJson.record
import ai.nuxie.sdk.experiences.JourneyReleaseJson.text
import ai.nuxie.sdk.runtime.NuxieRuleGroup
import ai.nuxie.sdk.runtime.NuxieRuleGroupMember
import ai.nuxie.sdk.runtime.NuxieValuePolicy
import ai.nuxie.sdk.runtime.NuxieValueRule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The signed M17 records are operands for native installation, never a value store. */
internal object JourneyReleaseValuePolicy {
    fun parse(root: JsonObject): NuxieValuePolicy {
        for ((key, value) in record(root["state"])) { name(key); state(value) }
        val forms = record(root["responses"])
        val rules = mutableListOf<NuxieValueRule>()
        val fieldsByModel = mutableMapOf<String, List<String>>()
        for ((key, value) in forms.toSortedMap()) {
            name(key)
            val form = exact(value, setOf("title", "model", "fields"))
            text(form["title"])
            val model = text(form["model"])
            if (model != "Responses:$key") fail("form model")
            val fields = array(form["fields"], 4096).map { raw ->
                val f = exact(raw, setOf("key", "label", "type", "rules"), setOf("values", "multiple"))
                val field = name(text(f["key"]))
                if (field in setOf("valid", "errors", "saving", "saved", "saveError")) fail("reserved field")
                text(f["label"])
                val type = text(f["type"])
                if (type !in setOf("string", "number", "boolean", "enum", "date")) fail("field kind")
                val values = f["values"]?.let(::choices)
                val multiple = f["multiple"]?.let { boolean(it).also { yes -> if (!yes) fail("multiple") } } ?: false
                if ((type == "enum") != (values != null) || multiple && type != "enum") fail("field shape")
                for (rawRule in array(f["rules"], 4096)) {
                    val rule = rule(rawRule)
                    if (rule.model != model || rule.property != field) fail("rule target")
                    val allowed = rule.kind == 10 || type == "number" && rule.kind in listOf(1, 2) ||
                        type == "string" && rule.kind in listOf(8, 9, 11) || type == "date" && rule.kind in listOf(3, 4, 12) ||
                        type == "enum" && rule.kind == (if (multiple) 7 else 5)
                    if (!allowed || rule.kind == 5 && rule.values != values) fail("rule field kind")
                    rules += rule
                }
                field
            }
            if (fields.toSet().size != fields.size) fail("duplicate field")
            fieldsByModel[model] = fields
        }
        val groups = array(root["ruleGroups"], 4096).map { raw ->
            val g = exact(raw, setOf("model", "valid", "member_count", "members"))
            val model = text(g["model"])
            val fields = fieldsByModel[model] ?: fail("group model")
            if (text(g["valid"]) != "valid") fail("group validity property")
            val members = array(g["members"], 4096).map { value ->
                val m = exact(value, setOf("property", "errors_path", "item_model", "code_property", "message_property"))
                val property = name(text(m["property"]))
                if (text(m["errors_path"]) != "errors/$property" || text(m["item_model"]) != "ResponseError" ||
                    text(m["code_property"]) != "rule" || text(m["message_property"]) != "message") fail("group member")
                NuxieRuleGroupMember(property, text(m["errors_path"]), "ResponseError", "rule", "message")
            }
            if (uint(g["member_count"]) != members.size.toLong() || members.map { it.property } != fields) fail("group coverage")
            NuxieRuleGroup(model, "valid", members)
        }
        if (groups.size != forms.size || groups.map { it.model }.toSet().size != groups.size) fail("form groups")
        val ruleBytes = rules.sumOf { r -> 152L + listOf(r.model, r.property, r.text, r.pickedProperty, r.code, r.message)
            .sumOf { bytes(it) } + r.values.sumOf { 16L + bytes(it) } }
        val groupBytes = groups.sumOf { g -> 48L + bytes(g.model) + bytes(g.valid) + g.members.sumOf { m ->
            80L + listOf(m.property, m.errorsPath, m.itemModel, m.codeProperty, m.messageProperty).sumOf { bytes(it) } } }
        if (rules.size > 4096 || groups.size + groups.sumOf { it.members.size } > 4096 ||
            ruleBytes > 8 * 1024 * 1024 || groupBytes > 8 * 1024 * 1024) fail("native policy limits")
        return NuxieValuePolicy(rules, groups)
    }

    private fun rule(raw: JsonElement): NuxieValueRule {
        val r = exact(raw, setOf("model", "property", "kind", "mode", "number_bound", "text", "values",
            "value_count", "picked_property", "bound_flags", "minimum", "maximum", "code", "message"))
        val model = nonempty(r["model"]); val property = name(text(r["property"]))
        val kind = uint(r["kind"]).toInt(); val mode = uint(r["mode"]).toInt()
        val flags = uint(r["bound_flags"]).toInt(); val minimum = uint(r["minimum"]); val maximum = uint(r["maximum"])
        val bound = number(r["number_bound"]); val operand = text(r["text"]); val values = choices(r["values"])
        val picked = text(r["picked_property"]); val code = nonempty(r["code"]); val message = nonempty(r["message"])
        if (kind !in listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12) || mode !in 0..1 || flags !in 0..2 ||
            values.size > 4096 || uint(r["value_count"]) != values.size.toLong()) fail("rule operands")
        val expected = mapOf(1 to (0 to "min"), 2 to (1 to "max"), 3 to (0 to "min"), 4 to (1 to "max"),
            5 to (1 to "values"), 10 to (0 to "required"), 11 to (0 to "format"), 12 to (0 to "date"))[kind]
        if (expected != null && expected != (mode to code)) fail("rule mode/code")
        if (kind in listOf(7, 8)) {
            val isMinimum = flags == 1
            val expectedCode = if (kind == 7) (if (isMinimum) "minItems" else "maxItems") else (if (isMinimum) "minLength" else "maxLength")
            if (flags == 0 || mode != (if (isMinimum) 0 else 1) || code != expectedCode ||
                (if (isMinimum) maximum != 0L else minimum != 0L)) fail("bound rule")
        } else if (flags != 0 || minimum != 0L || maximum != 0L) fail("unused bounds")
        if (kind == 9 && (mode != 0 || code !in listOf("format", "pattern")) ||
            kind !in listOf(1, 2) && bound != 0.0 || kind !in listOf(3, 4, 9) && operand.isNotEmpty() ||
            kind != 5 && values.isNotEmpty() || picked != (if (kind == 7) "picked" else "")) fail("unused rule operands")
        return NuxieValueRule(model, property, kind, mode, bound, operand, values, picked, flags, minimum, maximum, code, message)
    }

    private fun state(value: JsonElement) {
        val state = exact(value, setOf("type"), setOf("values", "multiple", "items", "fields"))
        val type = text(state["type"])
        if (type !in listOf("string", "number", "boolean", "color", "enum", "date", "list", "trigger", "image", "object") ||
            (type == "enum") != state.containsKey("values") || (type == "list") != state.containsKey("items") ||
            (type == "object") != state.containsKey("fields")) fail("state kind")
        state["values"]?.let(::choices)
        state["multiple"]?.let { if (!boolean(it) || type != "enum") fail("multiple") }
        state["items"]?.let { for ((key, item) in record(it)) { name(key); state(item) } }
        state["fields"]?.let {
            val fields = record(it)
            if (fields.isEmpty()) fail("empty object fields")
            for ((key, field) in fields) { name(key); state(field) }
        }
    }
    private fun name(value: String): String = value.also {
        if (!it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) || it in listOf("true", "false", "null")) fail("value name")
    }
    private fun choices(value: JsonElement?): List<String> = array(value).map(::text).also {
        if (it.toSet().size != it.size) fail("duplicate choice")
    }
    private fun nonempty(value: JsonElement?): String = text(value).also { if (it.isEmpty()) fail("empty string") }
    private fun uint(value: JsonElement?): Long = integer(value, maximum = 0xffff_ffffL)
    private fun bytes(value: String): Long = value.encodeToByteArray().size.toLong()
}
