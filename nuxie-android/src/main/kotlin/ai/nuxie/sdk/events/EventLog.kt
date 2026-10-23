package ai.nuxie.sdk.events

import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.logging.NuxieLog as Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Process-local control route; durable parks recreate it after recovery replay. */
internal data class JourneyDeadline(
    val distinctId: String,
    val runId: String,
    val stepId: String,
    val anchorAtMillis: Long?,
    val wakeAtMillis: Long,
) {
    internal var work: JourneyWork? = null
    internal var queueKey: String = "deadline:${this}"
}

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
    private val retrySleep: suspend (Long) -> Unit = { delay(it) },
    private val retryClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
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
        val plan: (suspend (StoredEvent, Long) -> List<JourneyWork>?)? = null,
        val afterAttempt: suspend (StoredEvent, Boolean) -> Unit = { _, _ -> },
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
        data class Work(val work: JourneyWork) : Command
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
            val children: MutableList<JourneyWork> = mutableListOf(),
            var submitted: Boolean = false,
        ) : RouteCommand
        data class RecoveryPrefix(val work: List<JourneyWork>) : RouteCommand
        data class Work(val work: JourneyWork) : RouteCommand
        data class Deadline(val deadline: JourneyDeadline) : RouteCommand
        data object Retry : RouteCommand
        data object RetryTimer : RouteCommand
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
    @Volatile private var journeyDeadlineHandler: (suspend (JourneyDeadline) -> JourneyWork.Result)? = null
    @Volatile private var journeyInputsSettled: (String) -> Boolean = { true }
    private val routeQueue = JourneyWorkQueue(scope, {
        routeCommands.trySend(RouteCommand.RetryTimer)
    }, retrySleep, retryClockMillis)
    // Index committed commands not yet offered to the queue. It never applies
    // inputs or retries them; a deadline freezes this index exactly once.
    private val committedInputsLock = Any()
    private val committedInputs = linkedMapOf<String, RouteCommand.Event>()
    // Inputs captured but not yet committed, such as a renderer emission waiting in
    // staging. Guarded by committedInputsLock; identity equality keeps each stage apart.
    private class StagedInput(val distinctId: String, val occurredAtMillis: Long, val stagedAtMillis: Long)
    private val stagedInputs = mutableSetOf<StagedInput>()
    private val pendingCommittedNotifications = mutableListOf<RouteCommand.Event>()
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
                is Command.Work -> routeCommands.trySend(RouteCommand.Work(command.work))
                is Command.Barrier -> command.done.complete(Unit)
            }
            if (command !is Command.Barrier) commitProgress.incrementAndGet()
        }
    }

    private val routeWorker = scope.launch {
        for (command in routeCommands) {
            when (command) {
                is RouteCommand.Event -> {
                    pendingCommittedNotifications += command
                    if (!command.submitted) {
                        command.submitted = true
                        routeQueue.add(eventWork(command))
                    }
                    drainRoutes()
                }
                is RouteCommand.Deadline -> {
                    val deadline = command.deadline
                    routeQueue.add(JourneyWork(
                        key = deadline.queueKey, distinctId = deadline.distinctId, runId = deadline.runId,
                        deadline = deadline, pendingInputs = { takeCommittedInputs(deadline) },
                    ) {
                        if (deadline.distinctId != identity.distinctId()) JourneyWork.Result.Done()
                        else journeyDeadlineHandler?.invoke(deadline) ?: JourneyWork.Result.Done()
                    })
                    drainRoutes()
                }
                is RouteCommand.Work -> {
                    routeQueue.add(command.work)
                    drainRoutes()
                }
                is RouteCommand.RecoveryPrefix -> {
                    command.work.asReversed().forEach { routeQueue.add(it, first = true) }
                    drainRoutes()
                }
                RouteCommand.Retry -> drainRoutes()
                RouteCommand.RetryTimer -> {
                    if (!closing.get()) routeQueue.retryReady()
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

    fun enqueueJourneyWork(work: JourneyWork) {
        if (!closing.get()) commands.trySend(Command.Work(work))
    }

    /** Eligibility changed (for example, foreground); failed entries keep their backoff. */
    fun resumeJourneyWork() {
        if (!closing.get()) routeCommands.trySend(RouteCommand.Retry)
    }

    fun setJourneyDeadlineHandler(handler: suspend (JourneyDeadline) -> JourneyWork.Result) {
        journeyDeadlineHandler = handler
    }

    /** False while a customer's recovery is pending or a read of their committed inputs failed. */
    fun setJourneyInputsSettled(settled: (String) -> Boolean) {
        journeyInputsSettled = settled
    }

    /**
     * Records an input captured but not yet committed. Until the returned release runs,
     * or [STAGED_INPUT_BOUND_MILLIS] passes on the monotonic clock, it holds back its
     * customer's input watermark, so a deadline waits for an input that happened in time.
     */
    fun stageJourneyInput(distinctId: String, occurredAtMillis: Long): () -> Unit {
        val staged = StagedInput(distinctId, occurredAtMillis, retryClockMillis())
        synchronized(committedInputsLock) { stagedInputs += staged }
        return { synchronized(committedInputsLock) { stagedInputs -= staged } }
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
        val before = routeQueue.progress
        routeQueue.drain { routingDeferred.get() }
        routeProgress.addAndGet(routeQueue.progress - before)
        // Ordinary subscribers keep progressing even if Journey admission is unavailable.
        if (!routingDeferred.get()) {
            for (event in pendingCommittedNotifications) notifyCommittedSubscribers(event)
            pendingCommittedNotifications.clear()
        }
    }

    private suspend fun notifyCommittedSubscribers(head: RouteCommand.Event) {
        while (head.nextSubscriber < subscribers.size) {
            val subscriber = subscribers[head.nextSubscriber++]
            if (subscriber.predicate(head.event)) runCatching { subscriber.handler.onCommitted(head.event) }
                .onFailure { Log.w(LOG_TAG, "Committed-event subscriber failed", it) }
        }
    }

    /**
     * The customer's input watermark W, worked out afresh each time a deadline asks:
     * every input this device has captured for them that happened at or before W is
     * committed and readable. W is now, or just before the earliest input of theirs
     * still in staging; the wait window is inclusive, so an input staged exactly at a
     * deadline holds it. Null while their recovery is pending or a read failed.
     */
    private fun inputWatermark(distinctId: String): Long? {
        if (!journeyInputsSettled(distinctId)) return null
        val earliestStaged = synchronized(committedInputsLock) {
            val monotonicNow = retryClockMillis()
            // An input staged longer than the bound no longer holds W back; it is late.
            stagedInputs.removeAll { monotonicNow - it.stagedAtMillis >= STAGED_INPUT_BOUND_MILLIS }
            stagedInputs.filter { it.distinctId == distinctId }.minOfOrNull { it.occurredAtMillis }
        }
        val now = nowMillis()
        return if (earliestStaged == null) now else minOf(now, earliestStaged - 1)
    }

    /** Null until the customer's watermark reaches the deadline; then its committed inputs, once. */
    private fun takeCommittedInputs(deadline: JourneyDeadline): List<JourneyWork>? {
        // A departed customer's deadline is dropped by its own attempt, so it never waits.
        if (deadline.distinctId == identity.distinctId()) {
            val watermark = inputWatermark(deadline.distinctId) ?: return null
            if (watermark < deadline.wakeAtMillis) return null
        }
        return synchronized(committedInputsLock) {
            committedInputs.values.filter { head ->
                !head.submitted && head.event.distinctId == deadline.distinctId &&
                    head.event.timestampMillis <= deadline.wakeAtMillis
            }.map { head ->
                head.submitted = true
                eventWork(head)
            }
        }
    }

    private fun eventWork(head: RouteCommand.Event): JourneyWork = JourneyWork(
        key = "event:${head.event.id}", distinctId = head.event.distinctId, kind = JourneyWork.Kind.EVENT,
        input = head.event,
    ) {
        synchronized(committedInputsLock) { committedInputs.remove(head.event.id) }
        notifyCommittedSubscribers(head)
        while (head.nextAdmission < head.admissionTickets.size) {
            var ticket = head.admissionTickets[head.nextAdmission]
            if (ticket.subscriber.predicate(head.event)) {
                suspend fun accepts(): Boolean {
                    val plan = ticket.subscriber.plan
                    if (plan == null) return ticket.subscriber.handler.onCommitted(head.event, ticket.generation)
                    val children = plan(head.event, ticket.generation) ?: return false
                    head.children.addAll(children)
                    return true
                }
                if (!accepts()) {
                    val refreshed = sampleAdmissionTickets()
                    if (refreshed == head.admissionTickets) return@JourneyWork JourneyWork.Result.Failed
                    head.admissionTickets = refreshed
                    ticket = refreshed.getOrNull(head.nextAdmission)
                        ?: return@JourneyWork JourneyWork.Result.Done(head.children)
                    if (!accepts()) return@JourneyWork JourneyWork.Result.Failed
                }
            }
            head.nextAdmission++
        }
        JourneyWork.Result.Done(head.children)
    }.also { work ->
        work.completed = {
            acknowledgeLocalRouteIfNeeded(head.localRouteEventId)
            for (ticket in head.admissionTickets) ticket.subscriber.afterAttempt(head.event, true)
        }
        work.failed = {
            for (ticket in head.admissionTickets) ticket.subscriber.afterAttempt(head.event, false)
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

    /** Expand an event at its queue position into independently ordered Journey inputs. */
    fun subscribeJourneyEvents(
        sampleGeneration: () -> Long,
        afterAttempt: suspend (StoredEvent, Boolean) -> Unit = { _, _ -> },
        plan: suspend (StoredEvent, Long) -> List<JourneyWork>?,
    ) {
        admissionSubscribers.add(AdmissionSubscriber(
            { true }, sampleGeneration, AdmissionCommittedSubscription { _, _ -> true }, plan, afterAttempt,
        ))
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
        // New controls and retries stop. Commands already accepted still reach Journey
        // admission and acknowledge their routes before the queue closes.
        closing.set(true)
        routeQueue.stopRetries()
        acknowledgementRetryJob?.cancel()
        awaitBarrier()
        commands.close()
        worker.join()
        routeCommands.close()
        routeWorker.join()
        routeQueue.close()
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
        val head = RouteCommand.Event(event, admissionTickets, localRouteEventId)
        val result = synchronized(committedInputsLock) {
            committedInputs[event.id] = head
            routeCommands.trySend(head)
        }
        if (result.isFailure) {
            synchronized(committedInputsLock) { committedInputs.remove(event.id) }
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
        val retained = mutableListOf<JourneyWork>()
        var accepted = true
        try {
            for (event in recovered) {
                // Check ownership before the suspension: receipt cleanup may finish while
                // the pending-route snapshot is being read.
                if (event.id in activeLocalRouteIds) continue
                if (!store.hasPendingLocalRoute(event.id, distinctId)) continue
                if (!activeLocalRouteIds.add(event.id)) continue
                val work = eventWork(RouteCommand.Event(event, admissionTickets, event.id))
                if (retained.isNotEmpty()) retained += work else {
                    val attempted = attemptRecoveryWork(work)
                    retained.addAll(attempted.remaining)
                    accepted = accepted && attempted.accepted
                }
            }
        } finally {
            if (retained.isNotEmpty()) routeCommands.trySend(RouteCommand.RecoveryPrefix(retained))
        }
        if (retained.isNotEmpty()) return@runCatching accepted
        retryFailedLocalRouteAcknowledgements()
        if (!routingDeferred.get()) routeCommands.trySend(RouteCommand.Retry)
        true
    }.onFailure {
        Log.w(LOG_TAG, "Failed to replay pending local routes", it)
    }.getOrDefault(false)

    // Recovery can run on the Journey worker that a live route is awaiting.
    // Attempt inline, then transfer refused work to the routing worker without
    // waiting for that worker or giving recovery a retry timer of its own.
    private data class RecoveryAttempt(val remaining: List<JourneyWork>, val accepted: Boolean)

    private suspend fun attemptRecoveryWork(work: JourneyWork): RecoveryAttempt {
        val result = try { work.attempt() }
        catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            JourneyWork.Result.Failed
        }
        catch (error: Exception) {
            Log.w(LOG_TAG, "Recovered Journey work failed", error)
            JourneyWork.Result.Failed
        }
        return when (result) {
            JourneyWork.Result.NotYet -> {
                work.waiting = true
                work.failed()
                RecoveryAttempt(listOf(work), false)
            }
            JourneyWork.Result.Failed -> {
                work.hasFailed = true
                work.waiting = true
                work.failed()
                RecoveryAttempt(listOf(work), false)
            }
            is JourneyWork.Result.Done -> {
                if (result.children.isEmpty()) { work.completed(); RecoveryAttempt(emptyList(), true) }
                else {
                    var remaining = result.children.size
                    var accepted = true
                    val retained = mutableListOf<JourneyWork>()
                    for (child in result.children) {
                        val completed = child.completed
                        val failed = child.failed
                        child.completed = { completed(); remaining--; if (remaining == 0) work.completed() }
                        child.failed = { failed(); work.failed() }
                        val attempted = attemptRecoveryWork(child)
                        retained.addAll(attempted.remaining)
                        accepted = accepted && attempted.accepted
                    }
                    RecoveryAttempt(retained, accepted)
                }
            }
        }
    }

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
        /**
         * How long a staged input may hold back its customer's watermark. The Android
         * renderer has no timed flush: JourneyRuntimeEmissionCoordinator hands each
         * committed renderer transaction to Journeys on the frame that produced it
         * (ExperienceSurfaceHost's Choreographer callback), so staging lasts only as long
         * as the Journey worker and the durable commit. The bound is one tick of the
         * queue's retry timer: a waiting deadline asked again by that timer finds every
         * input staged before its first ask either committed or late.
         */
        const val STAGED_INPUT_BOUND_MILLIS = 1_000L
        const val LOG_TAG = "Nuxie"
        const val DISTINCT_ID_PROPERTY = "\$distinct_id"
        const val SESSION_ID_PROPERTY = "\$session_id"
    }
}
