package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieHostCommand
import ai.nuxie.sdk.runtime.NuxieHostValue
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns one committed renderer transaction into at most one durable Journey
 * emission batch. Publication remains serialized until the presented frame is
 * visible, so a native event can never outrun its screen's reveal boundary.
 */
internal class DeviceLegRuntimeEmissionCoordinator(
    private val journeyId: String,
    private val screenId: String,
    descriptor: JsonObject,
    nextBatchSequence: Long,
    nextEmissionSequence: Long,
    private val onEmissionBatch: suspend (DeviceLegScreenEmissionBatch) -> Boolean,
    private val onScreenChanged: suspend (String) -> Boolean = { true },
    private val onPresentationRevealed: suspend (String) -> Unit,
    private val onOpenLink: (String, String?) -> Unit = { _, _ -> },
    private val createId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val gate = Mutex()
    private val revealed = CompletableDeferred<Unit>()
    private val controls = controlsForScreen(descriptor, screenId)
    private var nextBatch = nextBatchSequence
    private var nextEmission = nextEmissionSequence
    private var closed = false

    init {
        require(nextBatchSequence >= 0) { "Next presentation batch sequence must be nonnegative" }
        require(nextEmissionSequence >= 0) {
            "Next presentation emission sequence must be nonnegative"
        }
    }

    suspend fun reveal(): Boolean = gate.withLock {
        if (closed) return@withLock false
        if (revealed.isCompleted) return@withLock true
        return@withLock runCatching {
            check(onScreenChanged(screenId)) { "Journey screen activation was rejected" }
            onPresentationRevealed(screenId)
            revealed.complete(Unit)
            true
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Journey presentation reveal callback failed", error)
            closed = true
            revealed.complete(Unit)
            false
        }
    }

    suspend fun publish(outcome: NuxiePlayerStepOutcome, correlationId: ULong): Boolean {
        revealed.await()
        return gate.withLock {
            if (closed) return@withLock false
            val projected = project(outcome, correlationId)
            projected.links.forEach { link ->
                runCatching { onOpenLink(link.url, link.target) }
                    .onFailure { error ->
                        Log.w(LOG_TAG, "Journey renderer open-link callback failed", error)
                    }
            }
            val drafts = when (val control = projected.control) {
                null -> projected.drafts
                else -> {
                    val authored = materializeControl(control) ?: return@withLock true
                    authored + projected.drafts
                }
            }
            if (drafts.isEmpty()) return@withLock true
            if (drafts.any(Draft::isInvalid)) {
                Log.w(LOG_TAG, "Rejected invalid renderer emission transaction for $screenId")
                return@withLock true
            }
            if (nextBatch == Long.MAX_VALUE ||
                drafts.size.toLong() > Long.MAX_VALUE - nextEmission
            ) {
                closed = true
                return@withLock false
            }

            val source = projected.control?.invocation?.let { invocation ->
                DeviceLegScreenEmissionSource(
                    screenId = projected.control.screenId,
                    actionId = invocation.actionId,
                    componentId = invocation.componentId,
                    instanceId = invocation.instanceId,
                )
            } ?: projected.source ?: DeviceLegScreenEmissionSource(
                screenId = screenId,
                actionId = "runtime:$correlationId",
            )
            val firstEmission = nextEmission
            val occurredAt = nowMillis()
            val invocationId = createId()
            val emissions = drafts.mapIndexed { offset, draft ->
                draft.materialize(
                    id = createId(),
                    sequence = firstEmission + offset.toLong(),
                    occurredAtMillis = occurredAt,
                )
            }
            val batch = DeviceLegScreenEmissionBatch(
                journeyId = journeyId,
                batchSequence = nextBatch,
                invocationId = invocationId,
                source = source,
                emissions = emissions,
            )
            val accepted = runCatching { onEmissionBatch(batch) }
                .onFailure { error ->
                    Log.w(LOG_TAG, "Journey renderer emission publication failed", error)
                }
                .getOrDefault(false)
            if (!accepted) {
                closed = true
                return@withLock false
            }
            nextBatch += 1
            nextEmission += emissions.size.toLong()
            true
        }
    }

    /** Waits for an in-flight publication before preventing any later one. */
    suspend fun close() = gate.withLock {
        closed = true
        if (!revealed.isCompleted) revealed.complete(Unit)
    }

    private fun project(
        outcome: NuxiePlayerStepOutcome,
        correlationId: ULong,
    ): Projection {
        val drafts = mutableListOf<Draft>()
        val links = mutableListOf<OpenLink>()
        var source: DeviceLegScreenEmissionSource? = null
        var control: Control? = null
        var multipleControls = false

        outcome.events.forEach { event ->
            val properties = event.propertiesMap() ?: return@forEach
            val eventScreenId = properties.string("screenId", "screen_id") ?: screenId
            val componentId = properties.string(
                "componentId",
                "component_id",
                "elementId",
                "element_id",
            )
            val instanceId = properties.string("instanceId", "instance_id")
            val actionId = controlActionId(event, properties)
            if (event.name == GENERATED_INTERACTION_EVENT && actionId == null) {
                return@forEach
            }
            when {
                actionId != null -> {
                    if (control != null) multipleControls = true else {
                        control = Control(
                            screenId = eventScreenId,
                            invocation = Invocation(
                                actionId = actionId,
                                value = properties["value"],
                                componentId = componentId,
                                instanceId = instanceId,
                            ),
                        )
                    }
                }
                event.url.isNotEmpty() -> links += OpenLink(
                    event.url,
                    event.target.takeIf(String::isNotEmpty),
                )
                event.name.isNotEmpty() -> {
                    drafts += Draft.Event(event.name, properties)
                    if (source == null) {
                        source = DeviceLegScreenEmissionSource(
                            screenId = eventScreenId,
                            actionId = "runtime:$correlationId",
                            componentId = componentId,
                            instanceId = instanceId,
                        )
                    }
                }
            }
        }
        outcome.hostCommands.forEach { command ->
            val payload = command.value.toJsonElement() ?: return@forEach
            val properties = payload.properties()
            when (command.name) {
                RESPONSE_SET_EVENT -> {
                    val field = properties["field"]?.jsonPrimitive?.contentOrNull
                    val value = properties["value"]
                    if (field != null && field.isNotEmpty() && value != null) {
                        drafts += Draft.ResponseSet(field, value)
                    }
                }
                RESPONSE_UNSET_EVENT -> {
                    val field = properties["field"]?.jsonPrimitive?.contentOrNull
                    if (field != null && field.isNotEmpty()) drafts += Draft.ResponseUnset(field)
                }
                NAVIGATE_EVENT -> Log.w(
                    LOG_TAG,
                    "Rejected renderer \$navigate command: Journey routes own navigation",
                )
                else -> {
                    drafts += Draft.Event(command.name, properties)
                    if (source == null) {
                        source = DeviceLegScreenEmissionSource(
                            screenId = properties.string("screenId", "screen_id") ?: screenId,
                            actionId = "runtime:$correlationId",
                            componentId = properties.string(
                                "componentId",
                                "component_id",
                                "elementId",
                                "element_id",
                            ),
                            instanceId = properties.string("instanceId", "instance_id"),
                        )
                    }
                }
            }
        }
        if (multipleControls) {
            Log.w(LOG_TAG, "Rejected renderer transaction with multiple signed controls")
            return Projection(emptyList(), null, null, emptyList())
        }
        return Projection(drafts, source, control, links)
    }

    private fun controlActionId(
        event: NuxieRuntimeEvent,
        properties: JsonObject,
    ): String? {
        if (event.name != GENERATED_INTERACTION_EVENT) {
            return event.name.takeIf(controls::containsKey)
        }
        if (event.coreType != GENERATED_INTERACTION_CORE_TYPE ||
            event.url.isNotEmpty() || event.target.isNotEmpty() ||
            event.properties.map { it.name }.distinct().size != event.properties.size
        ) return null
        val actionId = properties.string("actionId") ?: return null
        val required = listOf("nuxieTrigger", "actionId", "componentId") +
            if ("instanceId" in properties) listOf("instanceId") else emptyList()
        if (required.any { properties.string(it)?.isNotBlank() != true }) return null
        return actionId.takeIf(controls::containsKey)
    }

    private fun materializeControl(control: Control): List<Draft>? {
        val behavior = controls[control.invocation.actionId] ?: return null
        if (behavior.string("kind") != "declarative") {
            Log.w(LOG_TAG, "Screen script control is unavailable: ${control.invocation.actionId}")
            return null
        }
        val program = behavior["program"] as? JsonArray ?: return null
        return runCatching {
            program.map { element ->
                val action = element.jsonObject
                when (action.string("type")) {
                    "emit" -> Draft.Event(
                        name = requireNotNull(action.string("eventName")),
                        payload = (action["payload"] as? JsonObject).orEmpty().mapValues {
                            resolveSource(it.value.jsonObject, control.invocation)
                        }.let(::JsonObject),
                    )
                    "response_set" -> Draft.ResponseSet(
                        field = requireNotNull(action.string("field")),
                        value = resolveSource(
                            requireNotNull(action["value"]) { "response value is missing" }.jsonObject,
                            control.invocation,
                        ),
                    )
                    "response_unset" -> Draft.ResponseUnset(
                        field = requireNotNull(action.string("field")),
                    )
                    else -> error("unsupported declarative screen action")
                }
            }
        }.onFailure { error ->
            Log.w(LOG_TAG, "Rejected signed screen control ${control.invocation.actionId}", error)
        }.getOrNull()
    }

    private fun resolveSource(source: JsonObject, invocation: Invocation): JsonElement =
        when (source.string("source")) {
            "literal" -> requireNotNull(source["value"])
            "invocation_value" -> requireNotNull(invocation.value)
            "component_id" -> JsonPrimitive(requireNotNull(invocation.componentId).takeIf {
                it.isNotEmpty()
            } ?: error("component id is missing"))
            "instance_id" -> JsonPrimitive(requireNotNull(invocation.instanceId).takeIf {
                it.isNotEmpty()
            } ?: error("instance id is missing"))
            else -> error("unsupported screen value source")
        }

    private sealed interface Draft {
        fun isInvalid(): Boolean = when (this) {
            is Event -> name.isEmpty() || name.startsWith('$')
            is ResponseSet -> field.isEmpty()
            is ResponseUnset -> field.isEmpty()
        }

        fun materialize(id: String, sequence: Long, occurredAtMillis: Long) =
            when (this) {
                is Event -> DeviceLegScreenEmission(id, sequence, occurredAtMillis, name, payload)
                is ResponseSet -> DeviceLegScreenEmission(
                    id,
                    sequence,
                    occurredAtMillis,
                    RESPONSE_SET_EVENT,
                    JsonObject(mapOf("field" to JsonPrimitive(field), "value" to value)),
                )
                is ResponseUnset -> DeviceLegScreenEmission(
                    id,
                    sequence,
                    occurredAtMillis,
                    RESPONSE_UNSET_EVENT,
                    JsonObject(mapOf("field" to JsonPrimitive(field))),
                )
            }

        data class Event(val name: String, val payload: JsonObject) : Draft
        data class ResponseSet(val field: String, val value: JsonElement) : Draft
        data class ResponseUnset(val field: String) : Draft
    }

    private data class Projection(
        val drafts: List<Draft>,
        val source: DeviceLegScreenEmissionSource?,
        val control: Control?,
        val links: List<OpenLink>,
    )

    private data class Control(val screenId: String, val invocation: Invocation)

    private data class Invocation(
        val actionId: String,
        val value: JsonElement?,
        val componentId: String?,
        val instanceId: String?,
    )

    private data class OpenLink(val url: String, val target: String?)

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val GENERATED_INTERACTION_EVENT = "Nuxie Interaction"
        const val GENERATED_INTERACTION_CORE_TYPE = 128
        const val RESPONSE_SET_EVENT = "\$response_set"
        const val RESPONSE_UNSET_EVENT = "\$response_unset"
        const val NAVIGATE_EVENT = "\$navigate"

        fun controlsForScreen(descriptor: JsonObject, screenId: String): Map<String, JsonObject> =
            (descriptor["screenBehaviors"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .singleOrNull { it.string("screenId") == screenId }
                ?.get("controls")
                ?.let { it as? JsonArray }
                .orEmpty()
                .mapNotNull { value ->
                    val control = value as? JsonObject ?: return@mapNotNull null
                    val actionId = control.string("actionId") ?: return@mapNotNull null
                    val behavior = control["behavior"] as? JsonObject ?: return@mapNotNull null
                    actionId to behavior
                }
                .toMap()

        fun NuxieRuntimeEvent.propertiesMap(): JsonObject? {
            val keys = properties.map { it.name }
            if (keys.size != keys.toSet().size) return null
            return properties.mapNotNull { property ->
                property.value.toJsonElement()?.let { property.name to it }
            }.toMap().let(::JsonObject)
        }

        fun NuxieRuntimeEventPropertyValue.toJsonElement(): JsonElement? = when (this) {
            is NuxieRuntimeEventPropertyValue.Number -> value
                .takeIf(Float::isFinite)
                ?.let(::JsonPrimitive)
            is NuxieRuntimeEventPropertyValue.Bool -> JsonPrimitive(value)
            is NuxieRuntimeEventPropertyValue.Bytes -> strictUtf8(value)?.let(::JsonPrimitive)
                ?: JsonNull
            is NuxieRuntimeEventPropertyValue.Color -> JsonPrimitive(argb.toUInt().toLong())
            is NuxieRuntimeEventPropertyValue.Enum -> JsonPrimitive(ordinal.toDouble())
            NuxieRuntimeEventPropertyValue.Trigger -> JsonNull
        }

        fun NuxieHostValue.toJsonElement(): JsonElement? = when (this) {
            NuxieHostValue.Null -> JsonNull
            is NuxieHostValue.Bool -> JsonPrimitive(value)
            is NuxieHostValue.Number -> value.takeIf(Double::isFinite)?.let(::JsonPrimitive)
            is NuxieHostValue.String -> JsonPrimitive(value)
            is NuxieHostValue.List -> values.map { it.toJsonElement() ?: return null }
                .let(::JsonArray)
            is NuxieHostValue.Object -> fields.associate { field ->
                field.key to (field.value.toJsonElement() ?: return null)
            }.let(::JsonObject)
        }

        fun JsonElement.properties(): JsonObject = when (this) {
            is JsonObject -> this
            else -> JsonObject(mapOf("value" to this))
        }

        fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        }

        fun strictUtf8(bytes: ByteArray): String? = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }
}
