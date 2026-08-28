package ai.nuxie.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NuxieActivityEqualityTest {
    @Test
    fun activityDoubleValueUsesSwiftEqualitySemantics() {
        val nan = NuxieActivityValue.Double(Double.NaN)

        assertEquals(NuxieActivityValue.Double(-0.0), NuxieActivityValue.Double(0.0))
        assertEquals(
            NuxieActivityValue.Double(-0.0).hashCode(),
            NuxieActivityValue.Double(0.0).hashCode(),
        )
        assertFalse(nan == nan)
        assertFalse(NuxieActivityValue.Double(Double.NaN) == NuxieActivityValue.Double(Double.NaN))
    }

    @Test
    fun featureUsedAmountUsesSwiftEqualitySemantics() {
        val negativeZero = NuxieActivity.FeatureUsed("credits", -0.0, "account-1")
        val positiveZero = NuxieActivity.FeatureUsed("credits", 0.0, "account-1")
        val nan = NuxieActivity.FeatureUsed("credits", Double.NaN, "account-1")

        assertEquals(negativeZero, positiveZero)
        assertEquals(negativeZero.hashCode(), positiveZero.hashCode())
        assertFalse(nan == nan)
        assertFalse(
            NuxieActivity.FeatureUsed("credits", Double.NaN, "account-1") ==
                NuxieActivity.FeatureUsed("credits", Double.NaN, "account-1"),
        )
    }
}
