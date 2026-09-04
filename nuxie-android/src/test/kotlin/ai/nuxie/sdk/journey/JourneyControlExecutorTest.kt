package ai.nuxie.sdk.journey

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyControlExecutorTest {
    @Test fun sharedFlatControlTransitions() {
        val fixture = Json.parseToJsonElement(File("../fixtures/journeys/planes/executor-controls.json").readText()).jsonObject
        val context = fixture.getValue("context").jsonObject
        val assignments = fixture.getValue("assignments").jsonObject
        val executor = JourneyControlExecutor(SignedTimezoneBundle.load(), "Etc/UTC", "Etc/UTC")
        for (item in fixture.getValue("cases").jsonArray) {
            val vector = item.jsonObject
            val checkpoint = vector["checkpoint"]?.jsonObject?.let {
                JourneyControlExecutor.Checkpoint(it.number("anchorAtMillis"), it.number("wakeAtMillis"))
            }
            val signal = vector["signal"]?.jsonObject?.let { value ->
                JourneyControlExecutor.Signal(
                    event = value["event"]?.jsonObject?.let {
                        JourneyControlExecutor.Event(it.text("name"), it.number("occurredAtMillis"), it.getValue("properties").jsonObject)
                    },
                    responsesChanged = value["responsesChanged"]?.jsonPrimitive?.booleanOrNull == true,
                )
            } ?: JourneyControlExecutor.Signal()
            val actual = executor.evaluate(vector.getValue("step").jsonObject, context, assignments,
                vector.number("nowMillis"), checkpoint, signal)
            val expected = vector.getValue("expected").jsonObject
            val id = vector.text("id")
            when (expected.text("kind")) {
                "advance" -> {
                    actual as JourneyControlExecutor.Result.Advance
                    assertEquals(id, expected.text("stepId"), actual.stepId)
                    expected["event"]?.let { assertEquals(id, it, actual.context["event"]) }
                    when (id) {
                        "experiment-uses-durable-assignment" -> {
                            val selection = requireNotNull(actual.experimentSelection)
                            assertEquals("experiment_assigned", selection.experimentId)
                            assertEquals("variant_b", selection.variantId)
                            assertEquals(
                                JourneyControlExecutor.ExperimentSelection.Source.PROFILE,
                                selection.source,
                            )
                        }
                        "unassigned-experiment-uses-authored-fallback" -> {
                            val selection = requireNotNull(actual.experimentSelection)
                            assertEquals("experiment_new", selection.experimentId)
                            assertEquals("default", selection.variantId)
                            assertEquals(
                                JourneyControlExecutor.ExperimentSelection.Source.FALLBACK,
                                selection.source,
                            )
                        }
                    }
                }
                "park" -> {
                    actual as JourneyControlExecutor.Result.Park
                    assertEquals(id, expected.text("stepId"), actual.stepId)
                    assertEquals(id, expected.number("anchorAtMillis"), actual.checkpoint.anchorAtMillis)
                    assertEquals(id, expected.number("wakeAtMillis"), actual.checkpoint.wakeAtMillis)
                }
                "complete" -> {
                    actual as JourneyControlExecutor.Result.Complete
                    assertEquals(id, expected.text("outcome"), actual.outcome)
                }
                "dispatch" -> {
                    actual as JourneyControlExecutor.Result.Dispatch
                    assertEquals(id, expected.text("stepId"), actual.stepId)
                    assertEquals(id, expected.text("actionType"), actual.action.getValue("type").jsonPrimitive.content)
                }
                else -> error("unknown expected result")
            }
        }
    }

    @Test fun `missing and invalid experiment assignments use the authored fallback`() {
        val fixture = Json.parseToJsonElement(
            File("../fixtures/journeys/planes/executor-controls.json").readText(),
        ).jsonObject
        val vector = fixture.getValue("cases").jsonArray
            .map { it.jsonObject }
            .single { it.text("id") == "unassigned-experiment-uses-authored-fallback" }
        val executor = JourneyControlExecutor(
            SignedTimezoneBundle.load(),
            "Etc/UTC",
            "Etc/UTC",
        )
        val assignments = listOf(
            """{"experiment_new":null}""",
            """{"experiment_new":{"variantId":"removed","isHoldout":true}}""",
        )

        for (rawAssignments in assignments) {
            val result = executor.evaluate(
                vector.getValue("step").jsonObject,
                fixture.getValue("context").jsonObject,
                Json.parseToJsonElement(rawAssignments).jsonObject,
                vector.number("nowMillis"),
            ) as JourneyControlExecutor.Result.Advance

            assertEquals("a", result.stepId)
            val selection = requireNotNull(result.experimentSelection)
            assertEquals("default", selection.variantId)
            assertEquals(true, selection.isHoldout)
            assertEquals(
                JourneyControlExecutor.ExperimentSelection.Source.FALLBACK,
                selection.source,
            )
        }
    }

    @Test fun `authenticated variant holdout metadata overrides the profile hint`() {
        val fixture = Json.parseToJsonElement(
            File("../fixtures/journeys/planes/executor-controls.json").readText(),
        ).jsonObject
        val vector = fixture.getValue("cases").jsonArray
            .map { it.jsonObject }
            .single { it.text("id") == "experiment-uses-durable-assignment" }
        val assignments = Json.parseToJsonElement(
            """{"experiment_assigned":{"variantId":"variant_b","isHoldout":true}}""",
        ).jsonObject
        val executor = JourneyControlExecutor(
            SignedTimezoneBundle.load(),
            "Etc/UTC",
            "Etc/UTC",
        )

        val result = executor.evaluate(
            vector.getValue("step").jsonObject,
            fixture.getValue("context").jsonObject,
            assignments,
            vector.number("nowMillis"),
        ) as JourneyControlExecutor.Result.Advance

        assertEquals("b", result.stepId)
        val selection = requireNotNull(result.experimentSelection)
        assertEquals("variant_b", selection.variantId)
        assertEquals(false, selection.isHoldout)
        assertEquals(
            JourneyControlExecutor.ExperimentSelection.Source.PROFILE,
            selection.source,
        )
    }

    @Test fun missingOutletsAndOverflowingDeadlinesFailClosed() {
        val executor = JourneyControlExecutor(SignedTimezoneBundle.load(), "Etc/UTC", "Etc/UTC")
        val context = JsonObject(mapOf("event" to JsonObject(emptyMap()), "responses" to JsonObject(emptyMap())))
        fun step(action: String, body: String) = Json.parseToJsonElement(
            """{"kind":"action","id":"step","action":{"type":"$action",$body},"outlets":{}}"""
        ).jsonObject
        assertEquals(JourneyControlExecutor.Result.Invalid,
            executor.evaluate(step("delay", "\"durationMs\":1"), context, JsonObject(emptyMap()), Long.MAX_VALUE))
        val condition = step("condition", "\"branches\":[]")
        assertEquals(JourneyControlExecutor.Result.Invalid,
            executor.evaluate(condition, context, JsonObject(emptyMap()), 0))
        for (type in listOf("future_action", "connector_action")) {
            assertEquals(
                JourneyControlExecutor.Result.Invalid,
                executor.evaluate(step(type, "\"payload\":{}"), context, JsonObject(emptyMap()), 0),
            )
        }
        val purchase = Json.parseToJsonElement(
            """{"kind":"action","id":"purchase","action":{"type":"purchase","placementId":"offer"},"outlets":{"completed":"done"}}"""
        ).jsonObject
        assertEquals(JourneyControlExecutor.Result.Advance("done", context),
            executor.selectOutlet(purchase, "completed", context))
        assertEquals(JourneyControlExecutor.Result.Invalid, executor.selectOutlet(purchase, "failed", context))
    }
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
