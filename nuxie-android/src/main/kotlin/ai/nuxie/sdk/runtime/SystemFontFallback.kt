package ai.nuxie.sdk.runtime

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors

/** Process-owned borrowed C handles. Native callbacks can arrive on any shaping thread. */
internal object SystemFontFallback {
    data class Acquisition(val status: Int, val handle: Long)
    data class Resolution(val handle: Long, val face: SystemFontFallbackChain.Face)

    interface Native {
        fun probe(face: SystemFontFallbackChain.Face): Acquisition
        fun covers(probe: Long, character: Int): Boolean
        fun fontCovers(font: Long, character: Int): Boolean
        fun makeFont(probe: Long): Acquisition
        fun withWeight(font: Long, weight: Int): Acquisition
    }

    /** Caches successful faces forever, unusable data forever, and file errors for 30 seconds. */
    class Cache(
        private val native: Native,
        private val chain: (Locale) -> List<SystemFontFallbackChain.Face>,
        private val now: () -> Long,
    ) {
        private class FaceState {
            var probe = 0L
            var font = 0L
            var failedUntil = 0L
            val weights = mutableMapOf<Int, Long>()
            val weightFailures = mutableMapOf<Int, Long>()
        }
        private data class Answer(val resolution: Resolution?, val until: Long)
        private val faces = mutableMapOf<SystemFontFallbackChain.Face, FaceState>()
        private var locale: Locale? = null
        private var ordered: List<SystemFontFallbackChain.Face> = emptyList()
        private var chainRetry = 0L
        // Unicode input is untrusted; a document cannot grow the character cache without bound.
        private val characters = object : LinkedHashMap<Pair<Int, Int>, Answer>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, Answer>?) = size > 4096
        }

        @Synchronized
        fun resolve(character: Int, weight: Int, requestedLocale: Locale): Resolution? {
            if (character !in 0..0x10ffff || character in 0xd800..0xdfff || weight !in 1..1000 ||
                character < 0x20 || character in 0x7f..0x9f || character in 0x200b..0x200f ||
                character in 0x2028..0x202e || character == 0xfeff) return null
            val time = now()
            if (locale != requestedLocale || (chainRetry != 0L && time >= chainRetry)) {
                characters.clear()
                locale = requestedLocale
                try {
                    ordered = chain(requestedLocale)
                    chainRetry = if (ordered.isEmpty()) time + RETRY_MILLIS else 0
                } catch (_: Exception) {
                    ordered = emptyList()
                    chainRetry = time + RETRY_MILLIS
                }
            }
            val key = character to weight
            characters[key]?.takeIf { time < it.until }?.let { return it.resolution }
            var until = if (chainRetry != 0L) chainRetry else Long.MAX_VALUE
            for (face in ordered) {
                val state = faces.getOrPut(face) { FaceState() }
                if (time < state.failedUntil) {
                    until = minOf(until, state.failedUntil)
                    continue
                }
                if (state.probe == 0L && state.font == 0L) {
                    val result = native.probe(face)
                    if (result.handle == 0L) {
                        state.failedUntil = failureDeadline(result.status, time)
                        until = minOf(until, state.failedUntil)
                        continue
                    }
                    state.probe = result.handle
                }
                val covered = if (state.font != 0L) native.fontCovers(state.font, character)
                    else native.covers(state.probe, character)
                if (!covered) continue
                if (state.font == 0L) {
                    val result = native.makeFont(state.probe)
                    if (result.handle == 0L) {
                        state.failedUntil = failureDeadline(result.status, time, promotion = true)
                        until = minOf(until, state.failedUntil)
                        continue
                    }
                    // Promotion consumes the probe's mapping. JNI frees its empty handle.
                    state.probe = 0
                    state.font = result.handle
                }
                var handle = state.weights[weight]
                if (handle == null) {
                    val deadline = state.weightFailures[weight] ?: 0
                    if (time < deadline) {
                        until = minOf(until, deadline)
                        continue
                    }
                    val result = native.withWeight(state.font, weight)
                    if (result.handle == 0L) {
                        val failed = failureDeadline(result.status, time)
                        state.weightFailures[weight] = failed
                        until = minOf(until, failed)
                        continue
                    }
                    handle = result.handle
                    state.weights[weight] = handle
                    state.weightFailures.remove(weight)
                }
                val resolution = Resolution(handle, face)
                characters[key] = Answer(resolution, until)
                return resolution
            }
            characters[key] = Answer(null, until)
            return null
        }

        private fun failureDeadline(status: Int, time: Long, promotion: Boolean = false): Long =
            // ImportError also includes mmap/I/O errors. Only promotion NotFound means unusable data.
            if (status == INVALID_ARGUMENT || status == LIMIT_EXCEEDED || (promotion && status == NOT_FOUND)) Long.MAX_VALUE else time + RETRY_MILLIS

        companion object {
            const val RETRY_MILLIS = 30_000L
            private const val INVALID_ARGUMENT = 5
            private const val LIMIT_EXCEEDED = 11
            private const val NOT_FOUND = 3
        }
    }

    private val cache = Cache(object : Native {
        private fun LongArray.result() = Acquisition(this[0].toInt(), this[1])
        override fun probe(face: SystemFontFallbackChain.Face) = nativeProbe(face.path, face.index).result()
        override fun covers(probe: Long, character: Int) = nativeCovers(probe, character)
        override fun fontCovers(font: Long, character: Int) = nativeFontCovers(font, character)
        override fun makeFont(probe: Long) = nativeMakeFont(probe).result()
        override fun withWeight(font: Long, weight: Int) = nativeWithWeight(font, weight).result()
    }, SystemFontFallbackChain::load, SystemClock::uptimeMillis)

    // NuxFontFileProbe is thread-affine, unlike the shareable decoded NuxFont.
    // A single process-owned worker preserves probes across different runtime lanes.
    private val fontThread = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NuxieFontFallback").apply { isDaemon = true }
    }

    @Volatile internal var selectionObserver: ((Int, SystemFontFallbackChain.Face) -> Unit)? = null

    // Called from JNI; indices 0 and 1 mirror iOS's provider. A new missing scalar
    // on retry 1 must select again (e.g. a second script within the same run).
    @JvmStatic
    fun request(character: Int, index: Int, weight: Int): Long {
        if (index !in 0..1) return 0
        val locale = Locale.getDefault()
        val resolution = try {
            fontThread.submit<Resolution?> { cache.resolve(character, weight, locale) }.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return 0
        } ?: return 0
        selectionObserver?.invoke(character, resolution.face)
        return resolution.handle
    }

    external fun requestFromNativeThreadForTesting(character: Int, index: Int, weight: Int): Long

    private external fun nativeProbe(path: String, index: Int): LongArray
    private external fun nativeCovers(probe: Long, character: Int): Boolean
    private external fun nativeFontCovers(font: Long, character: Int): Boolean
    private external fun nativeMakeFont(probe: Long): LongArray
    private external fun nativeWithWeight(font: Long, weight: Int): LongArray
}
