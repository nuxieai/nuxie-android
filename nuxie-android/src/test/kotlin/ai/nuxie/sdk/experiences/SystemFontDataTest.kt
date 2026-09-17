package ai.nuxie.sdk.experiences

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemFontDataTest {
    @Test fun `collection extraction preserves the selected variation table and fixes checksums`() {
        val collection = collection()
        val result = SystemFontData.standalone(collection, 1)
        assertArrayEquals(byteArrayOf(0, 1, 0, 0, 0, 2, 0, 32, 0, 1, 0, 0), result.copyOfRange(0, 12))
        assertArrayEquals("fvar".toByteArray(), result.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), result.copyOfRange(44, 48))
        assertEquals(0xB1B0AFBAL, checksum(result))
        assertArrayEquals(result, SystemFontData.standalone(result, 0))
        val first = SystemFontData.standalone(collection, 0)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first.copyOfRange(44, 48))
    }

    @Test fun `invalid indices truncated tables and oversized offsets fail with typed errors`() {
        val data = collection()
        for (index in listOf(-1, 2, Int.MAX_VALUE)) {
            assertThrows(SystemFontDataException::class.java) { SystemFontData.standalone(data, index) }
        }
        for (invalid in listOf(byteArrayOf(), byteArrayOf(0, 1, 0), data.copyOf(data.size - 1),
            data.copyOf().apply { for (i in 84..87) this[i] = 0xff.toByte() })) {
            assertThrows(SystemFontDataException::class.java) { SystemFontData.standalone(invalid, 1) }
        }
    }

    @Test fun `reassembly drops stale signatures and pads tables without changing their lengths`() {
        val bytes = mutableListOf(0, 1, 0, 0, 0, 3, 0, 32, 0, 1, 0, 16)
        bytes += listOf(0x44, 0x53, 0x49, 0x47, 0, 0, 0, 0, 0, 0, 0, 120, 0, 0, 0, 4)
        bytes += listOf(0x68, 0x65, 0x61, 0x64, 0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0, 54)
        bytes += listOf(0x66, 0x76, 0x61, 0x72, 0, 0, 0, 0, 0, 0, 0, 60, 0, 0, 0, 3)
        bytes += listOf(1, 2, 3, 0)
        bytes += List(56) { 0 }
        bytes += listOf(9, 9, 9, 9)
        val result = SystemFontData.standalone(bytes.map(Int::toByte).toByteArray(), 0)
        assertEquals(2, result[5].toInt())
        assertEquals(104, result.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 0), result.copyOfRange(16, 20))
        assertArrayEquals(byteArrayOf(0, 0, 0, 44, 0, 0, 0, 3), result.copyOfRange(20, 28))
        assertArrayEquals(byteArrayOf(1, 2, 3, 0), result.copyOfRange(44, 48))
        assertEquals(0xB1B0AFBAL, checksum(result))
    }

    @Test fun `duplicate table tags are rejected`() {
        val data = collection()
        "head".toByteArray().copyInto(data, 48)
        assertThrows(SystemFontDataException::class.java) { SystemFontData.standalone(data, 0) }
    }

    private fun collection(): ByteArray {
        val bytes = mutableListOf<Int>(0x74, 0x74, 0x63, 0x66, 0, 1, 0, 0, 0, 0, 0, 2, 0, 0, 0, 20, 0, 0, 0, 64)
        for (tableOffset in listOf(162, 166)) {
            bytes += listOf(0, 1, 0, 0, 0, 2, 0, 32, 0, 1, 0, 0)
            bytes += listOf(0x68, 0x65, 0x61, 0x64, 0, 0, 0, 0, 0, 0, 0, 108, 0, 0, 0, 54)
            bytes += listOf(0x66, 0x76, 0x61, 0x72, 0, 0, 0, 0, 0, 0, 0, tableOffset, 0, 0, 0, 4)
        }
        bytes += List(54) { 0 }
        bytes += listOf(1, 2, 3, 4, 5, 6, 7, 8)
        return bytes.map(Int::toByte).toByteArray()
    }

    private fun checksum(bytes: ByteArray): Long = bytes.toList().chunked(4).sumOf { word ->
        word.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
    } and 0xffffffffL
}
