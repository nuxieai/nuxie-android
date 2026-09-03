package ai.nuxie.sdk.experiences

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal object JourneyReleaseLimits {
    const val MEDIA_TYPE = "application/vnd.nuxie.journey+json"
    const val SCHEMA_VERSION = "nuxie.journey-release.v1"
    const val SIGNATURE_DOMAIN = "nuxie.journey-release.v1\u0000"
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

internal data class JourneyReleaseIdentity(
    val appId: String,
    val environment: String,
    val experienceId: String,
    val experienceVersionId: String,
    val buildId: String,
    val versionNumber: Long,
    val publishedAt: String,
    val publishedAtSeq: Long,
) {
    val streamKey: String get() = "$appId|$environment|$experienceId"

    companion object {
        private val wireKeys = setOf(
            "appId",
            "environment",
            "experienceId",
            "experienceVersionId",
            "buildId",
            "versionNumber",
            "publishedAt",
            "publishedAtSeq",
        )

        fun fromJson(
            json: JsonObject,
            additionalKeys: Set<String> = emptySet(),
        ): JourneyReleaseIdentity? {
            if (json.keys != wireKeys + additionalKeys) return null
            fun string(key: String): String? =
                (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String): Long? = (json[key] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.doubleOrNull
                ?.takeIf { it.isFinite() && it == kotlin.math.floor(it) }
                ?.toLong()
            return JourneyReleaseIdentity(
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

internal data class JourneyReleaseSupportedRuntime(
    val currentSdkVersion: String,
    val supportedRuntimeRevisions: Set<String>,
    val supportedLuauRevisions: Map<String, Set<Int>>,
    val sceneFormatMajor: Int,
    val sceneFormatMinor: Int,
    val timezoneDataRevision: String,
    val timezoneDataSha256: String,
    val supportedCapabilities: Set<String>,
)

internal sealed interface JourneyReleaseReplayPolicy {
    data class Active(val minimumPublishedAtSeq: Long) : JourneyReleaseReplayPolicy

    data class Pinned(
        val experienceVersionId: String,
        val buildId: String,
        val expectedDescriptorSha256: String,
    ) : JourneyReleaseReplayPolicy
}

internal class JourneyReleaseAuthenticationException(message: String) : Exception(message)

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
