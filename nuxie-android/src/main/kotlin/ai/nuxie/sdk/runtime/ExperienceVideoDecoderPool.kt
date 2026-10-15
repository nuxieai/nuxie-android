package ai.nuxie.sdk.runtime

import java.util.UUID

/**
 * Shared across runtime lanes. Revocation retains capacity until decoder disposal is acknowledged.
 * A running decoder's claim resizes in place when its demand changes; it is never torn down for it.
 */
internal class ExperienceVideoDecoderPool(private val budget: () -> NuxieVideoDecoderBudget) {
    companion object {
        // Shared across overlapping screens and Experiences. SDK workload
        // ceilings do not assert the platform's hardware decoder capacity.
        // Include upward rounding of the shortest sample interval.
        val productionBudget = NuxieVideoDecoderBudget(
            maxPlayers = 4, managedPlayers = 4, hardwarePlayers = 0,
            managedPixelsPerSecond = 3840L * 2160L * 61L, softwarePixelsPerSecond = 0,
        )
        val shared = ExperienceVideoDecoderPool { productionBudget }
    }

    private data class Key(val owner: UUID, val componentId: Long)
    private data class Entry(val id: Long, val request: NuxieVideoDecoderRequest)
    private var nextId = 0L
    private val entries = mutableMapOf<Key, Entry>()
    private val claims = mutableMapOf<Key, Long>()
    /** A running decoder's larger demand that does not fit yet; videos behind it cannot take it. */
    private val growth = mutableMapOf<Key, Long>()

    /** Pure allocation only: never calls into another owner's player or render lane. */
    @Synchronized
    fun update(owner: UUID, requests: List<NuxieVideoDecoderRequest>): Set<Long> {
        require(requests.map { it.id }.toSet().size == requests.size)
        require(requests.all { it.managedSupported && !it.hardwareSupported && !it.softwareSupported })
        val limits = budget()
        val live = requests.map { it.id }.toSet()
        entries.keys.removeAll { it.owner == owner && it.componentId !in live }
        growth.keys.removeAll { it.owner == owner && it.componentId !in live }
        for (request in requests.sortedBy { it.id }) {
            val key = Key(owner, request.id)
            val id = entries[key]?.id ?: run {
                check(nextId < Long.MAX_VALUE) { "Video decoder identity exhausted" }
                nextId++
            }
            entries[key] = Entry(id, request)
        }
        val ordered = entries.entries.sortedBy { it.value.id }
        // The allocator sees every request at the size it now asks for, a playing
        // decoder's growth included, so priority decides exactly as for new videos.
        val selected = select(ordered, limits) { _, entry -> entry.request.pixelsPerSecond }
        val growing = ordered.filter { (key, entry) -> claims[key]?.let { entry.request.pixelsPerSecond > it } == true }
            .map { it.key }.toSet()
        // The same choice if every growing decoder asked only for what it holds.
        val heldSelection by lazy {
            select(ordered, limits) { key, entry -> if (key in growing) claims.getValue(key) else entry.request.pixelsPerSecond }
        }
        val admitted = mutableSetOf<Long>()
        for ((key, entry) in ordered) {
            if (key.owner != owner) continue
            val wanted = entry.request.pixelsPerSecond
            val held = claims[key]
            if (key !in selected) {
                // A playing decoder is not revoked for its own growth: it keeps its
                // current claim while that claim still wins on priority and no video
                // ahead of it is waiting for a decoder. Otherwise priority revokes it.
                if (held != null && key in growing && key in heldSelection &&
                    ordered.none { (other, ahead) -> other in selected && other !in claims && isAhead(ahead, entry) }) {
                    growth[key] = wanted
                    admitted.add(key.componentId)
                } else growth.remove(key)
                continue
            }
            if (held != null) {
                // Resize the running decoder's claim in place. A smaller claim always
                // fits; a larger one grows under the same budget rule as a new claim.
                // When it does not fit, the decoder keeps playing on its current claim
                // and the difference is held back from videos behind it.
                if (wanted <= held || wanted <= available(limits, key, entry)) {
                    claims[key] = wanted
                    growth.remove(key)
                } else growth[key] = wanted
                admitted.add(key.componentId)
                continue
            }
            if (claims.size >= minOf(limits.maxPlayers, limits.managedPlayers)) continue
            if (wanted > available(limits, key, entry)) continue
            claims[key] = wanted
            admitted.add(key.componentId)
        }
        return admitted
    }

    private fun select(ordered: List<Map.Entry<Key, Entry>>, limits: NuxieVideoDecoderBudget,
                       pixels: (Key, Entry) -> Long): Set<Key> {
        val choices = JniNuxieTypedRuntimeNative.videoAllocateDecoders(ordered.map { (key, entry) ->
            entry.request.copy(id = entry.id, pixelsPerSecond = pixels(key, entry))
        }, limits)
        return ordered.zip(choices).filter { it.second == NuxieVideoAllocation.PlatformManaged }.map { it.first.key }.toSet()
    }

    /** The allocator's order: higher priority first, then the older request. */
    private fun isAhead(entry: Entry, of: Entry): Boolean =
        entry.request.priority > of.request.priority ||
            (entry.request.priority == of.request.priority && entry.id < of.id)

    /**
     * Pixel work left for [key] after every other claim. A waiting growth is held
     * back only from videos behind it, so it never blocks a higher-priority video
     * and two waiting growths cannot block each other.
     */
    private fun available(limits: NuxieVideoDecoderBudget, key: Key, entry: Entry): Long {
        var available = limits.managedPixelsPerSecond
        for ((other, held) in claims) {
            if (other == key) continue
            val pending = growth[other]
            val otherEntry = entries[other]
            val reserved = if (pending != null && otherEntry != null && isAhead(otherEntry, entry)) maxOf(held, pending) else held
            available = if (reserved > available) 0 else available - reserved
        }
        return available
    }

    /** Call only after close succeeds; a timed-out close still owns its reservation. */
    @Synchronized
    fun release(owner: UUID, componentId: Long) {
        claims.remove(Key(owner, componentId))
        growth.remove(Key(owner, componentId))
    }

    /** Call only after all of this owner's decoders have closed successfully. */
    @Synchronized
    fun remove(owner: UUID) {
        entries.keys.removeAll { it.owner == owner }
        claims.keys.removeAll { it.owner == owner }
        growth.keys.removeAll { it.owner == owner }
    }
}
