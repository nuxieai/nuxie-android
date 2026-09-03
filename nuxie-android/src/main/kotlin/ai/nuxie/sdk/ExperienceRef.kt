package ai.nuxie.sdk

/** Stable identity for an Experience participating in a Journey. */
data class ExperienceRef(
    val experienceId: String,
    val experienceVersion: String?,
    val journeyId: String?,
)
