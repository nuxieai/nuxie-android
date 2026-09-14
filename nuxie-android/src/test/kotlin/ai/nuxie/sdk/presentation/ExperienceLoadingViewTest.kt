package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.Settings
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExperienceLoadingViewTest {
    @Test fun `shared palette follows authored contrast and transparent fallback`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/loading-treatment.json").readText()).jsonObject
        for (raw in fixture.getValue("palette").jsonArray) {
            val vector = raw.jsonObject
            val rgba = vector.getValue("rgba").jsonPrimitive.content.removePrefix("#").toLong(16)
            val argb = ((rgba and 255) shl 24 or (rgba ushr 8)).toInt()
            val highlight = loadingHighlight(argb)
            assertEquals(if (vector.getValue("light").jsonPrimitive.boolean) 0xffffff else 0, highlight and 0xffffff)
            assertEquals((vector.getValue("fraction").jsonPrimitive.double * 255).roundToInt(), Color.alpha(highlight))
        }
    }

    @Test fun `overlay preserves ground and stops on motion preference inactivity detach and close`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val resolver = activity.contentResolver
        fun scale(value: Float) {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, value))
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, value)
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
        }
        scale(1f)
        val view = ExperienceLoadingView(activity, 0x80000000.toInt())
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        // Once attached, drive frames explicitly rather than exhausting the animation.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        view.layout(0, 0, 200, 400)
        view.setActive(true)
        val animator = ReflectionHelpers.getField<ValueAnimator>(view, "animator")
        val ground = Color.rgb(90, 90, 90)
        fun pixel(time: Long): Int {
            if (animator.isStarted) animator.currentPlayTime = time
            val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(ground)
            view.draw(Canvas(bitmap))
            return bitmap.getPixel(100, 200).also { bitmap.recycle() }
        }
        try {
            assertTrue(animator.isStarted)
            assertEquals(ground, pixel(0))
            assertTrue(Color.red(pixel(600)) > 90)
            assertEquals(ground, pixel(1300))
            scale(0f)
            assertTrue("motion preference must stop", ReflectionHelpers.getField<Boolean>(view, "reducedMotion"))
            assertFalse(animator.isStarted)
            assertEquals(ground, pixel(600))
            scale(1f)
            assertFalse("motion preference must resume", ReflectionHelpers.getField<Boolean>(view, "reducedMotion"))
            assertTrue("active=${ReflectionHelpers.getField<Boolean>(view, "active")} attached=${view.isAttachedToWindow}", animator.isStarted)
            view.setActive(false)
            assertFalse(animator.isStarted)
            assertEquals(ground, pixel(600))
            view.setActive(true)
            assertTrue(animator.isStarted)
            activity.setContentView(android.view.View(activity))
            assertFalse(animator.isStarted)
            activity.setContentView(view)
            assertTrue(animator.isStarted)
            view.close()
            scale(0f)
            scale(1f)
            view.setActive(true)
            assertFalse(animator.isStarted)
            assertEquals(ground, pixel(600))
        } finally {
            view.close()
            scale(1f)
            controller.pause().stop().destroy()
        }
    }
}
