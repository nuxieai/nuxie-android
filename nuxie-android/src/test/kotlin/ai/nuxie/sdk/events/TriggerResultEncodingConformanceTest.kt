package ai.nuxie.sdk.events

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.JourneyUpdate
import ai.nuxie.sdk.TriggerError
import ai.nuxie.sdk.TriggerErrorCode
import ai.nuxie.sdk.TriggerResult
import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TriggerResultEncodingConformanceTest {
    @Test
    fun everyTriggerResultVectorEncodesToTheExpectedWireMap() {
        FixtureRunner.run(
            relativePath = "encodings/trigger-result.json",
            expectedSuite = "encodings/trigger-result",
        ) { vector ->
            val input = vector.body.getValue("result").jsonObject
            val kind = input.getValue("kind").jsonPrimitive.content
            val source = (input["source"] as? JsonPrimitive)?.content

            val result: TriggerResult = when (kind) {
                "noMatch" -> TriggerResult.NoMatch
                "allowed" -> TriggerResult.Allowed
                "denied" -> TriggerResult.Denied
                "journeyCompleted" -> TriggerResult.JourneyCompleted(
                    JourneyUpdate(
                        ref = ExperienceRef(
                            experienceId = "exp-any",
                            experienceVersion = null,
                            journeyId = (input["journey_id"] as? JsonPrimitive)?.content,
                        ),
                        exitReason = requireNotNull(
                            TriggerWireEncoder.parseExitReason(
                                input.getValue("exit_reason").jsonPrimitive.content,
                            ),
                        ),
                        goalMet = input.getValue("goal_met").jsonPrimitive.content.toBoolean(),
                    ),
                )
                "error" -> TriggerResult.Error(
                    TriggerError(
                        TriggerErrorCode.valueOf(
                            input.getValue("code").jsonPrimitive.content.uppercase(),
                        ),
                        "message",
                    ),
                )
                else -> error("Unknown vector kind: $kind")
            }

            val expected = vector.body.getValue("expect").jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.content }
            assertEquals(
                "[${vector.name}]",
                expected,
                TriggerWireEncoder.encode(result, wireSource = source).toMap(),
            )
        }
    }
}
