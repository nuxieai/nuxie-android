package ai.nuxie.sdk.events

import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.logging.NuxieLog as Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Durable-store-driven `/batch` delivery. Every event is persisted before
 * delivery and all event kinds share one ordered wire lane.
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
        data object Kick : Signal
        data object TimerTick : Signal
        data class Flush(val done: CompletableDeferred<Boolean>) : Signal
    }

    private val signals = Channel<Signal>(capacity = Channel.UNLIMITED)
    private var consecutiveFailures = 0
    private var nextRetryAtMillis = 0L
    private var adaptiveBatchSize = batchSize

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = scope.launch {
        while (true) {
            val signal = select<Signal?> {
                signals.onReceiveCatching { it.getOrNull() }
                onTimeout(flushIntervalMillis) { Signal.TimerTick }
            } ?: break

            when (signal) {
                Signal.Kick ->
                    if (nowMillis() >= nextRetryAtMillis && pendingCount() >= flushAt) {
                        flushUntilDrained(force = false)
                    }
                Signal.TimerTick ->
                    if (nowMillis() >= nextRetryAtMillis && pendingCount() > 0) {
                        flushUntilDrained(force = false)
                    }
                is Signal.Flush -> signal.done.complete(flushUntilDrained(force = true))
            }
        }
    }

    fun kick() {
        signals.trySend(Signal.Kick)
    }

    suspend fun flushAll(): Boolean {
        val done = CompletableDeferred<Boolean>()
        if (signals.trySend(Signal.Flush(done)).isFailure) return false
        return done.await()
    }

    suspend fun close() {
        signals.close()
        worker.join()
    }

    private suspend fun pendingCount(): Int = store.pendingBatch(flushAt).size

    private suspend fun flushUntilDrained(force: Boolean): Boolean {
        if (!force && nowMillis() < nextRetryAtMillis) return false
        while (true) {
            val batch = store.pendingBatch(adaptiveBatchSize)
            if (batch.isEmpty()) {
                consecutiveFailures = 0
                nextRetryAtMillis = 0
                adaptiveBatchSize = batchSize
                cleanupDelivered()
                return true
            }
            val delivered = runCatching {
                val acknowledgment = api.postBatch(batch.map(BatchItemWireEncoder::encode))
                if (acknowledgment.acceptedIndexes.isEmpty()) {
                    throw java.io.IOException("Batch acknowledgment made no progress")
                }
                store.markDelivered(acknowledgment.acceptedIndexes.map { batch[it].id })
            }
            if (delivered.isFailure) {
                var error = delivered.exceptionOrNull()!!
                if (error is CancellationException) throw error
                var disposition = EventDeliveryPolicy.disposition(error, nowMillis())
                if (disposition == EventDeliveryDisposition.Split) {
                    if (batch.size > 1) {
                        adaptiveBatchSize = (batch.size / 2).coerceAtLeast(1)
                    } else {
                        // Match the retained-history contract: terminal transport
                        // poison leaves the pending queue but remains local history.
                        val retirement = runCatching { store.markDelivered(listOf(batch.single().id)) }
                        if (retirement.isSuccess) {
                            Log.e(LOG_TAG, "Terminal batch event retired after isolation", error)
                        } else {
                            error = retirement.exceptionOrNull()!!
                            if (error is CancellationException) throw error
                            disposition = EventDeliveryDisposition.Retry()
                        }
                    }
                    if (disposition == EventDeliveryDisposition.Split) {
                        consecutiveFailures = 0
                        nextRetryAtMillis = 0
                        continue
                    }
                }
                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(retryCount)
                val ordinaryDelay = retryDelayMillis * (1L shl (consecutiveFailures - 1))
                val maximumDelay = retryDelayMillis * (1L shl (retryCount - 1))
                val delay = when (disposition) {
                    EventDeliveryDisposition.UnhealthyAuthentication -> flushIntervalMillis
                    is EventDeliveryDisposition.Retry ->
                        (disposition.retryAfterMillis ?: ordinaryDelay).coerceIn(ordinaryDelay, maximumDelay)
                    EventDeliveryDisposition.Split -> error("Handled above")
                }
                nextRetryAtMillis = nowMillis() + delay
                Log.w(
                    LOG_TAG,
                    "Batch delivery failed; events retained pending",
                    delivered.exceptionOrNull(),
                    Log.status("attempt", consecutiveFailures),
                    Log.status("count", batch.size),
                )
                return false
            }
            consecutiveFailures = 0
            nextRetryAtMillis = 0
        }
    }

    private suspend fun cleanupDelivered() {
        runCatching { store.deleteOldestDeliveredEvents(keeping = maxEventsStored) }
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val EVENT_BATCH_SIZE = 50
        const val FLUSH_AT = 20
        const val FLUSH_INTERVAL_MILLIS = 30_000L
        const val RETRY_COUNT = 3
        const val RETRY_DELAY_MILLIS = 2_000L
        const val MAX_EVENTS_STORED = 1_000
    }
}
