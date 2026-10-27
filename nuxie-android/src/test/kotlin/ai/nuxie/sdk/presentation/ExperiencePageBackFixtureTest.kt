package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.experiences.ExperiencePageHostContract
import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared Back table (fixtures/sdk/page-back.json) against the page history and Back plan the
 * service uses. Rows, sleeping rows and removals are the page's own and are proved by
 * ExperienceInteractivePageTest; the hosted cells need the coordinator.
 */
class ExperiencePageBackFixtureTest {
    @Test
    fun backTable() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot().resolve("sdk/page-back.json").readText()).jsonObject
        val endpoints = ExperiencePageHostContract.Endpoints(0, true, "", "")
        val declarations = fixture.getValue("declarations").jsonArray.map {
            val value = it.jsonObject
            fun text(key: String) = value.getValue(key).jsonPrimitive.content
            ExperiencePageHostContract.Transition(text("id"), ExperiencePageHostContract.Kind.PAGE, text("from"), text("to"),
                endpoints, endpoints, value.getValue("defaultPair").jsonPrimitive.boolean)
        }
        // A played transition is {kind, id} in the table and the step's own transition object here.
        fun transition(value: kotlinx.serialization.json.JsonElement?): JsonObject? = (value as? JsonObject)?.let {
            buildJsonObject {
                put("type", it.getValue("kind"))
                put("transitionId", it.getValue("id"))
            }
        }
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(18, cases.size)
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val pages = mutableListOf(ExperiencePageHistory(listOf(case.getValue("first").jsonPrimitive.content)))
            for (operation in case.getValue("operations").jsonArray.map { it.jsonObject }) {
                val expect = operation["expect"]?.jsonObject
                when {
                    "forward" in operation -> {
                        val target = operation.getValue("forward").jsonPrimitive.content
                        if (target == pages.last().screens.last()) assertEquals(name, true, expect?.get("stays")?.jsonPrimitive?.boolean)
                        else pages[pages.lastIndex] = pages.last().visit(target, transition(operation["transition"]))
                    }
                    "push" in operation -> pages += ExperiencePageHistory(listOf(operation.getValue("push").jsonPrimitive.content))
                    else -> {
                        val steps = operation.getValue("back").jsonPrimitive.int
                        val back = ExperiencePageBack.of(steps, pages)
                        assertEquals(name, expect?.getValue("target")?.jsonPrimitive?.content, back.target)
                        val across = expect?.get("acrossPages")?.jsonObject
                        if (across != null) {
                            assertEquals(name, across.getValue("page").jsonPrimitive.int, back.page)
                            assertEquals(name, across.getValue("pops").jsonPrimitive.int, back.pagesToClose)
                            // The service cuts the lower page, covered, when its active Screen is not the target.
                            assertEquals(name, across.getValue("cutsLowerPage").jsonPrimitive.boolean,
                                pages[back.page].screens.last() != back.target)
                        } else {
                            assertEquals(name, pages.lastIndex, back.page)
                            val stays = expect?.get("stays")?.jsonPrimitive?.boolean == true
                            assertEquals(name, stays, back.target == pages.last().screens.last())
                            if (!stays) assertEquals(name, transition(expect?.get("plays")?.takeUnless { it is JsonNull }),
                                pages.last().backTransition(steps, back.target) { declarations })
                        }
                        while (pages.lastIndex > back.page) pages.removeAt(pages.lastIndex)
                        pages[back.page] = back.history
                    }
                }
                expect?.get("history")?.let { history ->
                    assertEquals(name, history.jsonArray.map { page -> page.jsonArray.map { it.jsonPrimitive.content } },
                        pages.map { it.screens })
                }
            }
        }
    }
}
