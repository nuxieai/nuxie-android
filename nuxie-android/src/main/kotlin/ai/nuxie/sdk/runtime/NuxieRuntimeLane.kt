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
    // One hold for native drain, plus one per observer until JNI releases it.
    // The runtime owns callback storage/order. These holds only keep its handle
    // available to concurrent registration and waits through the last callback.
    private var terminationHolds = if (handle == 0L) 0 else 1

    // JNI invokes run/close by name; consumer-rules.pro retains both methods.
    private class Job(
        private var operation: (() -> Unit)?,
        private var abandoned: (() -> Unit)?,
        private var released: (() -> Unit)? = null,
    ) : Runnable, Closeable {
        override fun run() {
            val body = operation
            operation = null
            abandoned = null
            body?.invoke()
        }

        override fun close() {
            try {
                operation = null
                val callback = abandoned
                abandoned = null
                callback?.invoke()
            } finally {
                val callback = released
                released = null
                callback?.invoke()
            }
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
            val registered = NuxieRuntimeBridge.nativeExecutorOnTerminated(
                native, Job({}, null, ::releaseTerminationHold),
            )
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
        val native = synchronized(lock) {
            handle.also {
                if (it != 0L) {
                    NuxieRuntimeBridge.nativeExecutorRetain(it)
                    terminationHolds++
                }
            }
        }
        if (native == 0L) {
            runTerminationCallback(callback)
            return
        }
        var registered = false
        try {
            val run = { runTerminationCallback(callback) }
            registered = NuxieRuntimeBridge.nativeExecutorOnTerminated(
                native, Job(run, run, ::releaseTerminationHold),
            )
            check(registered) { "Runtime termination observer registration failed" }
        } finally {
            // Rejection never transfers the Job to JNI, so no release callback
            // will discharge its hold. Never run resource cleanup before drain.
            if (!registered) releaseTerminationHold()
            NuxieRuntimeBridge.nativeExecutorRelease(native)
        }
    }

    private fun releaseTerminationHold() {
        val owner = synchronized(lock) {
            check(terminationHolds > 0)
            terminationHolds--
            if (terminationHolds == 0) handle.also { handle = 0L } else 0L
        }
        if (owner != 0L) NuxieRuntimeBridge.nativeExecutorRelease(owner)
    }

    override fun awaitQuiescence(timeoutMs: Long): Boolean {
        val native = retainedHandle()
        if (native == 0L) return true
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
