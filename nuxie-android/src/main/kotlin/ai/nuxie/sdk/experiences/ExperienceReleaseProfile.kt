package ai.nuxie.sdk.experiences

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `releases` field of a profile response — the sole experience-delivery
 * authority (iOS `ExperienceReleaseProfile`): a signed delivery origin plus
 * active and pinned entries whose descriptor envelopes ride INLINE, so
 * admission needs no additional network round trip.
 */
internal class ExperienceReleaseProfile(
    val renderBaseUrl: String,
    val assetBaseUrl: String,
    val active: List<Entry>,
    val pinned: List<Entry>,
) {
    class Entry(
        val locator: ExperienceReleaseIdentity,
        val descriptorSha256: String,
        val envelopeBytes: ByteArray,
    )

    companion object {
        private const val MAX_ENTRIES = 256
        private const val MAX_ENVELOPE_AGGREGATE = 16 * 1024 * 1024

        fun fromProfileBody(body: JsonObject): ExperienceReleaseProfile? {
            val releases = body["releases"] as? JsonObject ?: return null
            val delivery = releases["delivery"] as? JsonObject ?: return null
            val renderBaseUrl = delivery.string("renderBaseUrl") ?: return null
            val assetBaseUrl = delivery.string("assetBaseUrl") ?: return null

            var aggregate = 0L
            fun entries(key: String): List<Entry>? {
                val array = releases[key] as? JsonArray ?: return emptyList()
                if (array.size > MAX_ENTRIES) return null
                return array.map { element ->
                    val entry = element as? JsonObject ?: return null
                    val locator = (entry["locator"] as? JsonObject)
                        ?.let(ExperienceReleaseIdentity::fromJson) ?: return null
                    val sha = entry.string("descriptorSha256") ?: return null
                    val envelopeBase64 = entry.string("envelopeBytesBase64") ?: return null
                    val envelope = runCatching {
                        Base64.decode(envelopeBase64, Base64.NO_WRAP)
                    }.getOrNull() ?: return null
                    aggregate += envelope.size
                    if (aggregate > MAX_ENVELOPE_AGGREGATE) return null
                    Entry(locator, sha, envelope)
                }
            }

            val active = entries("active") ?: return null
            val pinned = entries("pinned") ?: return null
            return ExperienceReleaseProfile(renderBaseUrl, assetBaseUrl, active, pinned)
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
