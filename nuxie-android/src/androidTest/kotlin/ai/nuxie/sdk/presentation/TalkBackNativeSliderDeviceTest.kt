package ai.nuxie.sdk.presentation

import android.app.UiAutomation
import android.content.Intent
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeProvider
import ai.nuxie.sdk.runtime.NativeSemanticNode
import ai.nuxie.sdk.runtime.NuxieSemanticTree
import java.util.concurrent.atomic.AtomicInteger
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Independent native-widget control for the opt-in TalkBack gesture probe. */
class TalkBackNativeSliderDeviceTest {
    @Test fun nativeSliderAcceptsTalkBackAdjustmentsAfterRepeatedTraversal() = exercise(false)

    @Test fun virtualSliderAcceptsTalkBackAdjustmentsAfterRepeatedTraversal() = exercise(true)

    private fun exercise(virtual: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nuxieTalkBackQualification") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val input = TalkBackEmulatorInput(automation,
            checkNotNull(InstrumentationRegistry.getArguments().getString("nuxieTalkBackInputDevice")))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            SurfaceCompatibilityHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var slider: SeekBar
        val virtualValue = AtomicInteger(5)
        val labels = listOf("Choose your plan", "Seats", "After")
        fun label(node: AccessibilityNodeInfo?) = node?.let { listOf(it.text, it.contentDescription, it.hintText)
            .firstOrNull { text -> text?.toString() in labels }?.toString() }
        fun focused() = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        fun awaitFocus(expected: String, previous: AccessibilityNodeInfo? = null): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var current = focused()
            while ((current == null || current == previous || label(current) != expected) && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20)
                current = focused()
            }
            assertEquals(expected, label(current))
            assertNotEquals(previous, current)
            return checkNotNull(current)
        }
        try {
            instrumentation.runOnMainSync {
                slider = SeekBar(activity).apply { contentDescription = "Seats"; max = 10; progress = 5 }
                val controls = listOf<View>(
                    TextView(activity).apply { text = "Choose your plan"; isAccessibilityHeading = true },
                    slider,
                    EditText(activity).apply { hint = "After"; inputType = 129 },
                )
                if (virtual) {
                    activity.setContentView(SliderView(activity, virtualValue).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    })
                } else activity.setContentView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    isFocusableInTouchMode = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    controls.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120)) }
                    requestFocus()
                })
            }
            automation.waitForIdle(100, 5_000)
            repeat(4) {
                if (label(focused()) != "Seats") {
                    input.swipeForward()
                    automation.waitForIdle(100, 5_000)
                }
            }
            val firstSlider = awaitFocus("Seats")
            input.swipeForward()
            val after = awaitFocus("After", firstSlider)
            input.swipeBackward()
            assertEquals(firstSlider, awaitFocus("Seats", after))
            input.awaitAdjustableReadingControl()
            input.swipeUp()
            val deadline = SystemClock.uptimeMillis() + 5_000
            var value = 5
            while (value == 5 && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { value = if (virtual) virtualValue.get() else slider.progress }
                if (value == 5) SystemClock.sleep(20)
            }
            assertTrue("TalkBack must increase the ${if (virtual) "virtual" else "native"} slider", value > 5)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private class SliderView(context: Context, value: AtomicInteger) : View(context) {
        private val semantics = ExperienceAccessibilityProvider(this, { node ->
            val local = Rect(node.minX.toInt(), node.minY.toInt(), node.maxX.toInt(), node.maxY.toInt())
            val origin = IntArray(2)
            getLocationOnScreen(origin)
            ExperienceAccessibilityProvider.Bounds(local, Rect(local).apply { offset(origin[0], origin[1]) })
        }, { _, _, action ->
            if (action == 1) value.incrementAndGet()
            if (action == 2) value.decrementAndGet()
            true
        })

        init {
            semantics.publish(NuxieSemanticTree(1, 1, listOf(
                NativeSemanticNode(1, -1, 0, 7, 0, 0, 1, 0, 0f, 0f, 300f, 120f, "Choose your plan", "", ""),
                NativeSemanticNode(2, -1, 1, 5, 0, 0, 0, 6, 0f, 120f, 300f, 240f, "Seats", "5", ""),
                NativeSemanticNode(3, -1, 2, 1, 0, 0, 0, 1, 0f, 240f, 300f, 360f, "After", "", ""),
            )))
        }

        override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = semantics
    }

}
