package ai.nuxie.sdk.journey

import ai.nuxie.sdk.experiences.JourneyReleaseAuthenticationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class ClosedConditionPolicyTest {
    private fun policy(trigger: String, extra: String = "") = Json.parseToJsonElement("""{"entry":{"trigger":$trigger,"frequency":{"type":"every_match"}},"exitWhenAny":[]$extra}""").jsonObject
    @Test fun oneFilterOnEveryTrigger() {
        for (trigger in listOf("""{"type":"event","eventName":"start"}""", """{"type":"api"}""", """{"type":"server_event","connectorKey":"store","triggerKey":"paid"}""", """{"type":"segment_enter","segmentId":"gold"}""", """{"type":"segment_leave","segmentId":"gold"}""")) {
            val filtered = trigger.dropLast(1) + """, "condition":{"type":"Not","arg":{"type":"Feature","op":"has","id":"pro"}}}"""
            ClosedConditionPolicy(policy(filtered))
            val old = policy(filtered).toString().replace("\"frequency\":", "\"eligibility\":{\"type\":\"Bool\",\"value\":true},\"frequency\":")
            assertThrows(JourneyReleaseAuthenticationException::class.java) { ClosedConditionPolicy(Json.parseToJsonElement(old).jsonObject) }
            assertThrows(JourneyReleaseAuthenticationException::class.java) { ClosedConditionPolicy(Json.parseToJsonElement(policy(filtered).toString().replace("\"exitWhenAny\":[]", "\"exitWhenAny\":[{\"type\":\"eligibility_lost\"}]")).jsonObject) }
        }
    }
    @Test fun entryRunAndSegmentGoalOrExitConditionsAreRefused() {
        val plain = policy("""{"type":"api"}""").toString()
        ClosedConditionPolicy(Json.parseToJsonElement(plain).jsonObject)
        val goal = """, "goal":{"criterion":{"type":"segment_enter","segmentId":"gold","condition":{"type":"Bool","value":true}},"attribution":{"basis":"entry","window":{"amount":1,"unit":"day"}}}"""
        assertThrows(JourneyReleaseAuthenticationException::class.java) { ClosedConditionPolicy(policy("""{"type":"api"}""", goal)) }
        assertThrows(JourneyReleaseAuthenticationException::class.java) { ClosedConditionPolicy(Json.parseToJsonElement(plain.replace("\"exitWhenAny\":[]", "\"exitWhenAny\":[{\"type\":\"segment_leave\",\"segmentId\":\"gold\",\"condition\":{\"type\":\"Bool\",\"value\":true}}]")).jsonObject) }
        assertThrows(JourneyReleaseAuthenticationException::class.java) { ClosedConditionPolicy(policy("""{"type":"api","condition":{"type":"Run.Value","path":"responses.onboarding.done"}}""")) }
    }
    @Test fun goalsUseOccurrenceEvidence() {
        for ((node, accepted) in listOf("Customer.Field" to false, "Event.Field" to true, "Env.Platform" to true, "Time.Minute" to true)) {
            val value = if (node.endsWith("Field")) """{"type":"$node","key":"plan"}""" else """{"type":"$node"}"""
            val extra = """, "goal":{"criterion":{"type":"event","eventName":"paid","condition":{"type":"Compare","op":"==","left":$value,"right":{"type":"String","value":"ios"}}},"attribution":{"basis":"entry","window":{"amount":1,"unit":"day"}}}"""
            if (accepted) ClosedConditionPolicy(policy("""{"type":"api"}""",extra)) else assertThrows(JourneyReleaseAuthenticationException::class.java) { ClosedConditionPolicy(policy("""{"type":"api"}""",extra)) }
        }
    }
}
