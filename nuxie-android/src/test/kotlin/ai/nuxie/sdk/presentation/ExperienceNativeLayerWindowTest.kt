package ai.nuxie.sdk.presentation

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi")
class ExperienceNativeLayerWindowTest {
    @Test fun `dialog geometry comes from the shared oracle`() {
        val vector = Json.parseToJsonElement(File("../fixtures/journeys/planes/presentation-geometry.json").readText()).jsonObject
        val width = vector.getValue("window").jsonObject.getValue("width").jsonPrimitive.float
        val height = vector.getValue("window").jsonObject.getValue("height").jsonPrimitive.float
        val safe = vector.getValue("safeArea").jsonObject
        for (element in vector.getValue("dialogCases").jsonArray) {
            val item = element.jsonObject
            val expected = item.getValue("expectedFrame").jsonObject
            val frame = ExperienceNativeLayerWindow.dialogFrame(width, safe.getValue("top").jsonPrimitive.float,
                height - safe.getValue("bottom").jsonPrimitive.float, item.getValue("laidOutHeight").jsonPrimitive.float)
            assertEquals(expected.getValue("x").jsonPrimitive.float, frame.left, 0.0001f)
            assertEquals(expected.getValue("y").jsonPrimitive.float, frame.top, 0.0001f)
            assertEquals(expected.getValue("width").jsonPrimitive.float, frame.width(), 0.0001f)
            assertEquals(expected.getValue("height").jsonPrimitive.float, frame.height(), 0.0001f)
        }
    }

    @Test fun `fullscreen and dialog close only when the current layer permits it`() {
        for (allowed in listOf(false, true)) {
            for (shell in listOf(PresentationShell.FullScreen(allowed), PresentationShell.Dialog(allowed))) {
                val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                var closes = 0
                val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity), shell, 200f) { closes++ }
                try {
                    window.setInputEnabled(true)
                    window.show()
                    @Suppress("DEPRECATION")
                    window.dialog.onBackPressed()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(if (allowed) 1 else 0, closes)
                    assertEquals(!allowed, window.dialog.isShowing)
                } finally { window.retire(); activity.finish() }
                assertEquals(if (allowed) 1 else 0, closes)
            }
        }
    }

    @Test fun `medium sheet uses half the display height`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, true)) {}
        try {
            window.show()
            val view = window.dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            assertEquals(426, view.layoutParams.height)
        } finally { window.retire(); activity.finish() }
    }

    @Test fun `packaged looks match the shared vector`() {
        val data = checkNotNull(javaClass.getResourceAsStream("presentation-looks.json")).use { it.readBytes() }
        assertArrayEquals(File("../fixtures/journeys/planes/presentation-looks.json").readBytes(), data)
    }
}
