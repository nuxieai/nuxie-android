package ai.nuxie.sdk.profile

import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Durable first-use binding from one configured credential to app authority. */
internal class ProfileAuthorityBindingStore(
    context: Context,
    storageScope: ProfileStorageScope,
) {
    private val directory = File(
        File((context.applicationContext ?: context).filesDir, "nuxie"),
        "profile-authorities-v1",
    )
    private val file = File(directory, storageScope.authorityBindingFilename)

    @Throws(IOException::class)
    fun authority(): ProfileDeliveryAuthority? = synchronized(processLock) {
        read()
    }

    @Throws(IOException::class)
    fun bind(authority: ProfileDeliveryAuthority): Boolean = synchronized(processLock) {
        if (!authority.isValid) return@synchronized false
        val existing = read()
        if (existing != null) return@synchronized existing == authority
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create profile authority directory")
        }
        val bytes = buildJsonObject {
            put("appId", JsonPrimitive(authority.appId))
            put("environment", JsonPrimitive(authority.environment))
        }.toString().encodeToByteArray()
        if (bytes.size > MAX_BYTES) throw IOException("Profile authority exceeds byte limit")

        // A separate lock file coordinates other app processes. The binding
        // itself appears only after a complete, synced temporary file is
        // atomically renamed, so a crash cannot publish empty authority.
        RandomAccessFile(File(directory, "${file.name}.lock"), "rw").use { lockFile ->
            val fileLock = lockFile.channel.lock()
            try {
                val winner = read()
                if (winner != null) return@synchronized winner == authority

                val temporary = File.createTempFile(".${file.name}.", ".tmp", directory)
                try {
                    FileOutputStream(temporary).use { stream ->
                        stream.write(bytes)
                        stream.fd.sync()
                    }
                    if (!temporary.renameTo(file)) {
                        throw IOException("Could not persist profile authority")
                    }
                    // Some Android filesystems (and Robolectric) reject
                    // directory fsync. The synced file plus atomic rename still
                    // guarantees readers see either no binding or all bytes.
                    runCatching { syncDirectory() }
                } finally {
                    temporary.delete()
                }
            } finally {
                fileLock.release()
            }
        }
        read() == authority
    }

    private fun syncDirectory() {
        val descriptor = Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun read(): ProfileDeliveryAuthority? {
        if (!file.exists()) return null
        if (!file.isFile || file.length() > MAX_BYTES) {
            throw IOException("Invalid profile authority binding")
        }
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            if (root.keys != setOf("appId", "environment")) {
                throw IOException("Invalid profile authority binding")
            }
            val authority = ProfileDeliveryAuthority(
                appId = root.requiredString("appId"),
                environment = root.requiredString("environment"),
            )
            if (!authority.isValid) throw IOException("Invalid profile authority binding")
            authority
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Invalid profile authority binding", error)
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        (getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IOException("Invalid profile authority binding")

    private companion object {
        val processLock = Any()
        const val MAX_BYTES = 1_024
    }
}
