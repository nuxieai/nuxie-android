package ai.nuxie.sdk.journey

import kotlinx.serialization.json.*

/** Prepared continuation. Its owner arms the wake and includes the profile
 * revision in identity, so stale timers cannot admit a replacement profile. */
internal class ClosedConditionCheck {
    enum class Outcome { Pending, Satisfied, Refused, Timeout, Unavailable, Cancelled }
    sealed interface Mode {
        data object Admission : Mode
        data class Wait(val timeoutMilliseconds: Long, val eventName: String? = null) : Mode
    }
    data class State(val token: Long, val outcome: Outcome, val nextWakeMilliseconds: Long? = null)
    private data class Check(val tree: ClosedCondition, val identity: String, val mode: Mode, val deadline: Long)
    private var generation = 0L
    private var check: Check? = null
    var current = State(0,Outcome.Cancelled)
        private set
    fun cancel() {
        generation++
        check = null
        current = State(generation,Outcome.Cancelled)
    }
    fun start(tree: ClosedCondition, facts: ClosedConditionFacts, identity: String, mode: Mode): State {
        generation++
        val timeout = when(mode) { Mode.Admission -> 5_000L; is Mode.Wait -> mode.timeoutMilliseconds }
        if (timeout < 0 || facts.nowMilliseconds > Long.MAX_VALUE - timeout) {
            check = null
            current = State(generation,Outcome.Unavailable)
            return current
        }
        val deadline = facts.nowMilliseconds + timeout
        check = Check(tree,identity,mode,deadline)
        current = State(generation,Outcome.Pending,deadline)
        return advance(generation,facts,identity)
    }
    fun advance(token: Long, input: ClosedConditionFacts, identity: String, eventName: String? = null): State {
        val cancelled = State(token,Outcome.Cancelled)
        val check = check?.takeIf { token == generation } ?: return cancelled
        if (check.identity != identity) { cancel(); return cancelled }
        if (current.outcome != Outcome.Pending) return current
        val expired = input.nowMilliseconds >= check.deadline
        if (check.mode is Mode.Wait && check.mode.eventName != null && eventName != check.mode.eventName) {
            if (expired) current = State(token,Outcome.Timeout)
            return current
        }
        var facts = input
        if (check.mode == Mode.Admission) {
            val access = input.snapshot["access"] as? JsonObject
            val unknown = check.tree.featureIds().filter { access?.get(it) == JsonNull }
            if (unknown.isNotEmpty() && !expired) return current
            if (unknown.isNotEmpty()) facts = input.copy(snapshot = JsonObject(input.snapshot + ("access" to JsonObject(checkNotNull(access) + unknown.associateWith { JsonPrimitive(true) }))))
        }
        val outcome = when(check.tree.evaluate(facts)) {
            ClosedConditionResult.Answer(true) -> Outcome.Satisfied
            ClosedConditionResult.Unavailable -> Outcome.Unavailable
            else -> if (check.mode == Mode.Admission) Outcome.Refused else if (expired) Outcome.Timeout else Outcome.Pending
        }
        current = State(token,outcome,if (outcome == Outcome.Pending) minOf(check.deadline,check.tree.nextClockChange(facts.nowMilliseconds,facts.zone) ?: check.deadline) else null)
        return current
    }
    fun continueServer(deviceRemainder: ClosedCondition?, facts: ClosedConditionFacts): State {
        val allowed = setOf("And","Or","Not","Compare","Contains","String","Number","Bool","List","Null","Env.Platform")
        require(deviceRemainder?.nodes()?.all { it.getValue("type").jsonPrimitive.content in allowed } != false) { "person fact in device continuation" }
        val outcome = when(deviceRemainder?.evaluate(facts)) {
            null, ClosedConditionResult.Answer(true) -> Outcome.Satisfied
            ClosedConditionResult.Unavailable -> Outcome.Unavailable
            else -> Outcome.Refused
        }
        generation++
        check = null
        current = State(generation,outcome)
        return current
    }
}
