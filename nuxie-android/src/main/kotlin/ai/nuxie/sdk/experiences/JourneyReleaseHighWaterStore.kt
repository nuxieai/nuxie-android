package ai.nuxie.sdk.experiences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Publication identity per admission stream. Customer stores persist across SDK instances. */
internal class JourneyReleaseHighWaterStore private constructor(private val preferences: SharedPreferences?) {
    constructor(context: Context) : this((context.applicationContext ?: context)
        .getSharedPreferences("nuxie_journey_release_high_water", Context.MODE_PRIVATE))

    private val ephemeralRecords = mutableMapOf<String, Record>()

    private data class Record(val sequence: Long, val identity: JourneyReleaseIdentity?)

    fun floor(streamKey: String): Long = synchronized(processLock) {
        read(streamKey)?.sequence ?: 0L
    }

    /** Validate the complete batch before atomically publishing any replay authority. */
    fun admitBatch(candidates: Map<String, JourneyReleaseIdentity>) = synchronized(processLock) {
        val promotions = candidates.filter { (key, candidate) ->
            val current = read(key)
            if (candidate.streamKey != key || candidate.publishedAtSeq < 0 ||
                (current != null && candidate.publishedAtSeq <= current.sequence && candidate != current.identity)
            ) {
                throw JourneyReleaseAuthenticationException("replay rejected")
            }
            current == null || candidate.publishedAtSeq > current.sequence
        }
        if (promotions.isEmpty()) return@synchronized
        if (preferences == null) {
            promotions.forEach { (key, identity) ->
                ephemeralRecords[key] = Record(identity.publishedAtSeq, identity)
            }
            return@synchronized
        }
        val editor = preferences.edit()
        for ((key, identity) in promotions) {
            editor.putString(key, buildJsonObject {
                put("appId", identity.appId)
                put("environment", identity.environment)
                put("experienceId", identity.experienceId)
                put("experienceVersionId", identity.experienceVersionId)
                put("buildId", identity.buildId)
                put("versionNumber", identity.versionNumber)
                put("publishedAt", identity.publishedAt)
                put("publishedAtSeq", identity.publishedAtSeq)
            }.toString())
        }
        check(editor.commit()) { "Could not persist release replay authority" }
    }

    private fun read(key: String): Record? {
        if (preferences == null) return ephemeralRecords[key]
        val value = preferences.all[key] ?: return null
        // Sequence-only records cannot prove equal-sequence identity. Preserve
        // their floor and migrate only when a newer authenticated publication arrives.
        if (value is Long && value >= 0) return Record(value, null)
        val identity = (value as? String)?.let { text ->
            runCatching { JourneyReleaseIdentity.fromJson(Json.parseToJsonElement(text).jsonObject) }.getOrNull()
        }
        if (identity == null || identity.streamKey != key || identity.publishedAtSeq < 0) {
            throw JourneyReleaseAuthenticationException("corrupt release replay authority")
        }
        return Record(identity.publishedAtSeq, identity)
    }

    companion object {
        /** A preview can admit older releases without reading or advancing customer authority. */
        fun ephemeral(): JourneyReleaseHighWaterStore = JourneyReleaseHighWaterStore(null)

        private val processLock = Any()
    }
}
