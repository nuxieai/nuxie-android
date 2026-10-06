package ai.nuxie.sdk.presentation

/** Unicode 16 extended grapheme boundaries, independent of the phone's Unicode version. */
internal object ExperienceGrapheme {
    private const val CR = 1
    private const val LF = 2
    private const val CONTROL = 3
    private const val EXTEND = 4
    private const val ZWJ = 5
    private const val RI = 6
    private const val PREPEND = 7
    private const val SPACING_MARK = 8
    private const val L = 9
    private const val V = 10
    private const val T = 11
    private const val LV = 12
    private const val LVT = 13

    private fun property(scalar: Int): Int {
        if (scalar in 0xac00..0xd7a3) return if ((scalar - 0xac00) % 28 == 0) LV else LVT
        if (scalar in 0x20..0x7e) return 0
        val ranges = ExperienceGraphemeData.ranges
        var low = 0
        var high = ranges.size / 3
        while (low < high) {
            val middle = (low + high) / 2
            val offset = middle * 3
            when {
                scalar < ranges[offset] -> high = middle
                scalar > ranges[offset + 1] -> low = middle + 1
                else -> return ranges[offset + 2]
            }
        }
        return 0
    }

    fun forEachCluster(text: String, consume: (start: Int, end: Int, bytes: Int) -> Unit) {
        var start = 0
        var index = 0
        var bytes = 0
        var previous = 0
        var regionalCount = 0
        var emoji = 0
        var indicConsonant = false
        var indicLinker = false
        while (index < text.length) {
            val scalar = Character.codePointAt(text, index)
            val packed = property(scalar)
            val current = packed and 15
            val indic = (packed shr 4) and 3
            val pictographic = packed and 64 != 0
            val breaks = when {
                previous == CR && current == LF -> false // GB3
                previous in CR..CONTROL || current in CR..CONTROL -> true // GB4, GB5
                previous == L && (current == L || current == V || current == LV || current == LVT) -> false // GB6
                (previous == LV || previous == V) && (current == V || current == T) -> false // GB7
                (previous == LVT || previous == T) && current == T -> false // GB8
                current == EXTEND || current == ZWJ || current == SPACING_MARK -> false // GB9, GB9a
                previous == PREPEND -> false // GB9b
                indicLinker && indic == 1 -> false // GB9c
                emoji == 2 && pictographic -> false // GB11
                previous == RI && current == RI && regionalCount % 2 == 1 -> false // GB12, GB13
                else -> true
            }
            if (index != start && breaks) {
                consume(start, index, bytes)
                start = index
                bytes = 0
                regionalCount = 0
                emoji = 0
                indicConsonant = false
                indicLinker = false
            }
            regionalCount = if (current == RI) regionalCount + 1 else 0
            emoji = when {
                pictographic -> 1
                current == EXTEND && emoji == 1 -> 1
                current == ZWJ && emoji == 1 -> 2
                else -> 0
            }
            when (indic) {
                1 -> { indicConsonant = true; indicLinker = false }
                2 -> indicLinker = indicConsonant
                3 -> Unit
                else -> { indicConsonant = false; indicLinker = false }
            }
            previous = current
            bytes += when {
                scalar <= 0x7f -> 1
                scalar <= 0x7ff -> 2
                scalar <= 0xffff -> 3
                else -> 4
            }
            index += Character.charCount(scalar)
        }
        if (start != text.length) consume(start, text.length, bytes)
    }
}
