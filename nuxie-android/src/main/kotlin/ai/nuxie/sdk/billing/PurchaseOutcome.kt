package ai.nuxie.sdk.billing

/** Provenance for a conclusion entering the serialized purchase committer. */
internal enum class PurchaseOutcomeSource(val wireValue: String) {
    CHECKOUT("checkout"),
    PURCHASES_UPDATED_STREAM("transaction_stream"),
    STARTUP_RECOVERY("startup_recovery"),
    DEFERRED_UPDATE("deferred_update"),
    EXTERNAL_DELEGATE("external_delegate"),
}

/** Stable identity used by the serialized purchase committer. */
internal sealed interface PurchaseCommitIdentity {
    data class Evidence(val purchaseToken: String) : PurchaseCommitIdentity
    data class External(val operationId: String) : PurchaseCommitIdentity
}

/** Internal, passive test observation of the purchase committer boundary. */
internal sealed interface PurchaseCommitObservation {
    data class Committed(val identity: PurchaseCommitIdentity) : PurchaseCommitObservation

    data class Terminal(
        val outcome: PurchaseOutcome,
        val terminal: Boolean,
    ) : PurchaseCommitObservation
}

/**
 * The single conclusion shape consumed by [PurchaseService]. Store evidence and
 * host declarations deliberately remain different variants: only the former
 * can be persisted, projected, synchronized, acknowledged, or consumed.
 */
internal sealed interface PurchaseOutcome {
    val source: PurchaseOutcomeSource
    val testStore: Boolean get() = false

    data class Verified(
        val evidence: PlayPurchase,
        override val source: PurchaseOutcomeSource,
    ) : PurchaseOutcome

    data class External(
        val declaration: ExternalPurchaseDeclaration,
    ) : PurchaseOutcome {
        override val source: PurchaseOutcomeSource get() = declaration.source
        override val testStore: Boolean get() = declaration.testStore
    }

    data class Cancelled(
        override val source: PurchaseOutcomeSource,
        override val testStore: Boolean = false,
    ) : PurchaseOutcome

    data class Pending(
        override val source: PurchaseOutcomeSource,
        internal val evidence: PlayPurchase? = null,
        override val testStore: Boolean = false,
    ) : PurchaseOutcome

    data class Failed(
        val reason: Throwable,
        override val source: PurchaseOutcomeSource,
        override val testStore: Boolean = false,
    ) : PurchaseOutcome
}

/** One external declaration (delegate or Test Store), independent of native evidence. */
internal sealed interface ExternalPurchaseDeclaration {
    val operationId: String
    val ownerDistinctId: String
    val testStore: Boolean
    val source: PurchaseOutcomeSource
        get() = if (testStore) PurchaseOutcomeSource.CHECKOUT else PurchaseOutcomeSource.EXTERNAL_DELEGATE

    data class Purchase(
        override val operationId: String,
        override val ownerDistinctId: String,
        val product: StoreProduct,
        val outcomeEventId: String? = null,
        override val testStore: Boolean = false,
        val transactionId: String? = null,
    ) : ExternalPurchaseDeclaration

    data class Restore(
        override val operationId: String,
        override val ownerDistinctId: String,
        val outcomeEventId: String? = null,
        override val testStore: Boolean = false,
    ) : ExternalPurchaseDeclaration
}
