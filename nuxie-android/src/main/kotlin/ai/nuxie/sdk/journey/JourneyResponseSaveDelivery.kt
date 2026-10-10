package ai.nuxie.sdk.journey

import ai.nuxie.sdk.logging.NuxieLog as Log
import ai.nuxie.sdk.events.StableEventCommitAdmission
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal class JourneyResponseSaveDelivery(
    private val directory: File,
    private val transport: JourneyResponseSaveTransport,
    private val coroutineScope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private var storageScope: JourneyStorageScope? = null
    private var active = false
    private var displayObserver: (suspend (JourneyRunJournal, String) -> Unit)? = null
    private val journals = linkedMapOf<String, JourneyRunJournal>()
    private var generation = 0L
    private var worker: Job? = null
    private data class RetryBackoff(var delay: Long = 5_000, var retryAt: Long? = null) {
        fun failed(now: Long) { retryAt = now + delay; delay = minOf(300_000, delay * 2) }
    }
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var discoveryBackoff = RetryBackoff()
    private val readBackoffs = mutableMapOf<String, RetryBackoff>()
    private var lastClockReading: Long? = null
    private var discovered = false
    private val receiptBackoffs = mutableMapOf<String, RetryBackoff>()

    fun setDisplayObserver(observer: suspend (JourneyRunJournal, String) -> Unit) = synchronized(lock) {
        displayObserver = observer
    }

    private suspend fun notifyDisplay(journal: JourneyRunJournal, journeyId: String) {
        val observer = synchronized(lock) { displayObserver }
        try { observer?.invoke(journal, journeyId) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { Log.w(TAG, "Response save display could not be refreshed") }
    }

    fun activate(scope: JourneyStorageScope) = synchronized(lock) {
        if (storageScope != null && storageScope != scope) return@synchronized
        storageScope = scope
        active = true
        wake()
    }

    suspend fun enqueue(journal: JourneyRunJournal, run: JourneyRun, formName: String, answers: JsonObject,
        admission: StableEventCommitAdmission? = null): JourneyResponseSave {
        requireOwner(journal)
        return withContext(Dispatchers.IO) {
            val sheet = journal.reserveResponseSave(run, formName, answers, true, admission)
            synchronized(lock) {
                journals[journal.distinctId] = journal
                wake()
            }
            sheet
        }
    }

    suspend fun reserveWaiting(journal: JourneyRunJournal, run: JourneyRun, formName: String, answers: JsonObject,
        admission: StableEventCommitAdmission? = null): JourneyResponseSave {
        requireOwner(journal)
        return withContext(Dispatchers.IO) { journal.reserveResponseSave(run, formName, answers, false, admission) }
    }

    suspend fun sendWaiting(journal: JourneyRunJournal, run: JourneyRun, formName: String, answers: JsonObject): JourneyResponseSaveReply =
        sendWaiting(reserveWaiting(journal, run, formName, answers), journal)

    suspend fun sendWaiting(sheet: JourneyResponseSave, journal: JourneyRunJournal): JourneyResponseSaveReply {
        requireOwner(journal)
        check(sheet.distinctId == journal.distinctId) { "Wrong response save owner" }
        currentCoroutineContext().ensureActive()
        if (!synchronized(lock) { active }) throw CancellationException("Response save delivery stopped")
        val reply = try { transport.sendResponseSave(sheet) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); JourneyResponseSaveReply.noAnswer }
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) { journal.recordWaitingResponseSaveReply(sheet, reply) }
        notifyDisplay(journal, sheet.journeyId)
        return reply
    }

    private fun requireOwner(journal: JourneyRunJournal) = synchronized(lock) {
        check(active && journal.responseSaveNamespace == storageScope?.conversionNamespace) { "Wrong response save owner" }
    }

    suspend fun shutdown() {
        val task = synchronized(lock) { active = false; displayObserver = null; worker.also { worker = null } }
        task?.cancelAndJoin()
    }

    private fun wake() {
        generation += 1
        wakeups.trySend(Unit)
        kick()
    }

    private suspend fun pause(duration: Long, observedGeneration: Long) = coroutineScope {
        if (synchronized(lock) { generation != observedGeneration }) return@coroutineScope
        val timer = async { sleep(maxOf(100, duration)) }
        try { select { timer.onAwait { }; wakeups.onReceive { } } }
        finally { timer.cancelAndJoin() }
    }

    // Deadlines in this worker are process-local. Preserve their remaining delay on a clock rollback.
    private fun currentTime(): Long {
        val now = nowMillis()
        lastClockReading?.takeIf { now < it }?.let { previous ->
            val shift = now - previous
            discoveryBackoff.retryAt = discoveryBackoff.retryAt?.plus(shift)
            readBackoffs.values.forEach { it.retryAt = it.retryAt?.plus(shift) }
            receiptBackoffs.values.forEach { it.retryAt = it.retryAt?.plus(shift) }
        }
        lastClockReading = now
        return now
    }

    private fun kick() {
        if (!active || worker != null) return
        val job = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { deliver() }
        worker = job
        job.start()
    }

    private suspend fun deliver() {
        val ownJob = currentCoroutineContext()[Job]
        var seenGeneration = -1L
        try {
            while (synchronized(lock) { active }) {
                currentCoroutineContext().ensureActive()
                try {
                    val (scope, observedGeneration) = synchronized(lock) {
                        wakeups.tryReceive()
                        checkNotNull(storageScope) to generation
                    }
                    if (seenGeneration != observedGeneration) {
                        discoveryBackoff = RetryBackoff()
                        readBackoffs.clear()
                        receiptBackoffs.clear()
                        seenGeneration = observedGeneration
                    }
                    val now = currentTime()
                    if (!discovered && (discoveryBackoff.retryAt ?: Long.MIN_VALUE) <= now) {
                        try {
                            val recovery = JourneyRunJournal.recoverResponseSaveOwners(directory, scope)
                            synchronized(lock) {
                                recovery.owners.forEach { owner -> journals.getOrPut(owner) { JourneyRunJournal(directory, owner, scope) } }
                            }
                            discovered = !recovery.needsRetry
                            if (recovery.needsRetry) discoveryBackoff.failed(currentTime())
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            Log.w(TAG, "Response save journals could not be discovered")
                            discoveryBackoff.failed(currentTime())
                        }
                    }
                    val currentCandidates = synchronized(lock) { journals.values.toList() }
                    var nextDelay: Long? = if (discovered) null else discoveryBackoff.retryAt?.minus(currentTime())
                    var handedOff = false
                    deliveryPass@ for (journal in currentCandidates) {
                        val now = currentTime()
                        val readRetryAt = readBackoffs[journal.distinctId]?.retryAt
                        if (readRetryAt != null && readRetryAt > now) {
                            nextDelay = minOf(nextDelay ?: Long.MAX_VALUE, readRetryAt - now)
                            continue
                        }
                        val retryAt = receiptBackoffs[journal.distinctId]?.retryAt
                        if (retryAt != null) {
                            val remaining = retryAt - currentTime()
                            if (remaining > 0) {
                                nextDelay = minOf(nextDelay ?: remaining, remaining)
                                continue
                            }
                        }
                        val attempts = try { journal.responseSaveAttempts(currentTime()) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            Log.w(TAG, "Response save journal could not be read")
                            val backoff = readBackoffs.getOrPut(journal.distinctId) { RetryBackoff() }
                            backoff.failed(currentTime())
                            nextDelay = minOf(nextDelay ?: Long.MAX_VALUE, checkNotNull(backoff.retryAt) - currentTime())
                            continue
                        }
                        readBackoffs.remove(journal.distinctId)
                        if (attempts.isEmpty()) synchronized(lock) {
                            if (generation == observedGeneration) journals.remove(journal.distinctId)
                        }
                        for (attempt in attempts) {
                            currentCoroutineContext().ensureActive()
                            val now = currentTime()
                            val reply = if (attempt.unknownFormExpired(now)) {
                                JourneyResponseSaveReply(JourneyResponseSaveReply.Code.UNKNOWN_FORM)
                            } else if (attempt.delay(now) > 0) {
                                nextDelay = minOf(nextDelay ?: Long.MAX_VALUE, attempt.delay(now))
                                continue
                            } else {
                                try { transport.sendResponseSave(attempt.sheet) }
                                catch (error: CancellationException) { throw error }
                                catch (_: Exception) { currentCoroutineContext().ensureActive(); JourneyResponseSaveReply.noAnswer }
                            }
                            currentCoroutineContext().ensureActive()
                            val stopped = try { journal.recordResponseSaveReply(attempt.sheet, reply, currentTime()) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) {
                                Log.w(TAG, "Response save receipt could not be persisted")
                                receiptBackoffs.getOrPut(journal.distinctId) { RetryBackoff() }.failed(currentTime())
                                handedOff = true
                                break@deliveryPass
                            }
                            receiptBackoffs.remove(journal.distinctId)
                            if (reply.confirmed || stopped) notifyDisplay(journal, attempt.sheet.journeyId)
                            if (stopped) Log.w(TAG, "Response save stopped", null,
                                Log.sensitive("code", reply.code.wire), Log.sensitive("journey", attempt.sheet.journeyId),
                                Log.sensitive("form", attempt.sheet.formName), Log.sensitive("owner", attempt.sheet.distinctId))
                            handedOff = true
                            break@deliveryPass
                        }
                    }
                    if (handedOff) continue
                    val finished = synchronized(lock) {
                        if (observedGeneration != generation) false
                        else if (nextDelay == null) { if (worker === ownJob) worker = null; true }
                        else false
                    }
                    if (finished) return
                    if (synchronized(lock) { observedGeneration != generation }) continue
                    pause(checkNotNull(nextDelay), observedGeneration)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    Log.w(TAG, "Response save delivery could not finish durable work")
                    discoveryBackoff.failed(currentTime())
                    pause(checkNotNull(discoveryBackoff.retryAt) - currentTime(), synchronized(lock) { generation })
                }
            }
        } finally {
            synchronized(lock) { if (worker === ownJob) worker = null }
        }
    }

    private companion object { const val TAG = "NuxieResponseSave" }
}
