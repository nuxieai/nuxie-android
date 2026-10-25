package ai.nuxie.sdk.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Stop waiting on a wedged native owner; its actual retirement still owns handle/lease release. */
internal suspend fun <T> awaitPresentationRetirement(block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    // A withContext(Main) child cannot finish cancellation until Main dispatches it.
    // Keep that work independent so a wedged dispatcher cannot extend the caller's wait.
    val retirementScope = CoroutineScope(currentCoroutineContext().minusKey(Job))
    val retirement = retirementScope.async(start = CoroutineStart.UNDISPATCHED) { block() }
    return try {
        withTimeout(5_000) { retirement.await() }
    } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw ExperiencePresentationException(
            ExperiencePresentationException.Reason.HOST_FAILED,
            "Experience native retirement did not finish within 5000 ms",
            failure,
        )
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
