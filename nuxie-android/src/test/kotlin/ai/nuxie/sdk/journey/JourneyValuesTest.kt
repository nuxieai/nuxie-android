package ai.nuxie.sdk.journey

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyValuesTest {
    @Test fun sharedValueAndThreeValuedConditionVectors() {
        val vectors = Json.parseToJsonElement(File("../fixtures/journeys/planes/values.json").readText()).jsonObject
        val context = vectors.getValue("context").jsonObject
        for (vector in vectors.getValue("values").jsonArray.map { it.jsonObject }) {
            val id = vector.getValue("id").jsonPrimitive.content
            val actual = JourneyValues.resolve(vector.getValue("expression").jsonObject, context)
            val known = vector.getValue("known").jsonPrimitive.boolean
            assertEquals(id, known, actual != null)
            if (known) assertEquals(id, vector.getValue("expected"), actual)
        }
        for (vector in vectors.getValue("conditions").jsonArray.map { it.jsonObject }) {
            val expected = vector.getValue("expected").takeUnless { it == JsonNull }?.jsonPrimitive?.booleanOrNull
            assertEquals(vector.getValue("id").jsonPrimitive.content, expected,
                JourneyValues.evaluate(vector.getValue("expression").jsonObject, context))
        }
    }
}
