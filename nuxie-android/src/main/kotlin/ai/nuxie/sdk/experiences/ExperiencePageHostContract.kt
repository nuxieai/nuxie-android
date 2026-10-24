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

/** Published page names and transition references shared by admission and playback. */
internal object ExperiencePageHostContract {
    const val ARTBOARD = "\$nuxie.page"
    const val MACHINE = "\$nuxie.page"
    const val MODEL = "\$nuxie.Page"
    const val SCREENS = "screens"
    data class Host(val artboardName: String = ARTBOARD, val listPath: String = SCREENS,
        val modelName: String? = MODEL, val machineName: String? = if (modelName == MODEL) MACHINE else null)
    enum class Kind { PAGE, LAYER, CHOREOGRAPHED }
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

    // Signed rendered Experiences use pages. The imported file must supply the fixed artboard.
    fun isEnabled(descriptor: JsonObject?): Boolean = descriptor?.get("render") is JsonObject

    fun readHost(value: JsonElement): Host {
        val host = exact(value, setOf("artboardName", "list"))
        return Host(id(host["artboardName"], 256), oneOf(host["list"], "screens"))
    }

    fun transitionKind(value: JsonElement?): Kind {
        oneOf(value, "page")
        return Kind.PAGE
    }

    fun intent(input: JsonElement, source: String? = null): Intent {
        JourneyReleaseSchema.transition(input)
        val value = record(input)
        val style = when (text(value["type"])) {
            "magic-move" -> Style.MAGIC_MOVE
            "push" -> Style.PUSH
            "fade" -> Style.FADE
            "none" -> Style.NONE
            else -> Style.MODAL
        }
        val id = value["transitionId"] ?: (value["transitionIds"] as? JsonObject)?.get(source)
        return Intent(style, id?.let(JourneyReleaseSchema::releaseId))
    }

    /** Freeze a route's actual reference before adding its visit to page history. */
    fun resolveIntent(input: JsonObject?, source: String?): JsonObject? {
        if (input == null) return null
        val selected = intent(input, source)
        if (input.containsKey("transitionIds")) {
            return selected.transitionId?.let { id -> buildJsonObject {
                put("type", text(input["type"])); put("transitionId", id)
            } } ?: buildJsonObject { put("type", "none") }
        }
        if (selected.style == Style.FADE && selected.transitionId == null) {
            ai.nuxie.sdk.logging.NuxieLog.w("ExperiencePageStep", "Published fade has no transitionId; using a cut")
            return buildJsonObject { put("type", "none") }
        }
        return input
    }

    fun transitions(render: JsonObject): List<Transition> = array(render["transitions"]).map { input ->
        val value = record(input)
        fun endpoints(edge: JsonObject): Endpoints = Endpoints(
            integer(edge["durationMs"]),
            (edge["incomingOnTop"] ?: value["incomingOnTop"])?.let(::boolean) ?: true,
            JourneyReleaseSchema.releaseId(record(edge["source"])["completeEventName"]),
            JourneyReleaseSchema.releaseId(record(edge["destination"])["completeEventName"]))
        Transition(JourneyReleaseSchema.releaseId(value["id"]), transitionKind(value["kind"]),
            JourneyReleaseSchema.releaseId(value["sourceScreenId"]),
            JourneyReleaseSchema.releaseId(value["destinationScreenId"]), endpoints(value),
            value["reverse"]?.let { endpoints(record(it)) }, value["defaultPair"]?.let(::boolean) ?: false)
    }

    fun screenArtboard(descriptor: JsonObject, screenId: String): String {
        val render = record(descriptor["render"])
        val screen = array(render["screens"]).map(::record).single { text(it["id"]) == screenId }
        return text(screen["artboardName"])
    }

    // A null authored index takes the schema's first authored instance. The artboard default is the
    // same one (the runtime's ViewModel default is its first instance); defaultInstanceId only names it.
    fun pageRowSelection(descriptor: JsonObject, screenId: String, catalog: NuxieViewModelCatalog,
        schemaIndex: Int): RowSelection {
        val schema = catalog.schemas.single { it.index == schemaIndex }
        val screen = array(record(descriptor["leg"])["screens"]).map(::record).single { text(it["id"]) == screenId }
        return RowSelection(schema.index, null, schema.name, screen["defaultInstanceId"]?.let(::text))
    }

}
