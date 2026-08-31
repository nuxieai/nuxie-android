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
 * - A single commit worker coroutine serializes every capture, so persistence
 *   and committed-subscriber announcement happen in capture order.
 * - Committed subscribers run serially, in subscription order, AFTER the
 *   event is persisted pending delivery. Subscribers registered before the
 *   first capture observe every committed event.
 * - Forwarding admission is sampled before persistence, then successful
 *   commits enter a separate FIFO worker. Slow forwarding never blocks the
 *   commit worker, and a listener attached after admission receives no replay.
 * - beforeSend governs ordinary captures and host-governed stable captures.
 *   Ordinary captures retain their scoped distinctId while preserving the
 *   hook's id, name, properties, and timestamp. Governed stable captures
 *   additionally retain their replay id and timestamp. Returning null
 *   terminally drops the event and records a stable drop so recovery never
 *   resurrects it. Required SDK-authored system events use an owner-scoped
 *   stable lane that bypasses beforeSend.
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
) {
    internal fun interface CommittedSubscription {
        suspend fun onCommitted(event: StoredEvent)
    }

    internal fun interface ForwardingSubscription {
        suspend fun onForwarding(event: StoredEvent)
    }

    private data class Subscriber(
        val predicate: (StoredEvent) -> Boolean,
        val handler: CommittedSubscription,
    )

    private data class ForwardingSubscriber(
        val isEnabled: () -> Boolean,
        val handler: ForwardingSubscription,
    )

    private sealed interface Command {
        data class Capture(
            val name: String,
            val properties: Map<String, Any?>?,
            val distinctIdOverride: String?,
        ) : Command
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
            val applyBeforeSend: Boolean,
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
            val isCurrent: () -> Boolean,
            val done: CompletableDeferred<Boolean>,
        ) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }

    private sealed interface ForwardingCommand {
        data class Event(val event: StoredEvent) : ForwardingCommand
        data class Barrier(val done: CompletableDeferred<Unit>) : ForwardingCommand
    }

    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)
    private val forwardingCommands = Channel<ForwardingCommand>(capacity = Channel.UNLIMITED)

    /** Guarded by the worker: subscribers are read only on the worker coroutine. */
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<Subscriber>()
    private val forwardingSubscribers =
        java.util.concurrent.CopyOnWriteArrayList<ForwardingSubscriber>()

    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is Command.Capture -> runCatching {
                    process(command.name, command.properties, command.distinctIdOverride)
                }
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
                            command.applyBeforeSend,
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
                    if (command.isCurrent()) {
                        commitServerFactNow(command.event, command.receivedAtMillis)
                    } else {
                        false
                    },
                )
                is Command.Barrier -> command.done.complete(Unit)
            }
        }
    }

    private val forwardingWorker = scope.launch {
        for (command in forwardingCommands) {
            when (command) {
                is ForwardingCommand.Event -> forwardingSubscribers.forEach { subscriber ->
                    if (subscriber.isEnabled()) {
                        runCatching { subscriber.handler.onForwarding(command.event) }
                            .onFailure { Log.w(LOG_TAG, "Forwarding subscriber failed", it) }
                    }
                }
                is ForwardingCommand.Barrier -> command.done.complete(Unit)
            }
        }
    }

    /** Enqueue a capture; safe from any thread, never blocks the caller. */
    fun capture(name: String, properties: Map<String, Any?>? = null) {
        capture(name, properties, distinctIdOverride = null)
    }

    /** Enqueue a capture attributed to an already-owned customer scope. */
    fun capture(
        name: String,
        properties: Map<String, Any?>?,
        distinctIdOverride: String?,
    ) {
        if (name.isEmpty()) {
            // iOS parity: EventLog guards empty event names at every entry.
            Log.w(LOG_TAG, "Event name cannot be empty")
            return
        }
        val result = commands.trySend(Command.Capture(name, properties, distinctIdOverride))
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

    /**
     * Register a forwarding-only subscriber. Presence is sampled before the
     * store await and checked again by the FIFO forwarding worker immediately
     * before last-mile delivery.
     */
    fun subscribeForwarding(
        isEnabled: () -> Boolean = { true },
        handler: ForwardingSubscription,
    ) {
        forwardingSubscribers.add(ForwardingSubscriber(isEnabled, handler))
    }

    /** Await everything enqueued before this call. Internal/testing only. */
    suspend fun awaitBarrier() {
        awaitCommitBarrier()
        awaitForwardingBarrier()
        // A forwarding callback can synchronously enqueue another capture.
        awaitCommitBarrier()
    }

    private suspend fun awaitCommitBarrier() {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Barrier(done)).isFailure) return
        done.await()
    }

    private suspend fun awaitForwardingBarrier() {
        val done = CompletableDeferred<Unit>()
        if (forwardingCommands.trySend(ForwardingCommand.Barrier(done)).isFailure) return
        done.await()
    }

    suspend fun close() {
        awaitBarrier()
        commands.close()
        worker.join()
        forwardingCommands.close()
        forwardingWorker.join()
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
    ): Boolean = captureIdempotently(
        name,
        properties,
        eventId,
        distinctId,
        applyBeforeSend = true,
    )

    /** Durably captures a required SDK-authored system event without host interception. */
    suspend fun captureSystemEvent(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): Boolean = captureIdempotently(
        name,
        properties,
        eventId,
        distinctId,
        applyBeforeSend = false,
    )

    private suspend fun captureIdempotently(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        applyBeforeSend: Boolean,
    ): Boolean {
        if (name.isEmpty() || eventId.isEmpty() || distinctId.isEmpty()) return false
        val done = CompletableDeferred<Boolean>()
        val command = Command.CaptureIdempotently(
            name,
            properties,
            eventId,
            distinctId,
            applyBeforeSend,
            done,
        )
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
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        val done = CompletableDeferred<Boolean>()
        val command = Command.CommitServerFact(event, receivedAtMillis, isCurrent, done)
        if (commands.trySend(command).isFailure) return false
        return done.await()
    }

    private suspend fun process(
        name: String,
        commandProperties: Map<String, Any?>?,
        distinctIdOverride: String? = null,
    ): StoredEvent? {
        var sanitized = EventSanitizer.sanitizeDataTypes(commandProperties ?: emptyMap())
        if (!sanitized.containsKey(SESSION_ID_PROPERTY)) {
            sessionIdProvider?.invoke()?.let { sessionId ->
                sanitized = sanitized + (SESSION_ID_PROPERTY to sessionId)
            }
        }
        val enriched = contextBuilder.buildEnrichedProperties(sanitized)
        val original = NuxieEvent(
            name = name,
            distinctId = distinctIdOverride ?: identity.distinctId(),
            properties = enriched,
            timestampMillis = nowMillis(),
        )
        val transformed = applyBeforeSendForOrdinaryCapture(original)
        if (transformed == null) {
            // Terminal beforeSend drop: record it so recovery never resurrects
            // the id (iOS commits a stable capture with a nil event).
            store.recordStableDrop(original.id, original.timestampMillis)
            Log.d(LOG_TAG, "Event '$name' terminally dropped by beforeSend hook")
            return null
        }

        val stored = projectPostTransform(original, transformed)
        store.insertPending(stored)
        resolveForwarding(stored)
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
        applyBeforeSend: Boolean,
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
        val transformed = if (applyBeforeSend) {
            applyBeforeSendPreservingStableIdentity(original)
        } else {
            original
        }
        if (transformed == null) {
            store.recordStableDrop(original.id, original.timestampMillis)
            Log.d(LOG_TAG, "Event '$name' terminally dropped by beforeSend hook")
            return true
        }
        val stored = projectPostTransform(original, transformed)
        val inserted = store.insertPendingIfAbsent(stored)
        if (inserted) {
            resolveForwarding(stored)
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
        val admitted = event.withForwardingAdmission(forwardingAdmission(receivedAtMillis))
        val inserted = runCatching { store.insertDeliveredIfAbsent(admitted) }.getOrElse { return false }
        if (!inserted) return false
        resolveForwarding(admitted)
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
        val transformed = applyBeforeSendTransform(original)
        if (transformed == null) {
            store.recordStableDrop(original.id, original.timestampMillis)
            return true
        }
        val stored = projectPostTransform(original, transformed)
        val inserted = store.insertDeliveredIfAbsent(stored)
        if (inserted) {
            resolveForwarding(stored)
            announce(stored)
        }
        return true
    }

    private fun forwardingAdmission(receivedAtMillis: Long): Long? =
        receivedAtMillis.takeIf { forwardingSubscribers.any { it.isEnabled() } }

    private fun resolveForwarding(event: StoredEvent) {
        if (event.forwardingReceivedAtMillis == null) return
        val result = forwardingCommands.trySend(ForwardingCommand.Event(event))
        if (result.isFailure) {
            Log.w(LOG_TAG, "Committed event '${event.name}' dropped: forwarding pipeline is closed.")
        }
    }

    private suspend fun announce(event: StoredEvent) {
        subscribers.forEach { subscriber ->
            if (subscriber.predicate(event)) {
                runCatching { subscriber.handler.onCommitted(event) }
                    .onFailure { Log.w(LOG_TAG, "Committed-event subscriber failed", it) }
            }
        }
    }

    private fun applyBeforeSendForOrdinaryCapture(original: NuxieEvent): NuxieEvent? {
        val transformed = applyBeforeSendTransform(original) ?: return null
        // iOS keeps ordinary captures attributed to the identity snapshotted
        // before the hook while preserving the hook's other event fields.
        return NuxieEvent(
            id = transformed.id,
            name = transformed.name,
            distinctId = original.distinctId,
            properties = transformed.properties,
            timestampMillis = transformed.timestampMillis,
        )
    }

    private fun applyBeforeSendPreservingStableIdentity(original: NuxieEvent): NuxieEvent? {
        val transformed = applyBeforeSendTransform(original) ?: return null
        // Recovery owns stable identity: pin the replay id, distinctId, and timestamp.
        return NuxieEvent(
            id = original.id,
            name = transformed.name,
            distinctId = original.distinctId,
            properties = transformed.properties,
            timestampMillis = original.timestampMillis,
        )
    }

    private fun applyBeforeSendTransform(original: NuxieEvent): NuxieEvent? {
        val hook = beforeSend ?: return original
        return hook(original)
    }

    /**
     * The single post-beforeSend projection for every locally captured event.
     * Forwarding classification belongs to the original capture, while the
     * durable event and forwarding receipt time belong to the prepared result.
     */
    private fun projectPostTransform(original: NuxieEvent, transformed: NuxieEvent): StoredEvent {
        // The prepared field is authoritative, matching iOS: wrappers that pin
        // distinctId also restore its property after a deleting or spoofing hook.
        val projected = NuxieEvent(
            id = transformed.id,
            name = transformed.name,
            distinctId = transformed.distinctId,
            properties = transformed.properties + (DISTINCT_ID_PROPERTY to transformed.distinctId),
            timestampMillis = transformed.timestampMillis,
        )
        return StoredEvent.from(
            projected,
            forwardingName = original.name,
            forwardingReceivedAtMillis = forwardingAdmission(transformed.timestampMillis),
        )
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val DISTINCT_ID_PROPERTY = "\$distinct_id"
        const val SESSION_ID_PROPERTY = "\$session_id"
    }
}
