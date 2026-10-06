package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue
import ai.nuxie.sdk.runtime.NuxieViewModelMutationKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

class ExperienceRunSnapshotTest {
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
