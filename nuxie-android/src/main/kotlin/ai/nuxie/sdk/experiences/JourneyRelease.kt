package ai.nuxie.sdk.experiences

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Authentication is deliberately separate from mutable delivery bindings. */
internal class AuthenticatedJourneyRelease(
    private val envelope: JourneyReleaseEnvelope,
    val identity: JourneyReleaseIdentity,
    val descriptor: JsonObject,
    val publishedAtSeqToPromote: Long?,
) {
    val keyId: String get() = envelope.keyId
    val descriptorSha256: String get() = envelope.sha256
    val descriptorBytes: ByteArray get() = envelope.descriptorBytes
    val leg: JsonObject get() = descriptor.getValue("leg") as JsonObject
}

internal object JourneyReleaseVerifier {
    fun authenticate(
        envelopeBytes: ByteArray,
        trustedKeys: Map<String, ByteArray>,
        expectedIdentity: JourneyReleaseIdentity,
        expectedLegId: String,
        supportedRuntime: JourneyReleaseSupportedRuntime,
        replayPolicy: JourneyReleaseReplayPolicy,
    ): AuthenticatedJourneyRelease {
        val envelope = JourneyReleaseEnvelope.authenticate(envelopeBytes, trustedKeys)
        val descriptor = JourneyReleaseEnvelope.parseObject(envelope.descriptorBytes)
        JourneySchemaValidator.validate(descriptor)
        val identity = JourneyReleaseIdentity.fromJson(JourneyReleaseJson.record(descriptor["identity"]))
            ?: JourneyReleaseJson.fail("identity")
        if (identity != expectedIdentity || JourneyReleaseJson.text(JourneyReleaseJson.record(descriptor["leg"])["id"]) != expectedLegId) {
            JourneyReleaseJson.fail("Journey identity mismatch")
        }
        if (descriptor["requirements"] != JsonNull) {
            validateRequirements(JourneyReleaseJson.record(descriptor["requirements"]), supportedRuntime)
        }
        val promote = when (replayPolicy) {
            is JourneyReleaseReplayPolicy.Active -> {
                if (replayPolicy.minimumPublishedAtSeq < 0 || identity.publishedAtSeq < replayPolicy.minimumPublishedAtSeq) {
                    JourneyReleaseJson.fail("replay rejected")
                }
                identity.publishedAtSeq
            }
            is JourneyReleaseReplayPolicy.Pinned -> {
                if (identity.experienceVersionId != replayPolicy.experienceVersionId || identity.buildId != replayPolicy.buildId ||
                    envelope.sha256 != replayPolicy.expectedDescriptorSha256) JourneyReleaseJson.fail("replay rejected")
                null
            }
        }
        return AuthenticatedJourneyRelease(envelope, identity, descriptor, promote)
    }

    internal fun validateRequirements(
        requirements: JsonObject,
        supported: JourneyReleaseSupportedRuntime,
    ) {
        val minimumSdk = SemanticVersion.parse(
            requirements.string("minimumSdkVersion") ?: fail("minimumSdkVersion"),
        ) ?: fail("minimumSdkVersion")
        val currentSdk = SemanticVersion.parse(supported.currentSdkVersion)
            ?: fail("current sdk version")
        if (currentSdk < minimumSdk) fail("sdk below minimum")

        val runtimeRevision = requirements.string("runtimeRevision") ?: fail("runtimeRevision")
        if (runtimeRevision !in supported.supportedRuntimeRevisions) fail("runtime revision")

        val luau = requirements["luau"] as? JsonObject ?: fail("luau")
        val luauRevision = luau.string("revision") ?: fail("luau revision")
        val supportedBytecode = supported.supportedLuauRevisions[luauRevision]
            ?: fail("luau revision unsupported")
        val declaredBytecode = (luau["bytecodeVersions"] as? JsonArray
            ?: fail("bytecodeVersions")).map { value ->
            (value as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { it == kotlin.math.floor(it) && it in 0.0..65_535.0 }
                ?.toInt() ?: fail("bytecode version")
        }
        if (!supportedBytecode.containsAll(declaredBytecode)) fail("luau bytecode")

        val sceneFormat = requirements["sceneFormat"] as? JsonObject ?: fail("sceneFormat")
        val major = sceneFormat.long("major") ?: fail("scene major")
        val minor = sceneFormat.long("minor") ?: fail("scene minor")
        if (major != supported.sceneFormatMajor.toLong() ||
            minor > supported.sceneFormatMinor.toLong()
        ) fail("scene format")

        val timezone = requirements["timezoneData"] as? JsonObject ?: fail("timezoneData")
        if (timezone.string("format") != "iana-tzdb") fail("timezone format")
        if (timezone.string("revision") != supported.timezoneDataRevision) fail("timezone revision")
        if (timezone.string("sha256") != supported.timezoneDataSha256) fail("timezone sha")

        val unsupported = requiredCapabilities(requirements)
            .filterNot { it in supported.supportedCapabilities }
        if (unsupported.isNotEmpty()) {
            fail("unsupported capabilities: ${unsupported.sorted()}")
        }
    }

    internal fun canonicalBase64Decode(text: String, maximumBytes: Int): ByteArray? {
        val decoded = runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        if (decoded.size > maximumBytes) return null
        val encoded = Base64.encodeToString(decoded, Base64.NO_WRAP)
        return decoded.takeIf { encoded == text }
    }

    private fun requiredCapabilities(requirements: JsonObject): List<String> {
        val value = requirements["requiredCapabilities"] ?: return emptyList()
        val values = (value as? JsonArray)
            ?.takeIf { it.size <= JourneyReleaseLimits.REQUIRED_CAPABILITY_COUNT }
            ?: fail("requiredCapabilities")
        val capabilities = values.map { entry ->
            (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf {
                    it.isNotEmpty() &&
                        it.toByteArray().size <= JourneyReleaseLimits.KEY_ID_BYTES
                }
                ?: fail("requiredCapabilities entry")
        }
        capabilities.zipWithNext().forEach { (left, right) ->
            if (left >= right) fail("requiredCapabilities ordering")
        }
        return capabilities
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.doubleOrNull
            ?.takeIf { it == kotlin.math.floor(it) }
            ?.toLong()

    private fun fail(message: String): Nothing =
        throw JourneyReleaseAuthenticationException(message)
}
