package ai.nuxie.sdk.events

import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.identity.IdentityProvider
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The capture pipeline, ported from the iOS `EventLog` actor:
 * capture -> sanitize -> enrich -> beforeSend -> persist pending -> announce.
 *
 * Contract (identical to iOS, tested):
 * - A single worker coroutine serializes every capture, so persistence and
 *   subscriber announcement happen in capture order.
 * - Committed subscribers run serially, in subscription order, AFTER the
 *   event is persisted pending delivery. Subscribers registered before the
 *   first capture observe every committed event.
 * - beforeSend applies to every capture. Recovery owns identity: the
 *   transformed event keeps the original id, distinctId, and timestamp; hosts
 *   may rename the event or redact properties. Returning null terminally
 *   drops the event and records a stable drop so recovery never resurrects it.
 * - Delivery is a later PR: events accumulate as pending.
 */
internal class EventLog(
    private val store: EventStore,
    private val contextBuilder: NuxieContextBuilder,
    private val identity: IdentityProvider,
    private val beforeSend: ((NuxieEvent) -> NuxieEvent?)?,
    scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Stamps \$session_id and touches the session; null until sessions exist. */
    private val sessionIdProvider: (() -> String?)? = null,
    /** Sampled before persistence so listener attachment never replays prior captures. */
    private val forwardingEnabled: () -> Boolean = { false },
) {
    internal fun interface CommittedSubscription {
        suspend fun onCommitted(event: StoredEvent)
    }

    private data class Subscriber(
        val predicate: (StoredEvent) -> Boolean,
        val handler: CommittedSubscription,
    )

    private sealed interface Command {
        data class Capture(val name: String, val properties: Map<String, Any?>?) : Command
        data class CaptureForTrigger(
            val name: String,
            val properties: Map<String, Any?>?,
            val done: CompletableDeferred<StoredEvent?>,
        ) : Command
        data class CaptureIdempotently(
            val name: String,
            val properties: Map<String, Any?>,
            val eventId: String,
            val distinctId: String,
            val done: CompletableDeferred<Boolean>,
        ) : Command
        data class CaptureDeliveredIdempotently(
            val name: String,
            val properties: Map<String, Any?>,
            val eventId: String,
            val distinctId: String,
            val done: CompletableDeferred<Boolean>,
        ) : Command
        data class CommitServerFact(
            val event: StoredEvent,
            val receivedAtMillis: Long,
            val done: CompletableDeferred<Boolean>,
        ) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)

    /** Guarded by the worker: subscribers are read only on the worker coroutine. */
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<Subscriber>()

    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is Command.Capture -> runCatching { process(command.name, command.properties) }
                    .onFailure { Log.w(LOG_TAG, "Event capture failed", it) }
                is Command.CaptureForTrigger -> {
                    val stored = runCatching { process(command.name, command.properties) }
                        .onFailure { Log.w(LOG_TAG, "Trigger capture failed", it) }
                        .getOrNull()
                    command.done.complete(stored)
                }
                is Command.CaptureIdempotently -> {
                    val captured = runCatching {
                        processIdempotently(
                            command.name,
                            command.properties,
                            command.eventId,
                            command.distinctId,
                        )
                    }.onFailure { Log.w(LOG_TAG, "Idempotent event capture failed", it) }
                        .getOrDefault(false)
                    command.done.complete(captured)
                }
                is Command.CaptureDeliveredIdempotently -> {
                    val captured = runCatching {
                        processDeliveredIdempotently(
                            command.name,
                            command.properties,
                            command.eventId,
                            command.distinctId,
                        )
                    }.onFailure { Log.w(LOG_TAG, "Delivered event capture failed", it) }
                        .getOrDefault(false)
                    command.done.complete(captured)
                }
                is Command.CommitServerFact -> command.done.complete(
                    commitServerFactNow(command.event, command.receivedAtMillis),
                )
                is Command.Barrier -> command.done.complete(Unit)
            }
        }
    }

    /** Enqueue a capture; safe from any thread, never blocks the caller. */
    fun capture(name: String, properties: Map<String, Any?>? = null) {
        if (name.isEmpty()) {
            // iOS parity: EventLog guards empty event names at every entry.
            Log.w(LOG_TAG, "Event name cannot be empty")
            return
        }
        val result = commands.trySend(Command.Capture(name, properties))
        if (result.isFailure) {
            Log.w(LOG_TAG, "Event '$name' dropped: capture pipeline is closed.")
        }
    }

    /**
     * Register a committed-events subscriber. Handlers run serially in
     * subscription order after persistence. Downstream consumers subscribe —
     * they are never injected into the pipeline.
     */
    fun subscribeCommitted(
        predicate: (StoredEvent) -> Boolean = { true },
        handler: CommittedSubscription,
    ) {
        subscribers.add(Subscriber(predicate, handler))
    }

    /** Await everything enqueued before this call. Internal/testing only. */
    suspend fun awaitBarrier() {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Barrier(done)).isFailure) return
        done.await()
    }

    suspend fun close() {
        awaitBarrier()
        commands.close()
        worker.join()
        store.close()
    }

    /**
     * Decision-lane capture: persist + announce like every capture, then hand
     * the stored event back so the trigger service can run the synchronous
     * /event round trip in capture order.
     */
    suspend fun captureForTrigger(name: String, properties: Map<String, Any?>?): StoredEvent? {
        if (name.isEmpty()) {
            Log.w(LOG_TAG, "Event name cannot be empty")
            return null
        }
        val done = CompletableDeferred<StoredEvent?>()
        if (commands.trySend(Command.CaptureForTrigger(name, properties, done)).isFailure) return null
        return done.await()
    }

    /** Durably capture a stable-id event once, returning true for inserts and duplicates. */
    suspend fun captureIdempotently(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): Boolean {
        if (name.isEmpty() || eventId.isEmpty() || distinctId.isEmpty()) return false
        val done = CompletableDeferred<Boolean>()
        val command = Command.CaptureIdempotently(name, properties, eventId, distinctId, done)
        if (commands.trySend(command).isFailure) return false
        return done.await()
    }

    /** Persist an already server-accepted event in history and announce it locally exactly once. */
    suspend fun captureDeliveredIdempotently(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): Boolean {
        if (name.isEmpty() || eventId.isEmpty() || distinctId.isEmpty()) return false
        val done = CompletableDeferred<Boolean>()
        val command = Command.CaptureDeliveredIdempotently(name, properties, eventId, distinctId, done)
        if (commands.trySend(command).isFailure) return false
        return done.await()
    }

    /**
     * The decision lane delivered this event synchronously via /event; mark
     * it so batch delivery does not redundantly resend (the idempotency key
     * would dedupe server-side, but skipping the resend is cheaper).
     */
    suspend fun markDeliveredViaDecisionLane(eventId: String) {
        runCatching { store.markDelivered(listOf(eventId)) }
    }

    /** Commits a server fact once, delivers it locally, and never uploads it. */
    suspend fun commitServerFact(
        event: StoredEvent,
        receivedAtMillis: Long = nowMillis(),
    ): Boolean {
        val done = CompletableDeferred<Boolean>()
        val command = Command.CommitServerFact(event, receivedAtMillis, done)
        if (commands.trySend(command).isFailure) return false
        return done.await()
    }

    private suspend fun process(name: String, commandProperties: Map<String, Any?>?): StoredEvent? {
        var sanitized = EventSanitizer.sanitizeDataTypes(commandProperties ?: emptyMap())
        if (!sanitized.containsKey(SESSION_ID_PROPERTY)) {
            sessionIdProvider?.invoke()?.let { sessionId ->
                sanitized = sanitized + (SESSION_ID_PROPERTY to sessionId)
            }
        }
        val enriched = contextBuilder.buildEnrichedProperties(sanitized)
        val original = NuxieEvent(
            name = name,
            distinctId = identity.distinctId(),
            properties = enriched,
            timestampMillis = nowMillis(),
        )
        val transformed = applyBeforeSend(original)
        if (transformed == null) {
            // Terminal beforeSend drop: record it so recovery never resurrects
            // the id (iOS commits a stable capture with a nil event).
            store.recordStableDrop(original.id, original.timestampMillis)
            Log.d(LOG_TAG, "Event '$name' terminally dropped by beforeSend hook")
            return null
        }

        val stored = StoredEvent.from(
            transformed,
            forwardingName = original.name,
            forwardingReceivedAtMillis = original.timestampMillis.takeIf { forwardingEnabled() },
        )
        store.insertPending(stored)
        subscribers.forEach { subscriber ->
            if (subscriber.predicate(stored)) {
                runCatching { subscriber.handler.onCommitted(stored) }
                    .onFailure { Log.w(LOG_TAG, "Committed-event subscriber failed", it) }
            }
        }
        return stored
    }

    private suspend fun processIdempotently(
        name: String,
        commandProperties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): Boolean {
        if (store.hasStableOutcome(eventId)) return true
        var sanitized = EventSanitizer.sanitizeDataTypes(commandProperties)
        if (!sanitized.containsKey(SESSION_ID_PROPERTY)) {
            sessionIdProvider?.invoke()?.let { sessionId ->
                sanitized = sanitized + (SESSION_ID_PROPERTY to sessionId)
            }
        }
        val original = NuxieEvent(
            id = eventId,
            name = name,
            distinctId = distinctId,
            properties = contextBuilder.buildEnrichedProperties(sanitized),
            timestampMillis = nowMillis(),
        )
        val transformed = applyBeforeSend(original)
        if (transformed == null) {
            store.recordStableDrop(original.id, original.timestampMillis)
            Log.d(LOG_TAG, "Event '$name' terminally dropped by beforeSend hook")
            return true
        }
        val stored = StoredEvent.from(
            transformed,
            forwardingName = original.name,
            forwardingReceivedAtMillis = original.timestampMillis.takeIf { forwardingEnabled() },
        )
        val inserted = store.insertPendingIfAbsent(stored)
        if (inserted) {
            subscribers.forEach { subscriber ->
                if (subscriber.predicate(stored)) {
                    runCatching { subscriber.handler.onCommitted(stored) }
                        .onFailure { Log.w(LOG_TAG, "Committed-event subscriber failed", it) }
                }
            }
        }
        return true
    }

    private suspend fun commitServerFactNow(event: StoredEvent, receivedAtMillis: Long): Boolean {
        val admitted = event.withForwardingAdmission(receivedAtMillis.takeIf { forwardingEnabled() })
        val inserted = runCatching { store.insertDeliveredIfAbsent(admitted) }.getOrElse { return false }
        if (!inserted) return false
        subscribers.forEach { subscriber ->
            if (subscriber.predicate(admitted)) {
                runCatching { subscriber.handler.onCommitted(admitted) }
                    .onFailure { Log.w(LOG_TAG, "Committed-event subscriber failed", it) }
            }
        }
        return true
    }

    private suspend fun processDeliveredIdempotently(
        name: String,
        commandProperties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): Boolean {
        if (store.hasStableOutcome(eventId)) return true
        val sanitized = EventSanitizer.sanitizeDataTypes(commandProperties)
        val original = NuxieEvent(
            id = eventId,
            name = name,
            distinctId = distinctId,
            properties = contextBuilder.buildEnrichedProperties(sanitized),
            timestampMillis = nowMillis(),
        )
        val transformed = applyBeforeSendPreservingTransform(original)
        if (transformed == null) {
            store.recordStableDrop(original.id, original.timestampMillis)
            return true
        }
        val stored = StoredEvent.from(
            transformed,
            forwardingName = original.name,
            forwardingReceivedAtMillis = transformed.timestampMillis.takeIf { forwardingEnabled() },
        )
        val inserted = store.insertDeliveredIfAbsent(stored)
        if (inserted) announce(stored)
        return true
    }

    private suspend fun announce(event: StoredEvent) {
        subscribers.forEach { subscriber ->
            if (subscriber.predicate(event)) {
                runCatching { subscriber.handler.onCommitted(event) }
                    .onFailure { Log.w(LOG_TAG, "Committed-event subscriber failed", it) }
            }
        }
    }

    private fun applyBeforeSend(original: NuxieEvent): NuxieEvent? {
        val transformed = applyBeforeSendPreservingTransform(original) ?: return null
        // Recovery owns identity: pin id, distinctId, and timestamp.
        return NuxieEvent(
            id = original.id,
            name = transformed.name,
            distinctId = original.distinctId,
            properties = transformed.properties,
            timestampMillis = original.timestampMillis,
        )
    }

    private fun applyBeforeSendPreservingTransform(original: NuxieEvent): NuxieEvent? {
        val hook = beforeSend ?: return original
        return hook(original)
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val SESSION_ID_PROPERTY = "\$session_id"
    }
}
