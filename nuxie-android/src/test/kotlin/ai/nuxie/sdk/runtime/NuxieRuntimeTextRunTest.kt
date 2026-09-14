package ai.nuxie.sdk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieRuntimeTextRunTest {
    @Test
    fun ownedArtboardPreservesTextResultsErrorsAndClosure() {
        val writes = mutableListOf<Pair<String, String>>()
        var result = NativeCallResult<Boolean>(0, true)
        var frees = 0
        val native = object : NuxieTypedRuntimeNative {
            override fun setTextRun(handle: Long, name: String, text: String): NativeCallResult<Boolean> {
                assertEquals(42L, handle)
                writes += name to text
                return result
            }
            override fun freeArtboard(handle: Long) { frees++ }
        }
        val artboard = NuxieRuntimeArtboard(42L, native) { error("No view model expected") }
        assertTrue(artboard.setTextRun("headline", "😀\u0000漢e\u0301"))
        assertEquals(listOf("headline" to "😀\u0000漢e\u0301"), writes)
        result = NativeCallResult(0, false)
        assertFalse(artboard.setTextRun("headline", ""))
        result = NativeCallResult(3, null)
        assertThrows(NuxieRuntimeCallException::class.java) { artboard.setTextRun("missing", "text") }
        artboard.close()
        assertThrows(IllegalStateException::class.java) { artboard.setTextRun("headline", "after-close") }
        assertEquals(3, writes.size)
        assertEquals(1, frees)
    }
}
