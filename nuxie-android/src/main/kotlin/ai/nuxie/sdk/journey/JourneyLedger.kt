package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.StoredEvent
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/** Emits the five device-authored Journey facts through the durable EventLog. */
internal class JourneyLedger(private val eventLog: EventLog) {
    fun enrolled(run: JourneyRun, triggerRef: String) = capture(
        JourneyEventNames.ENROLLED,
        mapOf(
            "journey_id" to run.id,
            "epoch" to run.epoch,
            "experience_id" to run.experienceId,
            "experience_version" to run.experienceVersion,
            "trigger_ref" to triggerRef,
            "plane" to run.plane.wireName(),
            "settings_snapshot" to run.settingsSnapshot,
        ),
    )

    fun transition(run: JourneyRun, fromNode: String?, toNode: String, region: String) {
        val properties = linkedMapOf<String, Any?>(
            "journey_id" to run.id,
            "epoch" to run.epoch,
            "to_node" to toNode,
            "region" to region,
            "plane" to run.plane.wireName(),
        )
        fromNode?.takeIf(String::isNotEmpty)?.let { properties["from_node"] = it }
        capture(JourneyEventNames.TRANSITION, properties)
    }

    fun milestone(run: JourneyRun, milestoneId: String) = capture(
        JourneyEventNames.MILESTONE,
        mapOf("journey_id" to run.id, "epoch" to run.epoch, "milestone_id" to milestoneId),
    )

    fun exited(run: JourneyRun, reason: String, atMillis: Long) = capture(
        JourneyEventNames.EXITED,
        mapOf("journey_id" to run.id, "epoch" to run.epoch, "reason" to reason, "at" to atMillis),
    )

    fun effectRequested(
        run: JourneyRun,
        nodeId: String,
        attempt: Long,
        effect: String,
        payload: JsonObject,
    ): String {
        val invocationId = invocationId(run.id, nodeId, attempt)
        capture(
            JourneyEventNames.EFFECT_REQUESTED,
            mapOf(
                "journey_id" to run.id,
                "epoch" to run.epoch,
                "node_id" to nodeId,
                "invocation_id" to invocationId,
                "effect" to effect,
                "payload" to payload,
            ),
        )
        return invocationId
    }

    suspend fun serverFact(event: StoredEvent): Boolean = eventLog.commitServerFact(event)

    private fun capture(name: String, properties: Map<String, Any?>) {
        eventLog.capture(name, properties)
    }

    private fun JourneyPlane.wireName(): String = when (this) {
        JourneyPlane.DEVICE -> "device"
        JourneyPlane.SERVER -> "server"
    }

    companion object {
        fun invocationId(journeyId: String, nodeId: String, attempt: Long): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$journeyId:$nodeId:$attempt".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
