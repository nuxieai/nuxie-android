package ai.nuxie.sdk.events

/** Runs one synchronous store mutation only while its execution fences hold. */
internal fun interface StableEventCommitAdmission {
    /** Null means admission was revoked; false/true are the mutation result. */
    fun commitIfCurrent(commit: () -> Boolean): Boolean?
}

/** Result of atomically committing an event and its local-route receipt. */
internal data class EventRouteCommit(
    val inserted: Boolean,
    val localRoutePending: Boolean,
)

/** Settled stable capture. A null event is a durable beforeSend drop. */
internal data class StableEventCaptureResult(
    val settled: Boolean,
    val event: StoredEvent?,
    val localRoutePending: Boolean = false,
    val newlyCaptured: Boolean = false,
)

/** Persistence seam used by the future capture and delivery pipeline. */
internal interface EventStore {
    suspend fun insertPending(event: StoredEvent)

    /** Commits a pending event and its local subscriber receipt atomically. */
    suspend fun insertPendingAndStageRoute(event: StoredEvent): EventRouteCommit {
        insertPending(event)
        return EventRouteCommit(inserted = true, localRoutePending = true)
    }

    /** Inserts a pending event only when its stable id is not already present. */
    suspend fun insertPendingIfAbsent(event: StoredEvent): Boolean {
        insertPending(event)
        return true
    }

    /** Production stores override this to run [admission] around the commit. */
    suspend fun insertPendingIfAbsent(
        event: StoredEvent,
        admission: StableEventCommitAdmission,
    ): Boolean? {
        if (admission.commitIfCurrent { true } == null) return null
        return insertPendingIfAbsent(event)
    }

    /**
     * Commits a stable pending event and stages its local subscriber route in
     * the same storage transaction. Production stores must override this;
     * the default keeps lightweight test stores source-compatible.
     */
    suspend fun insertPendingIfAbsentAndStageRoute(
        event: StoredEvent,
    ): EventRouteCommit {
        val inserted = insertPendingIfAbsent(event)
        return EventRouteCommit(inserted, inserted)
    }

    /** Production stores override this to fence the complete transaction. */
    suspend fun insertPendingIfAbsentAndStageRoute(
        event: StoredEvent,
        admission: StableEventCommitAdmission,
    ): EventRouteCommit? {
        if (admission.commitIfCurrent { true } == null) return null
        return insertPendingIfAbsentAndStageRoute(event)
    }

    /** True when this stable id was already captured as an event or terminal drop. */
    suspend fun hasStableOutcome(eventId: String): Boolean = false

    /** Returns the immutable event for an already-settled stable id, when it was retained. */
    suspend fun stableEvent(eventId: String): StoredEvent? = null

    suspend fun isLocalRoutePending(eventId: String): Boolean = false

    /**
     * Inserts a server-authored fact directly into local history. The fact is
     * born delivered, so it can never enter the outbound pending queue.
     *
     * @return true only when this event id was inserted for the first time.
     */
    suspend fun insertDeliveredIfAbsent(event: StoredEvent): Boolean

    /** Commits a delivered event and its local subscriber receipt atomically. */
    suspend fun insertDeliveredIfAbsentAndStageRoute(event: StoredEvent): EventRouteCommit {
        val inserted = insertDeliveredIfAbsent(event)
        return EventRouteCommit(inserted, inserted)
    }

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

    /** Production stores override this to run [admission] around the commit. */
    suspend fun recordStableDrop(
        eventId: String,
        recordedAtMillis: Long,
        admission: StableEventCommitAdmission,
    ): Boolean? {
        if (admission.commitIfCurrent { true } == null) return null
        return recordStableDrop(eventId, recordedAtMillis)
    }

    /** Events whose local subscriber delivery has not been acknowledged. */
    suspend fun queryPendingLocalRoutes(distinctId: String): List<StoredEvent> = emptyList()

    /** Marks one event's local subscriber route as acknowledged. */
    suspend fun markLocalRouteDelivered(eventId: String) = Unit

    suspend fun pendingBatch(limit: Int): List<StoredEvent>

    suspend fun close()
}

internal data class EventHistoryPruneResult(
    val countDeleted: Int,
    val ageDeleted: Int,
    val coverageStartingAtMillis: Long,
)
