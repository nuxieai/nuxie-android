package ai.nuxie.sdk.billing

/** Provenance for a conclusion entering the serialized purchase committer. */
internal enum class PurchaseOutcomeSource(val wireValue: String) {
    CHECKOUT("checkout"),
    PURCHASES_UPDATED_STREAM("transaction_stream"),
    STARTUP_RECOVERY("startup_recovery"),
    DEFERRED_UPDATE("deferred_update"),
    EXTERNAL_DELEGATE("external_delegate"),
}

/**
 * The single conclusion shape consumed by [PurchaseService]. Store evidence and
 * host declarations deliberately remain different variants: only the former
 * can be persisted, projected, synchronized, acknowledged, or consumed.
 */
internal sealed interface PurchaseOutcome {
    val source: PurchaseOutcomeSource

    data class Verified(
        val evidence: PlayPurchase,
        override val source: PurchaseOutcomeSource,
    ) : PurchaseOutcome

    data class External(
        val declaration: ExternalPurchaseDeclaration,
    ) : PurchaseOutcome {
        override val source: PurchaseOutcomeSource = PurchaseOutcomeSource.EXTERNAL_DELEGATE
    }

    data class Cancelled(
        override val source: PurchaseOutcomeSource,
    ) : PurchaseOutcome

    data class Pending(
        override val source: PurchaseOutcomeSource,
        internal val evidence: PlayPurchase? = null,
    ) : PurchaseOutcome

    data class Failed(
        val reason: Throwable,
        override val source: PurchaseOutcomeSource,
    ) : PurchaseOutcome
}

/** One host callback, identified independently from any native transaction. */
internal sealed interface ExternalPurchaseDeclaration {
    val operationId: String
    val ownerDistinctId: String

    data class Purchase(
        override val operationId: String,
        override val ownerDistinctId: String,
        val product: StoreProduct,
    ) : ExternalPurchaseDeclaration

    data class Restore(
        override val operationId: String,
        override val ownerDistinctId: String,
    ) : ExternalPurchaseDeclaration
}
