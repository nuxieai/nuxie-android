package ai.nuxie.sdk.events

/** Persistence seam used by the future capture and delivery pipeline. */
internal interface EventStore {
    suspend fun insertPending(event: StoredEvent)

    /** Inserts a pending event only when its stable id is not already present. */
    suspend fun insertPendingIfAbsent(event: StoredEvent): Boolean {
        insertPending(event)
        return true
    }

    /** True when this stable id was already captured as an event or terminal drop. */
    suspend fun hasStableOutcome(eventId: String): Boolean = false

    /**
     * Inserts a server-authored fact directly into local history. The fact is
     * born delivered, so it can never enter the outbound pending queue.
     *
     * @return true only when this event id was inserted for the first time.
     */
    suspend fun insertDeliveredIfAbsent(event: StoredEvent): Boolean

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

    /** Null means this source cannot promise any complete history window. */
    suspend fun historyCoverageStartingAt(): Long? = null

    /** Monotonically fence history after a known persistence gap. */
    suspend fun advanceHistoryCoverage(startingAtMillis: Long): Long? = null

    /** Reads coverage and rows in one snapshot. Null means incomplete or truncated,
     * including unbounded lifetime queries against retention-bounded history. */
    suspend fun queryHistory(
        name: String,
        distinctId: String,
        sinceMillis: Long?,
        untilMillis: Long?,
    ): List<StoredEvent>? = null

    suspend fun recordStableDrop(eventId: String, recordedAtMillis: Long = System.currentTimeMillis()): Boolean

    suspend fun pendingBatch(limit: Int): List<StoredEvent>

    suspend fun close()
}

internal data class EventHistoryPruneResult(
    val countDeleted: Int,
    val ageDeleted: Int,
    val coverageStartingAtMillis: Long,
)
