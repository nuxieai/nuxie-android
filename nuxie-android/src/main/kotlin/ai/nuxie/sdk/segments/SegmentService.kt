package ai.nuxie.sdk.segments

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import ai.nuxie.sdk.util.IsoDates

/**
 * Server-authoritative segment-membership mirror, ported from the iOS
 * `SegmentService` model: a present `segmentMemberships` snapshot is the
 * authoritative replacement; an absent field makes no claim; an explicitly
 * empty snapshot clears the mirror. Server `enteredAt` values are preserved.
 * The SDK does NOT evaluate segment IR (server-owned targeting).
 */
internal class SegmentService(context: Context) {
    data class Membership(val segmentId: String, val enteredAtMillis: Long?)

    private val lock = Any()
    private val baseDir = File((context.applicationContext ?: context).cacheDir, "nuxie/segments")
    private var membershipsByUser: MutableMap<String, Map<String, Membership>> = linkedMapOf()

    init {
        baseDir.mkdirs()
    }

    fun memberships(distinctId: String): Map<String, Membership> = synchronized(lock) {
        membershipsByUser[distinctId] ?: loadLocked(distinctId).also {
            membershipsByUser[distinctId] = it
        }
    }

    fun isMember(distinctId: String, segmentId: String): Boolean =
        memberships(distinctId).containsKey(segmentId)

    /**
     * Apply a profile's `segmentMemberships` field (wire shape:
     * `{evaluatedAt, memberships: [{segmentId, enteredAt}]}`). Null = the
     * profile made no claim (mirror untouched); non-null = authoritative
     * replacement, including the explicit-empty clear. Server `enteredAt`
     * values are preserved.
     */
    fun applySnapshot(distinctId: String, snapshot: JsonObject?) {
        if (snapshot == null) return
        synchronized(lock) {
            val next = linkedMapOf<String, Membership>()
            (snapshot["memberships"] as? kotlinx.serialization.json.JsonArray)?.forEach { entry ->
                val membership = entry as? JsonObject ?: return@forEach
                val segmentId = (membership["segmentId"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: return@forEach
                val enteredAt = (membership["enteredAt"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.let(IsoDates::parseMillis)
                next[segmentId] = Membership(segmentId, enteredAt)
            }
            membershipsByUser[distinctId] = next
            persistLocked(distinctId, next)
        }
    }

    fun clearSegments(distinctId: String) {
        synchronized(lock) {
            membershipsByUser.remove(distinctId)
            fileFor(distinctId).delete()
        }
    }

    private fun loadLocked(distinctId: String): Map<String, Membership> {
        val file = fileFor(distinctId)
        if (!file.exists()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (segmentId, value) ->
                val millis = (value as? JsonPrimitive)?.content?.toLongOrNull()
                Membership(
                    segmentId = segmentId,
                    enteredAtMillis = millis?.takeIf { it >= 0 },
                )
            }
        }.getOrElse {
            Log.w(LOG_TAG, "Failed to load segment mirror; clearing.", it)
            file.delete()
            emptyMap()
        }
    }

    private fun persistLocked(distinctId: String, memberships: Map<String, Membership>) {
        runCatching {
            val json = buildJsonObject {
                memberships.forEach { (segmentId, membership) ->
                    put(segmentId, JsonPrimitive(membership.enteredAtMillis ?: -1L))
                }
            }
            fileFor(distinctId).writeText(json.toString())
        }.onFailure { Log.w(LOG_TAG, "Failed to persist segment mirror", it) }
    }

    private fun fileFor(distinctId: String): File =
        File(baseDir, sanitizeFileName(distinctId) + ".json")

    private fun sanitizeFileName(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")

    private companion object {
        const val LOG_TAG = "Nuxie"
    }
}
