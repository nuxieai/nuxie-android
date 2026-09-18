package ai.nuxie.sdk.runtime

import java.util.UUID

/** Shared across runtime lanes. Revocation retains capacity until decoder disposal is acknowledged. */
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

    /** Pure allocation only: never calls into another owner's player or render lane. */
    @Synchronized
    fun update(owner: UUID, requests: List<NuxieVideoDecoderRequest>): Set<Long> {
        require(requests.map { it.id }.toSet().size == requests.size)
        require(requests.all { it.managedSupported && !it.hardwareSupported && !it.softwareSupported })
        val limits = budget()
        val live = requests.map { it.id }.toSet()
        entries.keys.removeAll { it.owner == owner && it.componentId !in live }
        for (request in requests.sortedBy { it.id }) {
            val key = Key(owner, request.id)
            val id = entries[key]?.id ?: run {
                check(nextId < Long.MAX_VALUE) { "Video decoder identity exhausted" }
                nextId++
            }
            entries[key] = Entry(id, request)
        }
        val ordered = entries.entries.sortedBy { it.value.id }
        val choices = JniNuxieTypedRuntimeNative.videoAllocateDecoders(
            ordered.map { it.value.request.copy(id = it.value.id) }, limits)
        val selected = ordered.zip(choices).filter { it.second == NuxieVideoAllocation.PlatformManaged }
            .map { it.first.key }.toSet()
        val admitted = mutableSetOf<Long>()
        for ((key, entry) in ordered) {
            if (key.owner != owner || key !in selected) continue
            val held = claims[key]
            if (held != null) {
                if (held == entry.request.pixelsPerSecond) admitted.add(key.componentId)
                continue
            }
            if (claims.size >= minOf(limits.maxPlayers, limits.managedPlayers)) continue
            var available = limits.managedPixelsPerSecond
            for (cost in claims.values) available = if (cost > available) 0 else available - cost
            if (entry.request.pixelsPerSecond > available) continue
            claims[key] = entry.request.pixelsPerSecond
            admitted.add(key.componentId)
        }
        return admitted
    }

    /** Call only after close succeeds; a timed-out close still owns its reservation. */
    @Synchronized
    fun release(owner: UUID, componentId: Long) {
        claims.remove(Key(owner, componentId))
    }

    /** Call only after all of this owner's decoders have closed successfully. */
    @Synchronized
    fun remove(owner: UUID) {
        entries.keys.removeAll { it.owner == owner }
        claims.keys.removeAll { it.owner == owner }
    }
}
