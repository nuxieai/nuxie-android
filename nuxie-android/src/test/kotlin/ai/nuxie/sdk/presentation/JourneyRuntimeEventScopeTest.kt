package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue
import ai.nuxie.sdk.runtime.NuxiePlayerStepOutcome
import ai.nuxie.sdk.runtime.NuxieRuntimeEvent
import ai.nuxie.sdk.runtime.NuxieRuntimeEventProperty
import ai.nuxie.sdk.runtime.NuxieRuntimeEventPropertyValue
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
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
            val batches = mutableListOf<JourneyScreenEmissionBatch>()
            var resolved: JourneyRuntimeEventSource? = null
            val coordinator = JourneyRuntimeEmissionCoordinator(journeyId = "journey", screenId = "screen",
                descriptor = JsonObject(emptyMap()), nextBatchSequence = 0, nextEmissionSequence = 0,
                onEmissionBatch = { it, frameSources -> batches += it; resolved = frameSources?.source(it.emissions.first().id); JourneyEmissionBatchResult.ACCEPTED },
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
            assertEquals(name, "screen", batch.source.screenId)
            if (accepted) {
                assertEquals(name, JsonPrimitive("literal"), batch.emissions.first().payload["value"])
                // A property named instanceId is the author's value; the
                // source carries the runtime's alias.
                assertEquals(name, vector["declared"]?.jsonPrimitive?.contentOrNull?.let(::JsonPrimitive),
                    batch.emissions.first().payload["instanceId"])
                assertEquals(name, if (native == 0L) 1L else native, resolved?.nativeId)
            }
        }
    }

    @Test
    fun `shared event values are sent under their fields from the root model`() = runTest {
        val suite = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("events/runtime-event-values.json").readText()).jsonObject
        assertEquals(1, suite.getValue("version").jsonPrimitive.int)
        val graph = suite.getValue("snapshot").jsonObject
        val instances = graph.getValue("instances").jsonArray.map { it.jsonObject }
        val values = instances.flatMap { instance ->
            val owner = instance.getValue("id").jsonPrimitive.long
            instance.getValue("values").jsonObject.entries.sortedBy { it.key }.mapIndexed { index, (name, element) ->
                val value = element.jsonObject
                fun native(kind: NuxieViewModelPropertyKind) = NativeViewModelSnapshotValue(owner, index.toLong(),
                    name, kind.nativeValue, byteArrayOf(), 0L)
                when {
                    "reference" in value -> native(NuxieViewModelPropertyKind.VIEW_MODEL)
                        .copy(referencedInstanceId = value.getValue("reference").jsonPrimitive.long)
                    "string" in value -> native(NuxieViewModelPropertyKind.STRING)
                        .copy(bytesValue = value.getValue("string").jsonPrimitive.content.encodeToByteArray())
                    "number" in value -> native(NuxieViewModelPropertyKind.NUMBER)
                        .copy(numberValue = value.getValue("number").jsonPrimitive.float)
                    "boolean" in value -> native(NuxieViewModelPropertyKind.BOOLEAN)
                        .copy(boolValue = value.getValue("boolean").jsonPrimitive.boolean)
                    else -> native(NuxieViewModelPropertyKind.ENUM)
                        .copy(integerValue = value.getValue("enum").jsonPrimitive.long)
                }
            }
        }
        val snapshot = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(
            graph.getValue("root").jsonPrimitive.long,
            instances.map { NativeViewModelSnapshotInstance(it.getValue("id").jsonPrimitive.long, 0) }.toTypedArray(),
            values.toTypedArray()))
        for (entry in suite.getValue("cases").jsonArray) {
            val vector = entry.jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            val properties = vector.getValue("properties").jsonArray.map { element ->
                val property = element.jsonObject
                val key = property.getValue("name").jsonPrimitive.content
                NuxieRuntimeEventProperty(key, when {
                    "string" in property -> NuxieRuntimeEventPropertyValue.Bytes(
                        property.getValue("string").jsonPrimitive.content.encodeToByteArray())
                    "number" in property -> NuxieRuntimeEventPropertyValue.Number(property.getValue("number").jsonPrimitive.float)
                    else -> NuxieRuntimeEventPropertyValue.Bool(property.getValue("boolean").jsonPrimitive.boolean)
                })
            }
            val batches = mutableListOf<JourneyScreenEmissionBatch>()
            val coordinator = JourneyRuntimeEmissionCoordinator(journeyId = "journey", screenId = "screen",
                descriptor = JsonObject(emptyMap()), nextBatchSequence = 0, nextEmissionSequence = 0,
                onEmissionBatch = { it, _ -> batches += it; JourneyEmissionBatchResult.ACCEPTED },
                onPresentationRevealed = {})
            assertTrue(coordinator.reveal())
            val event = NuxieRuntimeEvent(0, 128, "picked", "", "", 0f, properties)
            assertTrue(name, coordinator.publish(NuxiePlayerStepOutcome(false, emptyList(), listOf(event),
                emptyList(), emptyList()), 1uL, snapshot = snapshot))
            val payload = batches.single().emissions.single().payload
            val expected = vector.getValue("expected").jsonObject
            assertEquals(name, expected.keys, payload.keys)
            for ((key, value) in expected) {
                val sent = payload.getValue(key).jsonPrimitive
                val wanted = value.jsonPrimitive
                when {
                    wanted.isString -> assertEquals("$name: $key", wanted, sent)
                    wanted.booleanOrNull != null -> assertEquals("$name: $key", wanted.boolean, sent.boolean)
                    else -> assertEquals("$name: $key", wanted.double, sent.double, 0.0)
                }
            }
        }
    }
}
