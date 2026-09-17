package ai.nuxie.sdk.experiences

internal class SystemFontDataException(message: String) : IllegalArgumentException(message)

/** Container extraction only; configured native import determines renderability. */
internal object SystemFontData {
    private const val HEAD = 0x68656164L
    private const val DSIG = 0x44534947L
    private const val TTC = 0x74746366L
    private const val MAXIMUM_FACE_BYTES = 32 * 1024 * 1024

    fun standalone(source: ByteArray, collectionIndex: Int): ByteArray {
        checkFont(source.size <= 256 * 1024 * 1024, "font container size")
        val start = if (u32(source, 0) == TTC) {
            val version = u32(source, 4)
            checkFont(version == 0x00010000L || version == 0x00020000L, "collection version")
            val count = u32(source, 8)
            checkFont(count in 1..256 && collectionIndex >= 0 && collectionIndex < count, "collection index")
            checkFont(12L + count * 4 <= source.size, "collection directory")
            val offset = u32(source, 12 + collectionIndex * 4)
            checkFont(offset <= source.size, "face offset")
            offset.toInt()
        } else {
            checkFont(collectionIndex == 0, "standalone face index")
            0
        }
        val version = u32(source, start)
        checkFont(version in listOf(0x00010000L, 0x4F54544FL, 0x74727565L), "font version")
        checkFont(start <= source.size - 12, "font directory")
        val count = ((source[start + 4].toInt() and 255) shl 8) or (source[start + 5].toInt() and 255)
        checkFont(count in 1..4095 && count <= (source.size - start - 12) / 16, "table count")
        val tables = sortedMapOf<Long, ByteArray>()
        var size = 0L
        repeat(count) { index ->
            val record = start + 12 + index * 16
            val tag = u32(source, record)
            val offset = u32(source, record + 8)
            val length = u32(source, record + 12)
            checkFont(offset <= source.size && length <= source.size - offset, "table bounds")
            checkFont(tag !in tables, "duplicate table")
            size += length
            checkFont(size <= MAXIMUM_FACE_BYTES, "face size")
            tables[tag] = source.copyOfRange(offset.toInt(), (offset + length).toInt())
        }
        // The original digital signature cannot authenticate a rebuilt file.
        tables.remove(DSIG)
        val head = tables[HEAD] ?: throw SystemFontDataException("missing head")
        checkFont(head.size >= 54, "head size")
        put32(head, 8, 0)
        val outputSize = 12L + tables.size * 16 + tables.values.sumOf { (it.size.toLong() + 3) and -4L }
        checkFont(outputSize <= MAXIMUM_FACE_BYTES, "standalone size")
        val output = ByteArray(outputSize.toInt())
        put32(output, 0, version)
        put16(output, 4, tables.size)
        val power = Integer.highestOneBit(tables.size)
        put16(output, 6, power * 16)
        put16(output, 8, Integer.numberOfTrailingZeros(power))
        put16(output, 10, tables.size * 16 - power * 16)
        var offset = 12 + tables.size * 16
        var headOffset = 0
        tables.entries.forEachIndexed { index, (tag, table) ->
            val record = 12 + index * 16
            put32(output, record, tag)
            put32(output, record + 4, checksum(table))
            put32(output, record + 8, offset.toLong())
            put32(output, record + 12, table.size.toLong())
            if (tag == HEAD) headOffset = offset
            table.copyInto(output, offset)
            offset += (table.size + 3) and -4
        }
        put32(output, headOffset + 8, (0xB1B0AFBAL - checksum(output)) and 0xffffffffL)
        return output
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        checkFont(offset >= 0 && offset <= bytes.size - 4, "truncated integer")
        var value = 0L
        repeat(4) { value = (value shl 8) or (bytes[offset + it].toLong() and 255) }
        return value
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (24 - it * 8)).toByte() }
    }

    private fun checksum(bytes: ByteArray): Long {
        var sum = 0L
        bytes.forEachIndexed { index, byte -> sum += (byte.toLong() and 255) shl (24 - (index % 4) * 8) }
        return sum and 0xffffffffL
    }

    private fun checkFont(condition: Boolean, reason: String) {
        if (!condition) throw SystemFontDataException(reason)
    }
}
