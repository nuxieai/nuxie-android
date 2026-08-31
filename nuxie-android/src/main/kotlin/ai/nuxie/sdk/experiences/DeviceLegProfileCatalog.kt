package ai.nuxie.sdk.experiences

import kotlinx.serialization.json.JsonObject

/**
 * Authenticates a complete plane profile before publishing any arm, fact, or
 * release. ProfileService owns admission ordering; this catalog owns the
 * immutable device-leg authority visible to the executor.
 */
internal class DeviceLegProfileCatalog(
    private val trustedKeys: Map<String, ByteArray>,
    private val highWater: ReleaseHighWaterStore,
    private val supportedRuntime: () -> SupportedRuntime?,
) {
    data class Snapshot(
        val profile: JourneyPlaneProfile,
        val releasesByDigest: Map<String, AuthenticatedDeviceLegRelease>,
    )

    class Prepared internal constructor(
        internal val snapshot: Snapshot,
        internal val promotions: Map<String, Long>,
    )

    private val lock = Any()
    private var current: Pair<String, Snapshot>? = null

    fun prepare(body: JsonObject): Prepared {
        val runtime = supportedRuntime() ?: throw ReleaseAuthenticationException("device leg runtime unavailable")
        val profile = JourneyPlaneProfile.decode(body.toString().encodeToByteArray())
        val authenticated = LinkedHashMap<String, AuthenticatedDeviceLegRelease>(profile.releases.size)
        val promotions = mutableMapOf<String, Long>()
        for (entry in profile.releases) {
            val release = DeviceLegReleaseVerifier.authenticate(
                envelopeBytes = entry.envelope.toString().encodeToByteArray(),
                trustedKeys = trustedKeys,
                expectedIdentity = entry.locator,
                expectedLegId = entry.legId,
                supportedRuntime = runtime,
                replayPolicy = ReplayPolicy.Active(highWater.floor(entry.locator.streamKey)),
            )
            if (authenticated.put(release.descriptorSha256, release) != null) {
                throw ReleaseAuthenticationException("duplicate authenticated device leg")
            }
            release.publishedAtSeqToPromote?.let { sequence ->
                val previous = promotions.put(entry.locator.streamKey, sequence)
                if (previous != null && previous != sequence) {
                    throw ReleaseAuthenticationException("conflicting release sequence")
                }
            }
        }
        return Prepared(Snapshot(profile, authenticated), promotions)
    }

    /** Called only inside ProfileService's current identity/generation commit. */
    fun commit(distinctId: String, prepared: Prepared) = synchronized(lock) {
        highWater.admitBatch(prepared.promotions)
        current = distinctId to prepared.snapshot
    }

    fun snapshot(distinctId: String): Snapshot? = synchronized(lock) {
        current?.takeIf { it.first == distinctId }?.second
    }

    fun clear(distinctId: String) = synchronized(lock) {
        if (current?.first == distinctId) current = null
    }
}
