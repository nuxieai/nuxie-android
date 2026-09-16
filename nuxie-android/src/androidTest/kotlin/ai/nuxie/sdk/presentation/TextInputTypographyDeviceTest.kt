package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeViewModelSnapshot
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotInstance
import ai.nuxie.sdk.runtime.NativeViewModelSnapshotValue
import ai.nuxie.sdk.runtime.NuxieViewModelPropertyKind
import ai.nuxie.sdk.runtime.NuxieViewModelSnapshot
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TextInputTypographyDeviceTest {
    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun multilineBaselinesMatchNativeMetricsAndAuthoredHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = JSONObject(instrumentation.context.assets.open("journeys/planes/text-input-typography.json")
            .bufferedReader().use { it.readText() })
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var overlay: ExperienceTextInputOverlay? = null
        try {
            instrumentation.runOnMainSync {
                val cases = fixture.getJSONArray("cases")
                for (index in 0 until cases.length()) {
                    val item = cases.getJSONObject(index)
                    val name = item.getString("name")
                    val text = fixture.getString("text")
                    val containScale = item.getDouble("containScale").toFloat()
                    val geometryScale = item.getDouble("geometryScale").toFloat()
                    val input = ExperienceTextInput("answer", "run", text, "answer", null, null,
                        false, true, null, listOf("x", "y", "width", "height", "rotation", "scaleX", "scaleY")
                            .associate { "${it}Path" to it },
                        ExperienceTextInput.Style("sans-serif", "400", false,
                            item.getDouble("fontSize").toFloat(), item.getDouble("lineHeight").toFloat(),
                            0f, 0xff000000.toInt(), "font", null))
                    var writes = 0
                    val current = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(400f, 400f),
                        listOf(input), emptyMap(), { _, _, _, done -> writes++; done(Result.success(Unit)) },
                        { throw AssertionError(it) })
                    overlay = current
                    activity.setContentView(current)
                    val size = (400 * containScale).toInt()
                    current.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                    current.layout(0, 0, size, size)
                    current.update(geometry(geometryScale))
                    val editor = current.findViewWithTag<EditText>("nuxie-text-input-answer")
                    val oracle = EditText(activity).apply {
                        background = null
                        setPadding(0, 0, 0, 0)
                        includeFontPadding = false
                        setSingleLine(false)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        gravity = Gravity.TOP or Gravity.START
                        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, item.getDouble("expectedFontSize").toFloat())
                        if (!item.isNull("expectedBaselineDistance")) {
                            setLineHeight(item.getDouble("expectedBaselineDistance").toInt())
                        }
                        setText(text)
                    }
                    fun layout(control: EditText) {
                        val width = editor.layoutParams.width
                        val height = editor.layoutParams.height
                        control.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        control.layout(0, 0, width, height)
                    }
                    layout(editor)
                    layout(oracle)
                    assertEquals(name, 3, editor.layout.lineCount)
                    assertEquals(name, item.getDouble("expectedFontSize").toFloat(), editor.textSize, 0.01f)
                    for (line in 1..2) {
                        val expected = oracle.layout.getLineBaseline(line) - oracle.layout.getLineBaseline(line - 1)
                        val actual = editor.layout.getLineBaseline(line) - editor.layout.getLineBaseline(line - 1)
                        assertEquals("$name baseline $line", expected, actual)
                    }
                    editor.setSelection(1, 4)
                    val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                    assertTrue(connection.setComposingRegion(1, 4))
                    assertEquals("$name composition precondition", 1, BaseInputConnection.getComposingSpanStart(editor.text))
                    val before = writes
                    current.update(geometry(geometryScale))
                    layout(editor)
                    assertEquals(name, text, editor.text.toString())
                    assertEquals(name, 1, editor.selectionStart)
                    assertEquals(name, 4, editor.selectionEnd)
                    assertEquals(name, 1, BaseInputConnection.getComposingSpanStart(editor.text))
                    assertEquals(name, 4, BaseInputConnection.getComposingSpanEnd(editor.text))
                    assertEquals("$name styling cannot write a response", before, writes)
                    current.close()
                    overlay = null
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                try { overlay?.close() } finally { activity.finish() }
            }
        }
    }

    private fun geometry(scale: Float) = NuxieViewModelSnapshot.fromNative(
        NativeViewModelSnapshot(1, arrayOf(NativeViewModelSnapshotInstance(1, 0)),
            mapOf("x" to 10f, "y" to 10f, "width" to 240f, "height" to 180f,
                "rotation" to 0f, "scaleX" to scale, "scaleY" to scale).map { (name, value) ->
                NativeViewModelSnapshotValue(1, 0, name, NuxieViewModelPropertyKind.NUMBER.nativeValue,
                    byteArrayOf(), 0, value)
            }.toTypedArray()),
    )
}
