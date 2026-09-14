package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceScreenLifecycleTest {
    @Test
    fun `same screen preparation enters anew without changing the outgoing lifecycle`() {
        val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/navigation-input-handoff-android.json").readText()).jsonObject
        val source = ExperienceScreenLifecycle()
        source.move(ExperienceScreenLifecycle.Phase.ENTERING)
        source.move(ExperienceScreenLifecycle.Phase.ACTIVE)
        val target = source.copyForPreparation()
        assertEquals(ExperienceScreenLifecycle.Phase.HIDDEN, target.phase)
        target.move(ExperienceScreenLifecycle.Phase.ENTERING)
        target.beginPreparedTransition("self")
        assertEquals(contract.getValue("destinationAppearances").jsonPrimitive.content.toULong(), target.appearances)
        assertEquals(contract.getValue("initialAppearances").jsonPrimitive.content.toULong(), source.appearances)
        assertEquals(ExperienceScreenLifecycle.Phase.ACTIVE, source.phase)
    }

    @Test
    fun `shared lifecycle vectors retain appearance counts independently of environment updates`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/runtime-screen-lifecycle.json").readText()).jsonObject
        val state = ExperienceScreenLifecycle()
        assertEquals(ExperienceScreenLifecycle.Phase.HIDDEN, state.phase)
        assertEquals(0uL, state.appearances)
        for (raw in fixture.getValue("steps").jsonArray) {
            val step = raw.jsonObject
            val phase = step.getValue("phase").jsonPrimitive.content
            val transition = step.getValue("transition").jsonPrimitive.content
            val reduced = step.getValue("reduceMotion").jsonPrimitive.boolean
            val values = if (step.getValue("action").jsonPrimitive.content == "reduceMotion") state.updateReduceMotion(reduced)
                else state.move(ExperienceScreenLifecycle.Phase.entries.single { it.wireValue == phase }, transition)
            assertEquals(mapOf(
                "screen/phase" to NuxieViewModelScalarValue.StringValue(phase),
                "screen/appearances" to NuxieViewModelScalarValue.NumberValue(step.getValue("appearances").jsonPrimitive.double),
                "screen/transition" to NuxieViewModelScalarValue.StringValue(transition),
                "env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(reduced),
            ), values)
        }
    }
}
