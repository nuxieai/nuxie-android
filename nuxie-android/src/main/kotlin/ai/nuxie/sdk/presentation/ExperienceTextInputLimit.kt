package ai.nuxie.sdk.presentation

/** Unicode 16 extended graphemes (UAX #29 rev. 45), independent of Android's ICU version. */
internal object ExperienceTextInputLimit {
    fun fits(text: String, maximum: Int?): Boolean = apply(text, maximum).length == text.length

    fun apply(text: String, maximum: Int?): String {
        if (maximum == null) return text
        return text.substring(0, scan(text, maximum).first)
    }

    private fun scan(text: String, maximum: Int): Pair<Int, Int> {
        require(maximum >= 0)
        var offset = 0
        var clusters = 0
        var previous = OTHER
        var regionalCount = 0
        var emojiPrefix = false
        var emojiBeforeZwj = false
        var indicConsonant = false
        var indicLinker = false
        while (offset < text.length) {
            val codepoint = Character.codePointAt(text, offset)
            val properties = UnicodeGraphemeProperties.get(codepoint)
            val kind = properties and 15
            val indic = properties and 48
            val pictographic = properties and 64 != 0
            val boundary = when {
                offset == 0 -> true // GB1
                previous == CR && kind == LF -> false // GB3
                previous.isControl() || kind.isControl() -> true // GB4/5
                previous == L && (kind == L || kind == V || kind == LV || kind == LVT) -> false // GB6
                (previous == LV || previous == V) && (kind == V || kind == T) -> false // GB7
                (previous == LVT || previous == T) && kind == T -> false // GB8
                kind == EXTEND || kind == ZWJ || kind == SPACING_MARK -> false // GB9/9a
                previous == PREPEND -> false // GB9b
                indic == INDIC_CONSONANT && indicConsonant && indicLinker -> false // GB9c
                pictographic && previous == ZWJ && emojiBeforeZwj -> false // GB11
                previous == REGIONAL && kind == REGIONAL && regionalCount % 2 == 1 -> false // GB12/13
                else -> true // GB999
            }
            if (boundary) {
                if (clusters == maximum) return offset to clusters
                clusters += 1
            }
            emojiBeforeZwj = kind == ZWJ && emojiPrefix
            emojiPrefix = pictographic || (kind == EXTEND && emojiPrefix)
            when (indic) {
                INDIC_CONSONANT -> { indicConsonant = true; indicLinker = false }
                INDIC_LINKER -> if (indicConsonant) indicLinker = true
                INDIC_EXTEND -> Unit
                else -> { indicConsonant = false; indicLinker = false }
            }
            regionalCount = if (kind == REGIONAL) regionalCount + 1 else 0
            previous = kind
            offset += Character.charCount(codepoint)
        }
        return text.length to clusters
    }

    /** Limits only the composing replacement, never the untouched prefix or suffix. */
    fun composingReplacement(current: String, start: Int, end: Int, replacement: String, maximum: Int?): String {
        if (maximum == null) return replacement
        val prefix = current.substring(0, start)
        val suffix = current.substring(end)
        if (fits(prefix + replacement + suffix, maximum)) return replacement
        val reserved = scan(prefix, Int.MAX_VALUE).second + scan(suffix, Int.MAX_VALUE).second
        // Concatenation can merge a boundary at either end of the replacement.
        // Start two clusters above the independent budget, then check the exact candidate.
        var budget = (maximum - reserved + 2).coerceAtLeast(0)
        while (true) {
            val candidate = apply(replacement, budget)
            if (fits(prefix + candidate + suffix, maximum) || budget == 0) return candidate
            budget--
        }
    }

    private fun Int.isControl() = this == CONTROL || this == CR || this == LF
    private const val OTHER = 0
    private const val CONTROL = 1
    private const val CR = 2
    private const val LF = 3
    private const val EXTEND = 4
    private const val ZWJ = 5
    private const val REGIONAL = 6
    private const val PREPEND = 7
    private const val SPACING_MARK = 8
    private const val L = 9
    private const val V = 10
    private const val T = 11
    private const val LV = 12
    private const val LVT = 13
    private const val INDIC_CONSONANT = 16
    private const val INDIC_EXTEND = 32
    private const val INDIC_LINKER = 48
}
