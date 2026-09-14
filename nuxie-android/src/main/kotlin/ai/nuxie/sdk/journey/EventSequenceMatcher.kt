package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.StoredEvent
import java.util.ArrayDeque
import kotlinx.serialization.json.JsonObject

/** Matches distinct chronological events while retaining viable intermediate choices. */
internal object EventSequenceMatcher {
    data class Step(val name: String, val predicate: ((JsonObject) -> Boolean)?)
    private data class Candidate(val first: Long, val last: Long)

    fun matches(events: List<StoredEvent>, steps: List<Step>, overallSeconds: Double?, perStepSeconds: Double?): Boolean {
        if (steps.isEmpty()) return true
        val prefixes = Array(steps.size - 1) { ArrayDeque<Candidate>() }
        fun elapsed(now: Long, then: Long) = (now.toDouble() - then.toDouble()) / 1000.0
        for (event in events) {
            // Reverse traversal prevents reuse of one row for adjacent steps.
            for (index in steps.indices.reversed()) {
                val step = steps[index]
                if (event.name != step.name || step.predicate?.invoke(event.properties) == false) continue
                val first = if (index == 0) event.timestampMillis else {
                    val previous = prefixes[index - 1]
                    if (perStepSeconds != null) {
                        while (previous.isNotEmpty() && elapsed(event.timestampMillis, previous.first.last) > perStepSeconds) {
                            previous.removeFirst()
                        }
                    }
                    val candidate = previous.peekFirst() ?: continue
                    if (overallSeconds != null && elapsed(event.timestampMillis, candidate.first) > overallSeconds) continue
                    candidate.first
                }
                if (index == steps.lastIndex) return true
                val candidates = prefixes[index]
                // A later end with an equal/later start dominates the older
                // candidate under both upper bounds. Keep all other choices.
                while (candidates.isNotEmpty() && candidates.last.first <= first) candidates.removeLast()
                candidates.addLast(Candidate(first, event.timestampMillis))
            }
        }
        return false
    }
}
