package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

class ListCheckpointDeviceTest {
    @Before fun requireRuntime() { assertTrue(NuxieRuntime.shared.isAvailable) }
    private data class Fixture(val bytes: ByteArray, val descriptor: JsonObject?)
    private fun fixture(folder: String): Fixture {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun read(name: String) = assets.open("runtime/$folder/$name").use { it.readBytes() }
        val provenance = Json.parseToJsonElement(read("provenance.json").decodeToString()).jsonObject
        val descriptor = provenance["fonts"]?.let { fonts -> buildJsonObject { putJsonObject("render") {
            put("assets", JsonArray(fonts.jsonArray.map { JsonObject(it.jsonObject + ("kind" to JsonPrimitive("font"))) }))
        } } }
        return Fixture(read("screen.riv"), descriptor)
    }
    private fun ids(snapshot: NativeViewModelSnapshot): List<Long> = snapshot.values.single {
        it.ownerInstanceId == snapshot.rootInstanceId && it.name == "goals"
    }.listItemIds.toList()
    private fun title(snapshot: NativeViewModelSnapshot, id: Long): String = snapshot.values.single {
        it.ownerInstanceId == id && it.name == "title"
    }.bytesValue.decodeToString()
    private fun roundTrip(snapshot: ExperienceRunSnapshot): ExperienceRunSnapshot = ExperienceRunSnapshot(
        Json.parseToJsonElement(snapshot.fields.toString()).jsonArray,
        snapshot.lists?.let { ExperienceRunListSnapshot.decode(Json.parseToJsonElement(it.encode().toString())) })

