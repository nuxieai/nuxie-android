package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.ReleaseJson.array
import ai.nuxie.sdk.experiences.ReleaseJson.boolean
import ai.nuxie.sdk.experiences.ReleaseJson.exact
import ai.nuxie.sdk.experiences.ReleaseJson.fail
import ai.nuxie.sdk.experiences.ReleaseJson.hash
import ai.nuxie.sdk.experiences.ReleaseJson.id
import ai.nuxie.sdk.experiences.ReleaseJson.ids
import ai.nuxie.sdk.experiences.ReleaseJson.integer
import ai.nuxie.sdk.experiences.ReleaseJson.record
import ai.nuxie.sdk.experiences.ReleaseJson.sortedUnique
import ai.nuxie.sdk.experiences.ReleaseJson.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Validates the local program before any adapter can interpret its actions. */
internal object DeviceLegSchemaValidator {
    fun validate(value: JsonObject) {
        val root = exact(value, setOf("schemaVersion", "identity", "metadata", "presentation", "leg", "products",
            "placements", "viewModelValues", "screenBehaviors", "render", "requirements", "provenance"))
        if (text(root["schemaVersion"]) != "nuxie.device-leg-release.v1") fail("descriptor version")
        DeviceLegReleaseSchema.validate(root)
        val leg = exact(root["leg"], setOf("schemaVersion", "id", "entryCondition", "entryStepId", "steps", "routes",
            "screens", "reentry", "entitlementGate", "facts", "inputs", "outputs", "completionOutputs"))
        if (text(leg["schemaVersion"]) != "nuxie.experience-planes.v1") fail("leg version")
        hash(leg["id"])
        JourneyPlaneProfile.validateEntry(leg["entryCondition"])
        val reentry = record(leg["reentry"])
        when (text(reentry["type"])) {
            "one_time", "every_time" -> exact(reentry, setOf("type"))
            "once_per_window" -> { exact(reentry, setOf("type", "windowSeconds")); integer(reentry["windowSeconds"], 1) }
            else -> fail("reentry")
        }
        val products = array(root["products"], 256).map { id(record(it)["id"]) }
        if (products.toSet().size != products.size) fail("duplicate product")
        val placements = array(root["placements"], 256).map {
            val placement = record(it)
            if (id(placement["productId"]) !in products) fail("placement product")
            id(placement["id"])
        }.toSet()
        val steps = array(leg["steps"], 10_000).map(::record)
        if (steps.isEmpty()) fail("empty leg")
        val ids = steps.map { id(it["id"]) }.toSet()
        if (ids.size != steps.size || id(leg["entryStepId"]) !in ids) fail("local cursor")
        val screens = array(leg["screens"]).map {
            val screen = exact(it, setOf("id", "responseCaptures"), setOf("defaultViewModelName", "defaultInstanceId"))
            screen["defaultViewModelName"]?.let { value -> id(value) }
            screen["defaultInstanceId"]?.let { value -> id(value) }
            val captures = ids(screen["responseCaptures"], 256)
            if (captures.any { it.encodeToByteArray().size > 128 }) fail("response capture key")
            sortedUnique(captures)
            id(screen["id"])
        }
        if (screens.toSet().size != screens.size) fail("duplicate screen")
        for (step in steps) when (text(step["kind"])) {
            "complete" -> { exact(step, setOf("kind", "id", "outcome")); id(step["outcome"]) }
            "action" -> {
                exact(step, setOf("kind", "id", "action", "outlets"))
                for (target in record(step["outlets"]).values) if (id(target) !in ids) fail("local outlet")
                DeviceLegGrammar.action(step["action"], screens.toSet(), placements)
            }
            else -> fail("step kind")
        }
        val routes = mutableSetOf<Pair<JsonObject, String>>()
        for (value in array(leg["routes"])) {
            val route = exact(value, setOf("host", "eventName", "entryStepId"))
            val host = record(route["host"])
            when (text(host["kind"])) {
                "journey" -> exact(host, setOf("kind"))
                "screen" -> { exact(host, setOf("kind", "screenId")); if (id(host["screenId"]) !in screens) fail("route screen") }
                else -> fail("route host")
            }
            val event = id(route["eventName"])
            val cursor = id(route["entryStepId"])
            if (cursor !in ids || !routes.add(host to event)) fail("route cursor or duplicate")
            val step = steps.first { id(it["id"]) == cursor }
            if (event == "host_dismissed" && text(step["kind"]) == "action" &&
                text(record(step["action"])["type"]) in DeviceLegGrammar.presenting) fail("host dismissal presents")
        }
        val gate = exact(leg["entitlementGate"], setOf("enabled", "products"))
        boolean(gate["enabled"])
        for (value in array(gate["products"])) {
            val product = exact(value, setOf("productId", "featureIds"))
            if (id(product["productId"]) !in products) fail("entitlement product")
            ids(product["featureIds"])
        }
        facts(leg)
        DeviceLegGrammar.boundary(leg["inputs"])
        DeviceLegGrammar.fields(leg["outputs"], true)
        for ((outcome, boundary) in record(leg["completionOutputs"])) {
            id(kotlinx.serialization.json.JsonPrimitive(outcome)); DeviceLegGrammar.boundary(boundary)
        }
        if (screens.isEmpty()) {
            if (root["render"] != JsonNull || root["requirements"] != JsonNull) fail("renderless leg")
        } else {
            val render = record(root["render"])
            record(root["requirements"])
            val rendered = array(render["screens"]).map { id(record(it)["id"]) }
            if (rendered.size != rendered.toSet().size || rendered.toSet() != screens.toSet()) fail("render closure")
        }
        val behaviors = array(root["screenBehaviors"]).map { id(record(it)["screenId"]) }
        if (behaviors.size != behaviors.toSet().size || behaviors.toSet() != screens.toSet()) fail("behavior closure")
        for (value in array(root["viewModelValues"])) {
            val binding = exact(value, setOf("viewModelName", "path", "value"), setOf("instanceId", "instanceName"))
            id(binding["viewModelName"]); text(binding["path"])
            binding["instanceId"]?.let { id(it) }; binding["instanceName"]?.let { id(it) }
        }
    }

    private fun facts(leg: JsonObject) {
        val facts = exact(leg["facts"], setOf("propertyKeys", "segmentIds", "experimentIds"))
        val properties = mutableSetOf<String>(); val segments = mutableSetOf<String>(); val experiments = mutableSetOf<String>()
        fun walk(value: JsonElement?) {
            when (value) {
                is JsonArray -> value.forEach { walk(it) }
                is JsonObject -> {
                    when ((value["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                        "User" -> properties.add(id(value["key"]))
                        "Segment" -> {
                            if ((value["op"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "entered_within") fail("segment transition")
                            segments.add(id(value["id"]))
                        }
                        "segment" -> segments.add(id(value["segmentId"]))
                        "experiment" -> experiments.add(id(value["experimentId"]))
                    }
                    value.values.forEach { walk(it) }
                }
                else -> Unit
            }
        }
        walk(leg["entryCondition"]); walk(leg["steps"])
        for ((key, expected) in listOf("propertyKeys" to properties, "segmentIds" to segments, "experimentIds" to experiments)) {
            if (ids(facts[key]) != expected.sorted()) fail("fact proof")
        }
    }
}
