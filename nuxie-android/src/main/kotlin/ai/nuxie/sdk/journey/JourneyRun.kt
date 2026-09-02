package ai.nuxie.sdk.journey

import ai.nuxie.sdk.JourneyExitReason
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
    /** Stable `/event` identity retained while enrollment is unresolved. */
    val pendingEnrollmentEventId: String? = null,
    val resumePoint: JourneyResumePoint? = null,
    val isGhost: Boolean = false,
    val convertedAtMillis: Long? = null,
    val terminalReason: String? = null,
    /** Exact public reason returned to the trigger waiter after a signed exit action. */
    val terminalTriggerExitReason: JourneyExitReason? = null,
    val terminalPresentationOutcome: JourneyRunPresentationOutcome? = null,
    val terminalInitiatingDistinctId: String? = null,
    val triggerRef: String? = null,
    val completedAtMillis: Long? = null,
    val pendingHostExitCapture: Boolean = false,
    val pendingHostCompletion: Boolean = false,
    val pendingHostTriggerCompletion: Boolean = false,
)

internal enum class JourneyPlane { DEVICE, SERVER }

internal enum class JourneyRunState { ENROLLING, ACTIVE, TRANSFERRED, TERMINAL }

/** Exact presentation outcome selected by the run's first terminal transition. */
internal enum class JourneyRunPresentationOutcome {
    USER_DISMISSED,
    HOST_DISMISSED,
    IDENTITY_CHANGED,
    GOAL_MET,
    PURCHASE_COMPLETED,
    TIMEOUT,
    AUTHENTICATED_EXIT,
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

/** Server execution-event vocabulary. This intentionally differs from trigger-result wire names. */
internal fun JourneyExitReason.executionReason(): String = when (this) {
    JourneyExitReason.COMPLETED -> "completed"
    JourneyExitReason.DISMISSED,
    JourneyExitReason.CANCELLED,
    -> "cancelled"
    JourneyExitReason.GOAL_MET -> "converted_exit"
    JourneyExitReason.TRIGGER_UNMATCHED -> "stopped_matching"
    JourneyExitReason.EXPIRED -> "time_limit"
    JourneyExitReason.ERROR -> "error"
    JourneyExitReason.SUPERSEDED -> "superseded"
}
