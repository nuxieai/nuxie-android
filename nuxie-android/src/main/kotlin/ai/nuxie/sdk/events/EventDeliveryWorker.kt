package ai.nuxie.sdk.events

import ai.nuxie.sdk.network.NuxieApi
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Durable-store-driven `/batch` delivery, porting the iOS EventLog delivery
 * policy as engine-owned internals (the audit removed all public knobs):
 * batches of 50, flush at 20 pending, a 30-second interval, and a bounded
 * retry backoff. Android has no in-memory delivery window — every capture is
 * persisted first, so delivery always reads `pendingBatch` from SQLite and
 * exactly-once lands on the wire idempotency key (the event UUIDv7).
 *
 * A flush cycle that delivers nothing (transport down) ends with the batch
 * retained pending; the next trigger, timer tick, or launch retries it.
 */
internal class EventDeliveryWorker(
    private val store: EventStore,
    private val api: NuxieApi,
    scope: CoroutineScope,
    private val batchSize: Int = EVENT_BATCH_SIZE,
    private val flushAt: Int = FLUSH_AT,
    private val flushIntervalMillis: Long = FLUSH_INTERVAL_MILLIS,
    private val retryCount: Int = RETRY_COUNT,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val maxEventsStored: Int = MAX_EVENTS_STORED,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Signal {
        /** Threshold check after a capture commits. */
        data object Kick : Signal

        /** Interval tick: flush whatever is pending. */
        data object TimerTick : Signal

        data class Flush(val done: kotlinx.coroutines.CompletableDeferred<Boolean>) : Signal
    }

    // UNLIMITED, not CONFLATED: a conflated channel could overwrite a queued
    // Flush (losing its completable) with a later Kick.
    private val signals = Channel<Signal>(capacity = Channel.UNLIMITED)
    private var consecutiveFailures = 0
    private var nextRetryAtMillis = 0L

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private val worker = scope.launch {
        while (true) {
            val signal = select<Signal?> {
                signals.onReceiveCatching { result -> result.getOrNull() }
                onTimeout(flushIntervalMillis) { Signal.TimerTick }
            } ?: break

            when (signal) {
                is Signal.Kick ->
                    if (nowMillis() >= nextRetryAtMillis && pendingCount() >= flushAt) {
                        flushUntilDrained(force = false)
                    }
                is Signal.TimerTick ->
                    if (nowMillis() >= nextRetryAtMillis && pendingCount() > 0) {
                        flushUntilDrained(force = false)
                    }
                is Signal.Flush -> signal.done.complete(flushUntilDrained(force = true))
            }
        }
    }

    /** Threshold check after a capture commits. */
    fun kick() {
        signals.trySend(Signal.Kick)
    }

    /** Drain everything now, bypassing backoff. Internal/testing + background flush. */
    suspend fun flushAll(): Boolean {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        if (signals.trySend(Signal.Flush(done)).isFailure) return false
        return done.await()
    }

    suspend fun close() {
        signals.close()
        worker.join()
    }

    private suspend fun pendingCount(): Int = store.pendingBatch(flushAt).size

    /**
     * Flush batches until pending is drained or a cycle makes no progress.
     * Returns true when nothing remains pending.
     */
    private suspend fun flushUntilDrained(force: Boolean): Boolean {
        if (!force && nowMillis() < nextRetryAtMillis) return false
        while (true) {
            val batch = store.pendingBatch(batchSize)
            if (batch.isEmpty()) {
                consecutiveFailures = 0
                nextRetryAtMillis = 0
                cleanupDelivered()
                return true
            }
            val delivered = runCatching {
                api.postBatch(batch.map(BatchItemWireEncoder::encode))
            }
            if (delivered.isFailure) {
                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(retryCount)
                nextRetryAtMillis = nowMillis() +
                    retryDelayMillis * (1L shl (consecutiveFailures - 1))
                Log.w(
                    LOG_TAG,
                    "Batch delivery failed (attempt $consecutiveFailures); " +
                        "${batch.size} events retained pending",
                    delivered.exceptionOrNull(),
                )
                return false
            }
            consecutiveFailures = 0
            nextRetryAtMillis = 0
            store.markDelivered(batch.map { it.id })
        }
    }

    private suspend fun cleanupDelivered() {
        runCatching { store.deleteOldestDeliveredEvents(keeping = maxEventsStored) }
    }

    private companion object {
        const val LOG_TAG = "Nuxie"

        // iOS delivery defaults, now engine-owned (audit removed the knobs).
        const val EVENT_BATCH_SIZE = 50
        const val FLUSH_AT = 20
        const val FLUSH_INTERVAL_MILLIS = 30_000L
        const val RETRY_COUNT = 3
        const val RETRY_DELAY_MILLIS = 2_000L
        const val MAX_EVENTS_STORED = 1_000
    }
}
