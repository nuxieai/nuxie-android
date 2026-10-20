package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceResponseSaveRequestTest {
    private val descriptor = Json.parseToJsonElement("""{"responses":{"feedback":{"model":"Responses:feedback","fields":[{"key":"stars","type":"number"}]}}}""").jsonObject
    private val catalog = NuxieViewModelCatalog(listOf(
        NuxieViewModelCatalog.Schema(0, "Experience", 0..0, IntRange.EMPTY, null, false),
        NuxieViewModelCatalog.Schema(1, "Responses:feedback", 1..2, IntRange.EMPTY, null, false),
    ), emptyList(), emptyList())
    private fun snapshot() = NativeViewModelSnapshot(1, arrayOf(
        NativeViewModelSnapshotInstance(1, 0), NativeViewModelSnapshotInstance(2, 1),
    ), arrayOf(
        NativeViewModelSnapshotValue(1, 0, "responses:feedback", 9, byteArrayOf(), 2),
        NativeViewModelSnapshotValue(2, 0, "stars", 2, byteArrayOf(), 0, numberValue = 4f),
        NativeViewModelSnapshotValue(2, 1, "isset:stars", 3, byteArrayOf(), 0, boolValue = true),
    ))
    private fun field(name: String, value: String) = NuxieRuntimeEventProperty(name,
        NuxieRuntimeEventPropertyValue.Bytes(value.encodeToByteArray()))
    private fun event(fields: List<NuxieRuntimeEventProperty>) = NuxieRuntimeEvent(0, 0,
        ExperienceResponseSaveRequest.EVENT, "", "", 0f, fields)

    @Test fun `one malformed save does not discard its valid frame sibling`() {
        val valid = event(listOf(field("form", "feedback")))
        val malformed = valid.copy(properties = emptyList())
        val command = NuxieHostCommand(ExperienceResponseSaveRequest.EVENT,
            NuxieHostValue.Object(listOf(NuxieHostValue.Object.Field("form", NuxieHostValue.String("feedback")))))
        val outcome = NuxiePlayerStepOutcome(false, emptyList(), listOf(malformed, valid),
            listOf(command.copy(value = NuxieHostValue.String("invalid")), command), emptyList())
        var rejected = 0
        val captured = ExperienceResponseSaveRequest.captureFrame(outcome, snapshot(), catalog, descriptor) { rejected++ }
        assertEquals(2, rejected)
        assertEquals(listOf("feedback", "feedback"), captured.map { it.form })
        assertTrue(captured.all { it.answers.getValue("stars").jsonPrimitive.double == 4.0 })
    }

    @Test fun `await path remains opaque and malformed saves cannot capture empty answers`() {
        val valid = event(listOf(field("form", "feedback"), field("awaitTrigger", "await/own-trigger")))
        val captured = checkNotNull(ExperienceResponseSaveRequest.capture(valid, snapshot(), catalog, descriptor))
        assertEquals("await/own-trigger", captured.awaitTrigger)
        assertEquals(setOf("stars"), captured.answers.keys)
        assertEquals(4.0, captured.answers.getValue("stars").jsonPrimitive.double, 0.0)
        for (invalid in listOf(
            valid.copy(properties = emptyList()),
            valid.copy(properties = listOf(field("form", "missing"))),
            valid.copy(properties = listOf(field("form", "feedback"), field("form", "feedback"))),
            valid.copy(properties = listOf(field("form", "feedback"), field("awaitTrigger", ""))),
            valid.copy(url = "https://example.test"),
            valid.copy(sourceViewModelInstanceId = 99),
        )) assertThrows(IllegalArgumentException::class.java) {
            ExperienceResponseSaveRequest.capture(invalid, snapshot(), catalog, descriptor)
        }
        assertNull(ExperienceResponseSaveRequest.capture(valid.copy(name = "ordinary"), snapshot(), catalog, descriptor))
        val malformedText = valid.copy(properties = listOf(NuxieRuntimeEventProperty("form",
            NuxieRuntimeEventPropertyValue.Bytes(byteArrayOf(0xff.toByte())))))
        assertThrows(Exception::class.java) { ExperienceResponseSaveRequest.capture(malformedText, snapshot(), catalog, descriptor) }
        val duplicate = NuxieHostCommand(ExperienceResponseSaveRequest.EVENT, NuxieHostValue.Object(listOf(
            NuxieHostValue.Object.Field("form", NuxieHostValue.String("feedback")),
            NuxieHostValue.Object.Field("form", NuxieHostValue.String("feedback")),
        )))
        assertThrows(IllegalArgumentException::class.java) { ExperienceResponseSaveRequest.capture(duplicate, snapshot(), catalog, descriptor) }
    }

    /** The screen's root with its `state`, holding what two awaited values keep. */
    private fun kept() = NativeViewModelSnapshot(1, arrayOf(
        NativeViewModelSnapshotInstance(1, 0), NativeViewModelSnapshotInstance(2, 1), NativeViewModelSnapshotInstance(3, 2),
    ), arrayOf(
        NativeViewModelSnapshotValue(1, 0, "responses:feedback", 9, byteArrayOf(), 2),
        NativeViewModelSnapshotValue(1, 1, "state", 9, byteArrayOf(), 3),
        NativeViewModelSnapshotValue(2, 0, "stars", 2, byteArrayOf(), 0, numberValue = 4f),
        NativeViewModelSnapshotValue(2, 1, "isset:stars", 3, byteArrayOf(), 0, boolValue = true),
        NativeViewModelSnapshotValue(3, 0, "await:rater:rated:1:stars", 2, byteArrayOf(), 0, numberValue = 4f),
        NativeViewModelSnapshotValue(3, 1, "await:rater:rated:1:mood", 5, byteArrayOf(), 0, integerValue = 1),
    ))

    @Test fun `an awaited save keeps the values it names, and writes them back before its trigger`() {
        val trigger = field("awaitTrigger", "state/save:rater:rated:1")
        val valid = event(listOf(field("form", "feedback"), trigger,
            field("keep:stars", "state/await:rater:rated:1:stars"), field("keep:mood", "state/await:rater:rated:1:mood")))
        val captured = checkNotNull(ExperienceResponseSaveRequest.capture(valid, kept(), catalog, descriptor))
        assertEquals(listOf("state/await:rater:rated:1:stars" to 4.0, "state/await:rater:rated:1:mood" to 1.0),
            captured.kept.map { it.jsonObject.getValue("path").jsonPrimitive.content to it.jsonObject.getValue("value").jsonPrimitive.double })
        val writes = ExperienceRunSnapshot(captured.kept).writes()
        assertEquals(listOf(NuxieViewModelMutationKind.SET_NUMBER, NuxieViewModelMutationKind.SET_ENUM), writes.map { it.kind })
        assertEquals(4f, writes[0].numberValue)
        assertEquals(1L, writes[1].integerValue)
        assertTrue(checkNotNull(ExperienceResponseSaveRequest.capture(event(listOf(field("form", "feedback"), trigger)),
            kept(), catalog, descriptor)).kept.isEmpty())
        // A kept value lives in the screen's state and needs an await to keep it for.
        for (invalid in listOf(
            listOf(field("form", "feedback"), trigger, field("keep:stars", "responses:feedback/stars")),
            listOf(field("form", "feedback"), trigger, field("keep:stars", "state/await:one/stars")),
            listOf(field("form", "feedback"), trigger, field("keep:stars", "state/await:absent:stars")),
            listOf(field("form", "feedback"), field("keep:stars", "state/await:rater:rated:1:stars")),
        )) assertThrows(IllegalArgumentException::class.java) {
            ExperienceResponseSaveRequest.capture(event(invalid), kept(), catalog, descriptor)
        }
    }
}
