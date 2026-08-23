package ai.nuxie.sdk.events

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeBasedEpochGeneratorTest {
    @Test
    fun idsAtTheSameMillisecondIncrementTheirRandomTail() {
        val generator = TimeBasedEpochGenerator(
            currentTimeMillis = { 0x0198_f0a4_7e11 },
            fillRandomBytes = { bytes -> bytes.fill(0) },
        )

        assertEquals("0198f0a4-7e11-7000-8000-000000000000", generator.next())
        assertEquals("0198f0a4-7e11-7000-8000-000000000001", generator.next())
    }

    @Test
    fun sameMillisecondEntropyOverflowWrapsLikeIos() {
        val generator = TimeBasedEpochGenerator(
            currentTimeMillis = { 0x0198_f0a4_7e11 },
            fillRandomBytes = { bytes -> bytes.fill(0xff.toByte()) },
        )

        assertEquals("0198f0a4-7e11-7fff-bfff-ffffffffffff", generator.next())
        assertEquals("0198f0a4-7e11-7000-8000-000000000000", generator.next())
    }
}
