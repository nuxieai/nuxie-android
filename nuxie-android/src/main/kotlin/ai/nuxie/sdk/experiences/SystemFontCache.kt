package ai.nuxie.sdk.experiences

/** Process-local, bounded cache containing only successfully imported device fonts. */
internal class SystemFontCache(private val maximumBytes: Int = 32 * 1024 * 1024) {
    data class Lease(val identity: String, val candidate: SystemFontCandidate)

    private val entries = LinkedHashMap<String, SystemFontCandidate>(16, 0.75f, true)

    fun prepare(requirement: SystemFontRequirement): Lease {
        val selection = SystemFontProvider.select(requirement)
        return candidate(selection.identity, selection.extract)
    }

    fun candidate(identity: String, extract: () -> SystemFontCandidate): Lease {
        val cached = synchronized(this) { entries[identity] }
        return Lease(identity, cached ?: extract())
    }

    @Synchronized
    fun didImport(leases: List<Lease>) {
        for (lease in leases) {
            val candidate = lease.candidate
            if (candidate.bytes.size > maximumBytes) continue
            val shared = entries.values.firstOrNull { it.digest == candidate.digest }?.bytes
            entries[lease.identity] = candidate.copy(bytes = shared ?: candidate.bytes)
        }
        while (entries.isNotEmpty() && (entries.size > 32 || retainedBytes() > maximumBytes)) {
            val oldest = entries.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    @Synchronized
    fun didFailImport(leases: List<Lease>) {
        val rejectedDigests = leases.map { it.candidate.digest }.toSet()
        // Evict aliases too, but do not remove a newer revision with different bytes.
        entries.entries.removeAll { it.value.digest in rejectedDigests }
    }

    private fun retainedBytes(): Long = entries.values.distinctBy { it.digest }
        .sumOf { it.bytes.size.toLong() }

    companion object {
        val shared = SystemFontCache()
    }
}
