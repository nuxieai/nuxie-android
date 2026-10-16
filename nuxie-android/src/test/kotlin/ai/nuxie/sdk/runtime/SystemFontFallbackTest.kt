package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import java.util.concurrent.CountDownLatch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SystemFontFallbackTest {
    private val first = SystemFontFallbackChain.Face("/system/fonts/first.ttc", 2)
    private val second = SystemFontFallbackChain.Face("/system/fonts/second.ttf", 0)
    private val caches = mutableListOf<SystemFontFallback.Cache>()
    @After fun closeWorkers() { caches.forEach { it.close() } }
    private fun cache(native: Native, chain: (Locale) -> List<SystemFontFallbackChain.Face>, now: () -> Long) =
        SystemFontFallback.Cache(native, chain, now).also { caches += it }
    private class Native : SystemFontFallback.Native {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val probeOwners = mutableMapOf<Long, Thread>()
        var beforeCoverage: (Int) -> Unit = {}
        private fun assertOwner(probe: Long) = assertSame("Probe operations must stay on their creating thread", probeOwners[probe], Thread.currentThread())
        var probeStatus = 0
        var fontStatus = 0
        var weightStatus = 0
        var covers = setOf(0x65e5, 0x672c, 0x1f600)
        override fun probe(face: SystemFontFallbackChain.Face): SystemFontFallback.Acquisition {
            calls += "probe:${face.path}:${face.index}"
            val status = if (face.path.endsWith("first.ttc")) probeStatus else 0
            val handle = if (status == 0) if (face.index == 2) 10L else 20L else 0L
            if (handle != 0L) probeOwners[handle] = Thread.currentThread()
            return SystemFontFallback.Acquisition(status, handle)
        }
        override fun covers(probe: Long, character: Int): Boolean {
            assertOwner(probe)
            beforeCoverage(character)
            calls += "covers:$probe:$character"
            return character in covers
        }
        override fun fontCovers(font: Long, character: Int): Boolean {
            beforeCoverage(character)
            calls += "fontCovers:$font:$character"
            return character in covers
        }
        override fun makeFont(probe: Long): SystemFontFallback.Acquisition {
            assertOwner(probe)
            calls += "font:$probe"
            return SystemFontFallback.Acquisition(fontStatus, if (fontStatus == 0) probe + 100 else 0)
        }
        override fun withWeight(font: Long, weight: Int): SystemFontFallback.Acquisition {
            calls += "weight:$font:$weight"
            return SystemFontFallback.Acquisition(weightStatus, if (weightStatus == 0) font * 1000 + weight else 0)
        }
    }

    @Test fun charactersShareProbesAndDecodedFacesButRespectWeight() {
        val native = Native()
        val cache = cache(native, { listOf(first, second) }, { 0 })
        val a = cache.resolve(0x65e5, 400, Locale.JAPAN)
        assertEquals(first, a?.face)
        assertEquals(a, cache.resolve(0x65e5, 400, Locale.JAPAN))
        assertEquals(a, cache.resolve(0x672c, 400, Locale.JAPAN))
        assertNotEquals(a?.handle, cache.resolve(0x65e5, 700, Locale.JAPAN)?.handle)
        assertEquals(1, native.calls.count { it.startsWith("probe:") })
        assertEquals(1, native.calls.count { it.startsWith("font:") })
        assertEquals(2, native.calls.count { it.startsWith("weight:") })
        assertEquals(1, native.calls.count { it.startsWith("covers:") })
        assertTrue(native.calls.any { it.startsWith("fontCovers:") })
    }

    @Test fun cacheMissAndInvalidScalarDoNotKeepWalkingTheChain() {
        val native = Native()
        val cache = cache(native, { listOf(first, second) }, { 0 })
        assertNull(cache.resolve(0x10ffff, 400, Locale.US))
        val calls = native.calls.toList()
        repeat(4) { assertNull(cache.resolve(0x10ffff, 400, Locale.US)) }
        for (character in listOf(-1, 0xd800, 0x110000, 0x200b, 10)) {
            assertNull(cache.resolve(character, 400, Locale.US))
        }
        assertEquals(calls, native.calls)
        assertFalse(native.calls.any { it.startsWith("font:") })
    }

    @Test fun localeChangeReordersAnswersWithoutReopeningFaces() {
        val native = Native()
        val cache = cache(native, { if (it == Locale.JAPAN) listOf(first, second) else listOf(second, first) }, { 0 })
        assertEquals(first, cache.resolve(0x65e5, 400, Locale.JAPAN)?.face)
        assertEquals(second, cache.resolve(0x65e5, 400, Locale.CHINA)?.face)
        assertEquals(first, cache.resolve(0x65e5, 400, Locale.JAPAN)?.face)
        assertEquals(2, native.calls.count { it.startsWith("probe:") })
    }

    @Test fun transientEarlierFailureExpiresEvenWhenALaterFaceSucceeded() {
        val native = Native().apply { probeStatus = 2 }
        var time = 100L
        val cache = cache(native, { listOf(first, second) }, { time })
        assertEquals(second, cache.resolve(0x65e5, 400, Locale.US)?.face)
        native.probeStatus = 0
        time += 29_999
        assertEquals(second, cache.resolve(0x65e5, 400, Locale.US)?.face)
        time++
        assertEquals(first, cache.resolve(0x65e5, 400, Locale.US)?.face)
        assertEquals(3, native.calls.count { it.startsWith("probe:") })
    }

    @Test fun permanentDataFailuresAreRememberedAcrossCharactersAndLocales() {
        for (status in listOf(5, 11)) {
            val native = Native().apply { probeStatus = status }
            var time = 0L
            val cache = cache(native, { listOf(first) }, { time })
            assertNull(cache.resolve(0x65e5, 400, Locale.US))
            native.probeStatus = 0
            time = 1_000_000
            assertNull(cache.resolve(0x672c, 400, Locale.JAPAN))
            assertEquals(1, native.calls.count { it.startsWith("probe:") })
        }
    }

    @Test fun promotionAndWeightFailuresAreThrottledAndRetried() {
        for (failWeight in listOf(false, true)) {
            val native = Native().apply { if (failWeight) weightStatus = 2 else fontStatus = 2 }
            var time = 0L
            val cache = cache(native, { listOf(first) }, { time })
            assertNull(cache.resolve(0x1f600, 400, Locale.US))
            native.fontStatus = 0
            native.weightStatus = 0
            assertNull(cache.resolve(0x65e5, 400, Locale.US))
            time = 30_000
            assertNotNull(cache.resolve(0x1f600, 400, Locale.US))
            assertEquals(1, native.calls.count { it.startsWith("probe:") })
            assertEquals(if (failWeight) 1 else 2, native.calls.count { it.startsWith("font:") })
        }
    }

    @Test fun configurationReadFailureIsRetriedWithoutThrowingThroughHook() {
        val native = Native()
        var reads = 0
        var time = 0L
        val cache = cache(native, {
            if (++reads == 1) error("file temporarily unavailable") else listOf(first)
        }, { time })
        assertNull(cache.resolve(0x65e5, 400, Locale.US))
        assertNull(cache.resolve(0x672c, 400, Locale.US))
        assertEquals(1, reads)
        time = 30_000
        assertNotNull(cache.resolve(0x65e5, 400, Locale.US))
        assertEquals(2, reads)
    }

    @Test fun unusablePromotionIsNotRetriedButMissingFilesAre() {
        val native = Native().apply { fontStatus = 3 }
        var time = 0L
        val cache = cache(native, { listOf(first) }, { time })
        assertNull(cache.resolve(0x65e5, 400, Locale.US))
        native.fontStatus = 0
        time = 1_000_000
        assertNull(cache.resolve(0x672c, 400, Locale.JAPAN))
        assertEquals(1, native.calls.count { it.startsWith("font:") })
        val files = Native().apply { probeStatus = 3 }
        val retry = cache(files, { listOf(first) }, { time })
        assertNull(retry.resolve(0x65e5, 400, Locale.US))
        files.probeStatus = 0
        time += 30_000
        assertNotNull(retry.resolve(0x65e5, 400, Locale.US))
    }

    @Test fun untrustedCharactersCannotGrowTheAnswerCacheWithoutBound() {
        val native = Native().apply { covers = emptySet() }
        val cache = cache(native, { listOf(first) }, { 0 })
        for (character in 0x3000..0x4000) assertNull(cache.resolve(character, 400, Locale.US))
        val before = native.calls.size
        assertNull(cache.resolve(0x3000, 400, Locale.US))
        assertTrue("Oldest of 4097 answers was evicted", native.calls.size > before)
        assertEquals(1, native.calls.count { it.startsWith("probe:") })
    }

    @Test fun concurrentRequestsAcquireOneFace() {
        val native = Native()
        val cache = cache(native, { listOf(first) }, { 0 })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..32).map { executor.submit<Long> { checkNotNull(cache.resolve(0x65e5, 400, Locale.US)).handle } }
            assertEquals(1, results.map { it.get(5, TimeUnit.SECONDS) }.distinct().size)
            assertEquals(1, native.calls.count { it.startsWith("font:") })
        } finally { executor.shutdownNow() }
    }
    @Test fun warmAnswersDoNotQueueBehindAColdRequest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = Native()
        val cache = cache(native, { listOf(first) }, { 0 })
        val warm = cache.resolve(0x65e5, 400, Locale.US)
        // A negative answer is also a warm hit.
        assertNull(cache.resolve(0x10ffff, 400, Locale.US))
        native.beforeCoverage = { if (it == 0x672c) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val callers = Executors.newFixedThreadPool(2)
        try {
            val cold = callers.submit { cache.resolve(0x672c, 400, Locale.US) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val hit = callers.submit<SystemFontFallback.Resolution?> {
                assertNull(cache.resolve(0x10ffff, 400, Locale.US))
                cache.resolve(0x65e5, 400, Locale.US)
            }
            assertEquals(warm, hit.get(1, TimeUnit.SECONDS))
            release.countDown()
            cold.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); callers.shutdownNow() }
    }

    @Test fun weightsClampAtThePublicCallbackBoundaryAndShareAnswers() {
        val native = Native()
        val cache = cache(native, { listOf(first) }, { 0 })
        assertEquals(cache.resolve(0x65e5, 1, Locale.US), cache.resolve(0x65e5, Int.MIN_VALUE, Locale.US))
        assertEquals(cache.resolve(0x65e5, 1000, Locale.US), cache.resolve(0x65e5, Int.MAX_VALUE, Locale.US))
        assertEquals(2, native.calls.count { it.startsWith("weight:") })
    }

}
