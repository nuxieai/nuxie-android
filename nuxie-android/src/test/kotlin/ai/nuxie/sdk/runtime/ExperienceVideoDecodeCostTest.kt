package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExperienceVideoDecodeCostTest {
    @Test fun budgetsExpandedPixelsWithoutDiscountingCodedWork() {
        val times = listOf(0L, 500_000L)
        assertEquals(8192L, ExperienceVideoDecodeCost.pixelsPerSecond(64, 32, 1_000_000, times, 2, 1))
        assertEquals(4096L, ExperienceVideoDecodeCost.pixelsPerSecond(64, 32, 1_000_000, times, 1, 2))
        assertEquals(66L, ExperienceVideoDecodeCost.pixelsPerSecond(10, 1, 1_000_000, times, 13, 4))
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 2, Int.MAX_VALUE to 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                ExperienceVideoDecodeCost.pixelsPerSecond(64, 32, 1_000_000, times, width, height)
            }
        }
    }

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
