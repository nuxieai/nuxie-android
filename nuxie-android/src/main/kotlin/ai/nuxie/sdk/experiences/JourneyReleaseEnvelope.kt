package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.StrictJsonValidator
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Exact-byte authentication for the canonical Journey release envelope. */
internal class JourneyReleaseEnvelope private constructor(
    val keyId: String,
    val sha256: String,
    private val bytes: ByteArray,
) {
    val descriptorBytes: ByteArray get() = bytes.copyOf()

    companion object {
        fun authenticate(
            envelopeBytes: ByteArray,
            trustedKeys: Map<String, ByteArray>,
        ): JourneyReleaseEnvelope {
            val envelope = decode(envelopeBytes)
            val key = trustedKeys[envelope.keyId] ?: fail("unknown signing key: ${envelope.keyId}")
            if (key.size != 32) fail("invalid trust root")
            if (!Ed25519Verifier.verify(
                    key,
                    JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + envelope.bytes,
                    envelope.signature,
                )
            ) {
                fail("invalid signature")
            }
            return JourneyReleaseEnvelope(envelope.keyId, envelope.sha, envelope.bytes)
        }

        /** Shape/digest checking for profile staging; this does not authenticate. */
        fun validateShape(envelopeBytes: ByteArray) {
            decode(envelopeBytes)
        }

        private data class Decoded(val keyId: String, val sha: String, val bytes: ByteArray, val signature: ByteArray)

        private fun decode(envelopeBytes: ByteArray): Decoded {
            if (envelopeBytes.size > JourneyReleaseLimits.ENVELOPE_BYTES) fail("envelope exceeds size limit")
            val envelope = parseObject(envelopeBytes)
            exact(envelope, setOf("mediaType", "encoding", "descriptorSha256", "descriptorSizeBytes", "descriptorBytesBase64", "signature"))
            if (envelope.string("mediaType") != JourneyReleaseLimits.MEDIA_TYPE) fail("media type")
            if (envelope.string("encoding") != "base64") fail("encoding")
            val signature = envelope["signature"] as? JsonObject ?: fail("signature missing")
            exact(signature, setOf("version", "algorithm", "keyId", "signatureBase64"))
            if (signature.integer("version") != 1L) fail("signature version")
            if (signature.string("algorithm") != "ed25519") fail("signature algorithm")
            val keyId = signature.string("keyId").takeIf { it.isNotEmpty() && it.length <= JourneyReleaseLimits.KEY_ID_BYTES }
                ?: fail("keyId")
            val bytes = JourneyReleaseVerifier.canonicalBase64Decode(
                envelope.string("descriptorBytesBase64"), JourneyReleaseLimits.DESCRIPTOR_BYTES,
            ) ?: fail("descriptor base64")
            if (bytes.isEmpty() || envelope.integer("descriptorSizeBytes") != bytes.size.toLong()) fail("descriptor size mismatch")
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (envelope.string("descriptorSha256") != sha) fail("descriptor sha mismatch")
            val signatureBytes = JourneyReleaseVerifier.canonicalBase64Decode(signature.string("signatureBase64"), 64)
                ?.takeIf { it.size == 64 } ?: fail("signature encoding")
            return Decoded(keyId, sha, bytes, signatureBytes)
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
        private fun fail(message: String): Nothing = throw JourneyReleaseAuthenticationException(message)
    }
}
