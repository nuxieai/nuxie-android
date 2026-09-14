package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.graphics.Insets
import android.graphics.Rect
import android.view.WindowInsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ExperienceWindowInsetsTest {
    @Test
    @Config(sdk = [23, 30])
    fun `window fixtures remove space already excluded by rendering layout`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-window-insets-android.json").readText()).jsonObject
        for (raw in fixture.getValue("cases").jsonArray) {
            val vector = raw.jsonObject
            fun numbers(key: String) = vector.getValue(key).jsonArray.map { it.jsonPrimitive.int }
            val w = numbers("window")
            val v = numbers("view")
            val i = numbers("insets")
            val result = ExperienceWindowInsets.relativeInsets(
                ExperienceSafeAreaInsets(i[0].toDouble(), i[1].toDouble(), i[2].toDouble(), i[3].toDouble()),
                w[0], w[1], v[0], v[1], v[2], v[3],
            )
            assertEquals(vector.getValue("name").jsonPrimitive.content,
                numbers("expected").map(Int::toDouble), listOf(result.top, result.bottom, result.left, result.right))
        }
    }

    @Test
    @Config(sdk = [30])
    fun `typed insets combine cutouts and visible bars without including IME`() {
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 48))
            .setInsets(WindowInsets.Type.displayCutout(), Insets.of(40, 0, 0, 0))
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 300))
            .build()
        assertEquals(ExperienceSafeAreaInsets(24.0, 48.0, 40.0, 0.0), ExperienceWindowInsets.systemInsets(insets))
    }

    @Test
    @Config(sdk = [29])
    @Suppress("DEPRECATION")
    fun `legacy insets cap keyboard contribution at stable bars and respect hidden bars`() {
        val insets = WindowInsets.Builder()
            .setStableInsets(Insets.of(0, 24, 0, 48))
            .setSystemWindowInsets(Insets.of(0, 0, 0, 300))
            .build()
        assertEquals(ExperienceSafeAreaInsets(0.0, 48.0, 0.0, 0.0), ExperienceWindowInsets.systemInsets(insets))
    }

    @Test
    @Config(sdk = [23])
    @Suppress("DEPRECATION")
    fun `API 23 accepts an empty platform inset without newer API calls`() {
        assertEquals(ExperienceSafeAreaInsets.ZERO, ExperienceWindowInsets.systemInsets(WindowInsets::class.java.getDeclaredConstructor(Rect::class.java).newInstance(Rect())))
    }
}
