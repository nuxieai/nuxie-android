package ai.nuxie.sdk.events

import ai.nuxie.sdk.TriggerUpdate

/**
 * Per-trigger update routing, ported from the iOS `TriggerBroker`: handlers
 * register under the trigger event's id; emissions after completion are
 * dropped. All calls are serialized by the owning service.
 */
internal class TriggerBroker {
    private val lock = Any()
    private val handlers = mutableMapOf<String, (TriggerUpdate) -> Unit>()

    fun register(eventId: String, handler: (TriggerUpdate) -> Unit) {
        synchronized(lock) { handlers[eventId] = handler }
    }

    fun emit(eventId: String, update: TriggerUpdate) {
        val handler = synchronized(lock) { handlers[eventId] } ?: return
        handler(update)
    }

    fun complete(eventId: String) {
        synchronized(lock) { handlers.remove(eventId) }
    }
}
