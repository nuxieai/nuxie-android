package ai.nuxie.sdk.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Completion events are observed before EXITING is written to the native lane. */
internal class ExperienceScreenExitHandshake {
    private val lock = Any()
    private val waiters = mutableMapOf<CompletableDeferred<Unit>, String>()
    private var closed = false

    suspend fun perform(declaration: JsonObject?, reduceMotion: Boolean, requestExit: () -> Unit) {
        val event = (declaration?.get("completeEventName") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        val duration = (declaration?.get("durationMs") as? JsonPrimitive)?.longOrNull
        if (reduceMotion || event == null || duration == null) {
            requestExit()
            return
        }
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            check(!closed) { "Screen is closed" }
            waiters[completion] = event
        }
        try {
            requestExit()
            withTimeoutOrNull(duration.coerceIn(0, Long.MAX_VALUE - 250) + 250) { completion.await() }
        } finally {
            synchronized(lock) { waiters.remove(completion) }
        }
    }

    fun receive(eventName: String) {
        val matches = synchronized(lock) { waiters.filterValues { it == eventName }.keys.toList() }
        matches.forEach { it.complete(Unit) }
    }

    fun close() {
        val pending = synchronized(lock) {
            closed = true
            waiters.keys.toList().also { waiters.clear() }
        }
        pending.forEach { it.completeExceptionally(CancellationException("Screen closed during exit")) }
    }
}
