package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.JourneyReleaseJson.array
import ai.nuxie.sdk.experiences.JourneyReleaseJson.boolean
import ai.nuxie.sdk.experiences.JourneyReleaseJson.exact
import ai.nuxie.sdk.experiences.JourneyReleaseJson.fail
import ai.nuxie.sdk.experiences.JourneyReleaseJson.id
import ai.nuxie.sdk.experiences.JourneyReleaseJson.integer
import ai.nuxie.sdk.experiences.JourneyReleaseJson.journeyId
import ai.nuxie.sdk.experiences.JourneyReleaseJson.oneOf
import ai.nuxie.sdk.experiences.JourneyReleaseJson.record
import ai.nuxie.sdk.experiences.JourneyReleaseJson.text
import ai.nuxie.sdk.runtime.NuxieViewModelCatalog
import kotlinx.serialization.json.*

/** Provisional publish metadata until UNIV-5113 supplies the final contract. */
internal object ExperienceLayerHostContract {
    data class Host(val artboardName: String, val listPath: String)
    enum class Kind { LAYER, CHOREOGRAPHED }
    enum class Style { MAGIC_MOVE, PUSH, FADE, NONE, CUSTOM, MODAL }
    data class Intent(val style: Style, val transitionId: String?)
    data class Endpoints(val durationMs: Long, val incomingOnTop: Boolean,
        val outgoingCompletionEvent: String, val incomingCompletionEvent: String)
    data class Transition(val id: String, val kind: Kind, val sourceScreenId: String,
        val destinationScreenId: String, val forward: Endpoints, val reverse: Endpoints?,
        val isDefaultPair: Boolean = false) {
        fun resolve(source: String, destination: String): Endpoints? = when {
            source == sourceScreenId && destination == destinationScreenId -> forward
            source == destinationScreenId && destination == sourceScreenId -> reverse
            else -> null
        }
    }
    data class RowSelection(val schemaIndex: Int, val authoredInstanceIndex: Int?,
        val rootName: String, val defaultInstanceId: String?)

    fun host(render: JsonObject): Host? = render["layerHost"]?.let(::readHost)

    fun isEnabled(descriptor: JsonObject?): Boolean =
        (descriptor?.get("render") as? JsonObject)?.let(::host) != null

    fun readHost(value: JsonElement): Host {
        val host = exact(value, setOf("artboardName", "list"))
        return Host(id(host["artboardName"], 256), oneOf(host["list"], "screens"))
    }

    fun transitionKind(value: JsonElement?): Kind = when (oneOf(value, "layer", "choreographed")) {
        "layer" -> Kind.LAYER
        else -> Kind.CHOREOGRAPHED
    }

    fun intent(input: JsonElement): Intent {
        val value = record(input)
        val style = when (text(value["type"])) {
            "magic-move" -> Style.MAGIC_MOVE
            "push" -> Style.PUSH
            "fade" -> Style.FADE
            "none" -> Style.NONE
            "custom" -> Style.CUSTOM
            "modal" -> Style.MODAL
            else -> fail("transition")
        }
        when (style) {
            Style.MAGIC_MOVE, Style.CUSTOM -> exact(value, setOf("type", "transitionId"))
            Style.MODAL -> {
                exact(value, setOf("type"), setOf("present", "height", "closedby"))
                value["present"]?.let { oneOf(it, "sheet", "fullscreen") }
                value["height"]?.let { oneOf(it, "large", "medium", "fit-content") }
                value["closedby"]?.let { oneOf(it, "any", "none") }
            }
            else -> exact(value, setOf("type"), setOf("transitionId"))
        }
        return Intent(style, value["transitionId"]?.let(::journeyId))
    }

    fun transitions(render: JsonObject): List<Transition> = array(render["transitions"]).map { input ->
        val value = record(input)
        fun endpoints(edge: JsonObject): Endpoints = Endpoints(
            integer(edge["durationMs"] ?: value["durationMs"]),
            boolean(edge["incomingOnTop"] ?: value["incomingOnTop"]),
            JourneyReleaseSchema.releaseId(record(edge["source"])["completeEventName"]),
            JourneyReleaseSchema.releaseId(record(edge["destination"])["completeEventName"]))
        Transition(JourneyReleaseSchema.releaseId(value["id"]), transitionKind(value["kind"]),
            JourneyReleaseSchema.releaseId(value["sourceScreenId"]),
            JourneyReleaseSchema.releaseId(value["destinationScreenId"]), endpoints(value),
            value["reverse"]?.let { endpoints(record(it)) }, value["defaultPair"]?.let(::boolean) ?: false)
    }

    /** Catalog defaults index the flat table; allocation takes a schema-local index. */
    fun rowSelection(descriptor: JsonObject, screenId: String, catalog: NuxieViewModelCatalog): RowSelection {
        val screens = array(record(descriptor["leg"])["screens"]).map(::record)
        val screen = requireNotNull(screens.singleOrNull { text(it["id"]) == screenId }) { "Unknown Screen" }
        val name = text(screen["defaultViewModelName"])
        require(screens.count { it["defaultViewModelName"] == JsonPrimitive(name) } == 1) {
            "Layer Screens require distinct root types"
        }
        val schema = requireNotNull(catalog.schemas.singleOrNull { it.name == name }) {
            "Layer row requires a unique Screen root type"
        }
        val authored = schema.defaultAuthoredInstance?.let { index ->
            requireNotNull(catalog.authoredInstances.getOrNull(index)?.takeIf { it.schemaIndex == schema.index }) {
                "Layer row has an invalid authored default"
            }.index
        }
        return RowSelection(schema.index, authored, name, screen["defaultInstanceId"]?.let(::text))
    }
}
