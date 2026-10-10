package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue
import ai.nuxie.sdk.runtime.NuxieViewModelMutationKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceRunSnapshotTest {
    @Test fun `null reference beside a list is not a checkpoint row`() {
        val vector = Json.parseToJsonElement(java.io.File("../fixtures/runtime/checkpoint-aliases/null-reference.json").readText()).jsonObject
        val root = vector.getValue("root").jsonPrimitive.long
        val row = vector.getValue("row").jsonPrimitive.long
        val title = vector.getValue("title").jsonPrimitive.content
        val native = NativeViewModelSnapshot(root, arrayOf(
            NativeViewModelSnapshotInstance(root, 0), NativeViewModelSnapshotInstance(row, 1)), arrayOf(
            NativeViewModelSnapshotValue(root, 0, "goals", ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind.LIST.nativeValue, byteArrayOf(), 0, listItemIds = longArrayOf(row)),
            NativeViewModelSnapshotValue(root, 1, "optional", 9, byteArrayOf(), vector.getValue("unsetReference").jsonPrimitive.long),
            NativeViewModelSnapshotValue(row, 0, "title", 1, title.encodeToByteArray(), 0),
        ))
        val captured = ExperienceRunSnapshot.capture(native,
            ExperienceRunListSnapshot.authoredOrigins(native), setOf(root, row))
        val graph = checkNotNull(captured.lists)
        val decoded = ExperienceRunListSnapshot.decode(Json.parseToJsonElement(graph.encode().toString()))
        assertEquals(vector.getValue("expectedNodeCount").jsonPrimitive.int, decoded.nodes.size)
        assertTrue(decoded.nodes.all { it.schema >= 0 && it.references.isEmpty() })
        assertEquals(buildJsonArray { addJsonObject { put("title", title) } }, decoded.journeyLists["goals"])
    }

    @Test fun `deep leaves and presence markers restore as typed paths`() {
        val native = NativeViewModelSnapshot(101, arrayOf(
            NativeViewModelSnapshotInstance(101, 0), NativeViewModelSnapshotInstance(202, 1),
            NativeViewModelSnapshotInstance(303, 2)), arrayOf(
            NativeViewModelSnapshotValue(101, 0, "profile", 9, byteArrayOf(), 202),
            NativeViewModelSnapshotValue(202, 0, "minutes", 2, byteArrayOf(), 0, numberValue = 20f),
            NativeViewModelSnapshotValue(202, 1, "isset:minutes", 3, byteArrayOf(), 0, boolValue = true),
            NativeViewModelSnapshotValue(202, 2, "isset:empty", 3, byteArrayOf(), 0, boolValue = false),
            NativeViewModelSnapshotValue(202, 3, "settings", 9, byteArrayOf(), 303),
            NativeViewModelSnapshotValue(303, 0, "day", 1, "2026-10-10".encodeToByteArray(), 0),
        ))
        val captured = ExperienceRunSnapshot.capture(native)
        val restored = ExperienceRunSnapshot(Json.parseToJsonElement(captured.fields.toString()).jsonArray)
        assertEquals(Json.parseToJsonElement("""[
            {"path":"profile/minutes","kind":2,"value":20.0},
            {"path":"profile/isset:minutes","kind":3,"value":true},
            {"path":"profile/isset:empty","kind":3,"value":false},
            {"path":"profile/settings/day","kind":1,"value":"2026-10-10"}
        ]"""), restored.fields)
        val writes = restored.writes()
        assertEquals(listOf("profile/minutes", "profile/isset:minutes", "profile/isset:empty", "profile/settings/day"), writes.map { it.path })
        assertEquals(listOf(NuxieViewModelMutationKind.SET_NUMBER, NuxieViewModelMutationKind.SET_BOOLEAN,
            NuxieViewModelMutationKind.SET_BOOLEAN, NuxieViewModelMutationKind.SET_STRING), writes.map { it.kind })
        assertEquals(20f, writes[0].numberValue)
        assertTrue(writes[1].boolValue)
        assertFalse(writes[2].boolValue)
        assertArrayEquals("2026-10-10".encodeToByteArray(), writes[3].bytesValue)
        assertTrue(writes.all { it.relatedViewModel == 0L })
    }

    @Test fun `nested native values survive serialization without native identities`() {
        val native = NativeViewModelSnapshot(10, arrayOf(
            NativeViewModelSnapshotInstance(10, 0), NativeViewModelSnapshotInstance(20, 1)), arrayOf(
            NativeViewModelSnapshotValue(10, 0, "trip", 9, byteArrayOf(), 20),
            NativeViewModelSnapshotValue(20, 0, "name", 1, "Italy".encodeToByteArray(), 0),
            NativeViewModelSnapshotValue(20, 1, "days", 2, byteArrayOf(), 0, numberValue = 30f),
            NativeViewModelSnapshotValue(20, 2, "reminder", 3, byteArrayOf(), 0, boolValue = false),
            NativeViewModelSnapshotValue(20, 3, "parent", 9, byteArrayOf(), 10),
        ))
        val snapshot = ExperienceRunSnapshot.capture(native)
        val restored = ExperienceRunSnapshot(Json.parseToJsonElement(snapshot.fields.toString()).jsonArray)
        assertEquals(Json.parseToJsonElement("""{"trip/name":"Italy","trip/days":30.0,"trip/reminder":false}"""), restored.journeyValues)
        val writes = restored.writes()
        assertEquals(listOf("trip/name", "trip/days", "trip/reminder"), writes.map { it.path })
        assertEquals(listOf(NuxieViewModelMutationKind.SET_STRING, NuxieViewModelMutationKind.SET_NUMBER,
            NuxieViewModelMutationKind.SET_BOOLEAN), writes.map { it.kind })
        assertArrayEquals("Italy".encodeToByteArray(), writes[0].bytesValue)
        assertEquals(30f, writes[1].numberValue)
        assertFalse(writes[2].boolValue)
        assertTrue(writes.all { it.relatedViewModel == 0L })
    }
}
