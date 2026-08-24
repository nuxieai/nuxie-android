package ai.nuxie.sdk.identity

import ai.nuxie.sdk.events.EventStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Serializes user identity transitions (identify/reset) across every service
 * holding per-user state, ported from the iOS `UserTransitionCoordinator`:
 * every transition runs to COMPLETION in FIFO order — a superseding
 * transition queues, never cancels. Enqueue is synchronous so FIFO order is
 * the CALLER's order.
 *
 * Local event migration runs first so downstream evaluation sees the new
 * user's full history. Future per-user services (profile, segments,
 * journeys, features) register as [Observer]s in their own PRs.
 */
internal class UserTransitionCoordinator(
    private val eventStore: EventStore,
    private val scope: CoroutineScope,
) {
    enum class Kind { IDENTIFY, RESET }

    data class Transition(
        val kind: Kind,
        val from: String,
        val to: String,
        /** Migrate the old user's local events (anonymous -> identified only). */
        val migrateEvents: Boolean,
    )

    internal fun interface Observer {
        suspend fun handleUserChange(kind: Kind, from: String, to: String)
    }

    private val lock = Any()
    private var tail: Job? = null
    private val observers = java.util.concurrent.CopyOnWriteArrayList<Observer>()

    fun addObserver(observer: Observer) {
        observers.add(observer)
    }

    /** Synchronous, fire-and-forget; execution order is the enqueue order. */
    fun enqueue(transition: Transition) {
        synchronized(lock) {
            val previous = tail
            tail = scope.launch {
                previous?.join()
                run(transition)
            }
        }
    }

    /** Await all currently queued transitions (test determinism). */
    suspend fun drain() {
        val current = synchronized(lock) { tail }
        current?.join()
    }

    private suspend fun run(transition: Transition) {
        if (transition.migrateEvents) {
            runCatching {
                val migrated = eventStore.reassignEvents(transition.from, transition.to)
                if (migrated > 0) {
                    Log.i(LOG_TAG, "Migrated $migrated anonymous events to the identified user")
                }
            }.onFailure { Log.w(LOG_TAG, "Failed to reassign anonymous events", it) }
        }
        observers.forEach { observer ->
            runCatching { observer.handleUserChange(transition.kind, transition.from, transition.to) }
                .onFailure { Log.w(LOG_TAG, "User-transition observer failed", it) }
        }
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
    }
}
