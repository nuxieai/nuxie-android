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
        original.setSelection(1, 3)
        val connection = checkNotNull(original.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
        overlay.setInputEnabled(false, preserveFocus = true)
        assertFalse("An old IME connection must not edit during the page step", connection.commitText("stale", 1))
        assertFalse(connection.setComposingText("stale", 1))
        assertFalse(connection.deleteSurroundingText(1, 1))
        assertFalse(connection.finishComposingText())
        assertEquals("kept", original.text.toString())
        assertEquals(1, original.selectionStart)
        assertEquals(3, original.selectionEnd)
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

    @Test fun `covering a page ends editing, and its reveal asks for no focus`() = withOverlay { overlay, _, _ ->
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123)))
        val original = editor(overlay, 10)
        assertTrue(original.requestFocus())
        // A Journey sheet's move turns input off with focus kept, then the sheet covers its source.
        overlay.setInputEnabled(false, preserveFocus = true)
        overlay.endEditing()
        assertFalse("The covered page's field loses focus", original.hasFocus())
        assertFalse("A covered page's editors are disabled", original.isEnabled)
        assertSame("The binding stays", original, editor(overlay, 10))
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123, captureId = 2)))
        assertFalse("Still disabled while input is off", original.isEnabled)
        overlay.setInputEnabled(true)
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123, captureId = 3)))
        assertFalse("Nothing asks for focus again at the reveal", original.hasFocus())
        assertTrue(original.isEnabled)
        assertTrue("A tap focuses it", original.requestFocus())
    }

    @Test @org.robolectric.annotation.Config(sdk = [34])
    fun `modern IME methods cannot bypass a page step fence`() = withOverlay { overlay, writes, _ ->
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123)))
        val original = editor(overlay, 10)
        assertTrue(original.requestFocus())
        original.setSelection(1, 3)
        val connection = checkNotNull(original.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
        val attribute = android.view.inputmethod.TextAttribute.Builder().build()
        overlay.setInputEnabled(false, preserveFocus = true)
        assertFalse(connection.commitText("stale", 1, attribute))
        assertFalse(connection.setComposingText("stale", 1, attribute))
        assertFalse(connection.setComposingRegion(0, 4, attribute))
        assertFalse(connection.replaceText(0, 4, "stale", 1, attribute))
        assertFalse(connection.performContextMenuAction(android.R.id.cut))
        assertEquals("kept", original.text.toString())
        assertEquals(1, original.selectionStart)
        assertEquals(3, original.selectionEnd)
        assertTrue(writes.isEmpty())
        overlay.setInputEnabled(true, preserveFocus = true)
        assertTrue(connection.commitText("landed", 1, attribute))
        assertEquals("klandedt", writes.single().write.text)
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

    @Test fun `a field a move carries to another declaration keeps its editor and focus and edits that declaration`() = withOverlay { overlay, writes, _ ->
        val first = ExperienceTextInput.forScreen(textInputDescriptor(), "survey").single().copy(maxLength = null)
        for (multiline in listOf(false, true)) {
            val second = first.copy(id = "email", placeholder = "Email", multiline = multiline)
            overlay.updateDeclarations(listOf(first))
            overlay.updateNativeFields(listOf(field(10, "abc")))
            val original = editor(overlay, 10)
            assertTrue(original.requestFocus())
            overlay.setInputEnabled(false, preserveFocus = true)
            overlay.updateDeclarations(listOf(second))
            overlay.updateNativeFields(listOf(field(10, "abc", captureId = 2, inputId = "email")))
            overlay.setInputEnabled(true, preserveFocus = true)
            assertSame("The carried field keeps its editor", original,
                overlay.findViewWithTag<EditText>("nuxie-text-input-email-10"))
            assertTrue(original.hasFocus())
            assertEquals("abc", original.text.toString())
            assertEquals("Email", original.hint.toString())
            assertEquals(multiline, original.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
            original.setText("abcd")
            assertEquals(ExperienceTextFieldTarget("email", 10), writes.last().target)
            writes.last().done(ExperienceSemanticTextDraft.Outcome.ACCEPTED)
            overlay.updateNativeFields(emptyList())
            writes.clear()
        }
    }

    @Test fun `a veiled field keeps its focus, its keyboard and its taps`() = withOverlay { overlay, _, _ ->
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123)))
        val original = editor(overlay, 10)
        assertTrue(original.requestFocus())
        overlay.setInputEnabled(false, preserveFocus = true)
        overlay.veil()
        assertEquals("Hidden while the step runs", 0f, original.alpha, 0f)
        assertTrue("Focus stays", original.hasFocus())
        assertEquals(View.VISIBLE, original.visibility)
    }

    @Test fun `a field bound during a step starts veiled`() = withOverlay { overlay, _, _ ->
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123)))
        // A covered page's look-only publication, with input off (C4), can bind a field mid-step.
        overlay.setInputEnabled(false)
        overlay.veil()
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123, captureId = 2),
            field(11, "new", ownerId = 124, captureId = 2)))
        assertEquals(0f, editor(overlay, 11).alpha, 0f)
        assertEquals("A publication with input off leaves the veil on", 0f, editor(overlay, 10).alpha, 0f)
    }

    @Test fun `the veil outlives input returning until the first field publication`() = withOverlay { overlay, _, _ ->
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123)))
        val original = editor(overlay, 10)
        overlay.setInputEnabled(false, preserveFocus = true)
        overlay.veil()
        overlay.setInputEnabled(true, preserveFocus = true)
        assertEquals("Input is back but the field is unplaced", 0f, original.alpha, 0f)
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123, captureId = 2)))
        assertEquals("The first publication after input lifts it", 1f, original.alpha, 0f)
    }

    @Test fun `a tap on a veiled field lands on it`() = withOverlay { overlay, _, _ ->
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123)))
        val original = editor(overlay, 10)
        overlay.setInputEnabled(false)
        overlay.veil()
        overlay.setInputEnabled(true)
        assertFalse(original.hasFocus())
        tap(overlay, original)
        assertTrue("Alpha leaves touch dispatch as it is", original.hasFocus())
    }

    @Test fun `closing ends the veil`() = withOverlay { overlay, _, _ ->
        overlay.updateNativeFields(listOf(field(10, "kept", ownerId = 123)))
        overlay.veil()
        overlay.close()
        assertFalse(privateField<Boolean>(overlay, "veiled"))
    }

    private fun tap(overlay: ExperienceTextInputOverlay, view: View) {
        overlay.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, 400, 200)
        // The view's center, through its own placement transform, in the overlay's coordinates.
        val point = floatArrayOf(view.width / 2f, view.height / 2f)
        view.matrix.mapPoints(point)
        var parent: View = view
        while (parent !== overlay) { point[0] += parent.left; point[1] += parent.top; parent = parent.parent as View }
        val time = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(time, time, action, point[0], point[1], 0)
            overlay.dispatchTouchEvent(event)
            event.recycle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

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

    private fun field(id: Long, text: String, ownerId: Long = id, captureId: Long = 1, obscured: Boolean = false,
        inputId: String = "name"): ExperienceNativeTextField {
        val transform = NuxieTextRunGeometry.Transform(1f, 0f, 0f, 1f, 0f, 0f)
        val bounds = NuxieTextRunGeometry.Bounds(0f, 0f, 100f, 40f)
        return ExperienceNativeTextField(ExperienceTextFieldTarget(inputId, id), ownerId, captureId,
            NativeSemanticNode(id, -1, 0, NativeSemanticRole.TEXT_FIELD, 0, 0, 0, 0,
                0f, 0f, 100f, 40f, "Name", "", ""),
            NuxieTextInputGeometry(1u, transform, bounds, NuxieTextRunGeometry.Layout(transform, bounds),
                20f, obscured, false), text,
            NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(ownerId,
                arrayOf(NativeViewModelSnapshotInstance(ownerId, 0)), emptyArray())))
    }
}
