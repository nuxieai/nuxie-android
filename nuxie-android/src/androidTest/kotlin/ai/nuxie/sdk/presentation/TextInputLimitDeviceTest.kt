package ai.nuxie.sdk.presentation

import androidx.test.platform.app.InstrumentationRegistry
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class TextInputLimitDeviceTest {
    @Test
    fun nativeInputConnectionPreservesCompositionThenLimitsCommittedGraphemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, android.R.style.Theme_Material_Light)
            val writes = mutableListOf<String>()
            val input = ExperienceTextInput("name", "headline", "", "answer", null, null,
                false, false, 2, emptyMap(), ExperienceTextInput.Style(
                    "sans-serif", "400", false, 16f, 20f, 0f, 0xff000000.toInt(), "font", null))
            val overlay = ExperienceTextInputOverlay(context, ExperienceArtboardSize(200f, 100f),
                listOf(input), emptyMap(), { _, text, _, done ->
                    writes += text
                    done(Result.success(Unit))
                }, { throw AssertionError(it) })
            try {
                val editor = overlay.getChildAt(0) as EditText
                val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                assertTrue(connection.setComposingText("abc", 1))
                assertEquals("abc", editor.text.toString())
                assertTrue(connection.commitText("👨‍👩‍👧‍👦🇺🇸z", 1))
                assertEquals("👨‍👩‍👧‍👦🇺🇸", editor.text.toString())
                assertEquals("👨‍👩‍👧‍👦🇺🇸", writes.last())
                editor.setText("")
                assertTrue(connection.setComposingText("abc", 1))
                assertTrue(connection.finishComposingText())
                assertEquals("ab", editor.text.toString())
                assertEquals("ab", writes.last())
            } finally { overlay.close() }
        }
    }

    @Test
    fun ordinaryReplacementAdmissionMatchesSharedContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("journeys/planes/text-input-edits.json").use { it.readBytes() }
        val cases = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("cases").jsonArray
        assertEquals(9, cases.size)
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, android.R.style.Theme_Material_Light)
            for (item in cases) {
                val vector = item.jsonObject
                val current = vector.getValue("current").jsonPrimitive.content
                val start = vector.getValue("start").jsonPrimitive.int
                val end = vector.getValue("end").jsonPrimitive.int
                val replacement = vector.getValue("replacement").jsonPrimitive.content
                val maximum = vector["maxLength"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int
                val allowed = vector.getValue("allowed").jsonPrimitive.content == "true"
                val input = ExperienceTextInput("name", "headline", current, "answer", null, null,
                    false, false, maximum, emptyMap(), ExperienceTextInput.Style(
                        "sans-serif", "400", false, 16f, 20f, 0f, 0xff000000.toInt(), "font", null))
                val overlay = ExperienceTextInputOverlay(context, ExperienceArtboardSize(200f, 100f),
                    listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw AssertionError(it) })
                try {
                    val editor = overlay.getChildAt(0) as EditText
                    val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                    editor.setSelection(start, end)
                    assertTrue(connection.commitText(replacement, 1))
                    val expected = if (allowed) current.substring(0, start) + replacement + current.substring(end) else current
                    assertEquals(vector.getValue("name").jsonPrimitive.content, expected, editor.text.toString())
                } finally { overlay.close() }
            }
        }
    }

    @Test
    fun compositionCompletionPreservesUntouchedText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("journeys/planes/text-input-composition-android.json").use { it.readBytes() }
        val fixture = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val cases = fixture.getValue("cases").jsonArray
        val presentation = fixture.getValue("editingPresentation").jsonObject
        assertEquals(8, cases.size)
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, android.R.style.Theme_Material_Light)
            for (item in cases) for (finish in listOf(false, true)) {
                val vector = item.jsonObject
                val current = vector.getValue("current").jsonPrimitive.content
                val start = vector.getValue("start").jsonPrimitive.int
                val end = vector.getValue("end").jsonPrimitive.int
                val replacement = vector.getValue("replacement").jsonPrimitive.content
                val maximum = vector["maxLength"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int
                val input = ExperienceTextInput("name", "headline", current, "answer", null, null,
                    false, false, maximum, emptyMap(), ExperienceTextInput.Style(
                        "sans-serif", "400", false, 16f, 20f, 0f, 0xff000000.toInt(), "font", null))
                var lastWrite: Pair<String, Boolean>? = null
                val overlay = ExperienceTextInputOverlay(context, ExperienceArtboardSize(200f, 100f),
                    listOf(input), emptyMap(), { _, text, commit, done ->
                        lastWrite = text to commit; done(Result.success(Unit))
                    }, { throw AssertionError(it) })
                try {
                    val editor = overlay.getChildAt(0) as EditText
                    editor.visibility = android.view.View.VISIBLE
                    assertTrue(editor.requestFocus())
                    val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                    editor.setSelection(start, end)
                    assertTrue(connection.setComposingText(replacement, 1))
                    assertEquals(current.substring(0, start) + replacement + current.substring(end), editor.text.toString())
                    assertEquals(presentation.getValue("nativeTextVisible").jsonPrimitive.content == "true",
                        editor.currentTextColor ushr 24 != 0)
                    assertEquals(presentation.getValue("rendererText").jsonPrimitive.content to false, lastWrite)
                    if (finish) assertTrue(connection.finishComposingText())
                    else assertTrue(connection.commitText(replacement, 1))
                    assertEquals(vector.getValue("name").jsonPrimitive.content + " finish=" + finish,
                        vector.getValue("expected").jsonPrimitive.content, editor.text.toString())
                    editor.clearFocus()
                    assertEquals(presentation.getValue("restoreRendererOnBlur").jsonPrimitive.content == "true",
                        lastWrite == (vector.getValue("expected").jsonPrimitive.content to true))
                    assertEquals(android.graphics.Color.TRANSPARENT, editor.currentTextColor)
                } finally { overlay.close() }
            }
        }
    }

    @Test
    fun committedTextLimitsPreserveWholeGraphemes() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("journeys/planes/text-input-limits.json").use { it.readBytes() }
        val cases = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val vector = element.jsonObject
            val maximum = vector["maxLength"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int
            val actual = ExperienceTextInputLimit.apply(vector.getValue("text").jsonPrimitive.content, maximum)
            assertArrayEquals(vector.getValue("name").jsonPrimitive.content,
                vector.getValue("expected").jsonPrimitive.content.encodeToByteArray(), actual.encodeToByteArray())
        }
    }
}
