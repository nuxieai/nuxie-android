package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.runtime.NuxieFocusInput
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExperienceFocusInputTest {
    @Test fun `Unicode 16 grapheme conformance`() {
        val file = FixtureRunner.fixturesRoot().resolve("input/unicode16/GraphemeBreakTest.txt")
        var cases = 0
        file.readLines().forEachIndexed { lineNumber, line ->
            val body = line.substringBefore('#').trim()
            if (body.isEmpty()) return@forEachIndexed
            val expected = mutableListOf<String>()
            val cluster = StringBuilder()
            body.split(Regex("\\s+")).forEach { token ->
                when (token) {
                    "÷" -> if (cluster.isNotEmpty()) { expected.add(cluster.toString()); cluster.setLength(0) }
                    "×" -> Unit
                    else -> cluster.appendCodePoint(token.toInt(16))
                }
            }
            val text = expected.joinToString("")
            val actual = mutableListOf<String>()
            ExperienceGrapheme.forEachCluster(text) { start, end, _ -> actual.add(text.substring(start, end)) }
            assertEquals("Unicode conformance line ${lineNumber + 1}", expected, actual)
            cases++
        }
        assertEquals(1_093, cases)
    }

    @Test fun `paste boundaries match shared oracle`() {
        val cases = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("input/text-boundaries.json").readText()).jsonObject.getValue("cases").jsonArray
        fun expand(pieces: JsonElement) = pieces.jsonArray.joinToString("") { piece ->
            val value = piece.jsonObject
            value.getValue("text").jsonPrimitive.content.repeat(value.getValue("repeat").jsonPrimitive.int)
        }
        assertEquals(8, cases.size)
        cases.forEach { row ->
            val item = row.jsonObject
            val queue = ExperienceFocusInputQueue()
            queue.add(NuxieFocusInput.Text(expand(item.getValue("input"))))
            val expected = item.getValue("chunks").jsonArray.map { NuxieFocusInput.Text(expand(it)) }
            assertEquals(item.getValue("name").jsonPrimitive.content, expected, queue.takeBatch())
            assertTrue(queue.isEmpty)
        }
    }

    @Test fun `empty text multibyte text and 3 MiB paste preserve order`() {
        val queue = ExperienceFocusInputQueue()
        queue.add(NuxieFocusInput.Text(""))
        val prefix = "a".repeat(1_048_575)
        queue.add(NuxieFocusInput.Text(prefix + "éb"))
        assertEquals(listOf(NuxieFocusInput.Text(""), NuxieFocusInput.Text(prefix), NuxieFocusInput.Text("éb")), queue.takeBatch())
        queue.add(NuxieFocusInput.Text("🙂".repeat(786_432)))
        val chunk = NuxieFocusInput.Text("🙂".repeat(262_144))
        assertEquals(listOf(chunk, chunk, chunk), queue.takeBatch())
        assertTrue(queue.isEmpty)
    }

    @Test fun `step byte and input limits preserve every queued item`() {
        val queue = ExperienceFocusInputQueue()
        val chunk = "a".repeat(1_048_576)
        queue.add(NuxieFocusInput.Text(chunk.repeat(5)))
        assertEquals(List(4) { NuxieFocusInput.Text(chunk) }, queue.takeBatch())
        assertEquals(listOf(NuxieFocusInput.Text(chunk)), queue.takeBatch())
        val keys = (0 until 5_000).map { NuxieFocusInput.Key(it, 0, true, false) }
        keys.forEach(queue::add)
        assertEquals(keys.subList(0, 4_096), queue.takeBatch())
        assertEquals(keys.subList(4_096, 5_000), queue.takeBatch())
        assertTrue(queue.isEmpty)
    }
}
