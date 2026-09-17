package ai.nuxie.sdk.presentation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = 31)
class SystemFontInputDeviceTest {
    @Test fun systemInputWeightsRenderAndCommitWithoutDownloadedFonts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val rendered = mutableMapOf<Int, IntArray>()
        try {
            instrumentation.runOnMainSync {
                for (weight in 100..900 step 100) {
                    val commits = mutableListOf<Triple<String, String, Boolean>>()
                    val input = ExperienceTextInput("name", "headline", "Native text", "answer", null, null,
                        false, false, null, emptyMap(), ExperienceTextInput.Style(
                            "System", "$weight", false, 24f, 32f, 0f, 0xff000000.toInt(), "system-font", null))
                    val overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(400f, 200f),
                        listOf(input), emptyMap(), { id, text, commit, done ->
                            commits += Triple(id, text, commit)
                            done(Result.success(Unit))
                        }, { throw AssertionError(it) })
                    try {
                        activity.setContentView(overlay)
                        val editor = overlay.findViewWithTag<EditText>("nuxie-text-input-name")
                        editor.layoutParams = FrameLayout.LayoutParams(400, 120)
                        editor.visibility = View.VISIBLE
                        editor.isCursorVisible = false
                        assertEquals(weight, editor.typeface.weight)
                        assertFalse(editor.typeface.isItalic)
                        assertTrue(editor.requestFocus())
                        editor.setText("Authored native font")
                        assertEquals("Authored native font", editor.text.toString())
                        editor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
                        editor.layout(0, 0, 400, 120)
                        val bitmap = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(0xffffffff.toInt())
                            editor.draw(Canvas(bitmap))
                            rendered[weight] = IntArray(400 * 120).also { bitmap.getPixels(it, 0, 400, 0, 0, 400, 120) }
                        } finally { bitmap.recycle() }
                        assertTrue(overlay.requestFocus())
                        assertEquals(Triple("name", "Authored native font", true), commits.last())
                    } finally { overlay.close() }
                }
            }
            val regular = rendered.getValue(400)
            val bold = rendered.getValue(700)
            assertTrue("Native input contains visible text", regular.count { it != 0xffffffff.toInt() } > 100)
            assertFalse("Authored weight changes native input pixels", regular.contentEquals(bold))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
