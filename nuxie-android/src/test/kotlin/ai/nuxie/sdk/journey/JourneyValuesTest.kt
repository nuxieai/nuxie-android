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
    @Test fun slashLeafKeysRemainExactAndSeparateFromFormFields() {
        fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
        val context = obj("""{"event":{},"responses":{"profile/minutes":20,"profile/settings/day":"2026-10-10","top":7},"formAnswers":{"onboarding":{"minutes":9}}}""")
        val field = obj("""{"type":"Response.Field","key":"profile/minutes"}""")
        assertEquals(kotlinx.serialization.json.JsonPrimitive(20), JourneyValues.resolve(field, context))
        assertEquals(true, JourneyValues.evaluate(obj("""{"type":"Compare","op":">","left":$field,"right":{"type":"Number","value":15}}"""), context))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("2026-10-10"), JourneyValues.resolve(obj("""{"type":"Response.Field","key":"profile/settings/day"}"""), context))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(7), JourneyValues.resolve(obj("""{"type":"Response.Field","key":"top"}"""), context))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(9), JourneyValues.resolve(obj("""{"type":"Response.Field","form":"onboarding","key":"minutes"}"""), context))
        assertEquals(null, JourneyValues.resolve(obj("""{"type":"Response.Field","key":"profile"}"""), context))
    }

    @Test fun formFieldWithoutAnswersDoesNotReadSameNamedState() {
        val field = Json.parseToJsonElement("""{"type":"Response.Field","form":"onboarding","key":"trip_days"}""").jsonObject
        val context = Json.parseToJsonElement("""{"event":{},"responses":{"trip_days":99}}""").jsonObject
        assertEquals(null, JourneyValues.resolve(field, context))
    }

    @Test fun formQualifiedConditionsKeepStateSeparate() {
        fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
        val field = obj("""{"type":"Response.Field","form":"onboarding","key":"trip_days"}""")
        val condition = obj("""{"type":"Compare","op":">","left":$field,"right":{"type":"Number","value":14}}""")
        for ((answer, expected) in listOf(21 to true, 7 to false)) {
            val context = obj("""{"event":{},"responses":{"trip_days":99},"formAnswers":{"onboarding":{"trip_days":$answer}}}""")
            assertEquals(expected, JourneyValues.evaluate(condition, context))
            assertEquals(kotlinx.serialization.json.JsonPrimitive(99),
                JourneyValues.resolve(obj("""{"type":"Response.Field","key":"trip_days"}"""), context))
            assertEquals(null, JourneyValues.resolve(obj("""{"type":"Response.Field","form":"unknown","key":"trip_days"}"""), context))
        }
        val empty = obj("""{"event":{},"responses":{"trip_days":99},"formAnswers":{"onboarding":{}}}""")
        assertEquals(null, JourneyValues.resolve(field, empty))
        assertEquals(null, JourneyValues.evaluate(condition, empty))
    }

    @Test fun sharedValueAndThreeValuedConditionVectors() {
        val vectors = Json.parseToJsonElement(File("../fixtures/journeys/planes/values.json").readText()).jsonObject
        val context = vectors.getValue("context").jsonObject
        val customer = vectors.getValue("customer").jsonObject
        for (vector in vectors.getValue("values").jsonArray.map { it.jsonObject }) {
            val id = vector.getValue("id").jsonPrimitive.content
            val actual = JourneyValues.resolve(vector.getValue("expression").jsonObject, context, customer)
            val known = vector.getValue("known").jsonPrimitive.boolean
            assertEquals(id, known, actual != null)
            if (known) assertEquals(id, vector.getValue("expected"), actual)
        }
        for (vector in vectors.getValue("conditions").jsonArray.map { it.jsonObject }) {
            val expected = vector.getValue("expected").takeUnless { it == JsonNull }?.jsonPrimitive?.booleanOrNull
            assertEquals(vector.getValue("id").jsonPrimitive.content, expected,
                JourneyValues.evaluate(vector.getValue("expression").jsonObject, context, customer))
        }
    }
}
