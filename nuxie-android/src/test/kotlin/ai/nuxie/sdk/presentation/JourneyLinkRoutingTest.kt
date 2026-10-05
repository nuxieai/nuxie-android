package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class JourneyLinkRoutingTest {
    @Test fun `shared targets call exactly one opener`() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("events/runtime-link-targets.json").readText()).jsonObject
        for (entry in fixture.getValue("cases").jsonArray) {
            val vector = entry.jsonObject
            val url = vector.getValue("url").jsonPrimitive.content
            val target = vector["target"]?.jsonPrimitive?.contentOrNull
            val destination = vector["destination"]?.jsonPrimitive?.contentOrNull
            val calls = mutableListOf<String>()
            val opened = JourneyLinkRouting.open(url, target,
                { calls += "in_app"; true }, { calls += "external"; true })
            assertEquals(url, destination?.let(::listOf) ?: emptyList<String>(), calls)
            assertEquals(url, destination != null, opened)
        }
    }

    @Test fun `an unavailable external handler does not report success`() {
        assertFalse(JourneyLinkRouting.open("sampleapp://item", "_blank", { fail("Unexpected in-app route"); false }, { false }))
    }
}
