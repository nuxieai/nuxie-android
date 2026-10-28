package ai.nuxie.sdk.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

/**
 * The authored waits of one Experience: exit and move watchdogs, and a sheet the person is holding.
 * Each has its own limit, so a retirement wait of the same Experience counts only the time when
 * none of them runs (section 4).
 */
internal class ExperienceAuthoredTime {
    private val lock = Any()
    private var running = 0
    private var started = 0L

    /** Opens a span that closes in a later callback, such as a finger on a sheet. Each [begin] needs exactly one [end]. */
    fun begin() = synchronized(lock) { running += 1; started += 1 }

    fun end() = synchronized(lock) { running -= 1 }

    private fun mark() = synchronized(lock) { running to started }

    /** Waits until [millis] have passed outside every authored wait. A slice any authored wait touched doesn't count. */
    suspend fun delayNative(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            val slice = minOf(remaining, SLICE_MS)
            val before = mark()
            delay(slice)
            val after = mark()
            if (before.first == 0 && after.first == 0 && before.second == after.second) remaining -= slice
        }
    }

    private companion object { const val SLICE_MS = 50L }
}

/** Runs [body] as one authored span of this Experience, when there is one. */
internal suspend inline fun <T> ExperienceAuthoredTime?.during(body: () -> T): T {
    this?.begin()
    try { return body() } finally { this?.end() }
}

/**
 * Stop waiting on a wedged native owner; its actual retirement still owns handle/lease release.
 * [authoredTime] pauses the limit during the Experience's authored waits; null bounds native work alone.
 */
internal suspend fun <T> awaitPresentationRetirement(authoredTime: ExperienceAuthoredTime? = null, block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    // A withContext(Main) child cannot finish cancellation until Main dispatches it.
    // Keep that work independent so a wedged dispatcher cannot extend the caller's wait.
    val retirementScope = CoroutineScope(currentCoroutineContext().minusKey(Job))
    val retirement = retirementScope.async(start = CoroutineStart.UNDISPATCHED) { block() }
    fun timedOut(cause: Throwable?) = ExperiencePresentationException(
        ExperiencePresentationException.Reason.HOST_FAILED,
        "Experience native retirement did not finish within 5000 ms",
        cause,
    )
    return try {
        if (authoredTime == null) withTimeout(5_000) { retirement.await() }
        else {
            val limit = retirementScope.async { authoredTime.delayNative(5_000) }
            select {
                retirement.onAwait { it }
                limit.onAwait { throw timedOut(null) }
            }
        }
    } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw timedOut(failure)
    } finally {
        // Stop the waiter; accepted native work still retires on its owning lane.
        retirementScope.cancel()
    }
}

/** Cleanup may fail on its own, but must never replace an in-flight failure or cancellation. */
internal suspend fun runPresentationCleanup(failure: Throwable?, cleanup: suspend () -> Unit) {
    try {
        cleanup()
    } catch (cleanupFailure: Throwable) {
        if (failure == null) throw cleanupFailure
        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
    }
}
