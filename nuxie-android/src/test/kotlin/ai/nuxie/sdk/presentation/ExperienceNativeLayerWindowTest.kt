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
    @Test fun `native page presentation completion runs once after the fullscreen window is shown`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity), PresentationShell.FullScreen()) {}
        var completions = 0
        try {
            window.whenPresented { completions++ }
            assertEquals(0, completions)
            window.show()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, completions)
            window.show()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, completions)
        } finally { window.retire(); activity.finish() }
    }

    @Test fun `retiring a native page cancels its pending presentation completion`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity), PresentationShell.FullScreen()) {}
        var completed = false
        window.whenPresented { completed = true }
        window.show()
        window.retire()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(completed)
        activity.finish()
    }

    @Test fun `fullscreen closes only when the current layer permits it`() {
        for (allowed in listOf(false, true)) {
            for (shell in listOf(PresentationShell.FullScreen(allowed))) {
                val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
                var closes = 0
                val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity), shell) { closes++ }
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

    @Test @Config(sdk = [34])
    fun `native predictive callback closes only the allowed top fullscreen layer`() {
        org.robolectric.shadows.ShadowApplication.setEnableOnBackInvokedCallback(true)
        for (allowed in listOf(false, true)) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val openLayers = mutableListOf("base", "top")
            val lower = ExperienceNativeLayerWindow(activity, FrameLayout(activity), PresentationShell.FullScreen()) {
                error("Covered layer must not close")
            }
            val upper = ExperienceNativeLayerWindow(activity, FrameLayout(activity), PresentationShell.FullScreen(allowed)) {
                openLayers.removeAt(openLayers.lastIndex)
            }
            try {
                lower.setInputEnabled(false)
                lower.show()
                upper.setInputEnabled(true)
                upper.show()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                // Invoke the callback actually registered by the native Dialog with its dispatcher.
                val dispatcher = org.robolectric.util.ReflectionHelpers.getField<Any>(
                    upper.dialog.onBackInvokedDispatcher, "mActualDispatcher")
                assertNotNull(dispatcher)
                val callback = org.robolectric.util.ReflectionHelpers.callInstanceMethod<android.window.OnBackInvokedCallback?>(
                    dispatcher, "getTopCallback")
                if (allowed) assertNotNull(callback)
                callback?.onBackInvoked()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(if (allowed) listOf("base") else listOf("base", "top"), openLayers)
                assertTrue(lower.dialog.isShowing)
            } finally { upper.retire(); lower.retire(); activity.finish() }
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

    @Test fun `sheet drag handle follows closedby and draws over the screen without adding height`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            for ((shell, shown) in listOf(
                PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, true) to true,
                PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true) to true,
                PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, false) to false,
            )) {
                val content = FrameLayout(activity)
                val window = ExperienceNativeLayerWindow(activity, content, shell) {}
                try {
                    window.setInputEnabled(true)
                    window.updateContentHeight(152f)
                    window.show()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val handle = checkNotNull(window.dragHandle)
                    assertEquals("closedby ${if (shell.dismissible) "any" else "none"}", shown, handle.visibility == android.view.View.VISIBLE)
                    // The handle overlays the screen: both share one frame, and the screen fills it.
                    assertSame(content.parent, handle.parent)
                    assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, content.layoutParams.height)
                    val sheet = window.dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
                    if (shell.fitsContent) assertEquals(152, sheet.layoutParams.height)
                    assertTrue("The screen is clipped to the sheet's rounded top", (content.parent as android.view.View).clipToOutline)
                } finally { window.retire() }
            }
            val fullScreen = ExperienceNativeLayerWindow(activity, FrameLayout(activity), PresentationShell.FullScreen(true)) {}
            try { assertNull("Full-screen layers keep no sheet chrome", fullScreen.dragHandle) } finally { fullScreen.retire() }
        } finally { activity.finish() }
    }

    @Test fun `drag handle stays inert with an accessibility service on and a collapse cannot move the sheet`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val accessibility = activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        Shadows.shadowOf(accessibility).setEnabled(true)
        try {
            for (shell in listOf(
                PresentationShell.Sheet(PresentationShell.Sheet.Detent.MEDIUM, true),
                PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, true),
                PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true),
            )) {
                val content = FrameLayout(activity)
                var touches = 0
                content.setOnTouchListener { _, _ -> touches++; true }
                val window = ExperienceNativeLayerWindow(activity, content, shell) {}
                try {
                    window.setInputEnabled(true)
                    window.updateContentHeight(152f)
                    window.show()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val handle = checkNotNull(window.dragHandle)
                    assertEquals(android.view.View.VISIBLE, handle.visibility)
                    assertFalse("The handle is a picture, not a control", handle.isClickable || handle.isFocusable)
                    assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO, handle.importantForAccessibility)
                    // A tap on the handle reaches the screen it is drawn over.
                    val frame = handle.parent as android.view.ViewGroup
                    assertTrue("The handle is laid out", handle.width > 0 && handle.height > 0)
                    val x = handle.left + handle.width / 2f
                    val y = handle.top + handle.height / 2f
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(0, 0, action, x, y, 0)
                        try { frame.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    assertEquals("${shell.detent}: both touches reach the screen", 2, touches)
                    // Collapsed and expanded are the same place, so no collapse can half show the sheet.
                    val sheet = window.dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
                    val placed = sheet.layoutParams.height.takeIf { it > 0 } ?: (sheet.parent as android.view.View).height
                    assertTrue(placed > 0)
                    assertEquals("${shell.detent}: peek height", placed,
                        (window.dialog as com.google.android.material.bottomsheet.BottomSheetDialog).behavior.peekHeight)
                } finally { window.retire() }
            }
        } finally { activity.finish() }
    }

    @Test fun `content sheet waits caps updates and ignores retired measurement`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val shell = PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true)
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity), shell) {}
        try {
            window.show()
            assertFalse(window.dialog.isShowing)
            window.updateContentHeight(152f)
            assertTrue(window.dialog.isShowing)
            val sheet = window.dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            assertEquals(152, sheet.layoutParams.height)
            // Parla's Saved hugs 177.79 points; the sheet rounds down so its content covers both edges.
            window.updateContentHeight(177.79f)
            assertEquals(177, sheet.layoutParams.height)
            window.updateContentHeight(272f)
            assertEquals(272, sheet.layoutParams.height)
            window.updateContentHeight(1232f)
            val available = (sheet.parent as android.view.View).height.takeIf { it > 0 } ?: 852
            assertEquals(available, sheet.layoutParams.height)
            window.retire()
            window.updateContentHeight(152f)
            assertFalse(window.dialog.isShowing)
        } finally { window.retire(); activity.finish() }
    }

    @Test fun `missing binding opens content sheet at large`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true)) {}
        try {
            window.show()
            window.updateContentHeight(null)
            assertTrue(window.dialog.isShowing)
            assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT, window.dialog.findViewById<android.view.View>(
                com.google.android.material.R.id.design_bottom_sheet).layoutParams.height)
        } finally { window.retire(); activity.finish() }
    }

    @Test fun `pre30 split window uses actual host bounds`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        try {
            activity.window.decorView.layout(0, 0, 300, 500)
            val shell = PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true)
            assertEquals(ExperienceArtboardSize(300f, 500f), ExperienceNativeLayerWindow.availableSize(activity, shell))
            val recreated = ExperienceNativeLayerWindow(activity, FrameLayout(activity), shell) {}
            try {
                recreated.show()
                recreated.updateContentHeight(152f)
                assertTrue(recreated.dialog.isShowing)
                assertEquals(152, recreated.dialog.findViewById<android.view.View>(
                    com.google.android.material.R.id.design_bottom_sheet).layoutParams.height)
            } finally { recreated.retire() }
        } finally { activity.finish() }
    }

    @Test fun `frame height updates do not reset an active native drag or settling`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.FIT_CONTENT, true)) {}
        try {
            window.updateContentHeight(152f)
            window.setInputEnabled(true)
            window.show()
            val behavior = (window.dialog as com.google.android.material.bottomsheet.BottomSheetDialog).behavior
            // Material's public setter refuses transient states. This is the
            // state its drag callback selects after accepting the pointer.
            for (state in listOf(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING,
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_SETTLING)) {
                org.robolectric.util.ReflectionHelpers.setField(behavior, "state", state)
                repeat(60) { window.updateContentHeight(152f) }
                assertEquals(state, behavior.state)
                window.updateContentHeight(272f)
                assertEquals("A content reflow must not seize a native gesture either", state, behavior.state)
            }
        } finally { window.retire(); activity.finish() }
    }

    private class Drags(private val admits: Boolean = true) : ExperienceNativeLayerWindow.DragHandler {
        val events = mutableListOf<String>()
        override fun dragStarted() = admits.also { events += "started" }
        override fun dragCancelled() { events += "cancelled" }
        override fun dragHid() { events += "hid" }
    }

    /** Material's own drag callback path: the state it selects after accepting the pointer, with its callbacks. */
    private fun ExperienceNativeLayerWindow.sheetState(state: Int) {
        val behavior = (dialog as com.google.android.material.bottomsheet.BottomSheetDialog).behavior
        check(org.robolectric.util.ReflectionHelpers.getField<Any?>(behavior, "viewRef") != null) {
            "The sheet has not been laid out, so Material sends no state callbacks"
        }
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(behavior, "setStateInternal",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, state))
    }

    private val ExperienceNativeLayerWindow.draggable: Boolean
        get() = (dialog as com.google.android.material.bottomsheet.BottomSheetDialog).behavior.isDraggable

    @Test fun `a sheet refuses a drag and outside taps while another move holds the slot`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, true)) {}
        try {
            window.setInputEnabled(true)
            window.show()
            val dialog = Shadows.shadowOf(window.dialog)
            assertTrue(window.draggable)
            assertTrue(dialog.isCancelableOnTouchOutside)
            window.setMoveBusy(true)
            assertFalse("A drag is refused while another move holds the slot", window.draggable)
            assertFalse("Outside taps don't cancel while another move holds the slot", dialog.isCancelableOnTouchOutside)
            assertTrue("Cancelability, and with it the sheet's hideability, is not the move gate", dialog.isCancelable)
            window.setMoveBusy(false)
            assertTrue(window.draggable)
            assertTrue(dialog.isCancelableOnTouchOutside)
        } finally { window.retire(); activity.finish() }
    }

    @Test fun `a drag takes the slot once and ends exactly once`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val drags = Drags()
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, true)) {}
        val B = com.google.android.material.bottomsheet.BottomSheetBehavior::class.java
        fun state(name: String) = B.getField(name).getInt(null)
        try {
            window.setDragHandler(drags)
            window.setInputEnabled(true)
            window.show()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            window.sheetState(state("STATE_DRAGGING"))
            // The drag's own slot makes the queue busy; its sheet stays draggable so the drag can finish.
            window.setMoveBusy(true)
            assertTrue(window.draggable)
            window.sheetState(state("STATE_DRAGGING"))
            window.sheetState(state("STATE_EXPANDED"))
            window.setMoveBusy(false)
            assertEquals("Released where it started, straight from dragging", listOf("started", "cancelled"), drags.events)
            window.sheetState(state("STATE_DRAGGING"))
            window.sheetState(state("STATE_COLLAPSED"))
            assertEquals(listOf("started", "cancelled", "started", "cancelled"), drags.events)
            window.sheetState(state("STATE_DRAGGING"))
            window.sheetState(state("STATE_SETTLING"))
            window.sheetState(state("STATE_HIDDEN"))
            assertEquals(listOf("started", "cancelled", "started", "cancelled", "started", "hid"), drags.events)
        } finally { window.retire(); activity.finish() }
        assertEquals("A hidden drag has ended; retiring adds nothing", 6, drags.events.size)
    }

    @Test fun `retiring a window mid-drag ends the drag, and a refused drag has no end`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val B = com.google.android.material.bottomsheet.BottomSheetBehavior::class.java
        fun state(name: String) = B.getField(name).getInt(null)
        for (admits in listOf(true, false)) {
            val drags = Drags(admits)
            val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity),
                PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, true)) {}
            window.setDragHandler(drags)
            window.setInputEnabled(true)
            window.show()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            window.sheetState(state("STATE_DRAGGING"))
            window.retire()
            assertEquals(if (admits) listOf("started", "cancelled") else listOf("started"), drags.events)
        }
        activity.finish()
    }

    @Test fun `loading permits leaving a closedby none sheet before content is revealed`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var closes = 0
        val window = ExperienceNativeLayerWindow(activity, FrameLayout(activity),
            PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, false)) { closes++ }
        try {
            window.setInputEnabled(true, allowDismissalDuringLoading = true)
            window.show()
            window.closeFromUser()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, closes)
        } finally { window.retire(); activity.finish() }
    }

    @Test fun `packaged looks match the shared vector`() {
        val data = checkNotNull(javaClass.getResourceAsStream("presentation-looks.json")).use { it.readBytes() }
        assertArrayEquals(File("../fixtures/journeys/planes/presentation-looks.json").readBytes(), data)
    }
}
