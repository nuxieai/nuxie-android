package ai.nuxie.sdk.events

import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.network.NuxieApi
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    private val eventLog: EventLog,
    private val api: NuxieApi,
    scope: CoroutineScope,
    private val onDecisionResponse: suspend (StoredEvent, JsonObject) -> Unit = { _, _ -> },
    private val onDecisionRejected: suspend (StoredEvent, Throwable) -> Unit = { _, _ -> },
    private val batchSize: Int = EVENT_BATCH_SIZE,
    private val flushAt: Int = FLUSH_AT,
    private val flushIntervalMillis: Long = FLUSH_INTERVAL_MILLIS,
    private val retryCount: Int = RETRY_COUNT,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val maxEventsStored: Int = MAX_EVENTS_STORED,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DecisionEventCapturing {
    private sealed interface Signal {
        /** Threshold check after a capture commits. */
        data object Kick : Signal

        /** Interval tick: flush whatever is pending. */
        data object TimerTick : Signal

        data class Flush(val done: CompletableDeferred<Boolean>) : Signal

        data class Direct(
            val name: String,
            val properties: Map<String, Any?>,
            val distinctId: String,
            val eventId: String,
            val applyBeforeSend: Boolean,
            val done: CompletableDeferred<DecisionEventCapture?>,
        ) : Signal
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
                is Signal.Direct -> runCatching { deliverDirect(signal) }
                    .fold(signal.done::complete, signal.done::completeExceptionally)
            }
        }
    }

    /** Threshold check after a capture commits. */
    fun kick() {
        signals.trySend(Signal.Kick)
    }

    /** Drain everything now, bypassing backoff. Internal/testing + background flush. */
    suspend fun flushAll(): Boolean {
        val done = CompletableDeferred<Boolean>()
        if (signals.trySend(Signal.Flush(done)).isFailure) return false
        return done.await()
    }

    /**
     * Serialize durable capture, predecessor delivery, `/event`, response
     * application, and acknowledgement on the same actor that owns recovery.
     */
    override suspend fun capture(
        name: String,
        properties: Map<String, Any?>,
        distinctId: String,
        eventId: String?,
        applyBeforeSend: Boolean,
    ): DecisionEventCapture? {
        if (name.isEmpty() || distinctId.isEmpty()) return null
        val done = CompletableDeferred<DecisionEventCapture?>()
        val signal = Signal.Direct(
            name = name,
            properties = properties,
            distinctId = distinctId,
            eventId = eventId ?: TimeBasedEpochGenerator.shared.next(),
            applyBeforeSend = applyBeforeSend,
            done = done,
        )
        if (signals.trySend(signal).isFailure) return null
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
    private suspend fun flushUntilDrained(
        force: Boolean,
        stopBeforeId: String? = null,
    ): Boolean {
        if (!force && nowMillis() < nextRetryAtMillis) return false
        while (true) {
            val batch = store.pendingBatch(batchSize)
            if (batch.isEmpty()) {
                if (stopBeforeId != null) return false
                consecutiveFailures = 0
                nextRetryAtMillis = 0
                cleanupDelivered()
                return true
            }
            if (batch.first().id == stopBeforeId) return true
            val stopIndex = stopBeforeId?.let { id -> batch.indexOfFirst { it.id == id } } ?: -1
            val deliverable = if (stopIndex >= 0) batch.take(stopIndex) else batch
            if (deliverable.isEmpty()) return false
            // Preserve durable capture order across the two wire lanes. Send
            // the ordinary prefix as a batch, or one decision event through
            // `/event`; then read the next pending prefix.
            val firstDecisionIndex = deliverable.indexOfFirst { event ->
                isJourneyDecisionEvent(event.name)
            }
            val ordinary = when {
                firstDecisionIndex < 0 -> deliverable
                firstDecisionIndex > 0 -> deliverable.take(firstDecisionIndex)
                else -> emptyList()
            }
            val delivered = if (ordinary.isNotEmpty()) {
                runCatching {
                    api.postBatch(ordinary.map(BatchItemWireEncoder::encode))
                    store.markDelivered(ordinary.map { it.id })
                }
            } else {
                val event = deliverable.first()
                runCatching {
                    deliverDecisionAndAcknowledge(event)
                }
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
        }
    }

    private suspend fun deliverDirect(signal: Signal.Direct): DecisionEventCapture? {
        val captured = eventLog.captureIdempotentlyWithResult(
            name = signal.name,
            properties = signal.properties,
            eventId = signal.eventId,
            distinctId = signal.distinctId,
            applyBeforeSend = signal.applyBeforeSend,
        )
        if (!captured.succeeded) return null
        val event = captured.storedEvent
            ?: return DecisionEventCapture(event = null, response = null)

        if (!flushUntilDrained(force = true, stopBeforeId = event.id)) {
            kick()
            return DecisionEventCapture(event, response = null)
        }

        return runCatching {
            val response = deliverDecisionAndAcknowledge(event)
            consecutiveFailures = 0
            nextRetryAtMillis = 0
            DecisionEventCapture(event, response)
        }.getOrElse { failure ->
            scheduleRetry(failure, event)
            DecisionEventCapture(event, response = null)
        }
    }

    private suspend fun deliverDecisionAndAcknowledge(event: StoredEvent): JsonObject? {
        val response = try {
            Json.parseToJsonElement(
                api.postEvent(BatchItemWireEncoder.encode(event)),
            ).jsonObject
        } catch (failure: Throwable) {
            if (!failure.isPermanentDecisionRejection()) throw failure
            // A terminal client request cannot make progress on retry. Let
            // the owning subsystem roll back its recoverable intent before
            // resolving the source event, so a crash can only repeat cleanup.
            onDecisionRejected(event, failure)
            eventLog.markDeliveredViaDecisionLane(event.id)
            return null
        }
        onDecisionResponse(event, response)
        eventLog.markDeliveredViaDecisionLane(event.id)
        return response
    }

    private fun Throwable.isPermanentDecisionRejection(): Boolean =
        this is NuxieApi.RequestRejectedException &&
            statusCode in 400..499 &&
            statusCode !in setOf(408, 429)

    private fun scheduleRetry(failure: Throwable, event: StoredEvent) {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(retryCount)
        nextRetryAtMillis = nowMillis() +
            retryDelayMillis * (1L shl (consecutiveFailures - 1))
        Log.w(
            LOG_TAG,
            "Decision event '${event.name}' retained pending after direct delivery failure",
            failure,
        )
        kick()
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

        val DECISION_EVENT_NAMES = setOf(
            JourneyEventNames.ENROLLED,
            JourneyEventNames.TRANSITION,
            JourneyEventNames.MILESTONE,
            JourneyEventNames.EXITED,
            JourneyEventNames.EFFECT_REQUESTED,
            "\$journey_claimed",
            "\$journey_handoff",
            "\$journey_parked",
        )

        fun isJourneyDecisionEvent(name: String): Boolean = name in DECISION_EVENT_NAMES
    }
}
