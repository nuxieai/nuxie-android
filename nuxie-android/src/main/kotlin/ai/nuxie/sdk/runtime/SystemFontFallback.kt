package ai.nuxie.sdk.runtime

import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.io.Closeable

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
    ) : Closeable {
        // Only this worker touches probes, face state, and the ordered chain.
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "NuxieFontFallback").apply { isDaemon = true }
        }
        private data class Generation(val locale: Locale, val id: Long)
        private data class Key(val character: Int, val weight: Int, val generation: Long)
        private val generation = AtomicReference(Generation(Locale.ROOT, 0))
        private val characters = ConcurrentHashMap<Key, Answer>()
        private val insertionOrder = java.util.ArrayDeque<Key>()
        override fun close() { worker.shutdownNow() }
        fun warm(locale: Locale) { worker.execute { ensureChain(locale, now()) } }
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
        fun resolve(character: Int, weight: Int, requestedLocale: Locale): Resolution? {
            if (character !in 0..0x10ffff || character in 0xd800..0xdfff ||
                character < 0x20 || character in 0x7f..0x9f || character in 0x200b..0x200f ||
                character in 0x2028..0x202e || character == 0xfeff) return null
            var current = generation.get()
            while (current.locale != requestedLocale) {
                val next = Generation(requestedLocale, current.id + 1)
                if (generation.compareAndSet(current, next)) { current = next; break }
                current = generation.get()
            }
            val key = Key(character, weight.coerceIn(1, 1000), current.id)
            // No task, monitor, or worker wait on a warm (including negative) answer.
            characters[key]?.takeIf { now() < it.until }?.let { return it.resolution }
            return try {
                worker.submit<Resolution?> {
                    val time = now()
                    characters[key]?.takeIf { time < it.until }?.let { return@submit it.resolution }
                    ensureChain(requestedLocale, time)
                    resolveOnWorker(key, time)
                }.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        }

        private fun ensureChain(requestedLocale: Locale, time: Long) {
            if (locale != requestedLocale || (chainRetry != 0L && time >= chainRetry)) {
                locale = requestedLocale
                try {
                    ordered = chain(requestedLocale)
                    chainRetry = if (ordered.isEmpty()) time + RETRY_MILLIS else 0
                } catch (_: Exception) {
                    ordered = emptyList()
                    chainRetry = time + RETRY_MILLIS
                }
            }
        }

        private fun remember(key: Key, answer: Answer): Resolution? {
            if (characters.put(key, answer) == null) insertionOrder.addLast(key)
            // Bounded FIFO keeps reads lock-free, including across locale generations.
            while (insertionOrder.size > 4096) characters.remove(insertionOrder.removeFirst())
            return answer.resolution
        }

        private fun resolveOnWorker(key: Key, time: Long): Resolution? {
            val character = key.character
            val weight = key.weight
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
                return remember(key, Answer(resolution, until))
            }
            return remember(key, Answer(null, until))
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

    // Debug seams are inert in release: the native build, rather than a mutable
    // Kotlin flag, decides whether instrumentation can observe or suppress selection.
    @Volatile private var observer: ((Int, SystemFontFallbackChain.Face) -> Unit)? = null
    internal var selectionObserver: ((Int, SystemFontFallbackChain.Face) -> Unit)?
        get() = observer
        set(value) { if (nativeTestingEnabled()) observer = value }

    @JvmStatic fun warm() { cache.warm(Locale.getDefault()) }

    // The pinned runtime stops when it receives the same FontRef again. Every
    // index may introduce a different missing scalar, so do not truncate the walk.
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun request(character: Int, index: Int, weight: Int): Long {
        val resolution = cache.resolve(character, weight, Locale.getDefault()) ?: return 0
        observer?.invoke(character, resolution.face)
        return resolution.handle
    }

    fun requestFromNativeThreadForTesting(character: Int, index: Int, weight: Int): Long =
        if (nativeTestingEnabled()) nativeRequestFromThread(character, index, weight) else 0
    fun sequenceFromNativeThreadForTesting(): LongArray =
        if (nativeTestingEnabled()) nativeSequenceFromThread() else longArrayOf()
    fun suppressForTesting(suppress: Boolean) {
        if (nativeTestingEnabled()) nativeSuppress(suppress)
    }
    private external fun nativeTestingEnabled(): Boolean
    private external fun nativeRequestFromThread(character: Int, index: Int, weight: Int): Long
    private external fun nativeSequenceFromThread(): LongArray
    private external fun nativeSuppress(suppress: Boolean)

    private external fun nativeProbe(path: String, index: Int): LongArray
    private external fun nativeCovers(probe: Long, character: Int): Boolean
    private external fun nativeFontCovers(font: Long, character: Int): Boolean
    private external fun nativeMakeFont(probe: Long): LongArray
    private external fun nativeWithWeight(font: Long, weight: Int): LongArray
}
