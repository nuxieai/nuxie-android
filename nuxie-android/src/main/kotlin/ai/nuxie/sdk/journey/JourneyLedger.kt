package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.util.IsoDates
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/** Emits the five device-authored Journey facts through the durable EventLog. */
internal class JourneyLedger(private val eventLog: EventLog) {
    suspend fun enrolled(run: JourneyRun, triggerRef: String): StoredEvent? = eventLog.captureForTrigger(
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

    fun exited(run: JourneyRun, reason: String, atMillis: Long) = eventLog.capture(
        JourneyEventNames.EXITED,
        mapOf(
            "journey_id" to run.id,
            "epoch" to run.epoch,
            "experience_id" to run.experienceId,
            "experience_version" to run.experienceVersion,
            "reason" to reason,
            "at" to atMillis,
        ),
        run.distinctId,
    )

    suspend fun hostExited(run: JourneyRun, atMillis: Long): Boolean =
        eventLog.captureIdempotently(
            name = JourneyEventNames.EXITED,
            properties = mapOf(
                "journey_id" to run.id,
                "experience_id" to run.experienceId,
                "experience_version" to run.experienceVersion,
                "epoch" to run.epoch,
                "reason" to "dismissed",
                "at" to IsoDates.formatMillis(atMillis),
                "dismissed_by" to "host",
            ),
            eventId = "journey-exited:${run.id}:${run.epoch}",
            distinctId = run.distinctId,
        )

    suspend fun userExited(run: JourneyRun, atMillis: Long): Boolean =
        eventLog.captureSystemEvent(
            name = JourneyEventNames.EXITED,
            properties = mapOf(
                "journey_id" to run.id,
                "experience_id" to run.experienceId,
                "experience_version" to run.experienceVersion,
                "epoch" to run.epoch,
                "reason" to "cancelled",
                "at" to IsoDates.formatMillis(atMillis),
                "dismissed_by" to "user",
            ),
            eventId = "journey-exited:${run.id}:${run.epoch}",
            distinctId = run.distinctId,
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

    suspend fun serverFact(event: StoredEvent, receivedAtMillis: Long): Boolean =
        eventLog.commitServerFact(event, receivedAtMillis)

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
