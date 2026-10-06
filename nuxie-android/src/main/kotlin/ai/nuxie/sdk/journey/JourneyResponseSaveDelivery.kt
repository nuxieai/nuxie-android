package ai.nuxie.sdk.journey

import android.util.Log
import java.io.File
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
    private val journals = linkedMapOf<String, JourneyRunJournal>()
    private var generation = 0L
    private var worker: Job? = null
    private var discovered = false
    private val receiptRetryAt = mutableMapOf<String, Long>()

    fun activate(scope: JourneyStorageScope) = synchronized(lock) {
        if (storageScope != null && storageScope != scope) return@synchronized
        storageScope = scope
        active = true
        kick()
    }

    suspend fun enqueue(journal: JourneyRunJournal, run: JourneyRun, formName: String, answers: JsonObject): JourneyResponseSave {
        requireOwner(journal)
        val sheet = withContext(Dispatchers.IO) { journal.reserveResponseSave(run, formName, answers, true) }
        synchronized(lock) {
            journals[journal.distinctId] = journal
            generation += 1
            kick()
        }
        return sheet
    }

    suspend fun sendWaiting(journal: JourneyRunJournal, run: JourneyRun, formName: String, answers: JsonObject): JourneyResponseSaveReply {
        requireOwner(journal)
        val sheet = withContext(Dispatchers.IO) { journal.reserveResponseSave(run, formName, answers, false) }
        currentCoroutineContext().ensureActive()
        if (!synchronized(lock) { active }) throw CancellationException("Response save delivery stopped")
        val reply = try { transport.sendResponseSave(sheet) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); return JourneyResponseSaveReply.noAnswer }
        currentCoroutineContext().ensureActive()
        if (reply.confirmed) withContext(Dispatchers.IO) { journal.confirmResponseSave(sheet, checkNotNull(reply.sequence)) }
        return reply
    }

    private fun requireOwner(journal: JourneyRunJournal) = synchronized(lock) {
        check(active && journal.responseSaveNamespace == storageScope?.conversionNamespace) { "Wrong response save owner" }
    }

    suspend fun shutdown() {
        val task = synchronized(lock) { active = false; worker.also { worker = null } }
        task?.cancelAndJoin()
    }

    private fun kick() {
        if (!active || worker != null) return
        val job = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { deliver() }
        worker = job
        job.start()
    }

    private suspend fun deliver() {
        val ownJob = currentCoroutineContext()[Job]
        try {
            while (synchronized(lock) { active }) {
                currentCoroutineContext().ensureActive()
                try {
                    val scope = synchronized(lock) { checkNotNull(storageScope) }
                    if (!discovered) {
                        val recovery = JourneyRunJournal.recoverResponseSaveOwners(directory, scope)
                        synchronized(lock) {
                            recovery.owners.forEach { owner -> journals.getOrPut(owner) { JourneyRunJournal(directory, owner, scope) } }
                        }
                        discovered = !recovery.needsRetry
                    }
                    val (observedGeneration, candidates) = synchronized(lock) { generation to journals.values.toList() }
                    var nextDelay: Long? = if (discovered) null else 5_000
                    var handedOff = false
                    deliveryPass@ for (journal in candidates) {
                        val retryAt = receiptRetryAt[journal.distinctId]
                        if (retryAt != null) {
                            val remaining = minOf(5_000, retryAt - nowMillis())
                            if (remaining > 0) {
                                nextDelay = minOf(nextDelay ?: remaining, remaining)
                                continue
                            }
                            receiptRetryAt.remove(journal.distinctId)
                        }
                        val attempts = try { journal.responseSaveAttempts(nowMillis()) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            Log.w(TAG, "Response save journal could not be read")
                            nextDelay = minOf(nextDelay ?: 5_000, 5_000)
                            continue
                        }
                        for (attempt in attempts) {
                            currentCoroutineContext().ensureActive()
                            val now = nowMillis()
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
                            val stopped = try { journal.recordResponseSaveReply(attempt.sheet, reply, nowMillis()) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) {
                                Log.w(TAG, "Response save receipt could not be persisted")
                                receiptRetryAt[journal.distinctId] = nowMillis() + 5_000
                                handedOff = true
                                break@deliveryPass
                            }
                            if (stopped) Log.w(TAG, "Response save stopped: code=${reply.code.wire}, journey=${attempt.sheet.journeyId}, form=${attempt.sheet.formName}, owner=${attempt.sheet.distinctId}")
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
                    sleep(minOf(5_000, maxOf(100, checkNotNull(nextDelay))))
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { Log.w(TAG, "Response save delivery could not finish durable work"); sleep(5_000) }
            }
        } finally {
            synchronized(lock) { if (worker === ownJob) worker = null }
        }
    }

    private companion object { const val TAG = "NuxieResponseSave" }
}
