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
import org.junit.Assert.assertFalse
import org.junit.Test

class ExperienceScreenLifecycleTest {
    @Test
    fun `exit reason follows its edge and clears on return`() {
        val state = ExperienceScreenLifecycle()
        val key = "screen/exitReason"
        assertEquals(NuxieViewModelScalarValue.StringValue("none"), state.snapshot()[key])
        assertEquals(NuxieViewModelScalarValue.StringValue("navigation"),
            state.move(ExperienceScreenLifecycle.Phase.EXITING, exitReason = ExperienceScreenLifecycle.ExitReason.NAVIGATION)[key])
        assertEquals(NuxieViewModelScalarValue.StringValue("navigation"), state.updateReduceMotion(true)[key])
        assertEquals(NuxieViewModelScalarValue.StringValue("none"), state.move(ExperienceScreenLifecycle.Phase.HIDDEN, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)[key])
        assertEquals(NuxieViewModelScalarValue.StringValue("none"), state.move(ExperienceScreenLifecycle.Phase.ENTERING, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)[key])
        assertEquals(NuxieViewModelScalarValue.StringValue("dismissed"),
            state.move(ExperienceScreenLifecycle.Phase.EXITING, exitReason = ExperienceScreenLifecycle.ExitReason.DISMISSED)[key])
        assertEquals(NuxieViewModelScalarValue.StringValue("none"), state.move(ExperienceScreenLifecycle.Phase.ACTIVE, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)[key])
    }

    @Test
    fun `font scale follows canonical cases without changing lifecycle or prepared copies`() {
        val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("runtime/font-scale-policy/cases.json").readText()).jsonObject
        val source = ExperienceScreenLifecycle()
        assertEquals(NuxieViewModelScalarValue.NumberValue(1.0), source.snapshot()["env/fontScale"])
        assertFalse(source.snapshot().containsKey("fontScale"))
        source.move(ExperienceScreenLifecycle.Phase.ENTERING, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)
        source.move(ExperienceScreenLifecycle.Phase.ACTIVE, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)
        val scales = contract.getValue("cases").jsonArray.map {
            it.jsonObject.getValue("scale").jsonPrimitive.double.toFloat()
        } + listOf(Float.MIN_VALUE, 4.56789f, Float.MAX_VALUE)
        for (scale in scales) {
            val values = source.updateFontScale(scale)
            assertEquals(NuxieViewModelScalarValue.NumberValue(scale.toDouble()), values["env/fontScale"])
            assertFalse(values.containsKey("fontScale"))
            assertEquals(ExperienceScreenLifecycle.Phase.ACTIVE, source.phase)
            assertEquals(1uL, source.appearances)
            val target = source.copyForPreparation()
            assertEquals(values["env/fontScale"], target.snapshot()["env/fontScale"])
            target.updateFontScale(4f)
            assertEquals(values["env/fontScale"], source.snapshot()["env/fontScale"])
        }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            source.updateFontScale(2f)
            val values = source.updateFontScale(invalid)
            assertEquals(NuxieViewModelScalarValue.NumberValue(1.0), values["env/fontScale"])
            assertFalse(values.containsKey("fontScale"))
        }
    }

    @Test
    fun `same screen preparation enters anew without changing the outgoing lifecycle`() {
        val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/navigation-input-handoff-android.json").readText()).jsonObject
        val source = ExperienceScreenLifecycle()
        source.move(ExperienceScreenLifecycle.Phase.ENTERING, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)
        source.move(ExperienceScreenLifecycle.Phase.ACTIVE, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)
        val target = source.copyForPreparation()
        assertEquals(ExperienceScreenLifecycle.Phase.HIDDEN, target.phase)
        target.move(ExperienceScreenLifecycle.Phase.ENTERING, exitReason = ExperienceScreenLifecycle.ExitReason.NONE)
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
                else state.move(ExperienceScreenLifecycle.Phase.entries.single { it.wireValue == phase }, transition,
                    exitReason = ExperienceScreenLifecycle.ExitReason.entries.single {
                        it.wireValue == step.getValue("exitReason").jsonPrimitive.content
                    })
            assertEquals(mapOf(
                "screen/phase" to NuxieViewModelScalarValue.StringValue(phase),
                "screen/appearances" to NuxieViewModelScalarValue.NumberValue(step.getValue("appearances").jsonPrimitive.double),
                "screen/transition" to NuxieViewModelScalarValue.StringValue(transition),
                "screen/exitReason" to NuxieViewModelScalarValue.StringValue(step.getValue("exitReason").jsonPrimitive.content),
                "env/reduceMotion" to NuxieViewModelScalarValue.BooleanValue(reduced),
                "env/fontScale" to NuxieViewModelScalarValue.NumberValue(1.0),
            ), values)
        }
    }
}
