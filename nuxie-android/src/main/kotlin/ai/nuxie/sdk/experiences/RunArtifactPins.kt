package ai.nuxie.sdk.experiences

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Durable pins are immutable by run key. A caller publishes the pin before
 * admitting a run and removes it after queuing the report; process exit and
 * ordinary profile replacement must never release it. */
internal class RunArtifactPins(private val filesystemLock: CacheFilesystemLock) {
    fun retain(runKey: String, digests: Set<String>): CacheProtectionLease = filesystemLock.withLock {
        if (digests.any { !DIGEST.matches(it) }) throw IOException("Invalid run artifact digest")
        val ownerId = owner(runKey)
        val directory = filesystemLock.runProtectionDirectory
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create run artifact pin directory")
        val destination = File(directory, "$ownerId.json")
        if (destination.exists()) {
            if (read(destination) != digests) throw IOException("Run artifact pin cannot change its immutable closure")
        } else {
            val temporary = File.createTempFile("pin-", ".tmp", directory)
            try {
                val bytes = buildJsonObject {
                    put("schemaVersion", JsonPrimitive(1))
                    put("digests", buildJsonArray { digests.sorted().forEach { add(JsonPrimitive(it)) } })
                }.toString().encodeToByteArray()
                if (bytes.size > MAX_PIN_BYTES) throw IOException("Run artifact pin exceeds byte limit")
                FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
                if (!temporary.renameTo(destination)) throw IOException("Could not publish run artifact pin")
            } finally {
                temporary.delete()
            }
        }
        // Closing a process-local handle does not forget durable ownership.
        CacheProtectionLease(ownerId) {}
    }

    fun release(runKey: String) = filesystemLock.withLock {
        val file = File(filesystemLock.runProtectionDirectory, "${owner(runKey)}.json")
        if (file.exists() && !file.delete()) throw IOException("Could not release run artifact pin")
    }

    fun digests(excludingOwnerId: String? = null): Set<String> = filesystemLock.withLock {
        val directory = filesystemLock.runProtectionDirectory
        if (!directory.exists()) return@withLock emptySet()
        val files = directory.listFiles() ?: throw IOException("Could not read run artifact pins")
        files.filter { it.extension == "json" && it.nameWithoutExtension != excludingOwnerId }
            .flatMap { read(it) }.toSet()
    }

    private fun read(file: File): Set<String> = try {
        if (file.length() > MAX_PIN_BYTES) throw IOException("Run artifact pin exceeds byte limit")
        val value = Json.parseToJsonElement(file.readText()) as? JsonObject ?: throw IOException("Invalid run artifact pin")
        if (value.keys != setOf("schemaVersion", "digests") || value["schemaVersion"] != JsonPrimitive(1)) {
            throw IOException("Unsupported run artifact pin")
        }
        val digests = value["digests"] as? JsonArray ?: throw IOException("Invalid run artifact pin digests")
        digests.map { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString && DIGEST.matches(it.content) }?.content
                ?: throw IOException("Invalid retained artifact digest")
        }.toSet()
    } catch (error: IOException) {
        throw error
    } catch (error: Exception) {
        // Refuse pruning when a retained closure cannot be read, rather than
        // interpreting corrupt storage as permission to evict live artifacts.
        throw IOException("Could not read run artifact pin", error)
    }

    private fun owner(runKey: String): String {
        if (runKey.isBlank() || runKey.length > 4096) throw IOException("Invalid run artifact owner")
        return "run-" + MessageDigest.getInstance("SHA-256").digest(runKey.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val DIGEST = Regex("^[a-f0-9]{64}$")
        const val MAX_PIN_BYTES = 2 * 1024 * 1024
    }
}
