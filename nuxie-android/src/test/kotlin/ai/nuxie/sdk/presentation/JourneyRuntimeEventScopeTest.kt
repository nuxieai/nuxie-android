package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventProperty
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JourneyRuntimeEventScopeTest {
    @Test
    fun `shared sources publish only live events with contiguous sequences`() = runTest {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("events/runtime-event-sources.json").readText()).jsonObject
        for (entry in fixture.getValue("cases").jsonArray) {
            val vector = entry.jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            val accepted = vector.getValue("accepted").jsonPrimitive.boolean
            val alias = vector["alias"]?.jsonPrimitive?.contentOrNull
            val aliases = vector.getValue("aliases").jsonArray.associate {
                val value = it.jsonObject
                value.getValue("name").jsonPrimitive.content to value.getValue("native").jsonPrimitive.long
            }
            val live = vector.getValue("live").jsonArray.map { it.jsonPrimitive.long }
            val snapshot = if (live.isEmpty()) null else NuxieViewModelSnapshot.fromNative(
                NativeViewModelSnapshot(1, live.map { NativeViewModelSnapshotInstance(it, 0) }.toTypedArray(),
                    emptyArray()), instanceIds = aliases)
            val sources = JourneyRuntimeEventSources()
            val batches = mutableListOf<JourneyScreenEmissionBatch>()
            var resolved: JourneyRuntimeEventSource? = null
            val coordinator = JourneyRuntimeEmissionCoordinator(journeyId = "journey", screenId = "screen",
                descriptor = JsonObject(emptyMap()), nextBatchSequence = 0, nextEmissionSequence = 0,
                eventSources = sources,
                onEmissionBatch = { batches += it; resolved = sources.take(it.invocationId)?.source(it.emissions.first().id); true },
                onPresentationRevealed = {})
            assertTrue(coordinator.reveal())
            val properties = mutableListOf(NuxieRuntimeEventProperty("value",
                NuxieRuntimeEventPropertyValue.Bytes("literal".encodeToByteArray())))
            vector["duplicateValue"]?.jsonPrimitive?.contentOrNull?.let {
                properties += NuxieRuntimeEventProperty("value", NuxieRuntimeEventPropertyValue.Bytes(it.encodeToByteArray()))
            }
            vector["declared"]?.jsonPrimitive?.contentOrNull?.let {
                properties += NuxieRuntimeEventProperty("instanceId", NuxieRuntimeEventPropertyValue.Bytes(it.encodeToByteArray()))
            }
            val native = vector["source"]?.jsonPrimitive?.longOrNull ?: 0L
            val event = NuxieRuntimeEvent(0, 128, "selected", "", "", 0f, properties, native)
            val sibling = NuxieRuntimeEvent(1, 128, "sibling", "", "", 0f, emptyList())
            assertTrue(name, coordinator.publish(NuxiePlayerStepOutcome(false, emptyList(), listOf(event, sibling),
                emptyList(), emptyList()), 1uL, snapshot = snapshot))
            val batch = batches.single()
            assertEquals(name, if (accepted) listOf("selected", "sibling") else listOf("sibling"), batch.emissions.map { it.name })
            assertEquals(name, if (accepted) listOf(0L, 1L) else listOf(0L), batch.emissions.map { it.sequence })
            assertEquals(name, if (accepted) alias else null, batch.source.instanceId)
            if (accepted) {
                assertEquals(name, JsonPrimitive("literal"), batch.emissions.first().payload["value"])
                assertEquals(name, alias?.let(::JsonPrimitive), batch.emissions.first().payload["instanceId"])
                assertEquals(name, if (native == 0L) 1L else native, resolved?.nativeId)
            }
            assertNull(sources.take(batch.invocationId))
        }
    }
}
