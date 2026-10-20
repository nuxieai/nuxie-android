package ai.nuxie.sdk.identity

/** Whether a customer has signed in, as the SDK declares it to the purchase authority. */
internal enum class CustomerKind(val wireValue: String) {
    ANONYMOUS("anonymous"),
    IDENTIFIED("identified"),
}

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

    /**
     * The kind of the customer named by [distinctId]: the current customer
     * is identified once identify set it and anonymous otherwise, including
     * after reset; this device's anonymous id is anonymous.
     */
    fun customerKind(distinctId: String): CustomerKind = when (distinctId) {
        distinctId() -> if (isIdentified) CustomerKind.IDENTIFIED else CustomerKind.ANONYMOUS
        anonymousId() -> CustomerKind.ANONYMOUS
        // An earlier customer this device no longer knows: the server merges only owners declared anonymous.
        else -> CustomerKind.IDENTIFIED
    }

    /** Capture the effective customer and its monotonic identity revision. */
    fun captureScope(): IdentityScope = IdentityScope(distinctId(), 0L)

    /** Whether [scope] still names the current identity decision. */
    fun isCurrentScope(scope: IdentityScope): Boolean = distinctId() == scope.distinctId

    /** Linearize a non-publishing admission decision against identity mutation. */
    fun <T> withCurrentScope(scope: IdentityScope, block: () -> T): T? =
        if (isCurrentScope(scope)) block() else null
    /** Suspending admission shares the same identity decision fence. */
    suspend fun <T> withCurrentScopeSuspending(scope: IdentityScope, block: suspend () -> T): T? =
        if (isCurrentScope(scope)) block() else null
}
