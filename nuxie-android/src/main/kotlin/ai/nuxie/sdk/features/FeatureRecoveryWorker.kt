package ai.nuxie.sdk.features

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Owns retry timing; the command journal owns eligibility and delivery. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FeatureRecoveryWorker(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val nextDueAt: suspend () -> Long?,
    private val recover: suspend () -> Unit,
) {
    private val lock = Any()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var closed = false

    fun start() = synchronized(lock) {
        if (closed || job != null) return@synchronized
        job = scope.launch(start = CoroutineStart.LAZY) { run() }.also { it.start() }
    }

    fun request() { signals.trySend(Unit) }

    suspend fun close() {
        val active = synchronized(lock) {
            closed = true
            signals.close()
            job
        }
        active?.cancelAndJoin()
    }

    private suspend fun run() {
        for (signal in signals) {
            var backoff = 1_000L
            var nextAttemptAt = after(backoff)
            while (true) {
                val dueAt = nextDueAt() ?: break
                val wait = (maxOf(nextAttemptAt, dueAt) - nowMillis()).coerceAtLeast(0)
                val elapsed = select<Boolean?> {
                    signals.onReceiveCatching { if (it.isClosed) null else false }
                    onTimeout(wait) { true }
                }
                // A newly queued command can advance a long server cooldown,
                // but repeated nudges must not postpone an existing attempt.
                if (elapsed == null) return
                if (!elapsed) continue
                recover()
                backoff = (backoff * 2).coerceAtMost(60_000)
                nextAttemptAt = after(backoff)
            }
        }
    }

    private fun after(delay: Long): Long = nowMillis().let { now ->
        if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
}
