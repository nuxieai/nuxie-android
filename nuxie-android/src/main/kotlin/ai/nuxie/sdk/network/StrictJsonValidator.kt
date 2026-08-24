package ai.nuxie.sdk.network

import java.io.IOException

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

        fun scanValue() {
            skipWhitespace()
            if (done()) return
            when (text[index]) {
                '{' -> scanObject()
                '[' -> scanArray()
                '"' -> scanString()
                else -> scanScalar()
            }
        }

        private fun scanObject() {
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
                scanValue()
                skipWhitespace()
                if (done()) return
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return }
                    else -> index++ // malformed; kotlinx will reject properly
                }
            }
        }

        private fun scanArray() {
            index++ // consume [
            skipWhitespace()
            if (!done() && text[index] == ']') { index++; return }
            while (!done()) {
                scanValue()
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
         * Returns the RAW string contents (escapes verbatim). Raw identity is
         * sufficient for duplicate detection; kotlinx handles real unescaping.
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
                        return raw
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
