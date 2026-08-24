package ai.nuxie.sdk.identity

/** Identity seam consumed by the event pipeline. */
internal interface IdentityProvider {
    /** The effective id events are attributed to. Anonymous until identify. */
    fun distinctId(): String

    /** The device-scoped anonymous id, minted once and persisted. */
    fun anonymousId(): String

    /** The host-supplied user id when identified, else null. */
    fun rawDistinctId(): String?

    val isIdentified: Boolean
}
