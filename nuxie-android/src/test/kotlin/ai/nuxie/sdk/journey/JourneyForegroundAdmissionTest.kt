package ai.nuxie.sdk.journey

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JourneyForegroundAdmissionTest {
    @Test fun visibilityAndAuthorityFollowCanonicalGenerations() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("sdk/android-foreground-admission.json").readText()).jsonObject
        for (vector in fixture.getValue("cases").jsonArray) {
            val test = vector.jsonObject
            val gate = JourneyForegroundAdmission()
            val tokens = mutableListOf<Long>()
            assertFalse(gate.get())
            val active = test.getValue("operations").jsonArray.map { operation ->
                when (operation.jsonPrimitive.content) {
                    "visible" -> {
                        gate.visibilityChanged(true)
                        tokens += checkNotNull(gate.revalidationToken())
                    }
                    "background" -> gate.visibilityChanged(false)
                    "complete" -> gate.completeRevalidation(tokens.last())
                    "complete-old" -> gate.completeRevalidation(tokens.first())
                    else -> error("Unknown fixture operation")
                }
                gate.get()
            }
            assertEquals(test.getValue("name").jsonPrimitive.content,
                test.getValue("expectedActive").jsonArray.map { it.jsonPrimitive.boolean }, active)
        }
    }
}
