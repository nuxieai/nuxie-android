package ai.nuxie.sdk.events

/** Persistence seam used by the future capture and delivery pipeline. */
internal interface EventStore {
    suspend fun insertPending(event: StoredEvent)

    suspend fun markDelivered(ids: List<String>)

    suspend fun hasEvent(name: String, distinctId: String, sinceMillis: Long? = null): Boolean

    suspend fun countEvents(
        name: String,
        distinctId: String,
        sinceMillis: Long? = null,
        untilMillis: Long? = null,
    ): Int

    suspend fun getFirstEventTime(
        name: String,
        distinctId: String,
        sinceMillis: Long? = null,
        untilMillis: Long? = null,
    ): Long?

    suspend fun getLastEventTime(
        name: String,
        distinctId: String,
        sinceMillis: Long? = null,
        untilMillis: Long? = null,
    ): Long?

    suspend fun querySessionEvents(sessionId: String): List<StoredEvent>

    suspend fun reassignEvents(from: String, to: String): Int

    suspend fun deleteOldestDeliveredEvents(keeping: Int): Int

    suspend fun recordStableDrop(eventId: String, recordedAtMillis: Long = System.currentTimeMillis()): Boolean

    suspend fun pendingBatch(limit: Int): List<StoredEvent>

    suspend fun close()
}
