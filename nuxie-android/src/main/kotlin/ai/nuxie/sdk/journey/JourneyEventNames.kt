package ai.nuxie.sdk.journey

/** Named constants for the reserved Journey event vocabulary. */
internal object JourneyEventNames {
    const val LEG_STARTED = "\$journey_leg_started"
    const val LEG_COMPLETED = "\$journey_leg_completed"
    const val ENROLLED = "\$journey_enrolled"
    const val TRANSITION = "\$journey_transition"
    const val MILESTONE = "\$journey_milestone"
    const val CONVERTED = "\$journey_converted"
    const val EXITED = "\$journey_exited"
    const val EFFECT_REQUESTED = "\$journey_effect_requested"
    const val EFFECT_COMPLETED = "\$journey_effect_completed"
    const val SUPERSEDED = "\$journey_superseded"
    const val EXPERIENCE_ARTIFACT_LOAD_FAILED = "\$experience_artifact_load_failed"
    const val EXPERIMENT_EXPOSURE = "\$experiment_exposure"
    const val EXPERIMENT_EXPOSURE_ERROR = "\$experiment_exposure_error"
}
