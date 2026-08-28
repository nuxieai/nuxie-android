package ai.nuxie.sdk

/**
 * The trigger vocabulary, born at the post-audit iOS contract
 * (specs/android-sdk/overview.md section 4): Experience/Feature nouns only,
 * no GateSource provenance, no dead cases. The wire projection for wrapper
 * SDKs lives in the internal encoder and stays lossless against
 * fixtures/encodings/trigger-result.json.
 */
data class ExperienceRef(
    val experienceId: String,
    val experienceVersion: String?,
    val journeyId: String?,
)

enum class JourneyExitReason {
    COMPLETED,
    DISMISSED,
    GOAL_MET,
    TRIGGER_UNMATCHED,
    EXPIRED,
    CANCELLED,
    ERROR,
    SUPERSEDED,
}

data class JourneyUpdate(
    val ref: ExperienceRef,
    val exitReason: JourneyExitReason,
    val goalMet: Boolean,
)

enum class SuppressReason { ALREADY_ACTIVE, REENTRY_LIMITED }

enum class TriggerErrorCode {
    NOT_CONFIGURED,
    TRIGGER_FAILED,
    EXPERIENCE_MISSING,
    FEATURE_MISSING,
    FEATURE_ACCESS_TIMEOUT,
    EXPERIENCE_PRESENT_FAILED,
}

class TriggerError internal constructor(
    val code: TriggerErrorCode,
    val message: String,
)

sealed interface TriggerDecision {
    /** No Experience matched this moment (not an analytics success). */
    data object NoMatch : TriggerDecision
    data class Suppressed(val reason: SuppressReason) : TriggerDecision
    data class JourneyStarted(val ref: ExperienceRef) : TriggerDecision
    data class ExperienceShown(val ref: ExperienceRef) : TriggerDecision
    data object AllowedImmediate : TriggerDecision
    data object DeniedImmediate : TriggerDecision
}

sealed interface FeatureAccessUpdate {
    data object Pending : FeatureAccessUpdate
    data object Allowed : FeatureAccessUpdate
    data object Denied : FeatureAccessUpdate
}

sealed interface TriggerUpdate {
    data class Decision(val decision: TriggerDecision) : TriggerUpdate
    data class FeatureAccess(val update: FeatureAccessUpdate) : TriggerUpdate
    data class Journey(val update: JourneyUpdate) : TriggerUpdate
    data class Error(val error: TriggerError) : TriggerUpdate
}

sealed interface TriggerResult {
    data object NoMatch : TriggerResult
    data object Allowed : TriggerResult
    data object Denied : TriggerResult
    data class JourneyCompleted(val update: JourneyUpdate) : TriggerResult
    data class Error(val error: TriggerError) : TriggerResult
}
