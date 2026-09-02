package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.DecisionEventCapture
import ai.nuxie.sdk.events.DecisionEventCapturing
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventLog.ServerFactCommitResult
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.util.IsoDates
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject

/** Emits the five device-authored Journey facts through the durable EventLog. */
internal data class JourneyEffectRequest(
    val invocationId: String,
    val capture: DecisionEventCapture?,
)

internal class JourneyLedger(
    private val eventLog: EventLog,
    private val decisionEvents: DecisionEventCapturing,
) {
    suspend fun enrolled(
        run: JourneyRun,
        triggerRef: String,
        eventId: String,
    ): DecisionEventCapture? = decisionEvents.capture(
        JourneyEventNames.ENROLLED,
        mapOf(
            "journey_id" to run.id,
            "epoch" to run.epoch,
            "experience_id" to run.experienceId,
            "experience_version" to run.experienceVersion,
            "trigger_ref" to triggerRef,
            "plane" to run.plane.wireName(),
            "settings_snapshot" to JsonValueConverter.toNativeMap(run.settingsSnapshot),
        ),
        run.distinctId,
        eventId = eventId,
        applyBeforeSend = false,
    )

    suspend fun transition(
        run: JourneyRun,
        fromNode: String?,
        toNode: String,
        region: String,
    ): DecisionEventCapture? {
        val properties = linkedMapOf<String, Any?>(
            "journey_id" to run.id,
            "epoch" to run.epoch,
            "to_node" to toNode,
            "region" to region,
            "plane" to run.plane.wireName(),
        )
        fromNode?.takeIf(String::isNotEmpty)?.let { properties["from_node"] = it }
        return decisionEvents.capture(JourneyEventNames.TRANSITION, properties, run.distinctId)
    }

    suspend fun milestone(run: JourneyRun, milestoneId: String): DecisionEventCapture? = decisionEvents.capture(
        JourneyEventNames.MILESTONE,
        mapOf("journey_id" to run.id, "epoch" to run.epoch, "milestone_id" to milestoneId),
        run.distinctId,
    )

    suspend fun exited(
        run: JourneyRun,
        reason: String,
        atMillis: Long,
    ): DecisionEventCapture? = decisionEvents.capture(
        JourneyEventNames.EXITED,
        mapOf(
            "journey_id" to run.id,
            "epoch" to run.epoch,
            "experience_id" to run.experienceId,
            "experience_version" to run.experienceVersion,
            "reason" to reason,
            "at" to IsoDates.formatMillis(atMillis),
        ),
        run.distinctId,
    )

    suspend fun hostExited(run: JourneyRun, atMillis: Long): DecisionEventCapture? =
        decisionEvents.capture(
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

    suspend fun userExited(run: JourneyRun, atMillis: Long): DecisionEventCapture? =
        decisionEvents.capture(
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
            applyBeforeSend = false,
        )

    suspend fun effectRequested(
        run: JourneyRun,
        nodeId: String,
        attempt: Long,
        effect: String,
        payload: JsonObject,
    ): JourneyEffectRequest {
        val invocationId = invocationId(run.id, nodeId, attempt)
        val capture = decisionEvents.capture(
            name = JourneyEventNames.EFFECT_REQUESTED,
            properties = mapOf(
                "journey_id" to run.id,
                "epoch" to run.epoch,
                "node_id" to nodeId,
                "invocation_id" to invocationId,
                "effect" to effect,
                "payload" to JsonValueConverter.toNativeMap(payload),
            ),
            distinctId = run.distinctId,
        )
        return JourneyEffectRequest(invocationId, capture)
    }

    suspend fun serverFact(
        event: StoredEvent,
        receivedAtMillis: Long,
    ): ServerFactCommitResult =
        eventLog.commitServerFact(event, receivedAtMillis)

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
