package ai.nuxie.sdk.features

/** A customer's current access to a Feature. */
data class FeatureAccess(
    val allowed: Boolean,
    val unlimited: Boolean,
    val balance: Double?,
    val type: FeatureType,
)

/** Server-confirmed result of reporting metered Feature use. */
data class FeatureUsageResult(
    val success: Boolean,
    val featureId: String,
    val amountUsed: Double,
    val message: String?,
    val usage: UsageInfo?,
    val authoritativeAccess: FeatureAccess? = null,
) {
    data class UsageInfo(
        val current: Double,
        val limit: Double?,
        val remaining: Double?,
    )
}

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

/** Immutable signed-product allowance used to derive optimistic Feature access. */
internal data class FeatureAllowance(
    val featureId: String,
    val type: FeatureType,
    val unlimited: Boolean = false,
    val allowance: Double? = null,
) {
    companion object {
        /** Classify the raw allowance fields carried by a signed product descriptor. */
        fun fromDescriptor(
            featureId: String,
            featureExternalId: String?,
            allowanceType: String?,
            allowance: Double?,
        ): FeatureAllowance {
            val normalizedType = allowanceType?.lowercase()
            return FeatureAllowance(
                featureId = featureExternalId ?: featureId,
                type = if (normalizedType == null) FeatureType.BOOLEAN else FeatureType.METERED,
                unlimited = normalizedType == "unlimited",
                allowance = allowance,
            )
        }
    }
}
