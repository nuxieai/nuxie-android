package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceTextInputLimitTest {
    @Test
    fun `composition replacement agrees with exhaustive ASCII capacity oracle`() {
        val strings = (0..3).flatMap { length ->
            (0 until (1 shl length)).map { bits ->
                buildString { repeat(length) { append(if (bits and (1 shl it) == 0) 'a' else 'b') } }
            }
        }
        for (current in strings) for (start in 0..current.length) for (end in start..current.length) {
            for (replacement in strings) for (maximum in current.length..4) {
                val capacity = maximum - current.length + end - start
                assertEquals("$current [$start,$end) + $replacement limit $maximum",
                    replacement.take(capacity),
                    ExperienceTextInputLimit.composingReplacement(current, start, end, replacement, maximum))
            }
        }
    }

    @Test
    fun `every official Unicode 16 boundary yields the expected limited prefix`() {
        val corpus = FixtureRunner.fixturesRoot().resolve("unicode/GraphemeBreakTest-16.0.0.txt").readLines()
        var cases = 0
        corpus.forEachIndexed { index, line ->
            val tokens = line.substringBefore('#').trim().split(Regex("\\s+"))
            if (tokens.first().isEmpty()) return@forEachIndexed
            val text = StringBuilder()
            val boundaries = mutableListOf<Int>()
            tokens.forEach { token ->
                when (token) {
                    "÷" -> boundaries += text.length
                    "×" -> Unit
                    else -> text.appendCodePoint(token.toInt(16))
                }
            }
            val value = text.toString()
            boundaries.forEachIndexed { maximum, end ->
                assertEquals("Unicode line ${index + 1}, limit $maximum", value.substring(0, end),
                    ExperienceTextInputLimit.apply(value, maximum))
            }
            assertEquals(value, ExperienceTextInputLimit.apply(value, boundaries.size))
            cases += 1
        }
        assertEquals(1093, cases)
    }
}
