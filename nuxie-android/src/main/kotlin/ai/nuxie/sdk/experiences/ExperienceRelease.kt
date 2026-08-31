package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.features.FeatureAllowance
import android.util.Base64
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Signed Experience release authentication, ported from the iOS
 * `ExperienceReleaseDescriptor` + `ExperienceReleaseDescriptorVerifier`:
 * no descriptor byte is interpreted until the exact, domain-separated
 * Ed25519 signature has authenticated it.
 */
internal object ExperienceReleaseLimits {
    const val MEDIA_TYPE = "application/vnd.nuxie.experience-release+json;version=1"
    const val SCHEMA_VERSION = "nuxie.experience-release.v1"
    const val SIGNATURE_DOMAIN = "nuxie.experience-release-descriptor.v1\u0000"
    const val DESCRIPTOR_BYTES = 4 * 1024 * 1024
    const val ENVELOPE_BYTES = 6 * 1024 * 1024
    const val GENERIC_STRING_BYTES = 4 * 1024
    const val KEY_ID_BYTES = 256
    const val REQUIRED_CAPABILITY_COUNT = 256
    const val RIV_ARTIFACT_BYTES = 64 * 1024 * 1024
    const val EXTERNAL_ASSET_BYTES = 32 * 1024 * 1024
    const val SCRIPT_ARTIFACT_AGGREGATE_BYTES = 16 * 1024 * 1024
    const val ARTIFACT_AGGREGATE_BYTES = 128 * 1024 * 1024
}

