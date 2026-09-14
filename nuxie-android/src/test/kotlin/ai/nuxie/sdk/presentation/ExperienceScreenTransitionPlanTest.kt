package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceScreenTransitionPlanTest {
    @Test
    fun `shared transition cases resolve direction events layering and reduced motion`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/screen-transition-plan-android.json").readText()).jsonObject
        for (value in fixture.getValue("cases").jsonArray) {
            val case = value.jsonObject
            val declaration = fixture.getValue("declaration").jsonObject.toMutableMap()
            if (case["removeReverse"]?.jsonPrimitive?.boolean == true) declaration.remove("reverse")
            case["reverseOverrides"]?.jsonObject?.let { overrides ->
                declaration["reverse"] = JsonObject(declaration.getValue("reverse").jsonObject + overrides)
            }
            val plan = ExperienceScreenTransitionPlan.resolve(
                case.getValue("intent").jsonObject, JsonArray(listOf(JsonObject(declaration))),
                case["source"]?.jsonPrimitive?.content ?: "one",
                case["destination"]?.jsonPrimitive?.content ?: "two",
            )
            assertEquals(case.toString(), case.getValue("kind").jsonPrimitive.content, plan.kind.name)
            assertFalse("Reduced motion must suppress animation: $case", plan.shouldAnimate(true))
            assertEquals(plan.kind != ExperienceScreenTransitionPlan.Kind.NONE, plan.shouldAnimate(false))
            val expected = case["expected"]?.jsonObject
            if (expected == null) assertNull(plan.custom)
            else {
                val custom = checkNotNull(plan.custom)
                assertEquals("checkout", custom.id)
                assertEquals(expected.getValue("durationMs").jsonPrimitive.long, custom.durationMs)
                assertEquals(expected.getValue("watchdogMs").jsonPrimitive.long, custom.watchdogMs)
                assertEquals(expected.getValue("incomingOnTop").jsonPrimitive.boolean, custom.incomingOnTop)
                assertEquals(expected.getValue("outgoing").jsonPrimitive.content, custom.outgoingCompletionEvent)
                assertEquals(expected.getValue("incoming").jsonPrimitive.content, custom.incomingCompletionEvent)
            }
        }
    }

    @Test
    fun `missing or non-string action kind resolves without animation`() {
        for (intent in listOf(null, buildJsonObject { put("type", 1) }, buildJsonObject { put("type", true) })) {
            val plan = ExperienceScreenTransitionPlan.resolve(intent, JsonArray(emptyList()), "one", "two")
            assertEquals(ExperienceScreenTransitionPlan.Kind.NONE, plan.kind)
        }
    }
}
