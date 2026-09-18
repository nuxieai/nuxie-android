package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExperienceVideoDecodeCostTest {
    @Test fun accountsForVariableRateAndLoopBoundary() {
        assertEquals(20_000L, ExperienceVideoDecodeCost.pixelsPerSecond(100, 10, 1_000_000, listOf(0, 500_000, 550_000)))
        assertEquals(100_000L, ExperienceVideoDecodeCost.pixelsPerSecond(100, 10, 1_000_000, listOf(990_000, 0)))
        assertEquals(10_000L, ExperienceVideoDecodeCost.pixelsPerSecond(100, 10, 100_000, listOf(0)))
    }

    @Test fun rejectsUnboundedOrAmbiguousTiming() {
        for (times in listOf(emptyList(), listOf(0L, 0L), listOf(-1L), listOf(0L, 1_000_000L))) {
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceVideoDecodeCost.pixelsPerSecond(100, 10, 1_000_000, times)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExperienceVideoDecodeCost.pixelsPerSecond(100, 10, 1_000_000, List(100_001) { it.toLong() })
        }
    }
}
