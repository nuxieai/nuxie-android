package ai.nuxie.sdk

import ai.nuxie.sdk.fixtures.FixtureRunner
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActionEncodingConformanceTest {
    @Test
    fun everyAppActionVectorResolvesAndEncodesToTheExpectedWireValue() {
        val coveredCases = mutableSetOf<String>()
        var fixtureExercisesNullOmission = false

        FixtureRunner.run(
            relativePath = "encodings/app-action.json",
            expectedSuite = "encodings/app-action",
        ) { vector ->
            val input = vector.body.getValue("action").jsonObject
            val experience = input.getValue("experience").jsonObject
            val authoredPayload = input["payload"]
                ?.takeUnless { it is JsonNull }
                ?.jsonObject
            fixtureExercisesNullOmission = fixtureExercisesNullOmission ||
                authoredPayload?.values?.any { it is JsonNull } == true
            val rawPayload = authoredPayload?.mapValues { (_, value) -> value.toResolvedInput() }
            val payload = rawPayload?.let(AppActionValueResolver::resolvedRecord)
            payload?.values?.forEach { value ->
                coveredCases += when (value) {
                    is AppActionValue.String -> "string"
                    is AppActionValue.Int -> "int"
                    is AppActionValue.Double -> "double"
                    is AppActionValue.Bool -> "bool"
                }
            }
            val action = AppAction(
                name = input.getValue("name").jsonPrimitive.content,
                payload = payload,
                experience = ExperienceRef(
                    experienceId = experience.getValue("experienceId").jsonPrimitive.content,
                    experienceVersion = experience.optionalString("experienceVersion"),
                    journeyId = experience.optionalString("journeyId"),
                ),
            )

            assertEquals(
                "[${vector.name}]",
                vector.body.getValue("expect").jsonObject,
                AppActionWireEncoder.encode(action),
            )
        }

        assertEquals(setOf("string", "int", "double", "bool"), coveredCases)
        assertTrue("fixture must contain an authored null field to exercise omission", fixtureExercisesNullOmission)
    }

    @Test
    fun containerFloatPromotesThroughDoubleLikeIOSFoundation() {
        assertResolvedContainer(
            // iOS JSONSerialization after Float(0.1) is promoted through NSNumber/Double.
            expected = "{\"value\":0.10000000149011612}",
            container = mapOf("value" to 0.1f),
        )
    }

    @Test
    fun containerDoubleUsesTheIOSFoundationRoundTripDigits() {
        assertResolvedContainer(
            // iOS JSONSerialization spells Double(0.1) with 17 significant digits.
            expected = "{\"value\":0.10000000000000001}",
            container = mapOf("value" to 0.1),
        )
    }

    @Test
    fun containerDoubleUsesIOSFoundationExponentSpellings() {
        assertResolvedContainer(
            // iOS switches below -4 and at 17, with lowercase e and a signed exponent.
            expected = "[0.0001,1.0000000000000001e-05,10000000000000000,1e+17]",
            container = listOf(1e-4, 1e-5, 1e16, 1e17),
        )
    }

    @Test
    fun containerDoublePreservesIOSFoundationNegativeZeroSpelling() {
        assertResolvedContainer(
            // iOS JSONSerialization preserves the sign but omits the fractional suffix.
            expected = "[-0,0]",
            container = listOf(-0.0, 0.0),
        )
    }

    @Test
    fun containerDoubleOmitsIOSFoundationIntegralSuffix() {
        assertResolvedContainer(
            // iOS emits integral doubles without `.0`, including below its exponent threshold.
            expected = "[1,10000000000000000]",
            container = listOf(1.0, 1e16),
        )
    }

    @Test
    fun containerDecimalUsesIOSFoundationScaleNormalization() {
        assertResolvedContainer(
            // iOS Decimal serialization removes insignificant scale and normalizes signed zero.
            expected = "[1.23,1000,0]",
            container = listOf(BigDecimal("1.2300"), BigDecimal("1000.00"), BigDecimal("-0.00")),
        )
    }

    @Test
    fun resolvedPayloadOmitsNonFiniteScalarsAndContainersThatAreNotStrictJson() {
        val payload = AppActionValueResolver.resolvedRecord(
            mapOf(
                "finite" to 1.5,
                "infinite" to Double.POSITIVE_INFINITY,
                "decimalContainer" to mapOf("amount" to BigDecimal("1.25")),
                "invalidContainer" to buildJsonObject {
                    put("notJson", JsonPrimitive(Double.NaN))
                },
                "setIsNotJson" to setOf(1, 2),
            ),
        )

        assertEquals(
            mapOf(
                "finite" to AppActionValue.Double(1.5),
                "decimalContainer" to AppActionValue.String("{\"amount\":1.25}"),
            ),
            payload,
        )
    }

    @Test
    fun publicDoubleCaseMirrorsTheReferenceWithoutConstructorValidation() {
        assertEquals(Double.POSITIVE_INFINITY, AppActionValue.Double(Double.POSITIVE_INFINITY).value, 0.0)
    }

    private fun kotlinx.serialization.json.JsonElement.toResolvedInput(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> mapValues { (_, value) -> value.toResolvedInput() }
        is JsonArray -> map { it.toResolvedInput() }
        is JsonPrimitive -> when {
            isString -> content
            content == "true" || content == "false" -> boolean
            content.contains('.') || content.contains('e', ignoreCase = true) -> double
            else -> long
        }
    }

    private fun JsonObject.optionalString(key: String): String? =
        getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun assertResolvedContainer(expected: String, container: Any) {
        val resolved = AppActionValueResolver.resolved(container) as AppActionValue.String
        assertEquals(expected, resolved.value)
    }

    @Test
    fun `nested object payloads serialize with recursively sorted keys like the iOS reference`() {
        val resolved = AppActionValueResolver.resolved(
            linkedMapOf(
                "zebra" to 1,
                "alpha" to linkedMapOf("delta" to true, "beta" to "x"),
            ),
        )
        val value = resolved as AppActionValue.String
        assertEquals(
            """{"alpha":{"beta":"x","delta":true},"zebra":1}""",
            value.value,
        )
    }
}
