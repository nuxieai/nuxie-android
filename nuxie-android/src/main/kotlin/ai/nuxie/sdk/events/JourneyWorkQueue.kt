package ai.nuxie.sdk.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A process-local queue entry. Durable routes and parks recreate entries after recovery. */
internal class JourneyWork(
    val key: String,
    val distinctId: String,
    var runId: String? = null,
    val repairsCustomer: Boolean = false,
    initiallyDeferred: Boolean = false,
    val kind: Kind = Kind.OPERATION,
    val attempt: suspend () -> Result,
) {
    enum class Kind { EVENT, OPERATION }

    sealed interface Result {
        data class Done(val following: List<JourneyWork> = emptyList()) : Result
        data class Expand(val children: List<JourneyWork>) : Result
        data object Retry : Result
        data object Blocked : Result
    }

    internal var hasFailed = initiallyDeferred
    internal var waiting = initiallyDeferred
    internal var retryAtMillis: Long? = null
    internal var completed: suspend () -> Unit = {}
    internal var failed: suspend () -> Unit = {}
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
    private var closed = false
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
            while (index < entries.size && !held()) {
                val work = entries[index]
                val obstructed = blocked.any { earlier ->
                    earlier.distinctId == work.distinctId &&
                        ((earlier.runId == null && !work.repairsCustomer) ||
                            (work.runId != null && earlier.runId == work.runId))
                }
                if (work.waiting || obstructed) {
                    blocked += work
                    index++
                    continue
                }
                val result = try { work.attempt() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { JourneyWork.Result.Retry }
                when (result) {
                    JourneyWork.Result.Blocked -> {
                        // A background pause alone preserves ordinary background input
                        // selection. An actual failed attempt still holds that run.
                        if (work.hasFailed) blocked += work
                        index++
                    }
                    JourneyWork.Result.Retry -> {
                        work.hasFailed = true
                        work.waiting = true
                        work.retryAtMillis = retryClockMillis() + 1_000L
                        work.failed()
                        blocked += work
                        index++
                    }
                    is JourneyWork.Result.Done -> {
                        entries.removeAt(index)
                        work.completed()
                        insertAt(index, result.following)
                        progress++
                    }
                    is JourneyWork.Result.Expand -> {
                        entries.removeAt(index)
                        if (result.children.isEmpty()) work.completed() else {
                            var remaining = result.children.size
                            for (child in result.children) {
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
        if (closed || retryJob != null || entries.none { it.waiting }) return
        val next = entries.mapNotNull { it.retryAtMillis }.minOrNull() ?: return
        retryJob = scope.launch {
            sleep((next - retryClockMillis()).coerceIn(1L, 1_000L))
            retry()
        }
    }

    fun close() {
        closed = true
        retryJob?.cancel()
        retryJob = null
    }
}
