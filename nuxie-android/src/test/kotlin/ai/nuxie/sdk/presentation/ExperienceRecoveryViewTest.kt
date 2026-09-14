package ai.nuxie.sdk.presentation

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class ExperienceRecoveryViewTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun `accepted retry disables repeated taps while Close remains available`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var retries = 0
        var closes = 0
        val view = ExperienceRecoveryView(controller.get(), Color.BLACK, AcquisitionProgress.Phase.FAILED,
            retry = { retries++; true }, onClose = { closes++ })
        controller.get().setContentView(view)
        val buttons = descendants(view).filterIsInstance<Button>()
        val retry = buttons.single { it.text == "Retry" }
        val close = buttons.single { it.text == "Close" }
        assertTrue(retry.isEnabled)
        retry.performClick()
        assertEquals(1, retries)
        assertFalse(retry.isEnabled)
        assertTrue(close.isEnabled)
        close.performClick()
        assertEquals(1, closes)
        assertTrue(buttons.all { it.minHeight >= 48 * view.resources.displayMetrics.density })
        controller.pause().stop().destroy()
    }

    @Test fun `draining attempt keeps Close reachable in a small large-font viewport`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val config = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        var closes = 0
        val view = ExperienceRecoveryView(activity, Color.WHITE, AcquisitionProgress.Phase.RETRYING,
            retry = { fail("Draining attempt must not retry"); false }, onClose = { closes++ })
        activity.setContentView(view)
        val density = view.resources.displayMetrics.density
        val width = (240 * density).toInt()
        val height = (180 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val scroll = descendants(view).filterIsInstance<ScrollView>().single()
        val buttons = descendants(view).filterIsInstance<Button>()
        assertFalse(buttons.single { it.text == "Retrying…" }.isEnabled)
        val close = buttons.single { it.text == "Close" }
        assertTrue(scroll.height <= height)
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        scroll.fullScroll(View.FOCUS_DOWN)
        assertTrue(scroll.scrollY > 0)
        close.performClick()
        assertEquals(1, closes)
        controller.pause().stop().destroy()
    }
}
