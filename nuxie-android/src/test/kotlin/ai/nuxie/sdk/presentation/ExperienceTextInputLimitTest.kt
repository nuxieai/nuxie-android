package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceTextInputLimitTest {
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
