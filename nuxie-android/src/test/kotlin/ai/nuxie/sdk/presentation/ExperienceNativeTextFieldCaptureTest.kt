package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceNativeTextFieldCaptureTest {
    private val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single()
        .copy(editableValueName = "editable")

    @Test fun `repeated fields carry exact native owners regardless of node order`() {
        val result = ExperienceNativeTextFieldCapture.read(listOf(input), listOf(node(20), node(10)), 9,
            geometry = { _, _ -> geometry() },
            readText = { id, name -> assertEquals("editable", name); "value-$id".encodeToByteArray() },
            readOwner = { id, _ -> owner(id + 100) })
        assertEquals(listOf(20L, 10L), result.map { it.target.nodeId })
        assertEquals(listOf(120L, 110L), result.map { it.ownerId })
        assertEquals(listOf("value-20", "value-10"), result.map { it.text })
        assertTrue(result.all { it.captureId == 9L })
    }

    @Test fun `missing endpoints do not borrow another field or read any value`() {
        val result = ExperienceNativeTextFieldCapture.read(listOf(input), listOf(node(10)), 1,
            geometry = { _, _ -> null }, readText = { _, _ -> error("must not read") },
            readOwner = { _, _ -> error("must not borrow owner") })
        assertTrue(result.isEmpty())
    }

    @Test fun `ambiguous declarations are rejected before reading a value`() {
        assertThrows(IllegalStateException::class.java) {
            ExperienceNativeTextFieldCapture.read(listOf(input, input.copy(id = "other")), listOf(node(10)), 1,
                geometry = { _, _ -> geometry() }, readText = { _, _ -> error("must not read") },
                readOwner = { _, _ -> error("must not read") })
        }
    }

    @Test fun `secure mismatch is rejected before execution-only value access`() {
        var reads = 0
        assertThrows(IllegalStateException::class.java) {
            ExperienceNativeTextFieldCapture.read(listOf(input), listOf(node(10)), 1,
                geometry = { _, _ -> geometry(obscured = true) },
                readText = { _, _ -> reads++; "secret".encodeToByteArray() },
                readOwner = { _, _ -> owner(10) })
        }
        assertEquals(0, reads)
    }

    @Test fun `secure values stay out of the diagnostic semantic node and object summary`() {
        val result = ExperienceNativeTextFieldCapture.read(listOf(input.copy(secure = true)), listOf(node(10)), 1,
            geometry = { _, _ -> geometry(obscured = true) },
            readText = { _, _ -> "private-input".encodeToByteArray() },
            readOwner = { _, _ -> owner(10) }).single()
        assertEquals("private-input", result.text)
        assertEquals("", result.node.value)
        assertFalse(result.toString().contains("private-input"))
    }

    @Test fun `invalid UTF8 fails without replacement characters or value in error`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ExperienceNativeTextFieldCapture.read(listOf(input), listOf(node(10)), 1,
                geometry = { _, _ -> geometry() }, readText = { _, _ -> byteArrayOf(0xc3.toByte(), 0x28) },
                readOwner = { _, _ -> error("must not read owner after invalid text") })
        }
        assertEquals("Native input contains invalid UTF-8", error.message)
    }

    private fun node(id: Long) = NativeSemanticNode(id, -1, 0, NativeSemanticRole.TEXT_FIELD,
        0, 0, 0, 0, 0f, 0f, 100f, 40f, "Field", "", "")
    private fun owner(id: Long) = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(id,
        arrayOf(NativeViewModelSnapshotInstance(id, 0)), emptyArray()))
    private fun geometry(obscured: Boolean = false): NuxieTextInputGeometry {
        val transform = NuxieTextRunGeometry.Transform(1f, 0f, 0f, 1f, 0f, 0f)
        val bounds = NuxieTextRunGeometry.Bounds(0f, 0f, 100f, 40f)
        return NuxieTextInputGeometry(1u, transform, bounds, NuxieTextRunGeometry.Layout(transform, bounds),
            20f, obscured, false)
    }
}
