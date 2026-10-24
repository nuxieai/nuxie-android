package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.app.Activity
import android.view.View
import android.widget.EditText
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceNativeTextInputOverlayTest {
    private class Pending(val target: ExperienceTextFieldTarget, val write: ExperienceSemanticTextDraft.Write,
        val done: (ExperienceSemanticTextDraft.Outcome) -> Unit)

    @Test fun `repeated native inputs read their own values without initial writes`() = withOverlay { overlay, writes, _ ->
        overlay.updateNativeFields(listOf(field(10, "alpha"), field(20, "beta")))
        assertEquals("alpha", editor(overlay, 10).text.toString())
        assertEquals("beta", editor(overlay, 20).text.toString())
        assertTrue(writes.isEmpty())
        assertEquals(setOf(10L, 20L), overlay.semanticViews().keys)
        assertEquals(View.VISIBLE, editor(overlay, 10).visibility)
    }

    @Test fun `native edits are serialized per occurrence and accepted notifications are isolated`() = withOverlay { overlay, writes, changes ->
        overlay.updateNativeFields(listOf(field(10, "alpha"), field(20, "beta")))
        editor(overlay, 10).setText("first")
        editor(overlay, 10).setText("latest")
        assertEquals(1, writes.size)
        assertTrue(changes.isEmpty())
        writes[0].done(ExperienceSemanticTextDraft.Outcome.ACCEPTED)
        assertEquals(2, writes.size)
        assertEquals("latest", writes[1].write.text)
        writes[1].done(ExperienceSemanticTextDraft.Outcome.ACCEPTED)
        assertEquals(listOf(10L to "latest"), changes)
        assertEquals("beta", editor(overlay, 20).text.toString())
    }

    @Test fun `replacement owner cannot inherit an old draft or receive its late completion`() = withOverlay { overlay, writes, changes ->
        overlay.updateNativeFields(listOf(field(10, "alpha")))
        val previous = editor(overlay, 10)
        previous.setText("pending")
        overlay.updateNativeFields(listOf(field(10, "replacement", ownerId = 999)))
        val replacement = editor(overlay, 10)
        assertNotSame(previous, replacement)
        assertEquals("replacement", replacement.text.toString())
        writes.single().done(ExperienceSemanticTextDraft.Outcome.ACCEPTED)
        assertTrue(changes.isEmpty())
        assertEquals("replacement", replacement.text.toString())
        assertFalse(previous.isEnabled)
    }

    @Test fun `rejection restores accepted text and next valid edit succeeds`() = withOverlay { overlay, writes, changes ->
        overlay.updateNativeFields(listOf(field(10, "valid")))
        editor(overlay, 10).setText("invalid")
        writes.single().done(ExperienceSemanticTextDraft.Outcome.REJECTED)
        assertEquals("valid", editor(overlay, 10).text.toString())
        assertTrue(changes.isEmpty())
        overlay.updateNativeFields(listOf(field(10, "valid", captureId = 2)))
        editor(overlay, 10).setText("recovered")
        writes.last().done(ExperienceSemanticTextDraft.Outcome.ACCEPTED)
        assertEquals(listOf(10L to "recovered"), changes)
    }

    @Test fun `native secure mismatch is rejected before creating a plaintext editor`() = withOverlay { overlay, writes, _ ->
        assertThrows(IllegalStateException::class.java) {
            overlay.updateNativeFields(listOf(field(10, "secret", obscured = true)))
        }
        assertNull(overlay.findViewWithTag<EditText>("nuxie-text-input-name-10"))
        assertTrue(writes.isEmpty())
    }

    @Test fun `removal fences callbacks and removes the semantic editor`() = withOverlay { overlay, writes, changes ->
        overlay.updateNativeFields(listOf(field(10, "alpha")))
        val old = editor(overlay, 10)
        old.setText("pending")
        overlay.updateNativeFields(emptyList())
        writes.single().done(ExperienceSemanticTextDraft.Outcome.ACCEPTED)
        old.setText("late")
        assertEquals(1, writes.size)
        assertTrue(changes.isEmpty())
        assertTrue(overlay.semanticViews().isEmpty())
    }

    @Test fun `page step preserves editor identity and focus while fencing native edits`() = withOverlay { overlay, writes, _ ->
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123)))
        val original = editor(overlay, 10)
        assertTrue(original.requestFocus())
        overlay.setInputEnabled(false, preserveFocus = true)
        assertSame(original, editor(overlay, 10))
        assertTrue(original.hasFocus())
        assertEquals(View.VISIBLE, original.visibility)
        assertTrue(writes.isEmpty())
        overlay.setInputEnabled(true, preserveFocus = true)
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123, captureId = 2)))
        assertSame(original, editor(overlay, 10))
        original.setText("after landing")
        assertEquals("after landing", writes.single().write.text)
    }

    @Test fun `Screen declarations change and restore without replacing the focused native field`() = withOverlay { overlay, _, _ ->
        val baseline = textInputDescriptor()
        val render = baseline.getValue("render").jsonObject
        val record = render.getValue("textInputs").jsonArray.single().jsonObject
        fun record(screen: String, limit: Int, keyboard: String, secure: Boolean, placeholder: String) =
            JsonObject(record + mapOf("screenId" to JsonPrimitive(screen), "maxLength" to JsonPrimitive(limit),
                "keyboardType" to JsonPrimitive(keyboard), "secureTextEntry" to JsonPrimitive(secure),
                "placeholder" to JsonPrimitive(placeholder), "responseFieldKey" to JsonPrimitive(screen),
                "actionEvent" to JsonPrimitive(if (secure) "return" else "editing-ended")))
        val descriptor = JsonObject(baseline + ("render" to JsonObject(render + ("textInputs" to JsonArray(listOf(
            record("first", 20, "email", false, "First"), record("second", 4, "number", true, "Second")))))))
        val first = ExperienceTextInput.forScreen(descriptor, "first").single()
        val second = ExperienceTextInput.forScreen(descriptor, "second").single()
        assertEquals("second", second.responseField)
        assertEquals(ExperienceSemanticTextDraft.EventKind.RETURN, second.actionEvent)
        overlay.updateDeclarations(listOf(first))
        overlay.updateNativeFields(listOf(field(10, "abc")))
        val original = editor(overlay, 10)
        assertTrue(original.requestFocus())
        fun navigate(input: ExperienceTextInput, capture: Long) {
            overlay.setInputEnabled(false, preserveFocus = true)
            overlay.updateDeclarations(listOf(input))
            overlay.updateNativeFields(listOf(field(10, "abc", captureId = capture, obscured = input.secure)))
            overlay.setInputEnabled(true, preserveFocus = true)
            assertSame(original, editor(overlay, 10))
            assertTrue(original.hasFocus())
            assertEquals(input.placeholder, original.hint.toString())
        }
        navigate(second, 2)
        assertEquals(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            original.inputType)
        assertFalse(original.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "12345") }))
        navigate(first, 3)
        assertEquals(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            original.inputType)
        assertTrue(original.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
            android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "12345") }))
    }

    private fun withOverlay(block: (ExperienceTextInputOverlay, MutableList<Pending>, MutableList<Pair<Long, String>>) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val writes = mutableListOf<Pending>()
        val changes = mutableListOf<Pair<Long, String>>()
        val input = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single()
            .copy(textInputName = "answer", maxLength = null)
        val overlay = ExperienceTextInputOverlay(controller.get(), ExperienceArtboardSize(200f, 100f),
            listOf(input), emptyMap(), nativeWriter = { target, write, done -> writes += Pending(target, write, done) },
            nativeNotification = { target, text -> changes += target.nodeId to text })
        try {
            overlay.layout(0, 0, 400, 200)
            block(overlay, writes, changes)
        } finally {
            overlay.close()
            controller.pause().stop().destroy()
        }
    }

    private fun editor(overlay: ExperienceTextInputOverlay, id: Long): EditText =
        requireNotNull(overlay.findViewWithTag("nuxie-text-input-name-$id"))

    private fun field(id: Long, text: String, ownerId: Long = id, captureId: Long = 1, obscured: Boolean = false): ExperienceNativeTextField {
        val transform = NuxieTextRunGeometry.Transform(1f, 0f, 0f, 1f, 0f, 0f)
        val bounds = NuxieTextRunGeometry.Bounds(0f, 0f, 100f, 40f)
        return ExperienceNativeTextField(ExperienceTextFieldTarget("name", id), ownerId, captureId,
            NativeSemanticNode(id, -1, 0, NativeSemanticRole.TEXT_FIELD, 0, 0, 0, 0,
                0f, 0f, 100f, 40f, "Name", "", ""),
            NuxieTextInputGeometry(1u, transform, bounds, NuxieTextRunGeometry.Layout(transform, bounds),
                20f, obscured, false), text,
            NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(ownerId,
                arrayOf(NativeViewModelSnapshotInstance(ownerId, 0)), emptyArray())))
    }
}
