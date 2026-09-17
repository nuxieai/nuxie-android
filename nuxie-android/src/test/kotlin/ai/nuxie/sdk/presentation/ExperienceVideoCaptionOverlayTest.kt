package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NuxieVideoCaption
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExperienceVideoCaptionOverlayTest {
    @Test
    fun `captions preserve accessible labels without intercepting touches and retire cleanly`() {
        val overlay = ExperienceVideoCaptionOverlay(RuntimeEnvironment.getApplication())
        overlay.update(mapOf(3L to NuxieVideoCaption("fr", "Bonjour 👋")))
        val label = overlay.getChildAt(0) as TextView
        assertEquals("Bonjour 👋", label.text.toString())
        assertEquals("fr", label.textLocale.language)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, label.importantForAccessibility)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, label.accessibilityLiveRegion)
        overlay.update(mapOf(3L to NuxieVideoCaption("fr", "Bienvenue")))
        assertSame(label, overlay.getChildAt(0))
        overlay.layout(0, 0, 320, 640)
        val touch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 150f, 600f, 0)
        try { assertFalse(overlay.dispatchTouchEvent(touch)) } finally { touch.recycle() }
        overlay.update(mapOf(3L to NuxieVideoCaption("fr", "")))
        assertEquals(View.GONE, label.visibility)
        overlay.update(emptyMap())
        assertEquals(0, overlay.childCount)
    }
}
