package ai.nuxie.sdk.presentation

import android.content.Intent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TextInputAccessibilityDeviceTest {
    @Test
    fun keyboardFocusParkingDoesNotBecomeAnAccessibilityTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        var overlay: ExperienceTextInputOverlay? = null
        lateinit var editor: EditText
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
            }
            instrumentation.runOnMainSync {
                val input = ExperienceTextInput("name", "headline", "Native editor", "answer", null, null,
                    false, false, null, emptyMap(), ExperienceTextInput.Style(
                        "sans-serif", "400", false, 16f, 20f, 0f, 0xff000000.toInt(), "font", null))
                overlay = ExperienceTextInputOverlay(activity, ExperienceArtboardSize(400f, 400f),
                    listOf(input), emptyMap(), { _, _, _, done -> done(Result.success(Unit)) },
                    { throw AssertionError(it) })
                activity.setContentView(overlay)
                editor = overlay!!.findViewWithTag<EditText>("nuxie-text-input-name")
                // Independent native geometry isolates focus parking from the renderer.
                editor.layoutParams = FrameLayout.LayoutParams(300, 100)
                editor.visibility = View.VISIBLE
                assertTrue(overlay!!.requestFocus())
                assertTrue(overlay!!.hasFocus())
                assertFalse(editor.hasFocus())
            }
            instrumentation.waitForIdleSync()
            automation.waitForIdle(100, 5_000)
            fun collect(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
                listOf(node) + (0 until node.childCount).mapNotNull(node::getChild).flatMap(::collect)
            val nodes = collect(checkNotNull(automation.rootInActiveWindow))
            val field = nodes.single { it.text?.toString() == "Native editor" }
            assertTrue(field.isEditable)
            assertEquals("android.widget.EditText", field.className.toString())
            assertFalse("Keyboard focus parking must not add an unlabelled semantic container",
                nodes.any { it.className?.toString() == "android.widget.FrameLayout" && it.isFocusable })
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue(editor.hasFocus()) }
        } finally {
            try {
                instrumentation.runOnMainSync {
                    overlay?.close()
                    activity.finish()
                }
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }
    }
}
