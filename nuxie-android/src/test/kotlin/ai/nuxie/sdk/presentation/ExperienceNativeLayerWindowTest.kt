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
            window.updateContentHeight(272f)
            assertEquals(272, sheet.layoutParams.height)
            window.updateContentHeight(1232f)
            assertEquals(852, sheet.layoutParams.height)
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
            assertEquals(852, window.dialog.findViewById<android.view.View>(
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

    @Test fun `packaged looks match the shared vector`() {
        val data = checkNotNull(javaClass.getResourceAsStream("presentation-looks.json")).use { it.readBytes() }
        assertArrayEquals(File("../fixtures/journeys/planes/presentation-looks.json").readBytes(), data)
    }
}
