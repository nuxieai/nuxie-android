package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.StrictJsonValidator
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Exact-byte authentication shared by release grammars. A verified envelope
 * is not an admitted program: the caller must still validate its descriptor,
 * expected identity, runtime requirements, and replay policy before use. */
internal class SignedReleaseEnvelope private constructor(
    val keyId: String,
    val sha256: String,
    private val bytes: ByteArray,
) {
    val descriptorBytes: ByteArray get() = bytes.copyOf()

    enum class Format(val mediaType: String, val signatureDomain: String) {
        EXPERIENCE(ExperienceReleaseLimits.MEDIA_TYPE, ExperienceReleaseLimits.SIGNATURE_DOMAIN),
        DEVICE_LEG("application/vnd.nuxie.device-leg+json", "nuxie.device-leg-release.v1\u0000"),
    }

    companion object {
        fun authenticate(
            envelopeBytes: ByteArray,
            trustedKeys: Map<String, ByteArray>,
            format: Format,
        ): SignedReleaseEnvelope {
            if (envelopeBytes.size > ExperienceReleaseLimits.ENVELOPE_BYTES) fail("envelope exceeds size limit")
            val envelope = parseObject(envelopeBytes)
            exact(envelope, setOf("mediaType", "encoding", "descriptorSha256", "descriptorSizeBytes", "descriptorBytesBase64", "signature"))
            if (envelope.string("mediaType") != format.mediaType) fail("media type")
            if (envelope.string("encoding") != "base64") fail("encoding")
            val signature = envelope["signature"] as? JsonObject ?: fail("signature missing")
            exact(signature, setOf("version", "algorithm", "keyId", "signatureBase64"))
            if (signature.integer("version") != 1L) fail("signature version")
            if (signature.string("algorithm") != "ed25519") fail("signature algorithm")
            val keyId = signature.string("keyId").takeIf { it.isNotEmpty() && it.length <= ExperienceReleaseLimits.KEY_ID_BYTES }
                ?: fail("keyId")
            val bytes = ExperienceReleaseVerifier.canonicalBase64Decode(
                envelope.string("descriptorBytesBase64"), ExperienceReleaseLimits.DESCRIPTOR_BYTES,
            ) ?: fail("descriptor base64")
            if (bytes.isEmpty() || envelope.integer("descriptorSizeBytes") != bytes.size.toLong()) fail("descriptor size mismatch")
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (envelope.string("descriptorSha256") != sha) fail("descriptor sha mismatch")
            val key = trustedKeys[keyId] ?: fail("unknown signing key: $keyId")
            if (key.size != 32) fail("invalid trust root")
            val signatureBytes = ExperienceReleaseVerifier.canonicalBase64Decode(signature.string("signatureBase64"), 64)
                ?.takeIf { it.size == 64 } ?: fail("signature encoding")
            if (!Ed25519Verifier.verify(key, format.signatureDomain.encodeToByteArray() + bytes, signatureBytes)) {
                fail("invalid signature")
            }
            return SignedReleaseEnvelope(keyId, sha, bytes)
        }

        /** Invoke only after authentication for descriptor bytes. */
        fun parseObject(bytes: ByteArray): JsonObject = try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            StrictJsonValidator.requireNoDuplicateKeys(text)
            Json.parseToJsonElement(text) as? JsonObject ?: fail("expected JSON object")
        } catch (_: Exception) {
            fail("invalid or duplicate JSON")
        }

        private fun exact(value: JsonObject, keys: Set<String>) {
            if (value.keys != keys) fail("unexpected envelope keys")
        }
        private fun JsonObject.string(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(key)
        private fun JsonObject.integer(key: String): Long =
            (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                ?.takeIf { it.isFinite() && it == kotlin.math.floor(it) && it in 0.0..9_007_199_254_740_991.0 }
                ?.toLong() ?: fail(key)
        private fun fail(message: String): Nothing = throw ReleaseAuthenticationException(message)
    }
}
