package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.journey.JourneyActionType
import ai.nuxie.sdk.runtime.NuxieViewModelScalarValue.StringValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JourneyCommerceFeedbackTest {
    @Test
    fun `store outcomes use the purchase and restore lifecycle contract`() {
        val cases = listOf(
            Triple(JourneyActionType.PURCHASE, "completed", "success"),
            Triple(JourneyActionType.PURCHASE, "failed", "error"),
            Triple(JourneyActionType.PURCHASE, "cancelled", "cancelled"),
            Triple(JourneyActionType.RESTORE, "restored", "success"),
            Triple(JourneyActionType.RESTORE, "failed", "error"),
            Triple(JourneyActionType.RESTORE, "noPurchases", "not_found"),
        )
        for ((type, outlet, status) in cases) {
            val feedback = JourneyCommerceFeedback()
            val path = "paywall/${type.wireValue}"
            val idle = feedback.begin(type, "paywall-screen", "attempt-1")
            assertEquals(StringValue("idle"), idle.values["$path/status"])
            assertEquals(StringValue(""), idle.values["$path/errorCode"])
            assertEquals(StringValue("running"), feedback.running(type, "attempt-1")?.values?.get("$path/status"))
            val outcome = requireNotNull(feedback.outcome(type, outlet, "store-code", "attempt-1"))
            assertEquals("paywall-screen", outcome.screenId)
            assertEquals(StringValue(status), outcome.values["$path/status"])
            assertEquals(StringValue(if (outlet == "failed") "store-code" else ""), outcome.values["$path/errorCode"])
            assertEquals(StringValue("attempt-1"), outcome.values["$path/invocationId"])
            assertNull(feedback.outcome(type, outlet, "store-code", "attempt-1"))
        }
    }

    @Test
    fun `a newer attempt ignores stale outcomes and retains its source screen`() {
        val feedback = JourneyCommerceFeedback()
        feedback.begin(JourneyActionType.PURCHASE, "first-screen", "old-attempt")
        feedback.begin(JourneyActionType.PURCHASE, "next-screen", "new-attempt")
        assertNull(feedback.running(JourneyActionType.PURCHASE, "old-attempt"))
        assertNull(feedback.outcome(JourneyActionType.PURCHASE, "failed", "stale-error", "old-attempt"))
        val outcome = requireNotNull(feedback.outcome(JourneyActionType.PURCHASE, "completed", "", "new-attempt"))
        assertEquals("next-screen", outcome.screenId)
        assertEquals(StringValue("new-attempt"), outcome.values["paywall/purchase/invocationId"])
    }

    @Test
    fun `purchase and restore attempts retain independent invocation ownership`() {
        val feedback = JourneyCommerceFeedback()
        feedback.begin(JourneyActionType.PURCHASE, "buy-screen", "buy-attempt")
        feedback.begin(JourneyActionType.RESTORE, "restore-screen", "restore-attempt")
        assertEquals("buy-screen", feedback.outcome(JourneyActionType.PURCHASE, "cancelled", "", "buy-attempt")?.screenId)
        assertEquals("restore-screen", feedback.outcome(JourneyActionType.RESTORE, "noPurchases", "", "restore-attempt")?.screenId)
    }
}
