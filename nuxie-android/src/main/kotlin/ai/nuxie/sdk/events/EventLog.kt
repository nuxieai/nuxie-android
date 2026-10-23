package ai.nuxie.sdk.events

import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.logging.NuxieLog as Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** Process-local control route; durable parks recreate it after recovery replay. */
internal data class JourneyDeadline(
    val distinctId: String,
    val runId: String,
    val stepId: String,
    val anchorAtMillis: Long?,
    val wakeAtMillis: Long,
)

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
 * - JourneyReleaseDelivery is a later PR: events accumulate as pending.
 */
internal class EventLog(
    private val store: EventStore,
    private val contextBuilder: NuxieContextBuilder,
    private val identity: IdentityProvider,
    private val beforeSend: ((NuxieEvent) -> NuxieEvent?)?,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Stamps \$session_id and touches the session; null until sessions exist. */
    private val sessionIdProvider: (() -> String?)? = null,
) {
    internal data class IdempotentCaptureResult(
        val succeeded: Boolean,
        val storedEvent: StoredEvent?,
        val newlyCaptured: Boolean,
    )

    internal enum class ServerFactCommitResult {
        INSERTED,
        DUPLICATE,
    }

    internal fun interface CommittedSubscription {
        suspend fun onCommitted(event: StoredEvent)
    }

    internal fun interface ForwardingSubscription {
        suspend fun onForwarding(event: StoredEvent)
    }

    internal fun interface AdmissionCommittedSubscription {
        /** False keeps the durable local-route receipt pending for replay. */
        suspend fun onCommitted(event: StoredEvent, admissionGeneration: Long): Boolean
    }

    private data class Subscriber(
        val predicate: (StoredEvent) -> Boolean,
        val handler: CommittedSubscription,
    )

    private data class ForwardingSubscriber(
        val isEnabled: () -> Boolean,
        val handler: ForwardingSubscription,
    )

    private data class AdmissionSubscriber(
        val predicate: (StoredEvent) -> Boolean,
        val sampleGeneration: () -> Long,
        val handler: AdmissionCommittedSubscription,
    )

    private data class AdmissionTicket(
        val subscriber: AdmissionSubscriber,
        val generation: Long,
    )

    private val forwardingActive = java.util.concurrent.atomic.AtomicBoolean(true)

    private sealed interface Command {
        data class Capture(
            val name: String,
            val properties: Map<String, Any?>?,
            val admissionTickets: List<AdmissionTicket>,
            val activityIdentity: ActivityIdentity,
        ) : Command
        data class CaptureIdempotently(
            val name: String,
            val properties: Map<String, Any?>,
            val eventId: String,
            val distinctId: String,
            val applyBeforeSend: Boolean,
            val occurredAtMillis: Long?,
            val commitAdmission: StableEventCommitAdmission?,
            val admissionTickets: List<AdmissionTicket>,
            val activityIdentity: ActivityIdentity,
            val done: CompletableDeferred<StableEventCaptureResult>,
            val journeyOrigin: JourneyEventOrigin? = null,
        ) : Command
        data class CaptureDeliveredIdempotently(
            val name: String,
            val properties: Map<String, Any?>,
            val eventId: String,
            val distinctId: String,
            val occurredAtMillis: Long?,
            val admissionTickets: List<AdmissionTicket>,
            val activityIdentity: ActivityIdentity,
            val done: CompletableDeferred<Boolean>,
        ) : Command
        data class CommitServerFact(
            val event: StoredEvent,
            val receivedAtMillis: Long,
            val admissionTickets: List<AdmissionTicket>,
            val activityIdentity: ActivityIdentity,
            val done: CompletableDeferred<ServerFactCommitResult>,
        ) : Command
        data class Deadline(val deadline: JourneyDeadline) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }

    private sealed interface ForwardingCommand {
        data class Event(val event: StoredEvent) : ForwardingCommand
        data class Barrier(val done: CompletableDeferred<Unit>) : ForwardingCommand
    }

    private sealed interface RouteCommand {
        data class Event(
            val event: StoredEvent,
            var admissionTickets: List<AdmissionTicket>,
            val localRouteEventId: String? = null,
            var nextSubscriber: Int = 0,
            var nextAdmission: Int = 0,
        ) : RouteCommand
        data class RecoveryPrefix(val events: List<Event>) : RouteCommand
        data class Deadline(val deadline: JourneyDeadline) : RouteCommand
        data object Retry : RouteCommand
        data class Barrier(val done: CompletableDeferred<Unit>) : RouteCommand
    }

    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)
    private val forwardingCommands = Channel<ForwardingCommand>(capacity = Channel.UNLIMITED)
    private val routeCommands = Channel<RouteCommand>(capacity = Channel.UNLIMITED)

    /** Guarded by the worker: subscribers are read only on the worker coroutine. */
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<Subscriber>()
    private val forwardingSubscribers =
        java.util.concurrent.CopyOnWriteArrayList<ForwardingSubscriber>()
    private val admissionSubscribers =
        java.util.concurrent.CopyOnWriteArrayList<AdmissionSubscriber>()
    private val activeLocalRouteIds = Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )
    private val failedLocalRouteAcknowledgementIds =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val routingHoldLock = Any()
    private var routingGeneration = 0L
    private val routingDeferred = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var journeyDeadlineHandler: (suspend (JourneyDeadline) -> Boolean)? = null
    private val pendingRoutes = ArrayDeque<RouteCommand>()
    private var routeRetryJob: Job? = null
    private var acknowledgementRetryJob: Job? = null
    private val closing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val commitProgress = java.util.concurrent.atomic.AtomicLong(0)
    private val routeProgress = java.util.concurrent.atomic.AtomicLong(0)
    private val forwardingProgress = java.util.concurrent.atomic.AtomicLong(0)

    private val worker = scope.launch {
        for (command in commands) {
            when (command) {
                is Command.Capture -> runCatching {
                    process(
                        command.name,
                        command.properties,
                        command.admissionTickets,
                        command.activityIdentity,
                    )
                }
                    .onFailure { Log.w(LOG_TAG, "Event capture failed", it) }
                is Command.CaptureIdempotently -> {
                    val result = runCatching {
                        processIdempotently(
                            command.name,
                            command.properties,
                            command.eventId,
                            command.distinctId,
                            command.applyBeforeSend,
                            command.occurredAtMillis,
                            command.commitAdmission,
                            command.admissionTickets,
                            command.activityIdentity,
                            command.journeyOrigin,
                        )
                    }.onFailure { Log.w(LOG_TAG, "Idempotent event capture failed", it) }
                        .getOrDefault(StableEventCaptureResult(false, null))
                    command.done.complete(result)
                }
                is Command.CaptureDeliveredIdempotently -> {
                    val captured = runCatching {
                        processDeliveredIdempotently(
                            command.name,
                            command.properties,
                            command.eventId,
                            command.distinctId,
                            command.occurredAtMillis,
                            command.admissionTickets,
                            command.activityIdentity,
                        )
                    }.onFailure { Log.w(LOG_TAG, "Delivered event capture failed", it) }
                        .getOrDefault(false)
                    command.done.complete(captured)
                }
                is Command.CommitServerFact -> runCatching {
                    commitServerFactNow(
                        command.event,
                        command.receivedAtMillis,
                        command.admissionTickets,
                        command.activityIdentity,
                    )
                }.fold(command.done::complete, command.done::completeExceptionally)
                is Command.Deadline -> routeCommands.trySend(RouteCommand.Deadline(command.deadline))
                is Command.Barrier -> command.done.complete(Unit)
            }
            if (command !is Command.Barrier) commitProgress.incrementAndGet()
        }
    }

    private val routeWorker = scope.launch {
        for (command in routeCommands) {
            when (command) {
                is RouteCommand.Event, is RouteCommand.Deadline -> {
                    pendingRoutes.addLast(command)
                    // Only the retry timer or a routing release retries a failed head.
                    if (routeRetryJob == null) drainRoutes()
                }
                is RouteCommand.RecoveryPrefix -> {
                    command.events.asReversed().forEach(pendingRoutes::addFirst)
                    if (routeRetryJob == null) drainRoutes()
                }
                RouteCommand.Retry -> {
                    routeRetryJob?.cancel()
                    routeRetryJob = null
                    drainRoutes()
                }
                is RouteCommand.Barrier -> command.done.complete(Unit)
            }
        }
    }

    /** Captures and controls share the commit FIFO, including captures still persisting. */
    fun enqueueJourneyDeadline(deadline: JourneyDeadline) {
        if (!closing.get()) commands.trySend(Command.Deadline(deadline))
    }

    fun setJourneyDeadlineHandler(handler: suspend (JourneyDeadline) -> Boolean) {
        journeyDeadlineHandler = handler
    }

    /** A hold stops routes, while capture, storage and recovery replay remain available. */
    fun deferCommittedRouting(): Long = synchronized(routingHoldLock) {
        routingGeneration += 1
        routingDeferred.set(true)
        routingGeneration
    }

    fun releaseCommittedRouting(ifGeneration: Long) = synchronized(routingHoldLock) {
        if (routingGeneration == ifGeneration) {
            routingDeferred.set(false)
            routeCommands.trySend(RouteCommand.Retry)
        }
        Unit
    }

    private suspend fun drainRoutes() {
        while (!routingDeferred.get()) {
            when (val head = pendingRoutes.firstOrNull() ?: return) {
                is RouteCommand.Event -> {
                    if (!deliverRouteHead(head)) {
                        scheduleRouteRetry()
                        return
                    }
                    acknowledgeLocalRouteIfNeeded(head.localRouteEventId)
                }
                is RouteCommand.Deadline -> {
                    // Unavailable deadline work is derived again from its stored park.
                    if (head.deadline.distinctId == identity.distinctId()) runCatching {
                        journeyDeadlineHandler?.invoke(head.deadline)
                    }.onFailure { Log.w(LOG_TAG, "Journey deadline handling failed", it) }
                }
                else -> error("Not a route")
            }
            pendingRoutes.removeFirst()
            routeProgress.incrementAndGet()
        }
    }

    private suspend fun deliverRouteHead(head: RouteCommand.Event): Boolean {
        while (head.nextSubscriber < subscribers.size) {
            val subscriber = subscribers[head.nextSubscriber++]
            if (subscriber.predicate(head.event)) runCatching { subscriber.handler.onCommitted(head.event) }
                .onFailure { Log.w(LOG_TAG, "Committed-event subscriber failed", it) }
        }
        while (head.nextAdmission < head.admissionTickets.size) {
            var ticket = head.admissionTickets[head.nextAdmission]
            if (ticket.subscriber.predicate(head.event)) {
                suspend fun accepts() = runCatching {
                    ticket.subscriber.handler.onCommitted(head.event, ticket.generation)
                }.onFailure { Log.w(LOG_TAG, "Admission committed-event subscriber failed", it) }.getOrDefault(false)
                if (!accepts()) {
                    val refreshed = sampleAdmissionTickets()
                    if (refreshed == head.admissionTickets) return false
                    head.admissionTickets = refreshed
                    ticket = refreshed.getOrNull(head.nextAdmission) ?: return true
                    if (!accepts()) return false
                }
            }
            head.nextAdmission++
        }
        return true
    }

    private fun scheduleRouteRetry() {
        if (routeRetryJob != null || closing.get()) return
        routeRetryJob = scope.launch {
            delay(1_000L)
            routeCommands.trySend(RouteCommand.Retry)
        }
    }

    private fun scheduleAcknowledgementRetry() {
        synchronized(failedLocalRouteAcknowledgementIds) {
            if (acknowledgementRetryJob != null || closing.get()) return
            acknowledgementRetryJob = scope.launch {
                delay(1_000L)
                // Receipt cleanup never occupies the serial route worker.
                retryFailedLocalRouteAcknowledgements()
                synchronized(failedLocalRouteAcknowledgementIds) {
                    acknowledgementRetryJob = null
                    if (failedLocalRouteAcknowledgementIds.isNotEmpty()) scheduleAcknowledgementRetry()
                }
            }
        }
    }

    private val forwardingWorker = scope.launch {
        for (command in forwardingCommands) {
            when (command) {
                is ForwardingCommand.Event -> {
                    forwardingSubscribers.forEach { subscriber ->
                        if (subscriber.isEnabled()) {
                            runCatching { subscriber.handler.onForwarding(command.event) }
                                .onFailure { Log.w(LOG_TAG, "Forwarding subscriber failed", it) }
                        }
                    }
                    forwardingProgress.incrementAndGet()
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
        val result = commands.trySend(
            Command.Capture(
                name, properties, sampleAdmissionTickets(),
                captureActivityIdentity(distinctIdOverride),
            ),
        )
        if (result.isFailure) {
            Log.w(LOG_TAG, "Event dropped: capture pipeline is closed", null, Log.sensitive("event", name))
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
     * Registers a committed-event consumer whose generation is sampled at
     * capture admission, before persistence can race a profile replacement.
     */
    fun subscribeCommittedWithAdmission(
        predicate: (StoredEvent) -> Boolean = { true },
        sampleGeneration: () -> Long,
        handler: AdmissionCommittedSubscription,
    ) {
        admissionSubscribers.add(
            AdmissionSubscriber(predicate, sampleGeneration, handler),
        )
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
        var previous = Triple(-1L, -1L, -1L)
        repeat(MAX_BARRIER_PASSES) {
            awaitCommitBarrier()
            awaitRouteBarrier()
            awaitForwardingBarrier()
            retryFailedLocalRouteAcknowledgements()
            val current = Triple(
                commitProgress.get(),
                routeProgress.get(),
                forwardingProgress.get(),
            )
            if (current == previous) return
            previous = current
        }
        Log.w(LOG_TAG, "Event pipeline did not quiesce", null, Log.status("passes", MAX_BARRIER_PASSES))
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

    private suspend fun awaitRouteBarrier() {
        val done = CompletableDeferred<Unit>()
        if (routeCommands.trySend(RouteCommand.Barrier(done)).isFailure) return
        done.await()
    }

    suspend fun close() {
        closeWorkers()
        store.close()
    }

    /** Stop the pipelines without closing the store owned by the composition root. */
    suspend fun closeWorkers() {
        forwardingActive.set(false)
        closing.set(true)
        routeRetryJob?.cancel()
        acknowledgementRetryJob?.cancel()
        awaitBarrier()
        commands.close()
        worker.join()
        routeCommands.close()
        routeWorker.join()
        forwardingCommands.close()
        forwardingWorker.join()
    }

    /** Durably capture a stable-id event once, returning true for inserts and duplicates. */
    suspend fun captureIdempotently(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): Boolean = captureStable(
        name,
        properties,
        eventId,
        distinctId,
        applyBeforeSend = true,
        occurredAtMillis = null,
        commitAdmission = null,
    ).settled

    /**
     * Durably capture a stable-id event and report whether this call inserted
     * the stored event. Existing stable outcomes remain successful duplicates.
     */
    suspend fun captureIdempotentlyWithResult(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): IdempotentCaptureResult = captureIdempotentlyWithResult(
        name,
        properties,
        eventId,
        distinctId,
        applyBeforeSend = true,
    )

    internal suspend fun captureIdempotentlyWithResult(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        applyBeforeSend: Boolean,
    ): IdempotentCaptureResult {
        val result = captureStable(
            name,
            properties,
            eventId,
            distinctId,
            applyBeforeSend = applyBeforeSend,
            occurredAtMillis = null,
            commitAdmission = null,
        )
        return IdempotentCaptureResult(
            succeeded = result.settled,
            storedEvent = result.event.takeIf { result.newlyCaptured },
            newlyCaptured = result.newlyCaptured,
        )
    }

    /** Stable capture whose final SQLite mutation is execution-fenced. */
    suspend fun captureIdempotentlyIfCurrent(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        admission: StableEventCommitAdmission,
        journeyOrigin: JourneyEventOrigin? = null,
    ): Boolean = captureStable(
        name,
        properties,
        eventId,
        distinctId,
        applyBeforeSend = true,
        occurredAtMillis = null,
        commitAdmission = admission,
        journeyOrigin = journeyOrigin,
    ).settled

    /** Stable ordinary event capture preserving renderer occurrence time. */
    suspend fun captureScreenEvent(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        occurredAtMillis: Long,
        admission: StableEventCommitAdmission?,
        journeyOrigin: JourneyEventOrigin? = null,
    ): StableEventCaptureResult = captureStable(
        name,
        properties,
        eventId,
        distinctId,
        applyBeforeSend = true,
        occurredAtMillis = occurredAtMillis,
        commitAdmission = admission,
        journeyOrigin = journeyOrigin,
    )

    /** Durably captures a required SDK-authored system event without host interception. */
    suspend fun captureSystemEvent(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): Boolean = captureStable(
        name,
        properties,
        eventId,
        distinctId,
        applyBeforeSend = false,
        occurredAtMillis = null,
        commitAdmission = null,
    ).settled

    /** Stable system event capture that preserves its local routing receipt. */
    suspend fun captureRoutedSystemEvent(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        occurredAtMillis: Long,
        admission: StableEventCommitAdmission?,
    ): StableEventCaptureResult = captureStable(
        name,
        properties,
        eventId,
        distinctId,
        applyBeforeSend = false,
        occurredAtMillis = occurredAtMillis,
        commitAdmission = admission,
    )

    private suspend fun captureStable(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        applyBeforeSend: Boolean,
        occurredAtMillis: Long?,
        commitAdmission: StableEventCommitAdmission?,
        journeyOrigin: JourneyEventOrigin? = null,
    ): StableEventCaptureResult {
        if (name.isEmpty() || eventId.isEmpty() || distinctId.isEmpty()) {
            return StableEventCaptureResult(false, null)
        }
        val done = CompletableDeferred<StableEventCaptureResult>()
        val command = Command.CaptureIdempotently(
            name,
            properties,
            eventId,
            distinctId,
            applyBeforeSend,
            occurredAtMillis,
            commitAdmission,
            sampleAdmissionTickets(),
            captureActivityIdentity(distinctId),
            done,
            journeyOrigin,
        )
        if (commands.trySend(command).isFailure) {
            return StableEventCaptureResult(false, null)
        }
        return done.await()
    }

    /** Persist an already server-accepted event in history and announce it locally exactly once. */
    suspend fun captureDeliveredIdempotently(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        occurredAtMillis: Long? = null,
    ): Boolean {
        if (name.isEmpty() || eventId.isEmpty() || distinctId.isEmpty()) return false
        val done = CompletableDeferred<Boolean>()
        val command = Command.CaptureDeliveredIdempotently(
            name,
            properties,
            eventId,
            distinctId,
            occurredAtMillis,
            sampleAdmissionTickets(),
            captureActivityIdentity(distinctId),
            done,
        )
        if (commands.trySend(command).isFailure) return false
        return done.await()
    }

    /**
     * Commits a server fact once, delivers it locally, and never uploads it.
     * Facts are customer-scoped by [StoredEvent.distinctId], not localized.
     */
    suspend fun commitServerFact(
        event: StoredEvent,
        receivedAtMillis: Long = nowMillis(),
    ): ServerFactCommitResult {
        val done = CompletableDeferred<ServerFactCommitResult>()
        val command = Command.CommitServerFact(
            event,
            receivedAtMillis,
            sampleAdmissionTickets(),
            captureActivityIdentity(event.distinctId),
            done,
        )
        check(commands.trySend(command).isSuccess) { "Event capture pipeline is closed." }
        return done.await()
    }

    private suspend fun process(
        name: String,
        commandProperties: Map<String, Any?>?,
        admissionTickets: List<AdmissionTicket>,
        activityIdentity: ActivityIdentity,
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
            distinctId = activityIdentity.customerId,
            properties = enriched,
            timestampMillis = nowMillis(),
        )
        val transformed = applyBeforeSendForOrdinaryCapture(original)
        if (transformed == null) {
            // Terminal beforeSend drop: record it so recovery never resurrects
            // the id (iOS commits a stable capture with a nil event).
            store.recordStableDrop(original.id, original.timestampMillis)
            Log.d(LOG_TAG, "Event terminally dropped by beforeSend hook", null, Log.sensitive("event", name))
            return null
        }

        val stored = projectPostTransform(original, transformed, activityIdentity)
        val commit = store.insertPendingAndStageRoute(stored)
        if (commit.inserted) resolveForwarding(stored)
        if (commit.localRoutePending && activeLocalRouteIds.add(stored.id)) {
            resolveRoute(stored, admissionTickets, localRouteEventId = stored.id)
        }
        return stored
    }

    private suspend fun processIdempotently(
        name: String,
        commandProperties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        applyBeforeSend: Boolean,
        occurredAtMillis: Long?,
        commitAdmission: StableEventCommitAdmission?,
        admissionTickets: List<AdmissionTicket>,
        activityIdentity: ActivityIdentity,
        journeyOrigin: JourneyEventOrigin?,
    ): StableEventCaptureResult {
        existingStableCapture(eventId)?.let { return it }
        var sanitized = EventSanitizer.sanitizeDataTypes(commandProperties)
        // Leg outputs are validated JSON. Keep their exact values through
        // analytics sanitization; beforeSend still governs the full event.
        if (name == JourneyEventNames.LEG_COMPLETED && commandProperties.containsKey("outputs")) {
            sanitized = sanitized + ("outputs" to commandProperties["outputs"])
        }
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
            timestampMillis = occurredAtMillis ?: nowMillis(),
        )
        val transformed = if (applyBeforeSend) {
            applyBeforeSendPreservingStableIdentity(original)
        } else {
            original
        }
        if (transformed == null) {
            val recorded = if (commitAdmission == null) {
                store.recordStableDrop(original.id, original.timestampMillis)
                true
            } else {
                store.recordStableDrop(
                    original.id,
                    original.timestampMillis,
                    commitAdmission,
                ) != null
            }
            if (!recorded) return StableEventCaptureResult(false, null)
            Log.d(LOG_TAG, "Event terminally dropped by beforeSend hook", null, Log.sensitive("event", name))
            return StableEventCaptureResult(true, null)
        }
        val stored = projectPostTransform(original, transformed, activityIdentity, journeyOrigin)
        val commit = if (commitAdmission == null) {
            store.insertPendingIfAbsentAndStageRoute(stored)
        } else {
            store.insertPendingIfAbsentAndStageRoute(stored, commitAdmission)
                ?: return StableEventCaptureResult(false, null)
        }
        if (commit.inserted) {
            resolveForwarding(stored)
        }
        if (commit.localRoutePending && activeLocalRouteIds.add(stored.id)) {
            resolveRoute(stored, admissionTickets, localRouteEventId = stored.id)
        }
        return if (commit.inserted) {
            StableEventCaptureResult(
                settled = true,
                event = stored,
                localRoutePending = commit.localRoutePending,
                newlyCaptured = true,
            )
        } else {
            existingStableCapture(eventId) ?: StableEventCaptureResult(false, null)
        }
    }

    private suspend fun existingStableCapture(eventId: String): StableEventCaptureResult? {
        store.stableEvent(eventId)?.let {
            return StableEventCaptureResult(
                settled = true,
                event = it,
                localRoutePending = store.isLocalRoutePending(eventId),
            )
        }
        return if (store.hasStableOutcome(eventId)) {
            StableEventCaptureResult(true, null)
        } else {
            null
        }
    }

    private suspend fun commitServerFactNow(
        event: StoredEvent,
        receivedAtMillis: Long,
        admissionTickets: List<AdmissionTicket>,
        activityIdentity: ActivityIdentity,
    ): ServerFactCommitResult {
        val admitted = event.withForwardingAdmission(forwardingAdmission(receivedAtMillis), activityIdentity)
        val commit = store.insertDeliveredIfAbsentAndStageRoute(admitted)
        if (!commit.inserted) return ServerFactCommitResult.DUPLICATE
        resolveForwarding(admitted)
        if (commit.localRoutePending && activeLocalRouteIds.add(admitted.id)) {
            resolveRoute(admitted, admissionTickets, localRouteEventId = admitted.id)
        }
        return ServerFactCommitResult.INSERTED
    }

    private suspend fun processDeliveredIdempotently(
        name: String,
        commandProperties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
        occurredAtMillis: Long?,
        admissionTickets: List<AdmissionTicket>,
        activityIdentity: ActivityIdentity,
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
            timestampMillis = occurredAtMillis ?: nowMillis(),
        )
        val transformed = applyBeforeSendTransform(original)
        if (transformed == null) {
            store.recordStableDrop(original.id, original.timestampMillis)
            return true
        }
        val stored = projectPostTransform(original, transformed, activityIdentity)
        val commit = store.insertDeliveredIfAbsentAndStageRoute(stored)
        if (commit.inserted) {
            resolveForwarding(stored)
        }
        if (commit.localRoutePending && activeLocalRouteIds.add(stored.id)) {
            resolveRoute(stored, admissionTickets, localRouteEventId = stored.id)
        }
        return true
    }

    private fun captureActivityIdentity(expectedCustomerId: String?): ActivityIdentity {
        val captured = identity.captureScope()
        val customerId = expectedCustomerId ?: captured.distinctId
        val provider = java.lang.ref.WeakReference(identity)
        val active = forwardingActive
        return ActivityIdentity(customerId) {
            active.get() && captured.distinctId == customerId &&
                provider.get()?.isCurrentScope(captured) == true
        }
    }

    private fun forwardingAdmission(receivedAtMillis: Long): Long? =
        receivedAtMillis.takeIf { forwardingSubscribers.any { it.isEnabled() } }

    private fun resolveForwarding(event: StoredEvent) {
        if (event.forwardingReceivedAtMillis == null) return
        val result = forwardingCommands.trySend(ForwardingCommand.Event(event))
        if (result.isFailure) {
            Log.w(LOG_TAG, "Committed event dropped: forwarding pipeline is closed", null, Log.sensitive("event", event.name))
        }
    }

    private fun resolveRoute(
        event: StoredEvent,
        admissionTickets: List<AdmissionTicket>,
        localRouteEventId: String? = null,
    ) {
        val result = routeCommands.trySend(
            RouteCommand.Event(event, admissionTickets, localRouteEventId),
        )
        if (result.isFailure) {
            localRouteEventId?.let(activeLocalRouteIds::remove)
            Log.w(LOG_TAG, "Committed event dropped: route pipeline is closed", null, Log.sensitive("event", event.name))
        }
    }

    /**
     * Replays subscriber routes left pending by a prior process. This
     * runs subscribers inline so a caller opening durable journey state can
     * finish recovery before admitting fresh work.
     * Live routes already owned by the route worker are excluded: that worker
     * may itself be waiting for the Journey command performing this replay.
     */
    suspend fun replayPendingLocalRoutes(distinctId: String): Boolean = runCatching {
        val admissionTickets = sampleAdmissionTickets()
        val recovered = store.queryPendingLocalRoutes(distinctId)
            .filter { activeLocalRouteIds.add(it.id) }
            .map { RouteCommand.Event(it, admissionTickets, it.id) }
        for ((index, route) in recovered.withIndex()) {
            if (!deliverRouteHead(route)) {
                // Recovery is inline because the Journey worker may own it.
                // A refused prefix transfers to the same ordered queue for retry.
                routeCommands.trySend(RouteCommand.RecoveryPrefix(recovered.drop(index)))
                return@runCatching false
            }
            acknowledgeLocalRouteIfNeeded(route.localRouteEventId)
        }
        retryFailedLocalRouteAcknowledgements()
        if (!routingDeferred.get()) routeCommands.trySend(RouteCommand.Retry)
        true
    }.onFailure {
        Log.w(LOG_TAG, "Failed to replay pending local routes", it)
    }.getOrDefault(false)

    private suspend fun acknowledgeLocalRouteIfNeeded(eventId: String?) {
        if (eventId == null) return
        runCatching { store.markLocalRouteDelivered(eventId) }
            .onSuccess {
                failedLocalRouteAcknowledgementIds.remove(eventId)
                activeLocalRouteIds.remove(eventId)
            }
            .onFailure {
                failedLocalRouteAcknowledgementIds.add(eventId)
                scheduleAcknowledgementRetry()
                Log.w(LOG_TAG, "Failed to acknowledge local route", it, Log.sensitive("eventId", eventId))
            }
    }

    private suspend fun retryFailedLocalRouteAcknowledgements(): Boolean {
        for (eventId in failedLocalRouteAcknowledgementIds.toList().sorted()) {
            acknowledgeLocalRouteIfNeeded(eventId)
        }
        return failedLocalRouteAcknowledgementIds.isEmpty()
    }

    private fun sampleAdmissionTickets(): List<AdmissionTicket> =
        admissionSubscribers.mapNotNull { subscriber ->
            runCatching { AdmissionTicket(subscriber, subscriber.sampleGeneration()) }
                .onFailure { Log.w(LOG_TAG, "Event admission generation failed", it) }
                .getOrNull()
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
    private fun projectPostTransform(
        original: NuxieEvent,
        transformed: NuxieEvent,
        activityIdentity: ActivityIdentity,
        journeyOrigin: JourneyEventOrigin? = null,
    ): StoredEvent {
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
            forwardingIdentity = activityIdentity,
            journeyOrigin = journeyOrigin,
        )
    }

    private companion object {
        const val MAX_BARRIER_PASSES = 100
        const val LOG_TAG = "Nuxie"
        const val DISTINCT_ID_PROPERTY = "\$distinct_id"
        const val SESSION_ID_PROPERTY = "\$session_id"
    }
}
