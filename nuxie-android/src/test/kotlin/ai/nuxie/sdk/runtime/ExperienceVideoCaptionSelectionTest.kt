package ai.nuxie.sdk.runtime

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceVideoCaptionSelectionTest {
    @Test fun captionLanguageSelectionContract() {
        val fixture = FixtureRunner.fixturesRoot().resolve("video/caption-selection.json")
        val examples = Json.parseToJsonElement(fixture.readText()).jsonObject.getValue("cases").jsonArray
        for (example in examples) {
            val value = example.jsonObject
            assertEquals(value.getValue("name").jsonPrimitive.content,
                value.getValue("expected").jsonPrimitive.intOrNull,
                ExperienceVideoCaptionSelection.index(
                    value.getValue("languages").jsonArray.map { it.jsonPrimitive.contentOrNull },
                    value.getValue("preferred").jsonArray.map { it.jsonPrimitive.content }))
        }
    }
}
