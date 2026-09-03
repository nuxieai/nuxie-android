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

class DeviceLegControlExecutorTest {
    @Test fun sharedFlatControlTransitions() {
        val fixture = Json.parseToJsonElement(File("../fixtures/journeys/planes/executor-controls.json").readText()).jsonObject
        val context = fixture.getValue("context").jsonObject
        val assignments = fixture.getValue("assignments").jsonObject
        val executor = DeviceLegControlExecutor(SignedTimezoneBundle.load(), "Etc/UTC", "Etc/UTC")
        for (item in fixture.getValue("cases").jsonArray) {
            val vector = item.jsonObject
            val checkpoint = vector["checkpoint"]?.jsonObject?.let {
                DeviceLegControlExecutor.Checkpoint(it.number("anchorAtMillis"), it.number("wakeAtMillis"))
            }
            val signal = vector["signal"]?.jsonObject?.let { value ->
                DeviceLegControlExecutor.Signal(
                    event = value["event"]?.jsonObject?.let {
                        DeviceLegControlExecutor.Event(it.text("name"), it.number("occurredAtMillis"), it.getValue("properties").jsonObject)
                    },
                    responsesChanged = value["responsesChanged"]?.jsonPrimitive?.booleanOrNull == true,
                )
            } ?: DeviceLegControlExecutor.Signal()
            val actual = executor.evaluate(vector.getValue("step").jsonObject, context, assignments,
                vector.number("nowMillis"), checkpoint, signal)
            val expected = vector.getValue("expected").jsonObject
            val id = vector.text("id")
            when (expected.text("kind")) {
                "advance" -> {
                    actual as DeviceLegControlExecutor.Result.Advance
                    assertEquals(id, expected.text("stepId"), actual.stepId)
                    expected["event"]?.let { assertEquals(id, it, actual.context["event"]) }
                    when (id) {
                        "experiment-uses-durable-assignment" -> {
                            val selection = requireNotNull(actual.experimentSelection)
                            assertEquals("experiment_assigned", selection.experimentId)
                            assertEquals("variant_b", selection.variantId)
                            assertEquals("variant_b", selection.assignedVariantId)
                            assertEquals(
                                DeviceLegControlExecutor.ExperimentSelection.Source.PROFILE,
                                selection.source,
                            )
                        }
                        "unassigned-experiment-uses-first-variant" -> {
                            val selection = requireNotNull(actual.experimentSelection)
                            assertEquals("experiment_new", selection.experimentId)
                            assertEquals("default", selection.variantId)
                            assertEquals(null, selection.assignedVariantId)
                            assertEquals(
                                DeviceLegControlExecutor.ExperimentSelection.Source.NO_ASSIGNMENT,
                                selection.source,
                            )
                        }
                    }
                }
                "park" -> {
                    actual as DeviceLegControlExecutor.Result.Park
                    assertEquals(id, expected.text("stepId"), actual.stepId)
                    assertEquals(id, expected.number("anchorAtMillis"), actual.checkpoint.anchorAtMillis)
                    assertEquals(id, expected.number("wakeAtMillis"), actual.checkpoint.wakeAtMillis)
                }
                "complete" -> {
                    actual as DeviceLegControlExecutor.Result.Complete
                    assertEquals(id, expected.text("outcome"), actual.outcome)
                }
                "dispatch" -> {
                    actual as DeviceLegControlExecutor.Result.Dispatch
                    assertEquals(id, expected.text("stepId"), actual.stepId)
                    assertEquals(id, expected.text("actionType"), actual.action.getValue("type").jsonPrimitive.content)
                }
                else -> error("unknown expected result")
            }
        }
    }

    @Test fun missingOutletsAndOverflowingDeadlinesFailClosed() {
        val executor = DeviceLegControlExecutor(SignedTimezoneBundle.load(), "Etc/UTC", "Etc/UTC")
        val context = JsonObject(mapOf("event" to JsonObject(emptyMap()), "responses" to JsonObject(emptyMap())))
        fun step(action: String, body: String) = Json.parseToJsonElement(
            """{"kind":"action","id":"step","action":{"type":"$action",$body},"outlets":{}}"""
        ).jsonObject
        assertEquals(DeviceLegControlExecutor.Result.Invalid,
            executor.evaluate(step("delay", "\"durationMs\":1"), context, JsonObject(emptyMap()), Long.MAX_VALUE))
        val condition = step("condition", "\"branches\":[]")
        assertEquals(DeviceLegControlExecutor.Result.Invalid,
            executor.evaluate(condition, context, JsonObject(emptyMap()), 0))
        val purchase = Json.parseToJsonElement(
            """{"kind":"action","id":"purchase","action":{"type":"purchase","placementId":"offer"},"outlets":{"completed":"done"}}"""
        ).jsonObject
        assertEquals(DeviceLegControlExecutor.Result.Advance("done", context),
            executor.selectOutlet(purchase, "completed", context))
        assertEquals(DeviceLegControlExecutor.Result.Invalid, executor.selectOutlet(purchase, "failed", context))
    }
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
