package ai.nuxie.sdk.presentation

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Stop waiting on a wedged native owner; its actual retirement still owns handle/lease release. */
internal suspend fun <T> awaitPresentationRetirement(block: suspend () -> T): T = try {
    withTimeout(5_000) { block() }
} catch (failure: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw ExperiencePresentationException(
        ExperiencePresentationException.Reason.HOST_FAILED,
        "Experience native retirement did not finish within 5000 ms",
        failure,
    )
}
