package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceLayoutSafeAreaTest {
    @Test fun `shared safe area vectors publish local insets in points`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-safe-area.json").readText()).jsonObject
        for (raw in fixture.getValue("cases").jsonArray) {
            val c = raw.jsonObject
            fun numbers(key: String) = c.getValue(key).jsonArray.map { it.jsonPrimitive.double }
            val pixels = numbers("pixelInsets")
            val expected = numbers("expected")
            val values = experienceSafeAreaInsets(ExperienceSafeAreaInsets(pixels[0], pixels[1], pixels[2], pixels[3]),
                c.getValue("scale").jsonPrimitive.float).stateValues()
            for ((index, side) in listOf("top", "bottom", "left", "right").withIndex()) {
                assertEquals(c.getValue("name").jsonPrimitive.content,
                    expected[index], (values.getValue("safeArea/$side") as NuxieViewModelScalarValue.NumberValue).value, 1e-9)
            }
        }
    }

    @Test fun `invalid density has no safe area publication`() {
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(ExperienceSafeAreaInsets.ZERO,
                experienceSafeAreaInsets(ExperienceSafeAreaInsets(147.0, 63.0, 21.0, 42.0), density))
        }
    }
}
