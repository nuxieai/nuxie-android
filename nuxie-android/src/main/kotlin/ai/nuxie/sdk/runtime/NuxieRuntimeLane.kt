package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.logging.NuxieLog as Log
import java.io.Closeable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One native CommandQueue owner for a run's handles, shared by its screens. */
internal interface NuxieRuntimeLane {
    suspend fun <T> call(block: () -> T): T
    fun enqueue(block: () -> Unit): Boolean
    fun shutdown()
    fun shutdown(onTerminated: () -> Unit)
    fun afterTermination(callback: () -> Unit)
    fun awaitQuiescence(timeoutMs: Long): Boolean

    companion object {
        operator fun invoke(): NuxieRuntimeLane = NativeRuntimeLane()
    }
}

private class NativeRuntimeLane : NuxieRuntimeLane {
    private val lock = Any()
    private var handle = if (NuxieRuntimeBridge.isAvailable) {
        NuxieRuntimeBridge.nativeExecutorNew().also { check(it != 0L) { "Runtime worker startup failed" } }
    } else 0L
    private var closing = false
    private var terminated = handle == 0L
    private val terminationCallbacks = mutableListOf<() -> Unit>()

    // JNI invokes run/close by name; consumer-rules.pro retains both methods.
    private class Job(
        private var operation: (() -> Unit)?,
        private var abandoned: (() -> Unit)?,
    ) : Runnable, Closeable {
        override fun run() {
            val body = operation
            operation = null
            abandoned = null
            body?.invoke()
        }

        override fun close() {
            operation = null
            val callback = abandoned
            abandoned = null
            callback?.invoke()
        }
    }

    private fun retainedHandle(forSubmission: Boolean = false): Long = synchronized(lock) {
        if (forSubmission && closing) return@synchronized 0L
        handle.also { if (it != 0L) NuxieRuntimeBridge.nativeExecutorRetain(it) }
    }

    private fun submit(job: Job): Boolean {
        val native = retainedHandle(forSubmission = true)
        if (native == 0L) return false
        return try {
            NuxieRuntimeBridge.nativeExecutorSubmit(native, job)
        } finally {
            NuxieRuntimeBridge.nativeExecutorRelease(native)
        }
    }

    override suspend fun <T> call(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val rejected = { continuation.resumeWithException(IllegalStateException("Runtime lane is shut down")) }
        val job = Job({
            runCatching(block).fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { continuation.resumeWithException(it) },
            )
        }, rejected)
        if (!submit(job)) rejected()
    }

    override fun enqueue(block: () -> Unit): Boolean = submit(Job({
        runCatching(block).onFailure { Log.w(LOG_TAG, "Runtime lane task failed", it) }
    }, { Log.w(LOG_TAG, "Runtime lane task abandoned") }))

    /** Close admission, drain accepted work, then release the native owner. */
    override fun shutdown() {
        val native = synchronized(lock) {
            if (closing) return
            closing = true
            handle.also { if (it != 0L) NuxieRuntimeBridge.nativeExecutorRetain(it) }
        }
        if (native == 0L) return
        try {
            val registered = NuxieRuntimeBridge.nativeExecutorOnTerminated(native, Job({
                val callbacks = synchronized(lock) {
                    terminated = true
                    terminationCallbacks.toList().also { terminationCallbacks.clear() }
                }
                callbacks.forEach(::runTerminationCallback)
                val owner = synchronized(lock) { handle.also { handle = 0L } }
                if (owner != 0L) NuxieRuntimeBridge.nativeExecutorRelease(owner)
            }, null))
            if (!registered) {
                synchronized(lock) { closing = false }
                error("Runtime termination observer registration failed")
            }
            NuxieRuntimeBridge.nativeExecutorClose(native)
        } catch (error: Throwable) {
            synchronized(lock) { closing = false }
            throw error
        } finally {
            NuxieRuntimeBridge.nativeExecutorRelease(native)
        }
    }

    override fun shutdown(onTerminated: () -> Unit) {
        afterTermination(onTerminated)
        shutdown()
    }

    override fun afterTermination(callback: () -> Unit) {
        val runNow = synchronized(lock) {
            if (terminated) true else {
                terminationCallbacks += callback
                false
            }
        }
        if (runNow) runTerminationCallback(callback)
    }

    override fun awaitQuiescence(timeoutMs: Long): Boolean {
        val native = retainedHandle()
        if (native == 0L) return synchronized(lock) { terminated }
        return try {
            NuxieRuntimeBridge.nativeExecutorWait(native, timeoutMs.coerceAtLeast(0))
        } finally {
            NuxieRuntimeBridge.nativeExecutorRelease(native)
        }
    }

    private fun runTerminationCallback(callback: () -> Unit) {
        runCatching(callback).onFailure { Log.w(LOG_TAG, "Runtime lane termination callback failed", it) }
    }

    private companion object { const val LOG_TAG = "Nuxie" }
}
