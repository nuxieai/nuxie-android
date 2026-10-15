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
    /** A running decoder's larger demand that does not fit yet; new claims cannot take it. */
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
        // A held claim competes with what it already holds, so a running decoder
        // is never revoked because its own demand grew (a faster playback rate).
        val choices = JniNuxieTypedRuntimeNative.videoAllocateDecoders(ordered.map { (key, entry) ->
            val held = claims[key]
            val pixels = if (held == null) entry.request.pixelsPerSecond else minOf(held, entry.request.pixelsPerSecond)
            entry.request.copy(id = entry.id, pixelsPerSecond = pixels)
        }, limits)
        val selected = ordered.zip(choices).filter { it.second == NuxieVideoAllocation.PlatformManaged }
            .map { it.first.key }.toSet()
        val admitted = mutableSetOf<Long>()
        for ((key, entry) in ordered) {
            if (key.owner != owner || key !in selected) continue
            val wanted = entry.request.pixelsPerSecond
            val held = claims[key]
            if (held != null) {
                // Resize the running decoder's claim in place. A smaller claim always
                // fits; a larger one grows under the same budget rule as a new claim.
                // When it does not fit, the decoder keeps playing on its current claim
                // and the difference is held back from new claims until it fits.
                if (wanted <= held || wanted <= available(limits, except = key)) {
                    claims[key] = wanted
                    growth.remove(key)
                } else growth[key] = wanted
                admitted.add(key.componentId)
                continue
            }
            if (claims.size >= minOf(limits.maxPlayers, limits.managedPlayers)) continue
            if (wanted > available(limits, except = null)) continue
            claims[key] = wanted
            admitted.add(key.componentId)
        }
        return admitted
    }

    /** Pixel work left after every other claim, counting growth that is still waiting. */
    private fun available(limits: NuxieVideoDecoderBudget, except: Key?): Long {
        var available = limits.managedPixelsPerSecond
        for ((key, held) in claims) {
            if (key == except) continue
            val reserved = maxOf(held, growth[key] ?: held)
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
