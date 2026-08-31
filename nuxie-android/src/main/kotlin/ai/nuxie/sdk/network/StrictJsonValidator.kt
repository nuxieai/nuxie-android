package ai.nuxie.sdk.network

import java.io.IOException
import kotlinx.serialization.json.Json

/**
 * Duplicate-JSON-key rejection, ported from the iOS
 * `StrictJSONDuplicateKeyValidator`: kotlinx (like Foundation) silently keeps
 * the last duplicate, which can smuggle conflicting server state past
 * validation. Validate JSON tokens as well: kotlinx can preserve unquoted
 * primitives and non-finite numbers inside otherwise opaque JsonElements.
 */
internal object StrictJsonValidator {
    class DuplicateKeyException(val key: String) :
        IOException("Duplicate JSON object key: $key")

    @Throws(DuplicateKeyException::class)
    fun requireNoDuplicateKeys(text: String) {
        val scanner = Scanner(text)
        scanner.skipWhitespace()
        scanner.scanValue()
        scanner.skipWhitespace()
        if (!scanner.done()) throw IOException("Trailing JSON content")
    }

    private class Scanner(private val text: String) {
        private var index = 0

        fun done(): Boolean = index >= text.length

        fun scanValue(depth: Int = 0) {
            if (depth > 64) throw IOException("JSON nesting exceeds the supported depth")
            skipWhitespace()
            if (done()) throw IOException("Missing JSON value")
            when (text[index]) {
                '{' -> scanObject(depth + 1)
                '[' -> scanArray(depth + 1)
                '"' -> scanString()
                else -> scanScalar()
            }
        }

        private fun scanObject(depth: Int) {
            index++ // consume {
            val seen = HashSet<String>()
            skipWhitespace()
            if (!done() && text[index] == '}') { index++; return }
            while (!done()) {
                skipWhitespace()
                val key = scanString()
                if (!seen.add(key)) throw DuplicateKeyException(key)
                skipWhitespace()
                consume(':')
                scanValue(depth)
                skipWhitespace()
                if (done()) throw IOException("Unterminated JSON object")
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return }
                    else -> throw IOException("Invalid JSON object separator")
                }
            }
            throw IOException("Unterminated JSON object")
        }

        private fun scanArray(depth: Int) {
            index++ // consume [
            skipWhitespace()
            if (!done() && text[index] == ']') { index++; return }
            while (!done()) {
                scanValue(depth)
                skipWhitespace()
                if (done()) throw IOException("Unterminated JSON array")
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return }
                    else -> throw IOException("Invalid JSON array separator")
                }
            }
            throw IOException("Unterminated JSON array")
        }

        /**
         * Decode keys before comparing them: `key` and `k\u0065y` name the same
         * JSON member and must not carry conflicting authority.
         */
        private fun scanString(): String {
            consume('"')
            val start = index
            while (!done()) {
                when (text[index]) {
                    '\\' -> {
                        index++
                        if (done()) throw IOException("Unterminated JSON escape")
                        when (text[index++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> repeat(4) {
                                if (done() || text[index++].digitToIntOrNull(16) == null) throw IOException("Invalid JSON Unicode escape")
                            }
                            else -> throw IOException("Invalid JSON escape")
                        }
                    }
                    '"' -> {
                        val raw = text.substring(start, index)
                        index++
                        return Json.decodeFromString<String>("\"$raw\"")
                    }
                    else -> {
                        if (text[index].code < 0x20) throw IOException("Unescaped JSON control character")
                        index++
                    }
                }
            }
            throw IOException("Unterminated JSON string")
        }

        fun skipWhitespace() {
            while (!done() && text[index] in " \t\r\n") index++
        }

        private fun scanScalar() {
            val start = index
            while (!done() && text[index] !in ",}] \t\r\n") index++
            val value = text.substring(start, index)
            if (value in setOf("true", "false", "null")) return
            if (!NUMBER.matches(value) || value.toDoubleOrNull()?.isFinite() != true) throw IOException("Invalid JSON primitive")
        }

        private fun consume(expected: Char) {
            if (done() || text[index] != expected) throw IOException("Expected JSON '$expected'")
            index++
        }

        private companion object {
            val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        }
    }
}
