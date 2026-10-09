package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.testsupport.JourneyTestFixtures
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

class JourneyRuntimeEmissionCoordinatorTest {
    @Test fun `save only frames and ordinary siblings reach the publication gate once`() = runTest {
        val saves = mutableListOf<ExperienceFrameSave>()
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val request = ExperienceFrameSave(ExperienceResponseSaveRequest("feedback", "resume", JsonObject(emptyMap())),
            "screen", {})
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "screen", JsonObject(emptyMap()), 0, 0,
            onEmissionBatch = { batch, sources ->
                batches += batch
                saves += checkNotNull(sources).saves
                JourneyEmissionBatchResult.ACCEPTED
            }, onPresentationRevealed = {})
        assertTrue(coordinator.reveal())
        fun step(name: String) = NuxiePlayerStepOutcome(false, emptyList(),
            listOf(NuxieRuntimeEvent(0, 128, name, "", "", 0f, emptyList())), emptyList(), emptyList())
        assertTrue(coordinator.publish(step("\$nuxie.response.save"), 1uL, saves = listOf(request)))
        assertTrue(coordinator.publish(step("continue"), 2uL, saves = listOf(request)))
        assertEquals(listOf(0L, 1L), batches.map { it.batchSequence })
        assertTrue(batches.first().emissions.isEmpty())
        assertEquals(listOf("continue"), batches.last().emissions.map { it.name })
        assertEquals(listOf(0L), batches.last().emissions.map { it.sequence })
        assertEquals(listOf(request, request), saves)
    }

    @Test fun `reserved event drops only itself and preserves the sibling source`() = runTest {
        val vector = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("events/reserved-event-filtering.json").readText()).jsonObject
        val published = mutableListOf<String>()
        val sequences = mutableListOf<Long>()
        val sourceIds = mutableListOf<Long?>()
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "screen", JsonObject(emptyMap()), 0, 0,
            onEmissionBatch = { batch, sources ->
                batch.emissions.forEach {
                    published += it.name
                    sequences += it.sequence
                    sourceIds += sources?.source(it.id)?.nativeId
                }
                JourneyEmissionBatchResult.ACCEPTED
            }, onPresentationRevealed = {})
        assertTrue(coordinator.reveal())
        val snapshot = ai.nuxie.sdk.runtime.NuxieViewModelSnapshot.fromNative(
            ai.nuxie.sdk.runtime.NativeViewModelSnapshot(71,
                arrayOf(ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance(71, 0),
                    ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance(72, 1)), emptyArray()))
        val events = vector.getValue("events").jsonArray.mapIndexed { index, name ->
            NuxieRuntimeEvent(index, 128, name.jsonPrimitive.content, "", "", 0f, emptyList(), 71L + index)
        }
        assertTrue(coordinator.publish(NuxiePlayerStepOutcome(false, emptyList(), events, emptyList(), emptyList()),
            1uL, snapshot = snapshot))
        assertEquals(vector.getValue("expectedEvents").jsonArray.map { it.jsonPrimitive.content }, published)
        assertEquals(vector.getValue("expectedSequences").jsonArray.map { it.jsonPrimitive.long }, sequences)
        assertEquals(listOf(71L), sourceIds)
    }

    @Test fun `shared frame changes produce no events and emissions retain their settled snapshot`() = runTest {
        val vectors = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("events/runtime-frame-writes.json").readText()).jsonObject.getValue("cases").jsonArray
        var expectedValue = ""
        val published = mutableListOf<String>()
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "screen", JsonObject(emptyMap()), 0, 0,
            onEmissionBatch = { batch, sources ->
                batch.emissions.forEach { emission ->
                    published += emission.name
                    val source = requireNotNull(sources?.source(emission.id))
                    assertEquals(expectedValue, source.snapshot.resolveNativeString("placementId", null, source.nativeId))
                }
                JourneyEmissionBatchResult.ACCEPTED
            }, onPresentationRevealed = {})
        assertTrue(coordinator.reveal())
        for (element in vectors) {
            val vector = element.jsonObject
            val value = vector.getValue("value").jsonPrimitive.content
            expectedValue = value
            published.clear()
            val snapshot = ai.nuxie.sdk.runtime.NuxieViewModelSnapshot.fromNative(
                ai.nuxie.sdk.runtime.NativeViewModelSnapshot(71,
                    arrayOf(ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance(71, 0)),
                    arrayOf(ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue(71, 0, "placementId",
                        ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind.STRING.nativeValue,
                        value.encodeToByteArray(), 0))))
            val events = vector.getValue("events").jsonArray.mapIndexed { index, name ->
                NuxieRuntimeEvent(index, 128, name.jsonPrimitive.content, "", "", 0f, emptyList())
            }
            val change = ai.nuxie.sdk.runtime.NuxieViewModelChange(
                ai.nuxie.sdk.runtime.NuxieViewModelChangeOrigin.RUNTIME, 77uL, 71uL, 0,
                ai.nuxie.sdk.runtime.NuxieViewModelValue.Bytes(value.encodeToByteArray()))
            assertTrue(coordinator.publish(NuxiePlayerStepOutcome(false, emptyList(), events,
                emptyList(), listOf(change)), 42uL, snapshot = snapshot))
            assertEquals(vector.getValue("name").jsonPrimitive.content,
                vector.getValue("expectedEvents").jsonArray.map { it.jsonPrimitive.content }, published)
        }
    }

    @Test fun `accepted frame link cancellation escapes publication`() = runTest {
        val cancelled = kotlinx.coroutines.CancellationException("link cancelled")
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "screen", JsonObject(emptyMap()), 0, 0,
            onEmissionBatch = { _, sources -> sources?.frameLinks?.perform(); JourneyEmissionBatchResult.ACCEPTED }, onPresentationRevealed = {},
            onOpenLink = { throw cancelled })
        assertTrue(coordinator.reveal())
        val events = listOf(NuxieRuntimeEvent(0, 128, "sibling", "", "", 0f, emptyList()),
            NuxieRuntimeEvent(1, 131, "", "https://example.test", "_self", 0f, emptyList()))
        try {
            coordinator.publish(NuxiePlayerStepOutcome(false, emptyList(), events, emptyList(), emptyList()), 1uL)
            org.junit.Assert.fail("Cancellation must escape the accepted batch")
        } catch (failure: kotlinx.coroutines.CancellationException) { org.junit.Assert.assertSame(cancelled, failure) }
    }

    @Test fun `rejected frame links run after batch handoff outside publication gate`() = runTest {
        val order = mutableListOf<String>()
        lateinit var coordinator: JourneyRuntimeEmissionCoordinator
        coordinator = JourneyRuntimeEmissionCoordinator("journey", "screen", JsonObject(emptyMap()), 0, 0,
            onEmissionBatch = { it, _ -> order += "batch"; JourneyEmissionBatchResult.REJECTED }, onPresentationRevealed = {},
            onOpenLink = { order += "link"; coordinator.close() })
        assertTrue(coordinator.reveal())
        val events = listOf(NuxieRuntimeEvent(0, 131, "", "https://example.test", "_self", 0f, emptyList(), 99),
            NuxieRuntimeEvent(1, 128, "sibling", "", "", 0f, emptyList()))
        assertFalse(coordinator.publish(NuxiePlayerStepOutcome(false, emptyList(), events, emptyList(), emptyList()), 1uL))
        assertEquals(listOf("batch", "link"), order)
    }

    @Test fun `two controls reject the batch but preserve its link`() = runTest {
        val descriptor = Json.parseToJsonElement("""{"screenBehaviors":[{"screenId":"screen","controls":[{"actionId":"control","behavior":{"kind":"declarative","program":[]}}]}]}""").jsonObject
        var batches = 0
        val links = mutableListOf<JourneyLinkRequest>()
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "screen", descriptor, 0, 0,
            onEmissionBatch = { _, _ -> batches++; JourneyEmissionBatchResult.ACCEPTED }, onPresentationRevealed = {}, onOpenLink = { links += it })
        assertTrue(coordinator.reveal())
        val control = NuxieRuntimeEvent(0, 128, "control", "", "", 0f, emptyList())
        val link = NuxieRuntimeEvent(2, 131, "", "https://example.test", "_self", 0f, emptyList())
        assertTrue(coordinator.publish(NuxiePlayerStepOutcome(false, emptyList(), listOf(control, control, link), emptyList(), emptyList()), 1uL))
        assertEquals(0, batches)
        assertEquals(listOf("https://example.test"), links.map { it.url })
    }

    @Test fun `unaliased control drops its own drafts and preserves siblings`() = runTest {
        val descriptor = Json.parseToJsonElement("""{"screenBehaviors":[{"screenId":"screen","controls":[{"actionId":"control","behavior":{"kind":"declarative","program":[{"type":"emit","eventName":"before_missing","payload":{}},{"type":"emit","eventName":"requires_alias","payload":{"instance":{"source":"instance_id"}}}]}}]}]}""").jsonObject
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "screen", descriptor, 0, 0,
            onEmissionBatch = { it, _ -> batches += it; JourneyEmissionBatchResult.ACCEPTED }, onPresentationRevealed = {})
        assertTrue(coordinator.reveal())
        val frame = ai.nuxie.sdk.runtime.NuxieViewModelSnapshot.fromNative(ai.nuxie.sdk.runtime.NativeViewModelSnapshot(1,
            arrayOf(ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance(1, 0), ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance(3, 0)), emptyArray()))
        val events = listOf(NuxieRuntimeEvent(0, 128, "control", "", "", 0f, emptyList(), 3),
            NuxieRuntimeEvent(1, 128, "sibling", "", "", 0f, emptyList()))
        assertTrue(coordinator.publish(NuxiePlayerStepOutcome(false, emptyList(), events, emptyList(), emptyList()), 1uL, snapshot = frame))
        assertEquals(listOf("sibling"), batches.single().emissions.map { it.name })
        assertEquals(listOf(0L), batches.single().emissions.map { it.sequence })
    }

    @Test
    fun `durable admission waits for live host before releasing effects and is not replayed on recreation`() = runTest {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/presentation-reveal-android.json").readText()).jsonObject
        val order = mutableListOf<String>()
        var admissionCount = 0
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey-reveal",
            screenId = screenEmissionFixture.getValue("run").jsonObject.getValue("screen_id").jsonPrimitive.content,
            descriptor = controlDescriptor(),
            nextBatchSequence = 0, nextEmissionSequence = 0,
            onEmissionBatch = { it, _ -> order += "batch"; JourneyEmissionBatchResult.ACCEPTED },
            onScreenChanged = { order += "screen"; true },
            onPresentationRevealed = { order += "admission"; admissionCount++ },
        )
        val publication = async { coordinator.publish(controlOutcome(), 17uL) }
        yield()
        assertEquals(JourneyRuntimeEmissionCoordinator.RevealResult.WAITING_FOR_HOST,
            coordinator.reveal { order += "retired-host"; false })
        yield()
        assertFalse(publication.isCompleted)
        assertEquals(JourneyRuntimeEmissionCoordinator.RevealResult.VISIBLE,
            coordinator.reveal { order += "live-host"; true })
        assertTrue(publication.await())
        assertEquals(fixture.getValue("order").jsonArray.map { it.jsonPrimitive.content }, order)
        assertEquals(JourneyRuntimeEmissionCoordinator.RevealResult.VISIBLE, coordinator.reveal { true })
        assertEquals(fixture.getValue("recreationAdmissionCount").jsonPrimitive.content.toInt(), admissionCount)
    }

    @Test
    fun `closing after admission but before live host releases waiting effects as rejected`() = runTest {
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey-reveal",
            screenId = screenEmissionFixture.getValue("run").jsonObject.getValue("screen_id").jsonPrimitive.content,
            descriptor = controlDescriptor(),
            nextBatchSequence = 0, nextEmissionSequence = 0,
            onEmissionBatch = { it, _ -> error("Closed generation must not publish") },
            onPresentationRevealed = {},
        )
        val publication = async { coordinator.publish(controlOutcome(), 17uL) }
        yield()
        assertEquals(JourneyRuntimeEmissionCoordinator.RevealResult.WAITING_FOR_HOST, coordinator.reveal { false })
        coordinator.close()
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/presentation-reveal-android.json").readText()).jsonObject
        assertEquals(fixture.getValue("closedWaitingPublication").jsonPrimitive.content.toBooleanStrict(), publication.await())
        assertEquals(JourneyRuntimeEmissionCoordinator.RevealResult.REJECTED,
            coordinator.reveal { error("Closed generation must not reveal") })
    }

    @Test
    fun `signed control publishes one atomic batch only after reveal`() = runTest {
        val fixture = screenEmissionFixture
        val run = fixture.getValue("run").jsonObject
        val input = fixture.getValue("input").jsonObject
        val expected = fixture.getValue("expected").jsonObject
        val expectedIds = expected.getValue("customer_event_ids").jsonArray.map {
            it.jsonPrimitive.content
        }
        val order = mutableListOf<String>()
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val ids = ArrayDeque(listOf("fixture-invocation") + expectedIds)
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = run.getValue("journey_id").jsonPrimitive.content,
            screenId = run.getValue("screen_id").jsonPrimitive.content,
            descriptor = controlDescriptor(),
            nextBatchSequence = expected.getValue("batch_sequence").jsonPrimitive.long,
            nextEmissionSequence = expected.getValue("emission_sequences").jsonArray
                .first().jsonPrimitive.long,
            onEmissionBatch = { batch, frameSources ->
                order += "batch"
                batches += batch
                JourneyEmissionBatchResult.ACCEPTED
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
            listOf(0L),
            batch.emissions.map { it.sequence },
        )
        assertEquals(expectedIds, batch.emissions.map { it.id })
        assertEquals(
            listOf("survey_submitted"),
            batch.emissions.map { it.name },
        )
        assertEquals(
            input.getValue("value"),
            batch.emissions[0].payload.getValue("answer"),
        )
        assertEquals(
            expected.getValue("customer_event_ids").jsonArray.map { it.jsonPrimitive.content },
            batch.emissions.filterNot { it.name.startsWith('$') }.map { it.id },
        )
    }

    @Test
    fun `reserved host answer is dropped while ordinary runtime event publishes`() = runTest {
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = JsonObject(emptyMap()),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { it, _ -> batches += it; JourneyEmissionBatchResult.ACCEPTED },
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
        assertEquals(listOf("survey_viewed"), batch.emissions.map { it.name })
        assertEquals("runtime:23", batch.source.actionId)
        assertEquals("survey", batch.source.screenId)
    }

    @Test
    fun `generated native control routes only its exact signed action identity`() = runTest {
        val fixture = generatedControlFixture
        val actionId = fixture.getValue("signedActionId").jsonPrimitive.content
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator(
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
            onEmissionBatch = { it, _ -> batches += it; JourneyEmissionBatchResult.ACCEPTED },
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
    fun `ordinary event carrying the signed action id remains ordinary`() = runTest {
        val fixture = generatedControlFixture
        val actionId = fixture.getValue("signedActionId").jsonPrimitive.content
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = controlDescriptor(),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { it, _ -> batches += it; JourneyEmissionBatchResult.ACCEPTED },
            onPresentationRevealed = {},
        )
        coordinator.reveal()

        assertTrue(
            coordinator.publish(
                outcome(events = listOf(event("survey_submitted", property("actionId", actionId)))),
                8uL,
            ),
        )

        val batch = batches.single()
        assertEquals("runtime:8", batch.source.actionId)
        assertEquals(listOf("survey_submitted"), batch.emissions.map { it.name })
        assertEquals(
            actionId,
            batch.emissions.single().payload.getValue("actionId").jsonPrimitive.content,
        )
    }

    @Test
    fun `renderer navigation is rejected without dropping sibling ordinary effects`() = runTest {
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = JsonObject(emptyMap()),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { it, _ -> batches += it; JourneyEmissionBatchResult.ACCEPTED },
            onPresentationRevealed = {},
        )
        coordinator.reveal()

        assertTrue(
            coordinator.publish(
                outcome(
                    hostCommands = listOf(
                        NuxieHostCommand(
                            name = "\$navigate",
                            value = hostObject("screenId" to NuxieHostValue.String("other")),
                        ),
                        NuxieHostCommand(
                            name = "survey_submitted",
                            value = hostObject("answer" to NuxieHostValue.String("yes")),
                        ),
                    ),
                ),
                9uL,
            ),
        )

        assertEquals(listOf("survey_submitted"), batches.single().emissions.map { it.name })
    }

    @Test
    fun `malformed generated control and multiple controls publish nothing`() = runTest {
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = controlDescriptor(),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { it, _ -> batches += it; JourneyEmissionBatchResult.ACCEPTED },
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
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = JsonObject(emptyMap()),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { it, _ ->
                attempts += 1
                JourneyEmissionBatchResult.REJECTED
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
        val coordinator = JourneyRuntimeEmissionCoordinator(
            journeyId = "journey-1",
            screenId = "survey",
            descriptor = JsonObject(emptyMap()),
            nextBatchSequence = 0,
            nextEmissionSequence = 0,
            onEmissionBatch = { it, _ ->
                attempts += 1
                JourneyEmissionBatchResult.ACCEPTED
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

    @Test
    fun `retiring host releases queued effects without closing replacement emission lane`() = runTest {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/presentation-reveal-android.json").readText()).jsonObject
            .getValue("rendererRetirement").jsonObject
        var batches = 0
        var admissions = 0
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "survey", JsonObject(emptyMap()), 0, 0,
            onEmissionBatch = { it, _ -> batches++; JourneyEmissionBatchResult.ACCEPTED }, onPresentationRevealed = { admissions++ })
        val old = RendererEffectLifetime()
        val waiting = async { coordinator.publish(outcome(events = listOf(event("old"))), 1uL, old) }
        val text = async { coordinator.publishTextCommit("old-input", "stale", lifetime = old) }
        yield()
        assertFalse(waiting.isCompleted)
        assertEquals(JourneyRuntimeEmissionCoordinator.RevealResult.WAITING_FOR_HOST, coordinator.reveal { false })
        old.retire()
        assertEquals(fixture.getValue("retiredWaiterHandled").jsonPrimitive.content.toBooleanStrict(), waiting.await())
        assertTrue(text.await())
        assertEquals(0, batches)
        assertTrue(coordinator.reveal())
        assertTrue(coordinator.publish(outcome(events = listOf(event("replacement"))), 2uL, RendererEffectLifetime()))
        assertEquals(fixture.getValue("batchCountAfterReplacement").jsonPrimitive.content.toInt(), batches)
        assertEquals(fixture.getValue("admissionCount").jsonPrimitive.content.toInt(), admissions)
    }

    @Test
    fun `retirement drains admitted publication but drops work queued behind it`() = runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sequences = mutableListOf<Long>()
        val coordinator = JourneyRuntimeEmissionCoordinator("journey", "survey", JsonObject(emptyMap()), 0, 0,
            onEmissionBatch = { batch, frameSources ->
                sequences += batch.batchSequence
                if (sequences.size == 1) { entered.complete(Unit); release.await() }
                JourneyEmissionBatchResult.ACCEPTED
            }, onPresentationRevealed = {})
        val old = RendererEffectLifetime()
        coordinator.reveal()
        val admitted = async { coordinator.publish(outcome(events = listOf(event("admitted"))), 1uL, old) }
        entered.await()
        val queued = async { coordinator.publish(outcome(events = listOf(event("queued"))), 2uL, old) }
        yield()
        old.retire()
        assertFalse(admitted.isCompleted)
        release.complete(Unit)
        assertTrue(admitted.await())
        assertTrue(queued.await())
        assertEquals(listOf(0L), sequences)
        assertTrue(coordinator.publish(outcome(events = listOf(event("replacement"))), 3uL, RendererEffectLifetime()))
        assertEquals(listOf(0L, 1L), sequences)
    }

    private fun controlDescriptor(): JsonObject {
        val run = screenEmissionFixture.getValue("run").jsonObject
        val input = screenEmissionFixture.getValue("input").jsonObject
        val program = screenEmissionFixture.getValue("effects").jsonArray.filter {
            it.jsonObject.getValue("kind").jsonPrimitive.content == "event"
        }.map { element ->
            val effect = element.jsonObject
            when (effect.getValue("kind").jsonPrimitive.content) {
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
        get() = JourneyTestFixtures.rendererPublication

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
