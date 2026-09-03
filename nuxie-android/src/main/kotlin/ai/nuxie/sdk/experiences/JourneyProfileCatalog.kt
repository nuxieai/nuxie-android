package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Authenticates a complete plane profile before publishing any arm, fact, or
 * release. ProfileService owns admission ordering; this catalog owns the
 * immutable journey authority visible to the executor.
 */
internal class JourneyProfileCatalog(
    private val trustedKeys: Map<String, ByteArray>,
    private val highWater: JourneyReleaseHighWaterStore,
    private val onReleaseAdmitted: (AuthenticatedJourneyRelease) -> Boolean = { true },
    private val supportedRuntime: () -> JourneyReleaseSupportedRuntime?,
) {
    data class Snapshot(
        val profile: JourneyPlaneProfile,
        val releasesByDigest: Map<String, AuthenticatedJourneyRelease>,
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
                this@JourneyProfileCatalog.authority != null &&
                    this@JourneyProfileCatalog.authority != deliveryAuthority
            }
        ) {
            throw JourneyReleaseAuthenticationException("invalid profile delivery authority")
        }
        val profile = JourneyPlaneProfile.decode(body.toString().encodeToByteArray())
        val runtime = if (profile.releases.isEmpty()) {
            null
        } else {
            supportedRuntime()
                ?: throw JourneyReleaseAuthenticationException("Journey runtime unavailable")
        }
        val authenticated = LinkedHashMap<String, AuthenticatedJourneyRelease>(profile.releases.size)
        val promotions = mutableMapOf<String, Long>()
        val activeDigests = profile.armedLegs.asSequence()
            .filter { it.binding.getValue("type").jsonPrimitive.content == "new" }
            .map { it.reference.getValue("descriptorSha256").jsonPrimitive.content }
            .toSet()
        for (entry in profile.releases) {
            if (entry.locator.appId != deliveryAuthority.appId ||
                entry.locator.environment != deliveryAuthority.environment
            ) {
                throw JourneyReleaseAuthenticationException("release locator changed profile authority")
            }
            val descriptorSha256 = entry.envelope.getValue("descriptorSha256").jsonPrimitive.content
            val isActive = descriptorSha256 in activeDigests
            val release = JourneyReleaseVerifier.authenticate(
                envelopeBytes = entry.envelope.toString().encodeToByteArray(),
                trustedKeys = trustedKeys,
                expectedIdentity = entry.locator,
                expectedLegId = entry.legId,
                supportedRuntime = requireNotNull(runtime),
                replayPolicy = if (isActive) {
                    JourneyReleaseReplayPolicy.Active(highWater.floor(entry.locator.streamKey))
                } else {
                    JourneyReleaseReplayPolicy.Pinned(
                        entry.locator.experienceVersionId,
                        entry.locator.buildId,
                        descriptorSha256,
                    )
                },
            )
            if (authenticated.put(release.descriptorSha256, release) != null) {
                throw JourneyReleaseAuthenticationException("duplicate authenticated Journey")
            }
            if (isActive) release.publishedAtSeqToPromote?.let { sequence ->
                val previous = promotions.put(entry.locator.streamKey, sequence)
                if (previous != null && previous != sequence) {
                    throw JourneyReleaseAuthenticationException("conflicting release sequence")
                }
            }
        }
        // JourneyReleaseDelivery may select an authenticated leg, but it cannot replace the
        // trigger authored inside that signed program.
        for (arm in profile.armedLegs) {
            val digest = arm.reference.getValue("descriptorSha256").jsonPrimitive.content
            val release = authenticated[digest]
                ?: throw JourneyReleaseAuthenticationException("missing authenticated Journey")
            if (arm.entryCondition != release.leg.getValue("entryCondition")) {
                throw JourneyReleaseAuthenticationException("Journey entry condition changed")
            }
        }
        return Prepared(Snapshot(profile, authenticated), promotions, deliveryAuthority)
    }

    /** Called only inside ProfileService's current identity/generation commit. */
    fun commit(distinctId: String, prepared: Prepared) = synchronized(lock) {
        if (this@JourneyProfileCatalog.authority != null &&
            this@JourneyProfileCatalog.authority != prepared.authority
        ) {
            throw JourneyReleaseAuthenticationException("profile delivery authority changed")
        }
        // Empty canonical profiles bind authority too. Clearing customer state
        // never changes the configured app/environment for this SDK setup.
        if (prepared.snapshot.releasesByDigest.values.any { !onReleaseAdmitted(it) }) {
            throw JourneyReleaseAuthenticationException(
                "authenticated Journey product mappings could not be retained",
            )
        }
        this@JourneyProfileCatalog.authority = prepared.authority
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
    ): AuthenticatedJourneyRelease {
        val boundAuthority = synchronized(lock) { authority }
            ?: throw JourneyReleaseAuthenticationException("profile delivery authority unavailable")
        val runtime = supportedRuntime()
            ?: throw JourneyReleaseAuthenticationException("Journey runtime unavailable")
        val entry = JourneyPlaneProfile.decodeRelease(value)
        val descriptorSha256 = entry.envelope.getValue("descriptorSha256").jsonPrimitive.content
        if (descriptorSha256 != reference.getValue("descriptorSha256").jsonPrimitive.content ||
            entry.locator.experienceId != reference.getValue("experienceId").jsonPrimitive.content ||
            entry.locator.experienceVersionId != reference.getValue("versionId").jsonPrimitive.content ||
            entry.legId != reference.getValue("legId").jsonPrimitive.content ||
            entry.locator.appId != boundAuthority.appId ||
            entry.locator.environment != boundAuthority.environment
        ) {
            throw JourneyReleaseAuthenticationException("retained Journey changed authority")
        }
        return JourneyReleaseVerifier.authenticate(
            envelopeBytes = entry.envelope.toString().encodeToByteArray(),
            trustedKeys = trustedKeys,
            expectedIdentity = entry.locator,
            expectedLegId = entry.legId,
            supportedRuntime = runtime,
            replayPolicy = JourneyReleaseReplayPolicy.Pinned(
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
