package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.logging.NuxieLog as Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Test-only execution for JVM tests with fake native handles and no runtime binary. */
internal class TestRuntimeLane : NuxieRuntimeLane {
    private val terminationLock = Any()
    private var terminated = false
    private val terminationCallbacks = mutableListOf<() -> Unit>()
    private val executor = object : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { runnable -> Thread(runnable, THREAD_NAME).apply { isDaemon = true } },
    ) {
        override fun terminated() {
            val callbacks = synchronized(terminationLock) {
                terminated = true
                terminationCallbacks.toList().also { terminationCallbacks.clear() }
            }
            callbacks.forEach(::runTerminationCallback)
            super.terminated()
        }
    }

    /** Run [block] on the runtime lane and await its result. */
    override suspend fun <T> call(block: () -> T): T = suspendCancellableCoroutine { continuation ->
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
    override fun enqueue(block: () -> Unit): Boolean {
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
    override fun shutdown() {
        executor.shutdown()
    }

    /**
     * Stop accepting work without blocking the caller, then invoke
     * [onTerminated] after every already-accepted task has finished and the
     * lane has terminated.
     */
    override fun shutdown(onTerminated: () -> Unit) {
        afterTermination(onTerminated)
        executor.shutdown()
    }

    /** Register independent cleanup without replacing another owner's completion. */
    override fun afterTermination(callback: () -> Unit) {
        val runNow = synchronized(terminationLock) {
            if (terminated) true else {
                terminationCallbacks += callback
                false
            }
        }
        if (runNow) runTerminationCallback(callback)
    }

    /**
     * After [shutdown], wait for already-accepted work to drain. Returns
     * true once the lane has fully terminated; false on timeout or if the
     * lane was never shut down.
     */
    override fun awaitQuiescence(timeoutMs: Long): Boolean {
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

internal fun testRunValues(snapshot: ai.nuxie.sdk.presentation.ExperienceRunSnapshot?) =
    ai.nuxie.sdk.presentation.ExperienceRunValues(snapshot, TestRuntimeLane())
