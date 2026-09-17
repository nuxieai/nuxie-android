package ai.nuxie.sdk.experiences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemFontCacheTest {
    private fun font(digest: String, size: Int = 4) =
        SystemFontCandidate(ByteArray(size), digest, "device-face", 400)

    @Test fun `extraction alone is never cached and failed imports cannot seed reuse`() {
        val cache = SystemFontCache()
        var reads = 0
        fun extract() = font("a").also { reads++ }
        val first = cache.candidate("device-400", ::extract)
        val second = cache.candidate("device-400", ::extract)
        assertEquals(2, reads)
        assertNotSame(first.candidate.bytes, second.candidate.bytes)
        cache.didFailImport(listOf(first, second))
        cache.candidate("device-400", ::extract)
        assertEquals(3, reads)
        cache.didImport(listOf(second))
        assertSame(second.candidate.bytes, cache.candidate("device-400", ::extract).candidate.bytes)
        assertEquals(3, reads)
    }

    @Test fun `successful aliases share storage and rejection evicts all matching bytes`() {
        val cache = SystemFontCache(maximumBytes = 4)
        val regular = cache.candidate("regular") { font("variable") }
        val bold = cache.candidate("bold") { font("variable") }
        cache.didImport(listOf(regular, bold))
        val cachedRegular = cache.candidate("regular") { error("unexpected extraction") }
        val cachedBold = cache.candidate("bold") { error("unexpected extraction") }
        assertSame(cachedRegular.candidate.bytes, cachedBold.candidate.bytes)
        cache.didFailImport(listOf(regular))
        for (key in listOf("regular", "bold")) {
            assertThrows(IllegalStateException::class.java) {
                cache.candidate(key) { error("cache miss") }
            }
        }
    }

    @Test fun `LRU enforces byte and entry bounds while preserving recently used fonts`() {
        val cache = SystemFontCache(maximumBytes = 8)
        val first = cache.candidate("one") { font("1") }
        val second = cache.candidate("two") { font("2") }
        cache.didImport(listOf(first, second))
        cache.candidate("one") { error("cache miss") }
        cache.didImport(listOf(cache.candidate("three") { font("3") }))
        cache.candidate("one") { error("recent entry evicted") }
        assertThrows(IllegalStateException::class.java) { cache.candidate("two") { error("evicted") } }
        val many = SystemFontCache()
        many.didImport((0..32).map { key -> many.candidate("$key") { font("shared") } })
        assertThrows(IllegalStateException::class.java) { many.candidate("0") { error("evicted") } }
        many.candidate("32") { error("newest entry evicted") }
    }

    @Test fun `oversized data is not retained and stale failure preserves newer content`() {
        val cache = SystemFontCache(maximumBytes = 4)
        val old = cache.candidate("same-source") { font("old") }
        cache.didImport(listOf(old))
        cache.didImport(listOf(SystemFontCache.Lease("same-source", font("new"))))
        cache.didFailImport(listOf(old))
        assertEquals("new", cache.candidate("same-source") { error("new entry evicted") }.candidate.digest)
        cache.didImport(listOf(SystemFontCache.Lease("huge", font("huge", 5))))
        assertThrows(IllegalStateException::class.java) { cache.candidate("huge") { error("not retained") } }
    }
}
