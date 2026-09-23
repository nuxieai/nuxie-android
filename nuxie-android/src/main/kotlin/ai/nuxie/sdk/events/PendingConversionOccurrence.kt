package ai.nuxie.sdk.events

/** Source evidence for measurement retry, independent of network delivery. */
internal data class PendingConversionOccurrence(val event: StoredEvent, val acceptedAt: Long) {
    companion object {
        const val RETENTION_MILLIS = 120L * 86_400_000
    }
}
