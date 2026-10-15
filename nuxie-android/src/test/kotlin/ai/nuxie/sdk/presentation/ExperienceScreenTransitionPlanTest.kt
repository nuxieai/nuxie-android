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

    @Test fun `dismiss accepts only the decided all scope`() {
        for (text in listOf("{\"type\":\"dismiss\"}", "{\"type\":\"dismiss\",\"scope\":\"all\"}")) {
            ai.nuxie.sdk.experiences.JourneyGrammar.action(Json.parseToJsonElement(text), emptySet(), emptySet())
        }
        for (scope in listOf(JsonPrimitive("top"), JsonPrimitive("layer"), JsonPrimitive(1), kotlinx.serialization.json.JsonNull)) {
            val result = runCatching { ai.nuxie.sdk.experiences.JourneyGrammar.action(
                JsonObject(mapOf("type" to JsonPrimitive("dismiss"), "scope" to scope)), emptySet(), emptySet()) }
            assertTrue("Unknown or non-string dismiss scope is refused", result.isFailure)
        }
    }

    @Test fun `shared modal options validate and select each native layer`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/presentation-options.json").readText()).jsonObject
        for (value in fixture.getValue("cases").jsonArray) {
            val item = value.jsonObject
            val transition = item.getValue("transition").jsonObject
            val action = JsonObject(mapOf("type" to JsonPrimitive("navigate"), "screenId" to JsonPrimitive("one"), "transition" to transition))
            ai.nuxie.sdk.experiences.JourneyGrammar.action(action, setOf("one"), emptySet())
            val plan = ExperienceScreenTransitionPlan.resolve(transition, JsonArray(emptyList()), "one", "two")
            assertEquals(ExperienceScreenTransitionPlan.Kind.MODAL, plan.kind)
            val shell = PresentationShell.fromTransition(transition)
            assertTrue(item.getValue("available").jsonPrimitive.boolean)
            assertEquals(item.getValue("closedby").jsonPrimitive.content == "any", shell.dismissible)
            when (item.getValue("present").jsonPrimitive.content) {
                "sheet" -> {
                    assertTrue(shell is PresentationShell.Sheet)
                    val expected = item.getValue("height").jsonPrimitive.content.replace('-', '_').uppercase(java.util.Locale.ROOT)
                    assertEquals(expected, (shell as PresentationShell.Sheet).detent.name)
                }
                "fullscreen" -> assertTrue(shell is PresentationShell.FullScreen)
            }
            val first = PresentationShell.fromTransition(transition)
            assertEquals(item.getValue("firstLayer").jsonPrimitive.content == "sheet", first is PresentationShell.Sheet)
        }
        for (transition in fixture.getValue("invalid").jsonArray) {
            val action = JsonObject(mapOf("type" to JsonPrimitive("navigate"), "screenId" to JsonPrimitive("one"), "transition" to transition))
            assertThrows(ai.nuxie.sdk.experiences.JourneyReleaseAuthenticationException::class.java) {
                ai.nuxie.sdk.experiences.JourneyGrammar.action(action, setOf("one"), emptySet())
            }
        }
    }

    @Test fun `removed and unknown presentations are refused without a sheet fallback`() {
        for (kind in listOf("dialog", "unknown")) {
            val transition = buildJsonObject { put("type", "modal"); put("present", kind) }
            assertThrows(ExperiencePresentationException::class.java) {
                PresentationShell.fromTransition(transition)
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
