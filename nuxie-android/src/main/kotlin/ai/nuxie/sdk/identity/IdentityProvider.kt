package ai.nuxie.sdk.identity

internal data class IdentityScope(
    val distinctId: String,
    val revision: Long,
)

/** Identity seam consumed by the event pipeline. */
internal interface IdentityProvider {
    /** The effective id events are attributed to. Anonymous until identify. */
    fun distinctId(): String

    /** The device-scoped anonymous id, minted once and persisted. */
    fun anonymousId(): String

    /** The host-supplied user id when identified, else null. */
    fun rawDistinctId(): String?

    val isIdentified: Boolean

    /** Capture the effective customer and its monotonic identity revision. */
    fun captureScope(): IdentityScope = IdentityScope(distinctId(), 0L)

    /** Whether [scope] still names the current identity decision. */
    fun isCurrentScope(scope: IdentityScope): Boolean = distinctId() == scope.distinctId

    /** Linearize a non-publishing admission decision against identity mutation. */
    fun <T> withCurrentScope(scope: IdentityScope, block: () -> T): T? =
        if (isCurrentScope(scope)) block() else null
}