    @Test fun publishedGoalsRestoreOrderAndBothScreenBindings() = runBlocking {
        val fixture = fixture("forms-saves/goals")
        val run = ExperienceRunValues()
        val checkpoint = try {
            run.lane.call {
                val native = run.prepare(fixture.bytes, fixture.descriptor, emptyMap())
                val root = checkNotNull(native.values)
                val before = root.nativeSnapshot()
                val schema = before.instances.single { it.id == ids(before)[0] }.schemaIndex.toInt()
                val added = native.file.newSchemaViewModel(schema)
                try {
                    added.setValue("title", NuxieViewModelScalarValue.StringValue("Sleep"))
                    root.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.LIST_MOVE,
                        "goals", index = 1, secondIndex = 0)))
                    root.insertListItem("goals", 1, added)
                } finally { added.close() }
            }
            roundTrip(checkNotNull(run.snapshot()))
        } finally { run.retire() }
        val expected = Json.parseToJsonElement("""[{"title":"Walk"},{"title":"Sleep"},{"title":"Read"}]""")
        assertEquals(expected, checkpoint.journeyValues["goals"])
        val restored = ExperienceRunValues(checkpoint)
        try {
            restored.lane.call {
                val native = restored.prepare(fixture.bytes, fixture.descriptor, emptyMap())
                val root = checkNotNull(native.values)
                val sharedIds = ids(root.nativeSnapshot())
                assertEquals(listOf("Walk", "Sleep", "Read"), sharedIds.map { title(root.nativeSnapshot(), it) })
                for (name in listOf("goals", "quiet")) {
                    val screen = checkNotNull(native.file.newArtboard(name))
                    try {
                        screen.bindDefaultViewModel("Runtime $name scr_screens_s$name")
                        assertTrue(screen.linkDefaultViewModel("experience", root))
                        val snapshot = checkNotNull(screen.defaultViewModelSnapshot())
                        assertTrue(snapshot.containsInstance(root.nativeSnapshot().rootInstanceId))
                        for ((index, id) in sharedIds.withIndex()) {
                            assertTrue(snapshot.containsInstance(id))
                            assertEquals(listOf("Walk", "Sleep", "Read")[index],
                                snapshot.resolveNativeString("title", null, id))
                        }
                    } finally { screen.close() }
                }
            }
            assertEquals(expected, restored.journeyValues()["goals"])
        } finally { restored.retire() }
    }

    @Test fun repeatedRowsStayAliasedAndEqualRowsStayDistinct() = runBlocking {
        val fixture = fixture("forms-saves/goals")
        val run = ExperienceRunValues()
        val checkpoint = try {
            run.lane.call {
                val native = run.prepare(fixture.bytes, fixture.descriptor, emptyMap())
                val root = checkNotNull(native.values)
                val before = root.nativeSnapshot()
                val schema = before.instances.single { it.id == ids(before)[0] }.schemaIndex.toInt()
                val repeated = native.file.newSchemaViewModel(schema)
                try {
                    val distinct = native.file.newSchemaViewModel(schema)
                    try {
                        repeated.setValue("title", NuxieViewModelScalarValue.StringValue("Same"))
                        distinct.setValue("title", NuxieViewModelScalarValue.StringValue("Same"))
                        root.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.LIST_CLEAR, "goals")))
                        root.insertListItem("goals", 0, repeated)
                        root.insertListItem("goals", 1, distinct)
                        root.insertListItem("goals", 2, repeated)
                    } finally { distinct.close() }
                } finally { repeated.close() }
            }
            roundTrip(checkNotNull(run.snapshot()))
        } finally { run.retire() }
        val restored = ExperienceRunValues(checkpoint)
        try {
            restored.lane.call {
                val root = checkNotNull(restored.prepare(fixture.bytes, fixture.descriptor, emptyMap()).values)
                val result = ids(root.nativeSnapshot())
                assertEquals(3, result.size)
                assertEquals(result[0], result[2])
                assertNotEquals(result[0], result[1])
            }
        } finally { restored.retire() }
    }

    @Test fun removedSelectedRowsKeepIdentityAndEditsOffRemainingRows() = runBlocking {
        val fixture = fixture("checkpoint-aliases")
        for (addedDuringRun in listOf(false, true)) {
            val run = ExperienceRunValues()
            val checkpoint = try {
                run.lane.call {
                    val native = run.prepare(fixture.bytes, fixture.descriptor, emptyMap())
                    val root = checkNotNull(native.values)
                    val before = root.nativeSnapshot()
                    val old = ids(before)
                    val selected = if (addedDuringRun) native.file.newSchemaViewModel(
                        before.instances.single { it.id == old[0] }.schemaIndex.toInt())
                        else root.acquireListItem("goals", 1, old[1])
                    try {
                        if (addedDuringRun) {
                            selected.setValue("title", NuxieViewModelScalarValue.StringValue("Added"))
                            root.insertListItem("goals", 2, selected)
                        }
                        root.restoreReference("selected", selected)
                        root.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.LIST_REMOVE,
                            "goals", index = if (addedDuringRun) 2 else 1)))
                    } finally { selected.close() }
                }
                roundTrip(checkNotNull(run.snapshot()))
            } finally { run.retire() }
            val restored = ExperienceRunValues(checkpoint)
            try {
                restored.lane.call {
                    val root = checkNotNull(restored.prepare(fixture.bytes, fixture.descriptor, emptyMap()).values)
                    val before = root.nativeSnapshot()
                    val remaining = ids(before)
                    val selected = before.values.single { it.ownerInstanceId == before.rootInstanceId && it.name == "selected" }.referencedInstanceId
                    assertEquals(if (addedDuringRun) 2 else 1, remaining.size)
                    assertFalse(selected in remaining)
                    assertEquals(if (addedDuringRun) "Added" else "B", title(before, selected))
                    root.setValue("selected/title", NuxieViewModelScalarValue.StringValue("Detached edit"))
                    assertEquals("A", title(root.nativeSnapshot(), remaining[0]))
                }
            } finally { restored.retire() }
        }
    }

    @Test fun editedAuthoredRowsRetainIdentityAndExtraRowsAreRemoved() = runBlocking {
        val fixture = fixture("forms-saves/goals")
        val run = ExperienceRunValues()
        val checkpoint = try {
            run.lane.call {
                val root = checkNotNull(run.prepare(fixture.bytes, fixture.descriptor, emptyMap()).values)
                val old = ids(root.nativeSnapshot())
                val child = root.acquireListItem("goals", 0, old[0])
                try { child.setValue("title", NuxieViewModelScalarValue.StringValue("Edited Read")) }
                finally { child.close() }
                root.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.LIST_MOVE,
                    "goals", index = 0, secondIndex = 1)))
            }
            roundTrip(checkNotNull(run.snapshot()))
        } finally { run.retire() }
        val fresh = ExperienceRunValues()
        try {
            fresh.lane.call {
                val native = fresh.prepare(fixture.bytes, fixture.descriptor, emptyMap())
                val root = checkNotNull(native.values)
                val before = root.nativeSnapshot()
                val originalIds = ids(before)
                val extra = native.file.newSchemaViewModel(before.instances.single { it.id == originalIds[0] }.schemaIndex.toInt())
                try {
                    extra.setValue("title", NuxieViewModelScalarValue.StringValue("Discard"))
                    root.insertListItem("goals", 2, extra)
                } finally { extra.close() }
                checkNotNull(checkpoint.lists).restore(native.file, root)
                val restored = root.nativeSnapshot()
                assertEquals(listOf(originalIds[1], originalIds[0]), ids(restored))
                assertEquals("Edited Read", title(restored, originalIds[0]))
            }
        } finally { fresh.retire() }
    }

    @Test fun selectedReferenceKeepsTheListRowAlias() = runBlocking {
        val fixture = fixture("checkpoint-aliases")
        val run = ExperienceRunValues()
        val checkpoint = try {
            run.lane.call {
                val root = checkNotNull(run.prepare(fixture.bytes, fixture.descriptor, emptyMap()).values)
                val old = ids(root.nativeSnapshot())
                val selected = root.acquireListItem("goals", 1, old[1])
                try { root.restoreReference("selected", selected) } finally { selected.close() }
            }
            roundTrip(checkNotNull(run.snapshot()))
        } finally { run.retire() }
        val restored = ExperienceRunValues(checkpoint)
        try {
            restored.lane.call {
                val root = checkNotNull(restored.prepare(fixture.bytes, fixture.descriptor, emptyMap()).values)
                val before = root.nativeSnapshot()
                val rows = ids(before)
                assertEquals(rows[1], before.values.single {
                    it.ownerInstanceId == before.rootInstanceId && it.name == "selected"
                }.referencedInstanceId)
                root.setValue("selected/title", NuxieViewModelScalarValue.StringValue("Edited through selected"))
                val after = root.nativeSnapshot()
                assertEquals("A", title(after, rows[0]))
                assertEquals("Edited through selected", title(after, rows[1]))
            }
        } finally { restored.retire() }
    }

    @Test fun duplicateOriginsCannotCollapseDistinctRows() = runBlocking {
        val fixture = fixture("forms-saves/goals")
        val run = ExperienceRunValues()
        try {
            run.lane.call { run.prepare(fixture.bytes, fixture.descriptor, emptyMap()) }
            val captured = checkNotNull(checkNotNull(run.snapshot()).lists)
            val nodes = captured.nodes.toMutableList()
            assertEquals(3, nodes.size)
            nodes[2] = nodes[2].copy(origin = nodes[1].origin)
            run.lane.call {
                val native = run.prepare(fixture.bytes, fixture.descriptor, emptyMap())
                val root = checkNotNull(native.values)
                val before = root.nativeSnapshot()
                try {
                    ExperienceRunListSnapshot(nodes).restore(native.file, root)
                    fail("Distinct nodes must not collapse onto one authored row")
                } catch (error: IllegalArgumentException) {
                    assertEquals("Distinct rows must retain distinct identities", error.message)
                }
                val after = root.nativeSnapshot()
                assertEquals(ids(before), ids(after))
                assertEquals(listOf("Read", "Walk"), ids(after).map { title(after, it) })
            }
        } finally { run.retire() }
    }

    @Test fun emptyListRestoresAndCanBeCheckpointedAgain() = runBlocking {
        val fixture = fixture("forms-saves/goals")
        val run = ExperienceRunValues()
        val empty = try {
            run.lane.call {
                val root = checkNotNull(run.prepare(fixture.bytes, fixture.descriptor, emptyMap()).values)
                root.restoreWrites(listOf(NativeViewModelWrite(NuxieViewModelMutationKind.LIST_CLEAR, "goals")))
            }
            roundTrip(checkNotNull(run.snapshot()))
        } finally { run.retire() }
        val restored = ExperienceRunValues(empty)
        val again = try {
            restored.lane.call { restored.prepare(fixture.bytes, fixture.descriptor, emptyMap()) }
            assertEquals(JsonArray(emptyList()), restored.journeyValues()["goals"])
            roundTrip(checkNotNull(restored.snapshot()))
        } finally { restored.retire() }
        val twice = ExperienceRunValues(again)
        try {
            twice.lane.call { twice.prepare(fixture.bytes, fixture.descriptor, emptyMap()) }
            assertEquals(JsonArray(emptyList()), twice.journeyValues()["goals"])
        } finally { twice.retire() }
        val separate = ExperienceRunValues()
        try {
            separate.lane.call { separate.prepare(fixture.bytes, fixture.descriptor, emptyMap()) }
            assertEquals(Json.parseToJsonElement("""[{"title":"Read"},{"title":"Walk"}]"""), separate.journeyValues()["goals"])
        } finally { separate.retire() }
    }
}
