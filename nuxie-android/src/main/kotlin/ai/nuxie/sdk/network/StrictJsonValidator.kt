package ai.nuxie.sdk.network

import java.io.IOException
import kotlinx.serialization.json.Json

/**
 * Duplicate-JSON-key rejection, ported from the iOS
 * `StrictJSONDuplicateKeyValidator`: kotlinx (like Foundation) silently keeps
 * the last duplicate, which can smuggle conflicting server state past
 * validation. This is a structural scan only — full parsing stays with
 * kotlinx afterwards.
 */
internal object StrictJsonValidator {
    class DuplicateKeyException(val key: String) :
        IOException("Duplicate JSON object key: $key")

    @Throws(DuplicateKeyException::class)
    fun requireNoDuplicateKeys(text: String) {
        val scanner = Scanner(text)
        scanner.skipWhitespace()
        if (scanner.done()) return
        scanner.scanValue()
    }

    private class Scanner(private val text: String) {
        private var index = 0

        fun done(): Boolean = index >= text.length

        fun scanValue(depth: Int = 0) {
            if (depth > 64) throw IOException("JSON nesting exceeds the supported depth")
            skipWhitespace()
            if (done()) return
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
                if (!done() && text[index] == ':') index++
                scanValue(depth)
                skipWhitespace()
                if (done()) return
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return }
                    else -> index++ // malformed; kotlinx will reject properly
                }
            }
        }

        private fun scanArray(depth: Int) {
            index++ // consume [
            skipWhitespace()
            if (!done() && text[index] == ']') { index++; return }
            while (!done()) {
                scanValue(depth)
                skipWhitespace()
                if (done()) return
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return }
                    else -> index++
                }
            }
        }

        /**
         * Decode keys before comparing them: `key` and `k\u0065y` name the same
         * JSON member and must not carry conflicting authority.
         */
        private fun scanString(): String {
            if (done() || text[index] != '"') {
                scanScalar()
                return ""
            }
            index++ // consume opening quote
            val start = index
            while (!done()) {
                when (text[index]) {
                    '\\' -> index += 2
                    '"' -> {
                        val raw = text.substring(start, index)
                        index++
                        return Json.decodeFromString<String>("\"$raw\"")
                    }
                    else -> index++
                }
            }
            return text.substring(start.coerceAtMost(text.length))
        }

        fun skipWhitespace() {
            while (!done() && text[index].isWhitespace()) index++
        }

        private fun scanScalar() {
            while (!done() && text[index] !in ",}]" && !text[index].isWhitespace()) index++
        }
    }
}
