package ai.nuxie.sdk.identity

import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject

/**
 * Thread-safe, synchronous identity store persisted as JSON under
 * `files/nuxie/identity.json`, ported from the iOS `IdentityService`:
 * an in-memory snapshot guarded by a lock, per-user property maps, and
 * anonymous -> identified property migration on the first identify.
 */
internal class IdentityService(
    context: Context,
) : IdentityProvider {
    private val lock = Any()
    private val file: File

    // In-memory snapshot (guarded by lock)
    private var identifiedId: String? = null
    private var anonymousIdValue: String? = null
    private var userPropertiesById: MutableMap<String, JsonObject> = linkedMapOf()

    init {
        val baseDir = File((context.applicationContext ?: context).filesDir, "nuxie")
        baseDir.mkdirs()
        file = File(baseDir, "identity.json")
        synchronized(lock) {
            loadLocked()
            if (anonymousIdValue == null) {
                anonymousIdValue = TimeBasedEpochGenerator.shared.next()
                persistLocked()
            }
        }
    }

    override fun distinctId(): String = synchronized(lock) { effectiveIdLocked() }

    override fun anonymousId(): String = synchronized(lock) { anonymousIdLocked() }

    override fun rawDistinctId(): String? = synchronized(lock) { identifiedId }

    override val isIdentified: Boolean
        get() = synchronized(lock) { identifiedId != null }

    /** Identify. Migrates anonymous properties on the anon -> identified edge. */
    fun setDistinctId(distinctId: String) {
        synchronized(lock) {
            val oldEffectiveId = effectiveIdLocked()
            val wasIdentified = identifiedId != null
            identifiedId = distinctId

            if (!wasIdentified && oldEffectiveId != distinctId) {
                val oldProperties = userPropertiesById[oldEffectiveId] ?: JsonObject(emptyMap())
                val existingNew = userPropertiesById[distinctId] ?: JsonObject(emptyMap())
                // Explicit properties already on the new id win.
                val merged = buildJsonObject {
                    oldProperties.forEach { (key, value) -> put(key, value) }
                    existingNew.forEach { (key, value) -> put(key, value) }
                }
                userPropertiesById[distinctId] = merged
                userPropertiesById.remove(oldEffectiveId)
            }
            persistLocked()
        }
    }

    /** Reset to anonymous. Clears the previous identity's property bag. */
    fun reset(keepAnonymousId: Boolean) {
        synchronized(lock) {
            val previousEffectiveId = effectiveIdLocked()
            userPropertiesById.remove(previousEffectiveId)
            identifiedId = null
            if (!keepAnonymousId) anonymousIdValue = null
            if (anonymousIdValue == null) {
                anonymousIdValue = TimeBasedEpochGenerator.shared.next()
            }
            persistLocked()
        }
    }

    fun setUserProperties(properties: Map<String, Any?>) {
        if (properties.isEmpty()) return
        synchronized(lock) {
            val key = effectiveIdLocked()
            val current = userPropertiesById[key] ?: JsonObject(emptyMap())
            val incoming = JsonValueConverter.fromMap(properties)
            userPropertiesById[key] = buildJsonObject {
                current.forEach { (k, v) -> put(k, v) }
                incoming.forEach { (k, v) -> put(k, v) }
            }
            persistLocked()
        }
    }

    fun setOnceUserProperties(properties: Map<String, Any?>) {
        if (properties.isEmpty()) return
        synchronized(lock) {
            val key = effectiveIdLocked()
            val current = userPropertiesById[key] ?: JsonObject(emptyMap())
            val incoming = JsonValueConverter.fromMap(properties)
            var added = false
            val merged = buildJsonObject {
                current.forEach { (k, v) -> put(k, v) }
                incoming.forEach { (k, v) ->
                    if (!current.containsKey(k)) {
                        put(k, v)
                        added = true
                    }
                }
            }
            if (added) {
                userPropertiesById[key] = merged
                persistLocked()
            }
        }
    }

    /** User property lookup for future IR evaluation. */
    fun userProperty(key: String): Any? = synchronized(lock) {
        val properties = userPropertiesById[effectiveIdLocked()] ?: return null
        (properties[key] as? JsonPrimitive)?.let { primitive ->
            if (primitive.isString) return primitive.content
            primitive.booleanOrNull?.let { return it }
            primitive.longOrNull?.let { return it }
            primitive.doubleOrNull?.let { return it }
        }
        properties[key]
    }

    // MARK: locked helpers

    private fun effectiveIdLocked(): String = identifiedId ?: anonymousIdLocked()

    private fun anonymousIdLocked(): String =
        anonymousIdValue ?: TimeBasedEpochGenerator.shared.next().also {
            anonymousIdValue = it
            persistLocked()
        }

    private fun loadLocked() {
        if (!file.exists()) return
        runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            identifiedId = (root["distinctId"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            anonymousIdValue = (root["anonymousId"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            val propertiesById = root["userPropertiesById"] as? JsonObject
            userPropertiesById = linkedMapOf()
            propertiesById?.forEach { (userId, value) ->
                (value as? JsonObject)?.let { userPropertiesById[userId] = it }
            }
        }.onFailure {
            Log.w(LOG_TAG, "Failed to load identity; resetting file.", it)
            file.delete()
        }
    }

    private fun persistLocked() {
        runCatching {
            val root = buildJsonObject {
                identifiedId?.let { put("distinctId", JsonPrimitive(it)) }
                anonymousIdValue?.let { put("anonymousId", JsonPrimitive(it)) }
                put(
                    "userPropertiesById",
                    JsonObject(userPropertiesById.mapValues { (_, v) -> v as JsonElement }),
                )
            }
            val temporary = File(file.parentFile, "identity.json.tmp")
            temporary.writeText(root.toString())
            if (!temporary.renameTo(file)) {
                file.writeText(root.toString())
                temporary.delete()
            }
        }.onFailure { Log.w(LOG_TAG, "Failed to persist identity", it) }
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
    }
}
