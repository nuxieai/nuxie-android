package ai.nuxie.sdk.experiences

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Authentication is deliberately separate from mutable delivery bindings. */
internal class AuthenticatedDeviceLegRelease(
    private val envelope: SignedReleaseEnvelope,
    val identity: ExperienceReleaseIdentity,
    val descriptor: JsonObject,
    val releaseSequenceToPromote: Long?,
) {
    val keyId: String get() = envelope.keyId
    val descriptorSha256: String get() = envelope.sha256
    val descriptorBytes: ByteArray get() = envelope.descriptorBytes
    val leg: JsonObject get() = descriptor.getValue("leg") as JsonObject
}

internal object DeviceLegReleaseVerifier {
    fun authenticate(
        envelopeBytes: ByteArray,
        trustedKeys: Map<String, ByteArray>,
        expectedIdentity: ExperienceReleaseIdentity,
        expectedLegId: String,
        supportedRuntime: SupportedRuntime,
        replayPolicy: ReplayPolicy,
    ): AuthenticatedDeviceLegRelease {
        val envelope = SignedReleaseEnvelope.authenticate(envelopeBytes, trustedKeys, SignedReleaseEnvelope.Format.DEVICE_LEG)
        val descriptor = SignedReleaseEnvelope.parseObject(envelope.descriptorBytes)
        DeviceLegSchemaValidator.validate(descriptor)
        val identity = ExperienceReleaseIdentity.fromJson(ReleaseJson.record(descriptor["identity"]))
            ?: ReleaseJson.fail("identity")
        if (identity != expectedIdentity || ReleaseJson.text(ReleaseJson.record(descriptor["leg"])["id"]) != expectedLegId) {
            ReleaseJson.fail("device leg identity mismatch")
        }
        if (descriptor["requirements"] != JsonNull) {
            ExperienceReleaseVerifier.validateRequirements(ReleaseJson.record(descriptor["requirements"]), supportedRuntime)
        }
        val promote = when (replayPolicy) {
            is ReplayPolicy.Active -> {
                if (replayPolicy.minimumReleaseSequence < 0 || identity.releaseSequence < replayPolicy.minimumReleaseSequence) {
                    ReleaseJson.fail("replay rejected")
                }
                identity.releaseSequence
            }
            is ReplayPolicy.Pinned -> {
                if (identity.experienceVersionId != replayPolicy.experienceVersionId || identity.buildId != replayPolicy.buildId ||
                    envelope.sha256 != replayPolicy.expectedDescriptorSha256) ReleaseJson.fail("replay rejected")
                null
            }
        }
        return AuthenticatedDeviceLegRelease(envelope, identity, descriptor, promote)
    }
}
