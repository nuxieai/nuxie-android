package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.logging.NuxieLog as Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Test-only execution for JVM tests with fake native handles and no runtime binary. */
internal class TestRuntimeLane : NuxieRuntimeLane {
    private val terminationLock = Any()
    private var closing = false
    private var terminated = false
    private val terminationCallbacks = mutableListOf<() -> Unit>()
    private val completion = CountDownLatch(1)
    @Volatile private var workerThread: Thread? = null
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { runnable ->
            Thread(runnable, THREAD_NAME).apply {
                isDaemon = true
                workerThread = this
            }
        },
    )

    private fun submit(block: () -> Unit): Boolean = synchronized(terminationLock) {
        if (closing) false else {
            executor.execute(block)
            true
        }
    }

    /** Run [block] on the runtime lane and await its result. */
    override suspend fun <T> call(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val accepted = submit {
            runCatching(block).fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { continuation.resumeWithException(it) },
            )
        }
        if (!accepted) {
            continuation.resumeWithException(IllegalStateException("Runtime lane is shut down"))
        }
    }

    /**
     * Fire-and-forget on the lane (frame ticks). Returns false when the
     * lane is already shut down and the task will never run, so callers
     * waiting on a completion signal from the task can skip the wait.
     */
    override fun enqueue(block: () -> Unit): Boolean {
        val accepted = submit {
            runCatching(block).onFailure { Log.w(LOG_TAG, "Runtime lane task failed", it) }
        }
        if (!accepted) Log.w(LOG_TAG, "Runtime lane task rejected after shutdown")
        return accepted
    }

    /** Run remaining work, then stop the lane. */
    override fun shutdown() {
        synchronized(terminationLock) {
            if (closing) return
            closing = true
            // An ordinary final task keeps callbacks off the caller and outside the
            // ThreadPoolExecutor termination hook's lock, including an unused lane.
            executor.execute(::finishTermination)
            executor.shutdown()
        }
    }

    private fun finishTermination() {
        while (true) {
            val callbacks = synchronized(terminationLock) {
                if (terminationCallbacks.isEmpty()) {
                    terminated = true
                    completion.countDown()
                    return
                }
                terminationCallbacks.toList().also { terminationCallbacks.clear() }
            }
            callbacks.forEach(::runTerminationCallback)
        }
    }

    /**
     * Stop accepting work without blocking the caller, then invoke
     * [onTerminated] after every already-accepted task has finished and the
     * lane has terminated.
     */
    override fun shutdown(onTerminated: () -> Unit) {
        afterTermination(onTerminated)
        shutdown()
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
        if (Thread.currentThread() === workerThread) return false
        return try {
            completion.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
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
