package ai.nuxie.sdk.presentation

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class AcquisitionProgress(val generation: Long, val startedAtMillis: Long, val phase: Phase) {
    enum class Phase { LOADING, FAILED, RETRYING }
}

/** One logical presentation, with sequential cancellable acquisition attempts. */
internal class ExperienceAcquisitionRecovery<T : Any>(
    private val publish: (AcquisitionProgress) -> Unit,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Attempt(val progress: AcquisitionProgress, val job: CompletableJob, val owner: Job?) {
        val retry = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private var current: Attempt? = null
    private var closed = false
    private var generation = 0L
    private var started = false

    /** Stale and repeated taps cannot cancel or enqueue work for another generation. */
    fun retry(expectedGeneration: Long): Boolean = synchronized(lock) {
        val attempt = current ?: return@synchronized false
        if (closed || attempt.owner?.isActive == false || attempt.progress.generation != expectedGeneration || attempt.retry.isCompleted) return@synchronized false
        attempt.retry.complete(Unit)
        publish(attempt.progress.copy(phase = AcquisitionProgress.Phase.RETRYING))
        attempt.job.cancel()
        true
    }

    suspend fun acquire(
        prepare: suspend () -> T,
        release: suspend (T) -> Unit,
        recoverable: (Throwable) -> Boolean,
        onFailure: (Throwable) -> Unit,
    ): T {
        synchronized(lock) { check(!started && !closed); started = true }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val parent = currentCoroutineContext()[Job]
                val attempt = synchronized(lock) {
                    check(!closed)
                    Attempt(AcquisitionProgress(++generation, nowMillis(), AcquisitionProgress.Phase.LOADING),
                        Job(parent), parent).also {
                        current = it
                        publish(it.progress)
                    }
                }
                var acquired: T? = null
                var transferred = false
                var failure: Throwable? = null
                try {
                    val result = withContext(attempt.job) {
                        // A cancellation-resistant provider can return a lease after Retry.
                        // Capture it before withContext discards the cancelled result.
                        prepare().also { acquired = it }
                    }
                    synchronized(lock) {
                        if (!attempt.retry.isCompleted) {
                            closed = true
                            transferred = true
                            return result
                        }
                    }
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    try { if (!transferred) acquired?.let { release(it) } }
                    finally { attempt.job.complete() }
                }
                currentCoroutineContext().ensureActive()
                failure?.let { error ->
                    if (!attempt.retry.isCompleted) {
                        if (!recoverable(error)) throw error
                        onFailure(error)
                        synchronized(lock) {
                            if (!attempt.retry.isCompleted) publish(attempt.progress.copy(phase = AcquisitionProgress.Phase.FAILED))
                        }
                        attempt.retry.await()
                    }
                }
                // No next attempt starts until the previous body and late lease drained.
            }
        } finally {
            synchronized(lock) {
                closed = true
                current?.job?.cancel()
                current = null
            }
        }
    }
}
