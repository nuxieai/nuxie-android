package ai.nuxie.sdk.presentation

import android.animation.ValueAnimator
import android.os.Build
import android.os.Looper
import android.provider.Settings
import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
class ExperienceReducedMotionTest {
    @Test
    @Config(sdk = [23, 29, 33])
    fun `preference changes publish once and notifications cannot escape close`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        fun setScale(value: Float) {
            if (Build.VERSION.SDK_INT >= 33) {
                // Robolectric's setter shadow only changes the static field.
                // Run the framework setter to exercise registered listener delivery.
                Shadow.directlyOn<Any, ValueAnimator>(ValueAnimator::class.java, "setDurationScale",
                    ClassParameter.from(Float::class.javaPrimitiveType, value))
            } else {
                Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, value)
                resolver.notifyChange(uri, null)
            }
        }
        setScale(1f)
        val values = mutableListOf<Boolean>()
        val preference = ExperienceReducedMotion(RuntimeEnvironment.getApplication(), values::add)
        try {
            assertEquals(listOf(false), values)
            val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
                .resolve("journeys/planes/runtime-reduced-motion-android.json").readText()).jsonObject
            for (raw in fixture.getValue("steps").jsonArray) {
                val step = raw.jsonObject
                setScale(step.getValue("scale").jsonPrimitive.float)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(step.getValue("expected").jsonArray.map { it.jsonPrimitive.boolean }, values)
            }
            if (Build.VERSION.SDK_INT < 33) setScale(0f)
            preference.close()
            if (Build.VERSION.SDK_INT >= 33) setScale(0f)
            preference.close()
            shadowOf(Looper.getMainLooper()).idle()
            preference.refresh()
            assertEquals(listOf(false, true, false), values)
        } finally {
            preference.close()
            setScale(1f)
        }
    }
}
