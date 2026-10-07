package ai.nuxie.sdk.presentation

import android.app.Activity
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceNativeSheetTest {
    @Test fun `native sheets retain the lower content and close only their own layer`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val lower = FrameLayout(activity)
        val upper = FrameLayout(activity)
        var lowerCloses = 0
        var upperCloses = 0
        val first = ExperienceNativeLayerWindow(activity, lower, PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, true)) { lowerCloses++ }
        val second = ExperienceNativeLayerWindow(activity, upper, PresentationShell.Sheet(PresentationShell.Sheet.Detent.LARGE, true)) { upperCloses++ }
        try {
            first.show()
            second.show()
            assertNotNull(lower.parent)
            assertNotNull(upper.parent)
            assertEquals(BottomSheetBehavior.STATE_EXPANDED, (second.dialog as BottomSheetDialog).behavior.state)
            second.setInputEnabled(false)
            assertFalse((second.dialog as BottomSheetDialog).behavior.isDraggable)
            assertFalse((second.dialog as BottomSheetDialog).behavior.isHideable)
            second.setInputEnabled(true)
            assertTrue((second.dialog as BottomSheetDialog).behavior.isDraggable)
            assertTrue((second.dialog as BottomSheetDialog).behavior.isHideable)
            second.closeFromUser()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, upperCloses)
            assertEquals(0, lowerCloses)
            assertTrue(first.dialog.isShowing)
            assertNotNull(lower.parent)
        } finally { second.retire(); first.retire(); activity.finish() }
        assertEquals(0, lowerCloses)
    }
}
