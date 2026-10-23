package ai.nuxie.sdk.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** A process-local queue entry. Durable routes and parks recreate entries after recovery. */
internal class JourneyWork(
    val key: String,
    val distinctId: String,
    var runId: String? = null,
    val repairsCustomer: Boolean = false,
    initiallyDeferred: Boolean = false,
    val kind: Kind = Kind.OPERATION,
    var input: StoredEvent? = null,
    val deadline: JourneyDeadline? = null,
    /**
     * For a deadline: the committed inputs at or before it to deliver first, or null
     * while its customer's input watermark has not reached it.
     */
    val pendingInputs: () -> List<JourneyWork>? = { emptyList() },
    val attempt: suspend () -> Result,
) {
    enum class Kind { EVENT, OPERATION }

    sealed interface Result {
        /** The effect is durable; child work must finish before the input is acknowledged. */
        data class Done(val children: List<JourneyWork> = emptyList()) : Result
        data object Failed : Result
        /** Eligibility is blocked. Retain this entry and its one-second backoff. */
        data object NotYet : Result
    }

    internal var hasFailed = initiallyDeferred
    internal var waiting = initiallyDeferred
    internal var retryAtMillis: Long? = null
    internal var completed: suspend () -> Unit = {}
    internal var failed: suspend () -> Unit = {}
    internal var inputsChecked = false
    internal var awaitingInputs = false
}

/**
 * Owned by the routing worker. Failed work keeps its key and position; only the
 * single retry tick makes it eligible again. A failed run holds that run's later
 * inputs, while event expansion and other runs/customers can still progress.
 */
internal class JourneyWorkQueue(
    private val scope: CoroutineScope,
    private val retry: () -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val retryClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val entries = mutableListOf<JourneyWork>()
    private var retryJob: Job? = null
    @Volatile private var closed = false
    @Volatile private var retrying = true
    private var draining = false
    var progress: Long = 0L
        private set

    fun add(work: JourneyWork, first: Boolean = false) {
        if (closed || entries.any { it.key == work.key }) return
        if (work.waiting && work.retryAtMillis == null) work.retryAtMillis = retryClockMillis() + 1_000L
        if (first) entries.add(0, work) else entries.add(work)
        armRetry()
    }

    fun retryReady() {
        retryJob = null
        val now = retryClockMillis()
        entries.filter { (it.retryAtMillis ?: Long.MAX_VALUE) <= now }.forEach {
            it.waiting = false
            it.retryAtMillis = null
        }
    }

    suspend fun drain(held: () -> Boolean = { false }) {
        if (draining) return
        draining = true
        try {
            var index = 0
            val blocked = mutableListOf<JourneyWork>()
            // Deadlines waiting for their customer's input watermark. Each holds back
            // only its own Journey's inputs that happened after it.
            val awaiting = mutableListOf<JourneyDeadline>()
            while (index < entries.size && !held()) {
                val work = entries[index]
                val obstructed = blocked.any { earlier ->
                    earlier.distinctId == work.distinctId &&
                        // An unexpanded event holds admission order. Independent
                        // work (such as reconciliation) holds only its own key.
                        ((earlier.kind == JourneyWork.Kind.EVENT && earlier.runId == null && !work.repairsCustomer) ||
                            (work.runId != null && earlier.runId == work.runId))
                } || awaiting.any { deadline ->
                    deadline.distinctId == work.distinctId && work.runId == deadline.runId &&
                        (work.input?.timestampMillis ?: Long.MIN_VALUE) > deadline.wakeAtMillis
                }
                if (work.waiting || obstructed) {
                    val waitingDeadline = work.deadline?.takeIf { work.awaitingInputs }
                    if (waitingDeadline != null) awaiting += waitingDeadline else if (work.hasFailed || obstructed) blocked += work
                    index++
                    continue
                }
                val deadline = work.deadline
                if (deadline != null && !work.inputsChecked) {
                    val committed = work.pendingInputs()
                    if (committed == null) {
                        // The watermark has not reached the deadline: ask again on the one retry timer.
                        work.awaitingInputs = true
                        work.waiting = true
                        work.retryAtMillis = retryClockMillis() + 1_000L
                        awaiting += deadline
                        index++
                        continue
                    }
                    // The answer is final: eligibility is frozen before the deadline's first attempt.
                    // Event plans and their children still use their ordinary apply path.
                    work.inputsChecked = true
                    work.awaitingInputs = false
                    val inputs = (entries.drop(index + 1) + committed).filter { candidate ->
                        candidate.input?.let { input ->
                            input.distinctId == deadline.distinctId && input.timestampMillis <= deadline.wakeAtMillis
                        } == true
                    }.distinctBy { it.key }.sortedBy { it.input!!.timestampMillis }
                    if (inputs.isNotEmpty()) {
                        val keys = inputs.mapTo(mutableSetOf()) { it.key }
                        entries.removeAll { it.key in keys }
                        entries.addAll(index, inputs)
                        continue
                    }
                }
                val result = try { work.attempt() }
                catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    JourneyWork.Result.Failed
                }
                catch (_: Exception) { JourneyWork.Result.Failed }
                when (result) {
                    JourneyWork.Result.NotYet, JourneyWork.Result.Failed -> {
                        work.hasFailed = work.hasFailed || result == JourneyWork.Result.Failed
                        work.waiting = true
                        work.retryAtMillis = retryClockMillis() + 1_000L
                        work.failed()
                        if (work.hasFailed) blocked += work
                        index++
                    }
                    is JourneyWork.Result.Done -> {
                        entries.removeAt(index)
                        if (result.children.isEmpty()) work.completed() else {
                            var remaining = result.children.size
                            for (child in result.children) {
                                if (child.input == null) child.input = work.input
                                val completed = child.completed
                                val failed = child.failed
                                child.completed = {
                                    completed()
                                    remaining--
                                    if (remaining == 0) work.completed()
                                }
                                child.failed = { failed(); work.failed() }
                            }
                            insertAt(index, result.children)
                        }
                        progress++
                    }
                }
            }
        } finally {
            draining = false
            armRetry()
        }
    }

    private fun insertAt(index: Int, work: List<JourneyWork>) {
        val fresh = work.filter { candidate -> entries.none { it.key == candidate.key } }
        fresh.filter { it.waiting && it.retryAtMillis == null }.forEach {
            it.retryAtMillis = retryClockMillis() + 1_000L
        }
        entries.addAll(index, fresh)
    }

    private fun armRetry() {
        if (closed || !retrying || retryJob != null || entries.none { it.waiting }) return
        val next = entries.mapNotNull { it.retryAtMillis }.minOrNull() ?: return
        retryJob = scope.launch {
            sleep((next - retryClockMillis()).coerceIn(1L, 1_000L))
            retry()
        }
    }

    /** Shutdown: no further retries, while accepted entries can still be added and drained. */
    fun stopRetries() {
        retrying = false
        retryJob?.cancel()
        retryJob = null
    }

    fun close() {
        closed = true
        stopRetries()
    }
}
