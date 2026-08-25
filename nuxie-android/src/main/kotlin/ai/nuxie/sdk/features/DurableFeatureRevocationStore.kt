package ai.nuxie.sdk.features

/** Durable tombstones that must be retired when the server restores Feature access. */
internal fun interface DurableFeatureRevocationStore {
    fun retireRevokedGrants(distinctId: String, featureIds: Set<String>): Boolean
}
