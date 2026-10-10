package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.*
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExperienceComposingUnderlineTest {
    @Test fun `composition draws tinted marks but no native glyphs and disappears on commit`() {
        for ((multiline, value) in listOf(false to "nihon", true to "nihon\nnihon", true to "abc אבג def")) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get()
            val tint = 0xff2166aa.toInt()
            val input = ExperienceTextInput("answer", "run", value, "answer", null, null,
                false, multiline, null, emptyMap(), ExperienceTextInput.Style("sans-serif", "400", false,
                    23f, -1f, 0f, tint, "font", null))
            val overlay = nativeInputOverlayFixture(activity, ExperienceArtboardSize(400f, 400f),
                listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) }, { throw it })
            try {
                activity.setContentView(overlay)
                val size = 400
                val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
                overlay.measure(spec, spec); overlay.layout(0, 0, size, size)
                val transform = NuxieTextRunGeometry.Transform(1f, 0f, 0f, 1f, 0f, 0f)
                val bounds = NuxieTextRunGeometry.Bounds(0f, 0f, 240f, 180f)
                val snapshot = NuxieViewModelSnapshot.fromNative(NativeViewModelSnapshot(1,
                    arrayOf(NativeViewModelSnapshotInstance(1, 0)), emptyArray()))
                overlay.updateNativeFixture(snapshot, NuxieTextGeometryCapture.Captured(mapOf("run" to
                    NuxieTextRunGeometry(1uL, transform, transform, bounds,
                        NuxieTextRunGeometry.Layout(transform, bounds), 23f))))
                overlay.measure(spec, spec); overlay.layout(0, 0, size, size)
                val editor = requireNotNull(overlay.findViewWithTag<EditText>("nuxie-text-input-answer"))
                editor.isCursorVisible = false
                editor.setSelection(editor.length())
                assertEquals(Color.TRANSPARENT, editor.currentTextColor)
                fun pixels(): IntArray {
                    val bitmap = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
                    editor.draw(Canvas(bitmap))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    bitmap.recycle()
                    return pixels
                }
                assertTrue("Native glyphs must remain invisible", pixels().all { Color.alpha(it) == 0 })
                BaseInputConnection.setComposingSpans(editor.text)
                assertEquals(value, editor.text.toString())
                assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
                assertEquals(value.length, BaseInputConnection.getComposingSpanEnd(editor.text))
                assertTrue(editor.width > 0 && editor.height > 0)
                val composed = pixels()
                assertTrue("The composing range must have a visible underline", composed.any { Color.alpha(it) > 0 })
                assertTrue("Only the field tint may be drawn", composed.filter { Color.alpha(it) != 0 }.all { pixel ->
                    // Bitmap storage rounds premultiplied channels to bytes.
                    val alpha = Color.alpha(pixel)
                    listOf(Color.red(pixel) to Color.red(tint), Color.green(pixel) to Color.green(tint),
                        Color.blue(pixel) to Color.blue(tint)).all { (actual, expected) ->
                        kotlin.math.abs(actual * alpha - expected * alpha) <= 255
                    }
                })
                assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
                assertEquals(value.length, BaseInputConnection.getComposingSpanEnd(editor.text))
                assertEquals(value, editor.text.toString())
                BaseInputConnection.removeComposingSpans(editor.text)
                assertTrue("Committing removes the underline without drawing glyphs", pixels().all { Color.alpha(it) == 0 })
            } finally {
                overlay.close()
                controller.pause().stop().destroy()
            }
        }
    }
}
