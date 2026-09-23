package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.JourneyReleaseJson.array
import ai.nuxie.sdk.experiences.JourneyReleaseJson.exact
import ai.nuxie.sdk.experiences.JourneyReleaseJson.fail
import ai.nuxie.sdk.experiences.JourneyReleaseJson.id
import ai.nuxie.sdk.experiences.JourneyReleaseJson.integer
import ai.nuxie.sdk.experiences.JourneyReleaseJson.number
import ai.nuxie.sdk.experiences.JourneyReleaseJson.record
import ai.nuxie.sdk.experiences.JourneyReleaseJson.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The single signed Experience policy; retired lifecycle fields have no decoder. */
internal object ExperiencePolicySchema {
    fun validate(value: JsonElement?) {
        val policy = exact(value, setOf("entry", "exitWhenAny"), setOf("goal"))
        val entry = exact(policy["entry"], setOf("trigger", "frequency"), setOf("eligibility"))
        val trigger = record(entry["trigger"])
        when (text(trigger["type"])) {
            "api" -> exact(trigger, setOf("type"))
            "server_event" -> {
                exact(trigger, setOf("type", "connectorKey", "triggerKey"), setOf("identityField", "condition"))
                identifier(trigger["connectorKey"]); identifier(trigger["triggerKey"])
                trigger["identityField"]?.let(::identifier)
                trigger["condition"]?.let(::ir)
            }
            else -> criterion(trigger)
        }
        entry["eligibility"]?.let(::ir)
        val frequency = record(entry["frequency"])
        when (text(frequency["type"])) {
            "one_time", "every_match" -> exact(frequency, setOf("type"))
            "once_per_window" -> {
                exact(frequency, setOf("type", "window"))
                durationMillis(frequency["window"])
            }
            else -> fail("Experience frequency")
        }
        policy["goal"]?.let {
            val goal = exact(it, setOf("criterion", "attribution"))
            criterion(goal["criterion"])
            record(goal["criterion"])["condition"]?.let(::goalEvidence)
            val attribution = exact(goal["attribution"], setOf("basis", "window"))
            if (text(attribution["basis"]) !in setOf("first_shown", "entry")) fail("attribution basis")
            if (durationMillis(attribution["window"]) > 90L * 86_400_000) fail("attribution window")
        }
        for (item in array(policy["exitWhenAny"])) {
            val exit = record(item)
            when (text(exit["type"])) {
                "goal_met" -> {
                    exact(exit, setOf("type"))
                    if (policy["goal"] == null) fail("goal exit without goal")
                }
                "eligibility_lost" -> {
                    exact(exit, setOf("type"))
                    if (entry["eligibility"] == null) fail("eligibility exit without eligibility")
                }
                else -> criterion(exit)
            }
        }
    }

    fun durationMillis(value: JsonElement?): Long {
        val duration = exact(value, setOf("amount", "unit"))
        val multiplier = when (text(duration["unit"])) {
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 604_800_000L
            else -> fail("duration unit")
        }
        val amount = integer(duration["amount"], 1, 9_007_199_254_740_991L / multiplier)
        return amount * multiplier
    }

    private fun identifier(value: JsonElement?): String = id(value, 128).also {
        if (it.trim() != it) fail("policy identifier whitespace")
    }

    private fun event(value: JsonElement?) {
        val name = identifier(value)
        if (name.startsWith("\$") && name !in systemEvents) fail("unsupported policy evidence")
    }

    private fun criterion(value: JsonElement?) {
        val criterion = record(value)
        when (text(criterion["type"])) {
            "event" -> {
                exact(criterion, setOf("type", "eventName"), setOf("condition"))
                event(criterion["eventName"])
                criterion["condition"]?.let(::ir)
            }
            "segment_enter", "segment_leave" -> {
                exact(criterion, setOf("type", "segmentId"))
                identifier(criterion["segmentId"])
            }
            else -> fail("Experience criterion")
        }
    }

    private fun goalEvidence(node: JsonElement) {
        when (node) {
            is JsonArray -> node.forEach(::goalEvidence)
            is JsonObject -> {
                val type = (node["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (type != null && type !in goalEvidenceNodes) fail("mutable goal evidence")
                node.values.forEach(::goalEvidence)
            }
            else -> Unit
        }
    }

    private val goalEvidenceNodes = setOf(
        "Bool", "Number", "String", "Timestamp", "Duration", "List",
        "And", "Or", "Not", "Compare", "Event", "Pred", "PredAnd", "PredOr",
        "Journey.Id", "Time.Now", "Time.Ago", "Time.Window",
    )

    private fun ir(value: JsonElement) {
        val envelope = exact(value, setOf("ir_version", "expr"), setOf("engine_min", "compiled_at"))
        if (integer(envelope["ir_version"]) != 1L) fail("IR version")
        DeviceEntryIrSchema.validate(envelope["expr"])
        envelope["engine_min"]?.let {
            if ((text(it).substringBefore('.').toIntOrNull() ?: 1) > 1) fail("IR engine")
        }
        envelope["compiled_at"]?.let { number(it) }
        fun evidence(node: JsonElement) {
            when (node) {
                is JsonArray -> node.forEach(::evidence)
                is JsonObject -> {
                    val type = (node["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (type?.startsWith("Events.") == true) {
                        node["name"]?.let(::event)
                        if (type == "Events.InOrder") array(node["steps"]).forEach { event(record(it)["name"]) }
                    }
                    node.values.forEach(::evidence)
                }
                else -> Unit
            }
        }
        evidence(envelope)
    }

    private val systemEvents = setOf(
        "\$identify", "\$app_installed", "\$app_updated", "\$app_opened", "\$app_backgrounded",
        "\$feature_used", "\$products_unavailable", "\$screen_shown", "\$screen_dismissed",
        "\$purchase_completed", "\$purchase_failed", "\$purchase_cancelled", "\$purchase_pending", "\$purchase_synced",
        "\$restore_completed", "\$restore_failed", "\$restore_no_purchases",
        "\$notifications_enabled", "\$notifications_denied", "\$permission_granted", "\$permission_denied",
        "\$tracking_authorized", "\$tracking_denied", "\$experience_shown", "\$experience_dismissed",
        "\$experience_errored", "\$experience_artifact_load_succeeded", "\$experience_artifact_load_failed",
        "\$customer_updated", "\$app_action_requested", "\$experiment_exposure",
    )
}
