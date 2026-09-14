package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.StoredEvent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EventSequenceMatcherTest {
    @Test
    fun matchesExhaustiveDistinctSubsequenceOracle() {
        val schedules = listOf(listOf(0L, 1000L, 2000L, 3000L, 4000L), listOf(0L, 0L, 1000L, 3000L, 4000L))
        val limits = listOf(null, 1.0, 3.0)
        for (times in schedules) for (eventMask in 0 until 32) {
            val events = times.mapIndexed { index, time ->
                StoredEvent(id = index.toString(), name = if (eventMask and (1 shl index) == 0) "a" else "b",
                    timestampMillis = time, distinctId = "person", properties = JsonObject(emptyMap()))
            }
            for (stepCount in 0..3) for (stepMask in 0 until (1 shl stepCount)) {
                val steps = (0 until stepCount).map { index ->
                    EventSequenceMatcher.Step(if (stepMask and (1 shl index) == 0) "a" else "b", null)
                }
                // Enumerate full distinct-row selections, independently of the
                // production matcher's streaming prefix state.
                val selections = (0 until 32).filter { Integer.bitCount(it) == stepCount }.map { mask ->
                    events.indices.filter { mask and (1 shl it) != 0 }
                }
                for (overall in limits) for (perStep in limits) {
                    val expected = selections.any { indices ->
                        indices.zip(steps).all { (index, step) -> events[index].name == step.name } &&
                            (indices.size < 2 || (
                                (overall == null || times[indices.last()] - times[indices.first()] <= overall * 1000) &&
                                    indices.zipWithNext().all { (a, b) -> perStep == null || times[b] - times[a] <= perStep * 1000 }
                                ))
                    }
                    assertEquals("times=$times events=$eventMask steps=$stepCount:$stepMask overall=$overall perStep=$perStep",
                        expected, EventSequenceMatcher.matches(events, steps, overall, perStep))
                }
            }
        }
    }
}
