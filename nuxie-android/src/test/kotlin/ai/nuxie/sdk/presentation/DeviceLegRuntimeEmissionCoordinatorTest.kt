package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieHostCommand
import ai.nuxie.sdk.runtime.NuxieHostValue
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventProperty
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLegRuntimeEmissionCoordinatorTest {
    @Test
    fun `signed control publishes one atomic batch only after reveal`() = runTest {
        val order = mutableListOf<String>()
        val batches = mutableListOf<DeviceLegScreenEmissionBatch>()
        val ids = ArrayDeque(listOf("invocation-1", "emission-1", "emission-2"))
        val coordinator = DeviceLegRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = controlDescriptor(),
            nextBatchSequence = 4,
            nextEmissionSequence = 9,
            onEmissionBatch = { batch ->
                order += "batch"
                batches += batch
                true
            },
            onPresentationRevealed = {
                order += "reveal:$it"
            },
            createId = ids::removeFirst,
            nowMillis = { 1_788_000_000_123 },
        )

        val publication = async { coordinator.publish(controlOutcome(), 17uL) }
        yield()
        assertFalse(publication.isCompleted)

        assertTrue(coordinator.reveal())
        assertTrue(publication.await())

        assertEquals(listOf("reveal:survey", "batch"), order)
        val batch = batches.single()
        assertEquals(4, batch.batchSequence)
        assertEquals("invocation-1", batch.invocationId)
        assertEquals("submit", batch.source.actionId)
        assertEquals("button", batch.source.componentId)
        assertEquals("survey-1", batch.source.instanceId)
        assertEquals(listOf(9L, 10L), batch.emissions.map { it.sequence })
        assertEquals(listOf("emission-1", "emission-2"), batch.emissions.map { it.id })
        assertEquals(listOf("\$response_set", "survey_submitted"), batch.emissions.map { it.name })
        assertEquals("premium", batch.emissions[0].payload["value"]?.toString()?.trim('"'))
        assertEquals("\"button\"", batch.emissions[1].payload["component"].toString())
    }

    @Test
    fun `runtime event and host response preserve native transaction order`() = runTest {
        val batches = mutableListOf<DeviceLegScreenEmissionBatch>()
        val coordinator = DeviceLegRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = JsonObject(emptyMap()),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { batches += it; true },
            onPresentationRevealed = {},
            createId = incrementingIds(),
            nowMillis = { 99 },
        )
        coordinator.reveal()

        assertTrue(
            coordinator.publish(
                outcome(
                    events = listOf(
                        event(
                            name = "survey_viewed",
                            property("screen_id", "survey"),
                            property("answer", "yes"),
                        ),
                    ),
                    hostCommands = listOf(
                        NuxieHostCommand(
                            name = "\$response_unset",
                            value = hostObject("field" to NuxieHostValue.String("answer")),
                        ),
                    ),
                ),
                23uL,
            ),
        )

        val batch = batches.single()
        assertEquals(listOf("survey_viewed", "\$response_unset"), batch.emissions.map { it.name })
        assertEquals("runtime:23", batch.source.actionId)
        assertEquals("survey", batch.source.screenId)
    }

    @Test
    fun `malformed generated control and multiple controls publish nothing`() = runTest {
        val batches = mutableListOf<DeviceLegScreenEmissionBatch>()
        val coordinator = DeviceLegRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = controlDescriptor(),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { batches += it; true },
            onPresentationRevealed = {},
        )
        coordinator.reveal()

        val malformed = eventWithCoreType(
            "Nuxie Interaction",
            128,
            property("actionId", "submit"),
        )
        assertTrue(coordinator.publish(outcome(events = listOf(malformed)), 1uL))
        assertTrue(
            coordinator.publish(
                outcome(events = listOf(controlOutcome().events.single(), directControlEvent())),
                2uL,
            ),
        )
        assertTrue(batches.isEmpty())
    }

    @Test
    fun `rejected durable publication closes the renderer emission lane`() = runTest {
        var attempts = 0
        val coordinator = DeviceLegRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = JsonObject(emptyMap()),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = {
                attempts += 1
                false
            },
            onPresentationRevealed = {},
        )
        coordinator.reveal()
        val ordinary = outcome(events = listOf(event("submitted")))

        assertFalse(coordinator.publish(ordinary, 1uL))
        assertFalse(coordinator.publish(ordinary, 2uL))
        assertEquals(1, attempts)
    }

    @Test
    fun `close wins against publication waiting for reveal`() = runTest {
        var attempts = 0
        val coordinator = DeviceLegRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = JsonObject(emptyMap()),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = {
                attempts += 1
                true
            },
            onPresentationRevealed = {},
        )
        val publication = async {
            coordinator.publish(outcome(events = listOf(event("submitted"))), 1uL)
        }
        yield()

        coordinator.close()

        assertFalse(publication.await())
        assertEquals(0, attempts)
    }

    private fun controlDescriptor(): JsonObject = Json.parseToJsonElement(
        """
        {
          "screenBehaviors": [{
            "screenId": "survey",
            "controls": [{
              "actionId": "submit",
              "behavior": {
                "kind": "declarative",
                "program": [
                  {
                    "type": "response_set",
                    "field": "answer",
                    "value": { "source": "invocation_value" }
                  },
                  {
                    "type": "emit",
                    "eventName": "survey_submitted",
                    "payload": {
                      "component": { "source": "component_id" },
                      "instance": { "source": "instance_id" }
                    }
                  }
                ]
              }
            }]
          }]
        }
        """.trimIndent(),
    ).jsonObject

    private fun controlOutcome() = outcome(
        events = listOf(
            eventWithCoreType(
                "Nuxie Interaction",
                128,
                property("nuxieTrigger", "tap"),
                property("actionId", "submit"),
                property("componentId", "button"),
                property("instanceId", "survey-1"),
                property("value", "premium"),
            ),
        ),
    )

    private fun directControlEvent() = event(
        name = "submit",
        property("componentId", "button"),
        property("value", "premium"),
    )

    private fun outcome(
        events: List<NuxieRuntimeEvent> = emptyList(),
        hostCommands: List<NuxieHostCommand> = emptyList(),
    ) = NuxiePlayerStepOutcome(
        keepGoing = true,
        pointerHits = emptyList(),
        events = events,
        hostCommands = hostCommands,
        viewModelChanges = emptyList(),
    )

    private fun event(
        name: String,
        vararg properties: NuxieRuntimeEventProperty,
    ) = eventWithCoreType(name, 0, *properties)

    private fun eventWithCoreType(
        name: String,
        coreType: Int,
        vararg properties: NuxieRuntimeEventProperty,
    ) = NuxieRuntimeEvent(
        localIndex = 0,
        coreType = coreType,
        name = name,
        url = "",
        target = "",
        delay = 0f,
        properties = properties.toList(),
    )

    private fun property(name: String, value: String) = NuxieRuntimeEventProperty(
        name,
        NuxieRuntimeEventPropertyValue.Bytes(value.encodeToByteArray()),
    )

    private fun hostObject(vararg fields: Pair<String, NuxieHostValue>) =
        NuxieHostValue.Object(
            fields.map { (key, value) -> NuxieHostValue.Object.Field(key, value) },
        )

    private fun incrementingIds(): () -> String {
        var next = 0
        return { "id-${++next}" }
    }
}
