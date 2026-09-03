package ai.nuxie.sdk.journey

/** Named constants for the reserved Journey event vocabulary. */
internal object JourneyEventNames {
    const val LEG_STARTED = "\$journey_leg_started"
    const val LEG_COMPLETED = "\$journey_leg_completed"
    const val MILESTONE = "\$journey_milestone"
    const val EXPERIENCE_ARTIFACT_LOAD_SUCCEEDED = "\$experience_artifact_load_succeeded"
    const val EXPERIENCE_ARTIFACT_LOAD_FAILED = "\$experience_artifact_load_failed"
    const val CUSTOMER_UPDATED = "\$customer_updated"
    const val APP_ACTION_REQUESTED = "\$app_action_requested"
    const val EXPERIMENT_EXPOSURE = "\$experiment_exposure"
}
