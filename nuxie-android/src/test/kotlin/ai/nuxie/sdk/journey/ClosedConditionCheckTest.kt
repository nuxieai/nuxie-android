package ai.nuxie.sdk.journey

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ClosedConditionCheckTest {
    private val now = 1_783_619_940_000L // 2026-07-09 17:59 UTC
    private val noPro = ClosedCondition(Json.parseToJsonElement("""{"type":"Not","arg":{"type":"Feature","op":"has","id":"pro"}}""").jsonObject)
    private fun facts(access: JsonElement, at: Long = now) = ClosedConditionFacts(buildJsonObject { put("access",buildJsonObject { put("pro",access) });put("platform","android") },at,"UTC")
    @Test fun unknownAccessUsesExactDeadlineAndOnlySubstitutesTheFeature() {
        val check = ClosedConditionCheck()
        val start = check.start(noPro,facts(JsonNull),"person-a:profile1",ClosedConditionCheck.Mode.Admission)
        assertEquals(ClosedConditionCheck.Outcome.Pending,start.outcome)
        assertEquals(now + 5_000,start.nextWakeMilliseconds)
        assertEquals(ClosedConditionCheck.Outcome.Pending,check.advance(start.token,facts(JsonNull,now+4_999),"person-a:profile1").outcome)
        assertEquals(ClosedConditionCheck.Outcome.Refused,check.advance(start.token,facts(JsonNull,now+5_000),"person-a:profile1").outcome)
        val early=check.start(noPro,facts(JsonNull),"person-a:profile1",ClosedConditionCheck.Mode.Admission)
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.advance(early.token,facts(JsonPrimitive(false),now+1),"person-a:profile1").outcome)
        val composite=ClosedCondition(Json.parseToJsonElement("""{"type":"And","args":[{"type":"Feature","op":"has","id":"pro"},{"type":"Bool","value":false}]}""").jsonObject)
        val nested=check.start(composite,facts(JsonNull),"person-a:profile1",ClosedConditionCheck.Mode.Admission)
        assertEquals(ClosedConditionCheck.Outcome.Refused,check.advance(nested.token,facts(JsonNull,now+5_000),"person-a:profile1").outcome)
    }
    @Test fun continuationDoesNotRecheckPersonAndCancellationOwnsTimers() {
        val check=ClosedConditionCheck()
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.continueServer(null,facts(JsonPrimitive(true))).outcome)
        val device=ClosedCondition(Json.parseToJsonElement("""{"type":"Compare","op":"==","left":{"type":"Env.Platform"},"right":{"type":"String","value":"android"}}""").jsonObject)
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.continueServer(device,facts(JsonPrimitive(true))).outcome)
        val admission=check.start(noPro,facts(JsonNull),"person-a:profile1",ClosedConditionCheck.Mode.Admission)
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.continueServer(null,facts(JsonPrimitive(true))).outcome)
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.current.outcome)
        assertEquals(ClosedConditionCheck.Outcome.Cancelled,check.advance(admission.token,facts(JsonNull,now+5_000),"person-a:profile1").outcome)
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.current.outcome)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { check.continueServer(noPro,facts(JsonPrimitive(true))) }
        val start=check.start(noPro,facts(JsonNull),"person-a:profile1",ClosedConditionCheck.Mode.Admission)
        assertEquals(ClosedConditionCheck.Outcome.Cancelled,check.advance(start.token,facts(JsonPrimitive(false)),"person-a:profile2").outcome)
        val next=check.start(noPro,facts(JsonNull),"person-b:profile1",ClosedConditionCheck.Mode.Admission)
        assertEquals(ClosedConditionCheck.Outcome.Cancelled,check.advance(start.token,facts(JsonPrimitive(false)),"person-a:profile1").outcome)
        assertEquals(ClosedConditionCheck.Outcome.Pending,check.current.outcome)
        check.cancel()
        assertEquals(ClosedConditionCheck.Outcome.Cancelled,check.advance(next.token,facts(JsonPrimitive(false)),"person-b:profile1").outcome)
    }
    @Test fun triggerlessWaitChecksImmediatelyAndWakeUsesThePersonClock() {
        val check=ClosedConditionCheck()
        val clock=ClosedCondition(Json.parseToJsonElement("""{"type":"Compare","op":">=","left":{"type":"Time.Hour"},"right":{"type":"Number","value":18}}""").jsonObject)
        val start=check.start(clock,facts(JsonNull),"a",ClosedConditionCheck.Mode.Wait(60_000))
        assertEquals(ClosedConditionCheck.Outcome.Pending,start.outcome)
        assertEquals(now+60_000,start.nextWakeMilliseconds)
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.advance(start.token,facts(JsonNull,now+60_000),"a").outcome)
        assertEquals(ClosedConditionCheck.Outcome.Timeout,check.start(clock,facts(JsonNull),"a",ClosedConditionCheck.Mode.Wait(0)).outcome)
        val yes=ClosedCondition(Json.parseToJsonElement("""{"type":"Bool","value":true}""").jsonObject)
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.start(yes,facts(JsonNull),"a",ClosedConditionCheck.Mode.Wait(0)).outcome)
        val event=check.start(yes,facts(JsonNull),"a",ClosedConditionCheck.Mode.Wait(2_000,"selected"))
        assertEquals(ClosedConditionCheck.Outcome.Pending,check.advance(event.token,facts(JsonNull,now+1_999),"a","other").outcome)
        assertEquals(ClosedConditionCheck.Outcome.Satisfied,check.advance(event.token,facts(JsonNull,now+1_999),"a","selected").outcome)
    }
}
