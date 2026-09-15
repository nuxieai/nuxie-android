package ai.nuxie.sdk.events

/** Transient capture provenance; never restored from durable event history. */
internal class ActivityIdentity(
    val customerId: String,
    val isCurrent: () -> Boolean,
)
