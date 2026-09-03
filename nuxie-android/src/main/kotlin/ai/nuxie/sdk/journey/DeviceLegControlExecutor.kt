package ai.nuxie.sdk.journey

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One deterministic transition of a compiled flat device leg. The caller
 * persists Advance/Park before evaluating another step or dispatching work. */
internal class DeviceLegControlExecutor(
    private val timezones: SignedTimezoneBundle,
    private val currentDeviceTimezoneIdentifier: String,
    private val appDefaultTimezoneIdentifier: String?,
) {
    data class Checkpoint(val anchorAtMillis: Long, val wakeAtMillis: Long)
    data class Event(val name: String, val occurredAtMillis: Long, val properties: JsonObject)
    data class Signal(val event: Event? = null, val responsesChanged: Boolean = false)
    data class ExperimentSelection(
        val experimentId: String,
        val variantId: String,
        val assignedVariantId: String?,
        val isHoldout: Boolean,
        val source: Source,
    ) {
        enum class Source {
            PROFILE,
            NO_ASSIGNMENT,
            INVALID_ASSIGNMENT,
        }
    }

    sealed interface Result {
        data class Advance(
            val stepId: String,
            val context: JsonObject,
            val experimentSelection: ExperimentSelection? = null,
        ) : Result
        data class Park(val stepId: String, val checkpoint: Checkpoint) : Result
        data class Complete(val outcome: String) : Result
        data class Dispatch(val stepId: String, val action: JsonObject) : Result
        data object Invalid : Result
    }

    fun evaluate(
        step: JsonObject,
        context: JsonObject,
        assignments: JsonObject,
        nowMillis: Long,
        checkpoint: Checkpoint? = null,
        signal: Signal = Signal(),
    ): Result = try {
        evaluateChecked(step, context, assignments, nowMillis, checkpoint, signal)
    } catch (_: Exception) {
        Result.Invalid
    }

    private fun evaluateChecked(
        step: JsonObject,
        context: JsonObject,
        assignments: JsonObject,
        nowMillis: Long,
        checkpoint: Checkpoint?,
        signal: Signal,
    ): Result {
        return when (step.text("kind")) {
            "complete" -> step.text("outcome")?.let(Result::Complete) ?: Result.Invalid
            "action" -> {
                val action = step["action"] as? JsonObject ?: return Result.Invalid
                val outlets = step["outlets"] as? JsonObject ?: return Result.Invalid
                when (action.text("type")) {
                    "condition" -> condition(action, outlets, context)
                    "experiment" -> experiment(action, outlets, context, assignments)
                    "time_window" -> timeWindow(step, action, outlets, context, nowMillis, checkpoint)
                    "delay" -> delay(step, action, outlets, context, nowMillis, checkpoint)
                    "wait_until" -> waitUntil(step, action, outlets, context, nowMillis, checkpoint, signal)
                    null -> Result.Invalid
                    else -> Result.Dispatch(step.text("id") ?: return Result.Invalid, action)
                }
            }
            else -> Result.Invalid
        }
    }

    private fun condition(action: JsonObject, outlets: JsonObject, context: JsonObject): Result {
        val branches = action["branches"] as? JsonArray ?: return Result.Invalid
        val selected = branches.mapNotNull { it as? JsonObject }.firstOrNull {
            val expression = it["condition"] as? JsonObject ?: return@firstOrNull false
            DeviceLegValues.evaluate(expression, context) == true
        }?.text("id") ?: "default"
        return advance(outlets, selected, context)
    }

    private fun experiment(
        action: JsonObject,
        outlets: JsonObject,
        context: JsonObject,
        assignments: JsonObject,
    ): Result {
        val experimentId = action.text("experimentId") ?: return Result.Invalid
        val variants = action["variants"] as? JsonArray ?: return Result.Invalid
        val available = variants.mapNotNull { (it as? JsonObject)?.text("id") }
        if (available.isEmpty()) return Result.Invalid
        val assignment = assignments[experimentId] as? JsonObject
        val assigned = assignment?.text("variantId")
        val selected = assigned?.takeIf(available::contains) ?: available.first()
        val source = when {
            assigned == null -> ExperimentSelection.Source.NO_ASSIGNMENT
            assigned == selected -> ExperimentSelection.Source.PROFILE
            else -> ExperimentSelection.Source.INVALID_ASSIGNMENT
        }
        val advanced = advance(outlets, selected, context)
        if (advanced !is Result.Advance) return advanced
        return advanced.copy(
            experimentSelection = ExperimentSelection(
                experimentId = experimentId,
                variantId = selected,
                assignedVariantId = assigned,
                isHoldout = assignment?.get("isHoldout")?.jsonPrimitive?.booleanOrNull ?: false,
                source = source,
            ),
        )
    }

    private fun timeWindow(
        step: JsonObject,
        action: JsonObject,
        outlets: JsonObject,
        context: JsonObject,
        nowMillis: Long,
        checkpoint: Checkpoint?,
    ): Result {
        val timezone = resolveTimezone(action["timezone"] as? JsonObject ?: return Result.Invalid)
            ?: return Result.Invalid
        val days = (action["daysOfWeek"] as? JsonArray)?.map { it.jsonPrimitive.content.toInt() }
            ?: return Result.Invalid
        return when (val decision = TimeWindowMath.evaluate(nowMillis, action.text("startTime") ?: return Result.Invalid,
            action.text("endTime") ?: return Result.Invalid, days, timezone)) {
            TimeWindowMath.Decision.InWindow -> advance(outlets, "inside", context)
            is TimeWindowMath.Decision.Pause -> Result.Park(step.text("id") ?: return Result.Invalid,
                Checkpoint(checkpoint?.anchorAtMillis ?: nowMillis, decision.untilMilliseconds))
            TimeWindowMath.Decision.Malformed, TimeWindowMath.Decision.Unavailable -> Result.Invalid
        }
    }

    private fun resolveTimezone(value: JsonObject): SignedTimezoneBundle.Timezone? = when (value.text("kind")) {
        "device" -> timezones.resolveDeviceIdentifier(currentDeviceTimezoneIdentifier)
        "app_default" -> appDefaultTimezoneIdentifier?.let(timezones::resolve)
        "iana" -> value.text("identifier")?.let(timezones::resolve)
        else -> null
    }

    private fun delay(
        step: JsonObject,
        action: JsonObject,
        outlets: JsonObject,
        context: JsonObject,
        nowMillis: Long,
        checkpoint: Checkpoint?,
    ): Result {
        val current = checkpoint ?: Checkpoint(nowMillis,
            Math.addExact(nowMillis, action.getValue("durationMs").jsonPrimitive.long))
        return if (nowMillis < current.wakeAtMillis) Result.Park(step.text("id") ?: return Result.Invalid, current)
        else advance(outlets, "next", context)
    }

    private fun waitUntil(
        step: JsonObject,
        action: JsonObject,
        outlets: JsonObject,
        context: JsonObject,
        nowMillis: Long,
        checkpoint: Checkpoint?,
        signal: Signal,
    ): Result {
        val current = checkpoint ?: Checkpoint(nowMillis,
            Math.addExact(nowMillis, action.getValue("maxTimeMs").jsonPrimitive.long))
        val trigger = action["trigger"] as? JsonObject ?: return Result.Invalid
        val kind = trigger.text("kind") ?: return Result.Invalid
        val event = signal.event
        val eventMatches = kind != "response_change" && event != null &&
            event.name == trigger.text("eventName") && event.occurredAtMillis in current.anchorAtMillis..current.wakeAtMillis
        val responseMatches = kind != "event" && signal.responsesChanged
        val evaluatedContext = if (eventMatches) JsonObject(context + ("event" to event!!.properties)) else context
        if ((eventMatches || responseMatches) &&
            DeviceLegValues.evaluate(action.getValue("condition").jsonObject, evaluatedContext) == true
        ) return advance(outlets, "satisfied", evaluatedContext)
        if (nowMillis >= current.wakeAtMillis) return advance(outlets, "timeout", context)
        return Result.Park(step.text("id") ?: return Result.Invalid, current)
    }

    private fun advance(outlets: JsonObject, outlet: String, context: JsonObject): Result =
        (outlets[outlet] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { Result.Advance(it, context) } ?: Result.Invalid

    /** Resolve a host/effect outcome through the authenticated local outlet map. */
    fun selectOutlet(step: JsonObject, outlet: String, context: JsonObject): Result {
        if (step.text("kind") != "action") return Result.Invalid
        return advance(step["outlets"] as? JsonObject ?: return Result.Invalid, outlet, context)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
