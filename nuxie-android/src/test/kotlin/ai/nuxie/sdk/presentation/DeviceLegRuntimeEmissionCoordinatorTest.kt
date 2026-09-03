package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLegRuntimeEmissionCoordinatorTest {
    @Test
    fun `signed control publishes one atomic batch only after reveal`() = runTest {
        val fixture = screenEmissionFixture
        val run = fixture.getValue("run").jsonObject
        val input = fixture.getValue("input").jsonObject
        val expected = fixture.getValue("expected").jsonObject
        val expectedIds = expected.getValue("emission_ids").jsonArray.map {
            it.jsonPrimitive.content
        }
        val order = mutableListOf<String>()
        val batches = mutableListOf<DeviceLegScreenEmissionBatch>()
        val ids = ArrayDeque(listOf("fixture-invocation") + expectedIds)
        val coordinator = DeviceLegRuntimeEmissionCoordinator(
            journeyId = run.getValue("journey_id").jsonPrimitive.content,
            screenId = run.getValue("screen_id").jsonPrimitive.content,
            descriptor = controlDescriptor(),
            nextBatchSequence = expected.getValue("batch_sequence").jsonPrimitive.long,
            nextEmissionSequence = expected.getValue("emission_sequences").jsonArray
                .first().jsonPrimitive.long,
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

        assertEquals(
            listOf("reveal:${run.getValue("screen_id").jsonPrimitive.content}", "batch"),
            order,
        )
        val batch = batches.single()
        assertEquals(expected.getValue("batch_sequence").jsonPrimitive.long, batch.batchSequence)
        assertEquals(input.getValue("action_id").jsonPrimitive.content, batch.source.actionId)
        assertEquals(input.getValue("component_id").jsonPrimitive.content, batch.source.componentId)
        assertEquals(input.getValue("instance_id").jsonPrimitive.content, batch.source.instanceId)
        assertEquals(
            expected.getValue("emission_sequences").jsonArray.map { it.jsonPrimitive.long },
            batch.emissions.map { it.sequence },
        )
        assertEquals(expectedIds, batch.emissions.map { it.id })
        assertEquals(
            fixture.getValue("effects").jsonArray.map { effect ->
                when (effect.jsonObject.getValue("kind").jsonPrimitive.content) {
                    "response_set" -> "\$response_set"
                    "event" -> effect.jsonObject.getValue("name").jsonPrimitive.content
                    else -> error("unsupported fixture effect")
                }
            },
            batch.emissions.map { it.name },
        )
        assertEquals(
            expected.getValue("response_values").jsonObject.getValue("answer"),
            batch.emissions[0].payload.getValue("value"),
        )
        assertEquals(
            expected.getValue("customer_event_ids").jsonArray.map { it.jsonPrimitive.content },
            batch.emissions.filterNot { it.name.startsWith('$') }.map { it.id },
        )
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
    fun `generated native control routes only its exact signed action identity`() = runTest {
        val fixture = generatedControlFixture
        val actionId = fixture.getValue("signedActionId").jsonPrimitive.content
        val batches = mutableListOf<DeviceLegScreenEmissionBatch>()
        val coordinator = DeviceLegRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = buildJsonObject {
                put("screenBehaviors", JsonArray(listOf(buildJsonObject {
                    put("screenId", JsonPrimitive("survey"))
                    put("controls", JsonArray(listOf(buildJsonObject {
                        put("actionId", JsonPrimitive(actionId))
                        put("behavior", buildJsonObject {
                            put("kind", JsonPrimitive("declarative"))
                            put("program", JsonArray(listOf(buildJsonObject {
                                put("type", JsonPrimitive("emit"))
                                put("eventName", JsonPrimitive("control_routed"))
                            })))
                        })
                    })))
                })))
            },
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { batches += it; true },
            onPresentationRevealed = {},
        )
        coordinator.reveal()

        assertTrue(coordinator.publish(exactGeneratedControlOutcome(), 7uL))

        val source = batches.single().source
        assertEquals(actionId, source.actionId)
        val fixtureProperties = fixture.getValue("properties").jsonArray.associate { element ->
            val property = element.jsonObject
            property.getValue("key").jsonPrimitive.content to
                property.getValue("value").jsonPrimitive.content
        }
        assertEquals(fixtureProperties.getValue("componentId"), source.componentId)
        assertEquals(fixtureProperties.getValue("instanceId"), source.instanceId)
        assertEquals(listOf("control_routed"), batches.single().emissions.map { it.name })
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

    private fun controlDescriptor(): JsonObject {
        val run = screenEmissionFixture.getValue("run").jsonObject
        val input = screenEmissionFixture.getValue("input").jsonObject
        val program = screenEmissionFixture.getValue("effects").jsonArray.map { element ->
            val effect = element.jsonObject
            when (effect.getValue("kind").jsonPrimitive.content) {
                "response_set" -> buildJsonObject {
                    put("type", JsonPrimitive("response_set"))
                    put("field", effect.getValue("field"))
                    put("value", buildJsonObject {
                        put("source", JsonPrimitive("invocation_value"))
                    })
                }
                "event" -> buildJsonObject {
                    put("type", JsonPrimitive("emit"))
                    put("eventName", effect.getValue("name"))
                    put(
                        "payload",
                        JsonObject(effect.getValue("payload").jsonObject.mapValues {
                            buildJsonObject {
                                put("source", JsonPrimitive("invocation_value"))
                            }
                        }),
                    )
                }
                else -> error("unsupported screen-emission fixture effect")
            }
        }
        return buildJsonObject {
            put("screenBehaviors", JsonArray(listOf(buildJsonObject {
                put("screenId", run.getValue("screen_id"))
                put("controls", JsonArray(listOf(buildJsonObject {
                    put("actionId", input.getValue("action_id"))
                    put("behavior", buildJsonObject {
                        put("kind", JsonPrimitive("declarative"))
                        put("program", JsonArray(program))
                    })
                })))
            })))
        }
    }

    private fun controlOutcome(): NuxiePlayerStepOutcome {
        val input = screenEmissionFixture.getValue("input").jsonObject
        val generated = generatedControlFixture
        val properties = generated.getValue("properties").jsonArray.map { element ->
            val fixtureProperty = element.jsonObject
            val key = fixtureProperty.getValue("key").jsonPrimitive.content
            val value = when (key) {
                "actionId" -> input.getValue("action_id").jsonPrimitive.content
                "componentId" -> input.getValue("component_id").jsonPrimitive.content
                "instanceId" -> input.getValue("instance_id").jsonPrimitive.content
                else -> fixtureProperty.getValue("value").jsonPrimitive.content
            }
            property(key, value)
        } + property("value", input.getValue("value").jsonPrimitive.content)
        return outcome(
            events = listOf(
                eventWithCoreType(
                    generated.getValue("eventName").jsonPrimitive.content,
                    128,
                    *properties.toTypedArray(),
                ),
            ),
        )
    }

    private fun exactGeneratedControlOutcome(): NuxiePlayerStepOutcome {
        val fixture = generatedControlFixture
        val properties = fixture.getValue("properties").jsonArray.map { element ->
            val fixtureProperty = element.jsonObject
            property(
                fixtureProperty.getValue("key").jsonPrimitive.content,
                fixtureProperty.getValue("value").jsonPrimitive.content,
            )
        }
        return outcome(events = listOf(eventWithCoreType(
            fixture.getValue("eventName").jsonPrimitive.content,
            128,
            *properties.toTypedArray(),
        )))
    }

    private fun directControlEvent() = event(
        name = screenEmissionFixture.getValue("input").jsonObject
            .getValue("action_id").jsonPrimitive.content,
        property("componentId", "button"),
        property("value", "premium"),
    )

    private val screenEmissionFixture: JsonObject
        get() = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot()
                .resolve("journeys/screen-emission-runtime/input-effect-persistence-replay.json")
                .readText(),
        ).jsonObject.also {
            assertEquals(
                "journeys/screen-emission-runtime",
                it.getValue("suite").jsonPrimitive.content,
            )
            assertEquals(1L, it.getValue("version").jsonPrimitive.long)
        }

    private val generatedControlFixture: JsonObject
        get() = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("events/generated-control-routing.json").readText(),
        ).jsonObject.also {
            assertEquals(
                "events/generated-control-routing",
                it.getValue("suite").jsonPrimitive.content,
            )
            assertEquals(1L, it.getValue("version").jsonPrimitive.long)
        }

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
