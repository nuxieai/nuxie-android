package ai.nuxie.sdk.journey

import ai.nuxie.sdk.experiences.ExperiencePolicySchema
import ai.nuxie.sdk.experiences.JourneyReleaseJson.array
import ai.nuxie.sdk.experiences.JourneyReleaseJson.exact
import ai.nuxie.sdk.experiences.JourneyReleaseJson.fail
import ai.nuxie.sdk.experiences.JourneyReleaseJson.id
import ai.nuxie.sdk.experiences.JourneyReleaseJson.record
import ai.nuxie.sdk.experiences.JourneyReleaseJson.text
import kotlinx.serialization.json.*

/** Prepared signed policy reader, selected with the conditions protocol. */
internal class ClosedConditionPolicy(val json: JsonObject) {
    val filter: ClosedCondition?
    init {
        val policy = exact(json,setOf("entry","exitWhenAny"),setOf("goal"))
        val entry = exact(policy["entry"],setOf("trigger","frequency"))
        filter = criterion(entry["trigger"],trigger = true)
        val frequency = record(entry["frequency"])
        when(text(frequency["type"])) {
            "one_time", "every_match" -> exact(frequency,setOf("type"))
            "once_per_window" -> { exact(frequency,setOf("type","window")); ExperiencePolicySchema.durationMillis(frequency["window"]) }
            else -> fail("condition frequency")
        }
        policy["goal"]?.let {
            val goal = exact(it,setOf("criterion","attribution"))
            criterion(goal["criterion"],goal = true)
            val attribution = exact(goal["attribution"],setOf("basis","window"))
            if (text(attribution["basis"]) !in setOf("entry","first_shown") || ExperiencePolicySchema.durationMillis(attribution["window"]) > 90L * 86_400_000) fail("condition attribution")
        }
        for(raw in array(policy["exitWhenAny"])) {
            val exit = record(raw)
            if (text(exit["type"]) == "goal_met") {
                exact(exit,setOf("type")); if (policy["goal"] == null) fail("goal exit without goal")
            } else criterion(raw)
        }
    }
    private fun identifier(value: JsonElement?) { if (id(value,128).trim() != text(value)) fail("condition identifier whitespace") }
    private fun criterion(raw: JsonElement?, trigger: Boolean = false, goal: Boolean = false): ClosedCondition? {
        val value = record(raw)
        when(text(value["type"])) {
            "event" -> { exact(value,setOf("type","eventName"),setOf("condition")); ExperiencePolicySchema.event(value["eventName"]) }
            "segment_enter", "segment_leave" -> { exact(value,setOf("type","segmentId"),if(trigger) setOf("condition") else emptySet()); identifier(value["segmentId"]) }
            "api" -> { if (!trigger) fail("criterion api"); exact(value,setOf("type"),setOf("condition")) }
            "server_event" -> {
                if (!trigger) fail("criterion server event")
                exact(value,setOf("type","connectorKey","triggerKey"),setOf("identityField","condition"))
                for(key in listOf("connectorKey","triggerKey","identityField")) value[key]?.let(::identifier)
            }
            else -> fail("condition criterion")
        }
        val condition = value["condition"]?.let { ClosedCondition(record(it)) } ?: return null
        for(node in condition.nodes()) {
            val type = text(node["type"])
            if (trigger && type == "Run.Value") fail("run fact at entry")
            if (goal && type in setOf("Customer.Field","Run.Value","Feature","Segment","Events.Count")) fail("mutable goal evidence")
            if (type == "Events.Count") ExperiencePolicySchema.event(node["name"])
        }
        return condition
    }
}
