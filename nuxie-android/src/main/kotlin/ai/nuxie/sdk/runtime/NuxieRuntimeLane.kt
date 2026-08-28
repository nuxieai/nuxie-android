package ai.nuxie.sdk.runtime

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    private val terminationCallback = AtomicReference<(() -> Unit)?>(null)
    private val executor = object : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } },
    ) {
        override fun terminated() {
            terminationCallback.getAndSet(null)?.let(::runTerminationCallback)
            super.terminated()
        }
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

    /**
     * Fire-and-forget on the lane (frame ticks). Returns false when the
     * lane is already shut down and the task will never run, so callers
     * waiting on a completion signal from the task can skip the wait.
     */
    fun enqueue(block: () -> Unit): Boolean {
        return try {
            executor.execute {
                runCatching(block).onFailure { Log.w(LOG_TAG, "Runtime lane task failed", it) }
            }
            true
        } catch (_: RejectedExecutionException) {
            Log.w(LOG_TAG, "Runtime lane task rejected after shutdown")
            false
        }
    }

    /** Run remaining work, then stop the lane. */
    fun shutdown() {
        executor.shutdown()
    }

    /**
     * Stop accepting work without blocking the caller, then invoke
     * [onTerminated] after every already-accepted task has finished and the
     * lane has terminated.
     */
    fun shutdown(onTerminated: () -> Unit) {
        terminationCallback.set(onTerminated)
        executor.shutdown()
        if (executor.isTerminated) {
            terminationCallback.getAndSet(null)?.let(::runTerminationCallback)
        }
    }

    /**
     * After [shutdown], wait for already-accepted work to drain. Returns
     * true once the lane has fully terminated; false on timeout or if the
     * lane was never shut down.
     */
    fun awaitQuiescence(timeoutMs: Long): Boolean {
        return try {
            executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun runTerminationCallback(callback: () -> Unit) {
        runCatching(callback).onFailure {
            Log.w(LOG_TAG, "Runtime lane termination callback failed", it)
        }
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val THREAD_NAME = "com.nuxie.runtime.android.native"
    }
}
