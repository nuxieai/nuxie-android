package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.runtime.NativeSemanticNode
import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30])
class ExperienceSemanticGeometryTest {
    @Test fun `centered contain and host scale project the same authored rectangle`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val parent = FrameLayout(activity)
            val host = View(activity)
            parent.addView(host)
            activity.setContentView(parent)
            parent.layout(0, 0, 900, 1200)
            host.layout(20, 40, 320, 340)
            host.pivotX = 0f
            host.pivotY = 0f
            host.scaleX = 2f
            host.scaleY = 2f
            val location = IntArray(2)
            host.getLocationOnScreen(location)
            val node = NativeSemanticNode(1, -1, 0, 1, 0, 0, 0, 1,
                10f, 5f, 30f, 15f, "Continue", "", "")
            val projected = checkNotNull(semanticBounds(host, ExperienceArtboardSize(100f, 50f), node))
            assertEquals(Rect(30, 90, 90, 120), projected.inHost)
            assertEquals(Rect(location[0] + 60, location[1] + 180,
                location[0] + 180, location[1] + 240), projected.inScreen)
            assertNull(semanticBounds(host, null, node))
            assertNull(semanticBounds(host, ExperienceArtboardSize(100f, 50f), node.copy(minX = Float.NaN)))
        } finally { controller.pause().stop().destroy() }
    }
}
