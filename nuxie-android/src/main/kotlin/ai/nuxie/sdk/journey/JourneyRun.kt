package ai.nuxie.sdk.journey

import kotlinx.serialization.json.JsonObject

/** A device-owned Journey run. Its settings snapshot is frozen at enrollment. */
internal data class JourneyRun(
    val id: String,
    val distinctId: String,
    val experienceId: String,
    val experienceVersion: String,
    val epoch: Long,
    val plane: JourneyPlane,
    val settingsSnapshot: JsonObject,
    val state: JourneyRunState,
    val resumePoint: JourneyResumePoint? = null,
    val isGhost: Boolean = false,
    val convertedAtMillis: Long? = null,
    val terminalReason: String? = null,
    val terminalPresentationOutcome: JourneyRunPresentationOutcome? = null,
    val terminalInitiatingDistinctId: String? = null,
    val triggerRef: String? = null,
    val completedAtMillis: Long? = null,
    val pendingHostExitCapture: Boolean = false,
    val pendingHostCompletion: Boolean = false,
    val pendingHostTriggerCompletion: Boolean = false,
)

internal enum class JourneyPlane { DEVICE, SERVER }

internal enum class JourneyRunState { ACTIVE, TRANSFERRED, TERMINAL }

/** Exact presentation outcome selected by the run's first terminal transition. */
internal enum class JourneyRunPresentationOutcome {
    USER_DISMISSED,
    HOST_DISMISSED,
    IDENTITY_CHANGED,
    GOAL_MET,
    PURCHASE_COMPLETED,
    TIMEOUT,
    ERROR,
}

internal data class JourneyResumePoint(
    val nodeId: String,
    val checkpointAtMillis: Long,
)

internal data class JourneyCompletion(
    val experienceId: String,
    val journeyId: String,
    val completedAtMillis: Long,
)
