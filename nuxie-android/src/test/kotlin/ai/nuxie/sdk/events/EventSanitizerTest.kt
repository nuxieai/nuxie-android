package ai.nuxie.sdk.events

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventSanitizerTest {
    @Test
    fun longStringsTruncateToOneThousandCharacters() {
        val sanitized = EventSanitizer.sanitizeDataTypes(mapOf("s" to "x".repeat(5000)))
        assertEquals(1000, (sanitized["s"] as String).length)
    }

    @Test
    fun nulCharactersAreStripped() {
        val sanitized = EventSanitizer.sanitizeDataTypes(mapOf("s" to "a\u0000b"))
        assertEquals("ab", sanitized["s"])
    }

    @Test
    fun datesBecomeIsoStrings() {
        val sanitized = EventSanitizer.sanitizeDataTypes(mapOf("d" to Date(1_784_462_400_000L)))
        assertEquals("2026-07-19T12:00:00Z", sanitized["d"])
    }

    @Test
    fun byteArraysBecomeBoundedBase64() {
        val sanitized = EventSanitizer.sanitizeDataTypes(mapOf("b" to ByteArray(4096) { 1 }))
        val encoded = sanitized["b"] as String
        // 1024 bytes -> ceil(1024/3)*4 base64 chars.
        assertEquals(1368, encoded.length)
    }

    @Test
    fun nestingBeyondDepthLimitIsTruncated() {
        var value: Any = "leaf"
        repeat(15) { value = mapOf("k" to value) }
        val sanitized = EventSanitizer.sanitizeDataTypes(mapOf("root" to value))
        // The over-deep subtree is dropped rather than crashing.
        var cursor: Any? = sanitized["root"]
        var depth = 0
        while (cursor is Map<*, *>) {
            cursor = cursor["k"]
            depth += 1
        }
        assertTrue(depth <= 11)
        assertNull(cursor.takeIf { it == "leaf" })
    }

    @Test
    fun nullsArePreservedAndUnknownTypesStringify() {
        val sanitized = EventSanitizer.sanitizeDataTypes(
            mapOf("n" to null, "obj" to Thing),
        )
        assertTrue(sanitized.containsKey("n"))
        assertNull(sanitized["n"])
        assertEquals("thing-description", sanitized["obj"])
    }

    private object Thing {
        override fun toString(): String = "thing-description"
    }
}
