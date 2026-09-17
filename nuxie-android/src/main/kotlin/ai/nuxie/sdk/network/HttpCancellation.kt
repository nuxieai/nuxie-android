package ai.nuxie.sdk.network

import java.io.Closeable
import java.util.concurrent.CancellationException

/** One request owner's cancellation, including resources acquired after cancellation. */
internal class HttpCancellation {
    private val lock = Any()
    private var cancelled = false
    private val callbacks = linkedMapOf<Any, () -> Unit>()

    fun checkActive() {
        synchronized(lock) { if (cancelled) throw CancellationException("HTTP acquisition cancelled") }
    }

    fun register(abort: () -> Unit): Closeable {
        val identity = Any()
        val abortNow = synchronized(lock) {
            if (cancelled) true else { callbacks[identity] = abort; false }
        }
        if (abortNow) abort()
        return Closeable { synchronized(lock) { callbacks.remove(identity) }; Unit }
    }

    fun cancel() {
        val pending = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            callbacks.values.toList().also { callbacks.clear() }
        }
        // One failed abort must not strand other owned resources.
        pending.forEach { runCatching(it) }
    }
}
