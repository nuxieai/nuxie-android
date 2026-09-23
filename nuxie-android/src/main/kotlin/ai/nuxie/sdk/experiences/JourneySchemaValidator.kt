package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.JourneyReleaseJson.array
import ai.nuxie.sdk.experiences.JourneyReleaseJson.boolean
import ai.nuxie.sdk.experiences.JourneyReleaseJson.exact
import ai.nuxie.sdk.experiences.JourneyReleaseJson.fail
import ai.nuxie.sdk.experiences.JourneyReleaseJson.hash
import ai.nuxie.sdk.experiences.JourneyReleaseJson.id
import ai.nuxie.sdk.experiences.JourneyReleaseJson.ids
import ai.nuxie.sdk.experiences.JourneyReleaseJson.integer
import ai.nuxie.sdk.experiences.JourneyReleaseJson.record
import ai.nuxie.sdk.experiences.JourneyReleaseJson.sortedUnique
import ai.nuxie.sdk.experiences.JourneyReleaseJson.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Validates the local program before any adapter can interpret its actions. */
internal object JourneySchemaValidator {
    fun validate(value: JsonObject) {
        val root = exact(value, setOf("schemaVersion", "identity", "metadata", "presentation", "leg", "products",
            "placements", "viewModelValues", "screenBehaviors", "render", "requirements", "provenance"))
        if (text(root["schemaVersion"]) != "nuxie.journey-release.v2") fail("descriptor version")
        JourneyReleaseSchema.validate(root)
        val leg = exact(root["leg"], setOf("schemaVersion", "id", "entryCondition", "entryStepId", "steps", "routes",
            "screens", "policy", "offers", "facts", "inputs", "outputs", "completionOutputs"))
        if (text(leg["schemaVersion"]) != "nuxie.experience-planes.v1") fail("leg version")
        hash(leg["id"])
        JourneyPlaneProfile.validateEntry(leg["entryCondition"])
        ExperiencePolicySchema.validate(leg["policy"])
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
                JourneyGrammar.action(step["action"], screens.toSet(), placements)
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
                text(record(step["action"])["type"]) in JourneyGrammar.presenting) fail("host dismissal presents")
        }
        val offerScreens = mutableSetOf<String>()
        val offerRoutes = array(leg["routes"]).map(::record)
        for (item in array(leg["offers"])) {
            val offer = exact(item, setOf("screenId", "placementIds", "alreadyEntitledStepId", "unknownStepId"))
            val screen = id(offer["screenId"])
            if (screen !in screens || !offerScreens.add(screen)) fail("offer screen")
            val offeredPlacements = ids(offer["placementIds"])
            if (offeredPlacements.isEmpty() || offeredPlacements.toSet().size != offeredPlacements.size ||
                !placements.containsAll(offeredPlacements)) fail("offer placements")
            for ((key, event) in listOf("alreadyEntitledStepId" to "\$offer_already_entitled", "unknownStepId" to "\$offer_access_unknown")) {
                val cursor = id(offer[key])
                if (cursor !in ids || offerRoutes.none {
                    val host = record(it["host"])
                    text(host["kind"]) == "screen" && text(host["screenId"]) == screen &&
                        text(it["eventName"]) == event && text(it["entryStepId"]) == cursor
                }) fail("offer alternative")
            }
        }
        facts(leg)
        JourneyGrammar.boundary(leg["inputs"])
        JourneyGrammar.fields(leg["outputs"], true)
        for ((outcome, boundary) in record(leg["completionOutputs"])) {
            id(kotlinx.serialization.json.JsonPrimitive(outcome)); JourneyGrammar.boundary(boundary)
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
        sortedUnique(behaviors)
        if (behaviors.toSet() != screens.toSet()) fail("behavior closure")
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
                        "User", "Customer.Field" -> properties.add(id(value["key"]))
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
