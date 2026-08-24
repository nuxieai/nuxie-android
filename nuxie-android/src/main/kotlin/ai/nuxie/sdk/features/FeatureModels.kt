package ai.nuxie.sdk.features

/** A customer's current access to a Feature. */
data class FeatureAccess(
    val allowed: Boolean,
    val unlimited: Boolean,
    val balance: Double?,
    val type: FeatureType,
)

/** The access accounting model used by a Feature. */
enum class FeatureType {
    BOOLEAN,
    METERED,
    CREDIT_SYSTEM,
}

/** Selects whether a Feature check may use fresh local access. */
enum class FeatureCheckPolicy {
    CACHE_FIRST,
    REMOTE,
}
