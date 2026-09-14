package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceSafeAreaInsetMapperTest {
    @Test
    fun `shared safe area vectors project into authored artboard coordinates`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-safe-area.json").readText()).jsonObject
        for (raw in fixture.getValue("cases").jsonArray) {
            val vector = raw.jsonObject
            fun numbers(key: String) = vector.getValue(key).jsonArray.map { it.jsonPrimitive.double }
            val view = numbers("view")
            val artboard = numbers("artboard")
            val insets = numbers("insets")
            val expected = numbers("expected")
            val result = ExperienceSafeAreaInsetMapper.artboardInsets(
                ExperienceSafeAreaInsets(insets[0], insets[1], insets[2], insets[3]),
                view[0], view[1], artboard[0], artboard[1],
            )
            val values = result.stateValues()
            for ((index, side) in listOf("top", "bottom", "left", "right").withIndex()) {
                assertEquals(vector.getValue("name").jsonPrimitive.content,
                    expected[index], (values.getValue("safeArea/$side") as NuxieViewModelScalarValue.NumberValue).value, 1e-9)
            }
        }
    }
}