internal data class ExperienceReleaseIdentity(
    val appId: String,
    val environment: String,
    val experienceId: String,
    val experienceVersionId: String,
    val buildId: String,
    val versionNumber: Long,
    val publishedAt: String,
    val publishedAtSeq: Long,
) {
    /** The admission stream this identity's replay protection is keyed on. */
    val streamKey: String get() = "$appId|$environment|$experienceId"

    companion object {
        fun fromJson(json: JsonObject): ExperienceReleaseIdentity? {
            fun string(key: String): String? =
                (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String): Long? = (json[key] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.doubleOrNull
                ?.takeIf { it == Math.floor(it) }?.toLong()
            return ExperienceReleaseIdentity(
                appId = string("appId") ?: return null,
                environment = string("environment") ?: return null,
                experienceId = string("experienceId") ?: return null,
                experienceVersionId = string("experienceVersionId") ?: return null,
                buildId = string("buildId") ?: return null,
                versionNumber = number("versionNumber") ?: return null,
                publishedAt = string("publishedAt") ?: return null,
                publishedAtSeq = number("publishedAtSeq") ?: return null,
            )
        }
    }
}

/** Runtime facts the embedded engine reports; releases gate against them. */
internal data class SupportedRuntime(
    val currentSdkVersion: String,
    val supportedRuntimeRevisions: Set<String>,
    /** luau revision -> supported bytecode versions */
    val supportedLuauRevisions: Map<String, Set<Int>>,
    val sceneFormatMajor: Int,
    val sceneFormatMinor: Int,
    val timezoneDataRevision: String,
    val timezoneDataSha256: String,
    val supportedCapabilities: Set<String>,
)

internal sealed class ReplayPolicy {
    /** Ordinary active-stream admission with monotonic replay protection. */
    class Active(val minimumPublishedAtSeq: Long) : ReplayPolicy()

    /** Pinned preview admission: exact version, build, and digest. */
    class Pinned(
        val experienceVersionId: String,
        val buildId: String,
        val expectedDescriptorSha256: String,
    ) : ReplayPolicy()
}

internal class AuthenticatedRelease(
    val keyId: String,
    val descriptorSha256: String,
    val identity: ExperienceReleaseIdentity,
    /** The exact authenticated descriptor bytes. */
    val descriptorBytes: ByteArray,
    /** The authenticated, exact-key-validated descriptor document. */
    val descriptor: JsonObject,
    /** publishedAtSeq to promote into the high-water store (Active only). */
    val publishedAtSeqToPromote: Long?,
    /** Raw signed Google Play allowances classified once at release admission. */
    val googlePlayProductAllowances: List<AuthenticatedGooglePlayProductAllowances> =
        authenticatedGooglePlayProductAllowances(descriptor),
)

internal data class AuthenticatedGooglePlayProductAllowances(
    val productId: String,
    val storeProductId: String,
    val featureAllowances: List<FeatureAllowance>,
)

/**
 * Production signed-descriptor adapter. Product resolution already accepts
 * classified [FeatureAllowance] values; this is the single raw-field boundary
 * that catalog registration can feed into it.
 */
internal fun authenticatedGooglePlayProductAllowances(
    descriptor: JsonObject,
): List<AuthenticatedGooglePlayProductAllowances> {
    fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    return (descriptor["products"] as? JsonArray).orEmpty().mapNotNull { productElement ->
        val product = productElement as? JsonObject ?: return@mapNotNull null
        val store = product["store"] as? JsonObject ?: return@mapNotNull null
        if (store.string("platform") != "google_play") return@mapNotNull null
        val productId = product.string("id") ?: return@mapNotNull null
        val storeProductId = store.string("productId") ?: return@mapNotNull null
        val allowances = (product["entitlements"] as? JsonArray).orEmpty()
            .mapNotNull { allowanceElement ->
                val allowanceDocument = allowanceElement as? JsonObject
                    ?: return@mapNotNull null
                val featureExternalId = allowanceDocument.string("featureExternalId")
                // The allowance document's own id is not a Feature id. A
                // source with neither Feature reference contributes nothing.
                val featureId = allowanceDocument.string("featureId")
                    ?: featureExternalId
                    ?: return@mapNotNull null
                FeatureAllowance.fromDescriptor(
                    featureId = featureId,
                    featureExternalId = featureExternalId,
                    allowanceType = allowanceDocument.string("allowanceType"),
                    allowance = allowanceDocument.number("allowance"),
                )
            }
        AuthenticatedGooglePlayProductAllowances(productId, storeProductId, allowances)
    }
}

internal class ReleaseAuthenticationException(message: String) : Exception(message)

internal object ExperienceReleaseVerifier {
    private val DESCRIPTOR_TOP_KEYS = setOf(
        "schemaVersion", "identity", "metadata", "enrollment", "lifecycle",
        "presentation", "products", "placements", "journey", "responseCaptures",
        "screenBehaviors", "render", "requirements", "provenance",
    )
    private val DESCRIPTOR_OPTIONAL_KEYS = setOf("responseSchema")

    fun authenticate(
        envelopeBytes: ByteArray,
        trustedKeys: Map<String, ByteArray>,
        expectedIdentity: ExperienceReleaseIdentity,
        supportedRuntime: SupportedRuntime,
        replayPolicy: ReplayPolicy,
    ): AuthenticatedRelease {
        if (envelopeBytes.size > ExperienceReleaseLimits.ENVELOPE_BYTES) {
            fail("envelope exceeds size limit")
        }
        val envelope = runCatching {
            Json.parseToJsonElement(envelopeBytes.decodeToString()).jsonObject
        }.getOrElse { fail("envelope is not JSON") }

        requireExactKeys(
            envelope,
            setOf(
                "mediaType", "encoding", "descriptorSha256",
                "descriptorSizeBytes", "descriptorBytesBase64", "signature",
            ),
        )
        if (envelope.string("mediaType") != ExperienceReleaseLimits.MEDIA_TYPE) fail("media type")
        if (envelope.string("encoding") != "base64") fail("encoding")

        val signature = envelope["signature"] as? JsonObject ?: fail("signature missing")
        requireExactKeys(signature, setOf("version", "algorithm", "keyId", "signatureBase64"))
        if (signature.long("version") != 1L) fail("signature version")
        if (signature.string("algorithm") != "ed25519") fail("signature algorithm")
        val keyId = signature.string("keyId")
            ?.takeIf { it.isNotEmpty() && it.length <= ExperienceReleaseLimits.KEY_ID_BYTES }
            ?: fail("keyId")

        val descriptorBase64 = envelope.string("descriptorBytesBase64") ?: fail("descriptor bytes")
        val descriptorBytes = canonicalBase64Decode(
            descriptorBase64,
            ExperienceReleaseLimits.DESCRIPTOR_BYTES,
        ) ?: fail("descriptor base64")

        val declaredSize = envelope.long("descriptorSizeBytes") ?: fail("descriptor size")
        if (declaredSize != descriptorBytes.size.toLong()) fail("descriptor size mismatch")
        val declaredSha = envelope.string("descriptorSha256") ?: fail("descriptor sha")
        if (sha256Hex(descriptorBytes) != declaredSha) fail("descriptor sha mismatch")

        val keyBytes = trustedKeys[keyId] ?: fail("unknown signing key: $keyId")
        if (keyBytes.size != 32) fail("invalid trust root")
        val signatureBytes = canonicalBase64Decode(
            signature.string("signatureBase64") ?: fail("signature bytes"),
            64,
        )?.takeIf { it.size == 64 } ?: fail("signature encoding")

        val signedBytes =
            ExperienceReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + descriptorBytes
        if (!Ed25519Verifier.verify(keyBytes, signedBytes, signatureBytes)) {
            fail("invalid signature")
        }

        // Authenticated: interpretation may begin.
        val descriptor = runCatching {
            Json.parseToJsonElement(descriptorBytes.decodeToString()).jsonObject
        }.getOrElse { fail("descriptor is not JSON") }
        val actualKeys = descriptor.keys.toSet()
        if (!actualKeys.containsAll(DESCRIPTOR_TOP_KEYS) ||
            !(actualKeys - DESCRIPTOR_TOP_KEYS - DESCRIPTOR_OPTIONAL_KEYS).isEmpty()
        ) {
            fail("unexpected descriptor keys")
        }
        if (descriptor.string("schemaVersion") != ExperienceReleaseLimits.SCHEMA_VERSION) {
            fail("schema version")
        }

        val identity = (descriptor["identity"] as? JsonObject)
            ?.let(ExperienceReleaseIdentity::fromJson)
            ?: fail("identity")
        if (identity != expectedIdentity) fail("identity mismatch")

        validateRequirements(
            descriptor["requirements"] as? JsonObject ?: fail("requirements"),
            supportedRuntime,
        )

        val promote = when (replayPolicy) {
            is ReplayPolicy.Active -> {
                if (replayPolicy.minimumPublishedAtSeq < 0 ||
                    identity.publishedAtSeq < replayPolicy.minimumPublishedAtSeq
                ) {
                    fail("replay rejected")
                }
                identity.publishedAtSeq
            }
            is ReplayPolicy.Pinned -> {
                if (identity.experienceVersionId != replayPolicy.experienceVersionId ||
                    identity.buildId != replayPolicy.buildId ||
                    declaredSha != replayPolicy.expectedDescriptorSha256
                ) {
                    fail("replay rejected")
                }
                null
            }
        }

        return AuthenticatedRelease(
            keyId = keyId,
            descriptorSha256 = declaredSha,
            identity = identity,
            descriptorBytes = descriptorBytes,
            descriptor = descriptor,
            publishedAtSeqToPromote = promote,
        )
    }

    private fun validateRequirements(requirements: JsonObject, supported: SupportedRuntime) {
        val minimumSdk = SemanticVersion.parse(requirements.string("minimumSdkVersion") ?: fail("minimumSdkVersion"))
            ?: fail("minimumSdkVersion")
        val currentSdk = SemanticVersion.parse(supported.currentSdkVersion) ?: fail("current sdk version")
        if (currentSdk < minimumSdk) fail("sdk below minimum")

        val runtimeRevision = requirements.string("runtimeRevision") ?: fail("runtimeRevision")
        if (runtimeRevision !in supported.supportedRuntimeRevisions) fail("runtime revision")

        val luau = requirements["luau"] as? JsonObject ?: fail("luau")
        val luauRevision = luau.string("revision") ?: fail("luau revision")
        val supportedBytecode = supported.supportedLuauRevisions[luauRevision]
            ?: fail("luau revision unsupported")
        val declaredBytecode = (luau["bytecodeVersions"] as? JsonArray ?: fail("bytecodeVersions"))
            .map { value ->
                (value as? JsonPrimitive)?.doubleOrNull
                    ?.takeIf { it == Math.floor(it) && it in 0.0..65_535.0 }
                    ?.toInt() ?: fail("bytecode version")
            }
        if (!supportedBytecode.containsAll(declaredBytecode)) fail("luau bytecode")

        val sceneFormat = requirements["sceneFormat"] as? JsonObject ?: fail("sceneFormat")
        val major = sceneFormat.long("major") ?: fail("scene major")
        val minor = sceneFormat.long("minor") ?: fail("scene minor")
        if (major != supported.sceneFormatMajor.toLong() ||
            minor > supported.sceneFormatMinor.toLong()
        ) {
            fail("scene format")
        }

        val timezone = requirements["timezoneData"] as? JsonObject ?: fail("timezoneData")
        if (timezone.string("format") != "iana-tzdb") fail("timezone format")
        if (timezone.string("revision") != supported.timezoneDataRevision) fail("timezone revision")
        if (timezone.string("sha256") != supported.timezoneDataSha256) fail("timezone sha")

        val required = requiredCapabilities(requirements)
        val unsupported = required.filterNot { it in supported.supportedCapabilities }
        if (unsupported.isNotEmpty()) fail("unsupported capabilities: ${unsupported.sorted()}")
    }

    private fun requiredCapabilities(requirements: JsonObject): List<String> {
        val value = requirements["requiredCapabilities"] ?: return emptyList()
        val values = (value as? JsonArray)
            ?.takeIf { it.size <= ExperienceReleaseLimits.REQUIRED_CAPABILITY_COUNT }
            ?: fail("requiredCapabilities")
        val capabilities = values.map { entry ->
            (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotEmpty() && it.toByteArray().size <= ExperienceReleaseLimits.KEY_ID_BYTES }
                ?: fail("requiredCapabilities entry")
        }
        // Strictly ascending: canonical, duplicate-free.
        capabilities.zipWithNext().forEach { (left, right) ->
            if (left >= right) fail("requiredCapabilities ordering")
        }
        return capabilities
    }

    /** Canonical base64: decode must re-encode to the identical text. */
    internal fun canonicalBase64Decode(text: String, maximumBytes: Int): ByteArray? {
        val decoded = runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (decoded.size > maximumBytes) return null
        val reEncoded = Base64.encodeToString(decoded, Base64.NO_WRAP)
        return decoded.takeIf { reEncoded == text }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun requireExactKeys(json: JsonObject, expected: Set<String>) {
        if (json.keys.toSet() != expected) fail("unexpected keys: ${json.keys - expected}")
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString }?.doubleOrNull
        ?.takeIf { it == Math.floor(it) }?.toLong()

    private fun fail(message: String): Nothing = throw ReleaseAuthenticationException(message)
}

/** RFC-ish semver with the iOS verifier's prerelease comparison rules. */
internal class SemanticVersion private constructor(
    private val major: Int,
    private val minor: Int,
    private val patch: Int,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        listOf(
            major.compareTo(other.major),
            minor.compareTo(other.minor),
            patch.compareTo(other.patch),
        ).firstOrNull { it != 0 }?.let { return it }
        if (prerelease.isEmpty() != other.prerelease.isEmpty()) {
            return if (prerelease.isEmpty()) 1 else -1
        }
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            if (left == right) return@forEach
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            return when {
                leftNumeric && rightNumeric -> {
                    val l = left.trimStart('0')
                    val r = right.trimStart('0')
                    if (l.length != r.length) l.length.compareTo(r.length) else l.compareTo(r)
                }
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun parse(value: String): SemanticVersion? {
            if (value.length > 64) return null
            val pieces = value.split("-", limit = 2)
            val core = pieces[0].split(".")
            if (core.size != 3) return null
            val numbers = core.map { part ->
                part.toIntOrNull()?.takeIf { it >= 0 && it.toString() == part } ?: return null
            }
            val prerelease = if (pieces.size == 2) pieces[1].split(".") else emptyList()
            if (!prerelease.all { part ->
                    part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '-' } &&
                        !(part.length > 1 && part[0] == '0' && part.all(Char::isDigit))
                }
            ) {
                return null
            }
            return SemanticVersion(numbers[0], numbers[1], numbers[2], prerelease)
        }
    }
}
