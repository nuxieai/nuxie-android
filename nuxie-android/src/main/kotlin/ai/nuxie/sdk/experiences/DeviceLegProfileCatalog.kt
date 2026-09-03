package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        internal val authority: ProfileDeliveryAuthority,
    )

    private val lock = Any()
    private var current: Pair<String, Snapshot>? = null
    private var authority: ProfileDeliveryAuthority? = null

    fun prepare(body: JsonObject, deliveryAuthority: ProfileDeliveryAuthority): Prepared {
        if (!deliveryAuthority.isValid || synchronized(lock) {
                this@DeviceLegProfileCatalog.authority != null &&
                    this@DeviceLegProfileCatalog.authority != deliveryAuthority
            }
        ) {
            throw ReleaseAuthenticationException("invalid profile delivery authority")
        }
        val runtime = supportedRuntime() ?: throw ReleaseAuthenticationException("device leg runtime unavailable")
        val profile = JourneyPlaneProfile.decode(body.toString().encodeToByteArray())
        val authenticated = LinkedHashMap<String, AuthenticatedDeviceLegRelease>(profile.releases.size)
        val promotions = mutableMapOf<String, Long>()
        val activeDigests = profile.armedLegs.asSequence()
            .filter { it.binding.getValue("type").jsonPrimitive.content == "new" }
            .map { it.reference.getValue("descriptorSha256").jsonPrimitive.content }
            .toSet()
        for (entry in profile.releases) {
            if (entry.locator.appId != deliveryAuthority.appId ||
                entry.locator.environment != deliveryAuthority.environment
            ) {
                throw ReleaseAuthenticationException("release locator changed profile authority")
            }
            val descriptorSha256 = entry.envelope.getValue("descriptorSha256").jsonPrimitive.content
            val isActive = descriptorSha256 in activeDigests
            val release = DeviceLegReleaseVerifier.authenticate(
                envelopeBytes = entry.envelope.toString().encodeToByteArray(),
                trustedKeys = trustedKeys,
                expectedIdentity = entry.locator,
                expectedLegId = entry.legId,
                supportedRuntime = runtime,
                replayPolicy = if (isActive) {
                    ReplayPolicy.Active(highWater.floor(entry.locator.streamKey))
                } else {
                    ReplayPolicy.Pinned(
                        entry.locator.experienceVersionId,
                        entry.locator.buildId,
                        descriptorSha256,
                    )
                },
            )
            if (authenticated.put(release.descriptorSha256, release) != null) {
                throw ReleaseAuthenticationException("duplicate authenticated device leg")
            }
            if (isActive) release.releaseSequenceToPromote?.let { sequence ->
                val previous = promotions.put(entry.locator.streamKey, sequence)
                if (previous != null && previous != sequence) {
                    throw ReleaseAuthenticationException("conflicting release sequence")
                }
            }
        }
        // Delivery may select an authenticated leg, but it cannot replace the
        // trigger authored inside that signed program.
        for (arm in profile.armedLegs) {
            val digest = arm.reference.getValue("descriptorSha256").jsonPrimitive.content
            val release = authenticated[digest]
                ?: throw ReleaseAuthenticationException("missing authenticated device leg")
            if (arm.entryCondition != release.leg.getValue("entryCondition")) {
                throw ReleaseAuthenticationException("device leg entry condition changed")
            }
        }
        return Prepared(Snapshot(profile, authenticated), promotions, deliveryAuthority)
    }

    /** Called only inside ProfileService's current identity/generation commit. */
    fun commit(distinctId: String, prepared: Prepared) = synchronized(lock) {
        if (this@DeviceLegProfileCatalog.authority != null &&
            this@DeviceLegProfileCatalog.authority != prepared.authority
        ) {
            throw ReleaseAuthenticationException("profile delivery authority changed")
        }
        // Empty canonical profiles bind authority too. Clearing customer state
        // never changes the configured app/environment for this SDK setup.
        this@DeviceLegProfileCatalog.authority = prepared.authority
        highWater.admitBatch(prepared.promotions)
        current = distinctId to prepared.snapshot
    }

    fun snapshot(distinctId: String): Snapshot? = synchronized(lock) {
        current?.takeIf { it.first == distinctId }?.second
    }

    /** Re-authenticates the exact release retained by a durable parked run. */
    fun authenticatePinnedRelease(
        value: JsonObject,
        reference: JsonObject,
    ): AuthenticatedDeviceLegRelease {
        val boundAuthority = synchronized(lock) { authority }
            ?: throw ReleaseAuthenticationException("profile delivery authority unavailable")
        val runtime = supportedRuntime()
            ?: throw ReleaseAuthenticationException("device leg runtime unavailable")
        val entry = JourneyPlaneProfile.decodeRelease(value)
        val descriptorSha256 = entry.envelope.getValue("descriptorSha256").jsonPrimitive.content
        if (descriptorSha256 != reference.getValue("descriptorSha256").jsonPrimitive.content ||
            entry.locator.experienceId != reference.getValue("experienceId").jsonPrimitive.content ||
            entry.locator.experienceVersionId != reference.getValue("versionId").jsonPrimitive.content ||
            entry.legId != reference.getValue("legId").jsonPrimitive.content ||
            entry.locator.appId != boundAuthority.appId ||
            entry.locator.environment != boundAuthority.environment
        ) {
            throw ReleaseAuthenticationException("retained device leg changed authority")
        }
        return DeviceLegReleaseVerifier.authenticate(
            envelopeBytes = entry.envelope.toString().encodeToByteArray(),
            trustedKeys = trustedKeys,
            expectedIdentity = entry.locator,
            expectedLegId = entry.legId,
            supportedRuntime = runtime,
            replayPolicy = ReplayPolicy.Pinned(
                entry.locator.experienceVersionId,
                entry.locator.buildId,
                descriptorSha256,
            ),
        )
    }

    fun clear(distinctId: String) = synchronized(lock) {
        if (current?.first == distinctId) current = null
    }
}
