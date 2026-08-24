package ai.nuxie.sdk.runtime

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The pinned runtime thread, mirroring the iOS
 * `NuxieRuntimePinnedThreadExecutor`: every native file/player/view-model/
 * renderer handle is created, used, and released only on this lane. Ordered
 * output batches return to callers afterward; the native side never calls
 * back from arbitrary threads.
 */
internal class NuxieRuntimeLane {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }

    /** Run [block] on the runtime lane and await its result. */
    suspend fun <T> call(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        try {
            executor.execute {
                val result = runCatching(block)
                result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it) },
                )
            }
        } catch (rejected: RejectedExecutionException) {
            continuation.resumeWithException(
                IllegalStateException("Runtime lane is shut down", rejected),
            )
        }
    }

    /** Fire-and-forget on the lane (frame ticks). */
    fun enqueue(block: () -> Unit) {
        try {
            executor.execute {
                runCatching(block).onFailure { Log.w(LOG_TAG, "Runtime lane task failed", it) }
            }
        } catch (_: RejectedExecutionException) {
            Log.w(LOG_TAG, "Runtime lane task rejected after shutdown")
        }
    }

    /** Run remaining work, then stop the lane. */
    fun shutdown() {
        executor.shutdown()
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val THREAD_NAME = "com.nuxie.runtime.android.native"
    }
}
