package ai.nuxie.sdk.events

import ai.nuxie.sdk.JourneyExitReason
import ai.nuxie.sdk.TriggerResult

/**
 * The canonical wire projection of TriggerResult that wrapper SDKs bind
 * (fixtures/encodings/trigger-result.json). The projection is lossless and
 * stable; it retains fields the post-cut PUBLIC Kotlin surface no longer
 * exposes (e.g. `source` on allowed) because the fixture pins the wrapper
 * contract until iOS lands its own cut and revs the fixture.
 */
internal object TriggerWireEncoder {
    /** Internal wire form used for conformance and wrapper bridges. */
    data class Wire(
        val result: String,
        val fields: Map<String, String> = emptyMap(),
    ) {
        fun toMap(): Map<String, String> = buildMap {
            put("result", result)
            putAll(fields)
        }
    }

    /**
     * [wireSource] is a provenance passthrough for `allowed` that Android
     * never produces (GateSource left the contract in the audit) but the
     * projection must be able to CARRY losslessly for wrappers bridging
     * pre-cut iOS results, per the pinned fixture.
     */
    fun encode(result: TriggerResult, wireSource: String? = null): Wire = when (result) {
        is TriggerResult.NoMatch -> Wire("no_match")
        is TriggerResult.Allowed ->
            Wire("allowed", wireSource?.let { mapOf("source" to it) } ?: emptyMap())
        is TriggerResult.Denied -> Wire("denied")
        is TriggerResult.JourneyCompleted -> Wire(
            "journey_completed",
            buildMap {
                result.update.ref.journeyId?.let { put("journey_id", it) }
                put("exit_reason", wireExitReason(result.update.exitReason))
                put("goal_met", result.update.goalMet.toString())
            },
        )
        is TriggerResult.Error -> Wire(
            "error",
            mapOf("code" to result.error.code.name.lowercase()),
        )
    }

    fun wireExitReason(reason: JourneyExitReason): String = when (reason) {
        JourneyExitReason.COMPLETED -> "completed"
        JourneyExitReason.DISMISSED -> "dismissed"
        JourneyExitReason.GOAL_MET -> "goal_met"
        JourneyExitReason.TRIGGER_UNMATCHED -> "trigger_unmatched"
        JourneyExitReason.EXPIRED -> "expired"
        JourneyExitReason.CANCELLED -> "cancelled"
        JourneyExitReason.ERROR -> "error"
    }

    fun parseExitReason(wire: String): JourneyExitReason? = when (wire) {
        "completed" -> JourneyExitReason.COMPLETED
        "dismissed" -> JourneyExitReason.DISMISSED
        "goal_met" -> JourneyExitReason.GOAL_MET
        "trigger_unmatched" -> JourneyExitReason.TRIGGER_UNMATCHED
        "expired" -> JourneyExitReason.EXPIRED
        "cancelled" -> JourneyExitReason.CANCELLED
        "error" -> JourneyExitReason.ERROR
        else -> null
    }
}
