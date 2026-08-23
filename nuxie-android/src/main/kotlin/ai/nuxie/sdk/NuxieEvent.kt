package ai.nuxie.sdk

import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import java.util.Collections

/**
 * An event snapshot exposed to event transformation hooks.
 *
 * The payload is write-once: create a new [NuxieEvent] when changing an event
 * instead of mutating this instance or its [properties].
 */
class NuxieEvent(
    val id: String = TimeBasedEpochGenerator.shared.next(),
    val name: String,
    val distinctId: String,
    properties: Map<String, Any?> = emptyMap(),
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    val properties: Map<String, Any?> = immutableMapSnapshot(properties)
}

private fun immutableMapSnapshot(source: Map<*, *>): Map<String, Any?> {
    val snapshot = linkedMapOf<String, Any?>()
    source.forEach { (key, value) ->
        require(key is String) { "Event property keys must be strings." }
        snapshot[key] = immutableValueSnapshot(value)
    }
    return Collections.unmodifiableMap(snapshot)
}

private fun immutableValueSnapshot(value: Any?): Any? = when (value) {
    is Map<*, *> -> immutableMapSnapshot(value)
    is Iterable<*> -> Collections.unmodifiableList(value.map(::immutableValueSnapshot))
    is Array<*> -> Collections.unmodifiableList(value.map(::immutableValueSnapshot))
    is BooleanArray -> Collections.unmodifiableList(value.toList())
    is ByteArray -> Collections.unmodifiableList(value.toList())
    is ShortArray -> Collections.unmodifiableList(value.toList())
    is IntArray -> Collections.unmodifiableList(value.toList())
    is LongArray -> Collections.unmodifiableList(value.toList())
    is FloatArray -> Collections.unmodifiableList(value.toList())
    is DoubleArray -> Collections.unmodifiableList(value.toList())
    is CharArray -> Collections.unmodifiableList(value.map(Char::toString))
    else -> value
}
