package ai.nuxie.sdk.experiences

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ProcessIdentity(val processId: Int, val startTimeTicks: Long)

internal class CacheProtectionLease(
    internal val ownerId: String,
    private val closeAction: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }
}

/**
 * Stable cooperating cross-process lock for one cache root. The lock inode is
 * outside the cache, so pruning or replacing cache entries cannot bypass it.
 * The in-process lock is reentrant and also prevents Java overlapping-lock
 * failures between cache instances.
 *
 * JVM tests prove local acquisition, reentrancy, and stale-owner reclamation.
 * Actual sibling-process mutual exclusion remains unproven without an Android
 * instrumentation test that launches a second app process.
 */
internal class CacheFilesystemLock(cacheRoot: File) {
    private val canonicalRoot = runCatching { cacheRoot.canonicalFile }.getOrElse {
        cacheRoot.absoluteFile
    }
    private val namespace = File(
        canonicalRoot.parentFile,
        ".nuxie-cache-locks/${sha256(canonicalRoot.path)}",
    )
    private val lockFile = File(namespace, "root.lock")

    internal val protectionDirectory = File(namespace, "protections")
    internal val runProtectionDirectory = File(namespace, "run-protections")

    fun <T> withLock(block: () -> T): T = withFileLock(lockFile, block)

    fun <T> withTargetLock(target: String, block: () -> T): T =
        withFileLock(File(namespace, "targets/${sha256(target)}.lock"), block)

    private fun <T> withFileLock(fileToLock: File, block: () -> T): T {
        val reference = retainScope(fileToLock)
        val scope = reference.scope
        scope.local.lock()
        val outermost = scope.local.holdCount == 1
        var file: RandomAccessFile? = null
        var fileLock: java.nio.channels.FileLock? = null
        try {
            if (outermost) {
                fileToLock.parentFile?.mkdirs()
                file = RandomAccessFile(fileToLock, "rw")
                fileLock = file.channel.lock()
            }
            return block()
        } finally {
            try {
                if (outermost) {
                    fileLock?.release()
                    file?.close()
                }
            } finally {
                scope.local.unlock()
                releaseScope(reference)
            }
        }
    }

    private companion object {
        private val scopesLock = Any()
        private val scopes = mutableMapOf<String, LockScope>()

        /**
         * Retention, reference changes, and removal all hold [scopesLock]. A
         * caller retains its scope before waiting on the local lock, so zero
         * references proves no waiter can later enter an otherwise removed scope.
         */
        fun retainScope(lockFile: File): ScopeReference {
            val path = runCatching { lockFile.canonicalPath }.getOrElse { lockFile.absolutePath }
            return synchronized(scopesLock) {
                val scope = scopes.getOrPut(path, ::LockScope)
                scope.references += 1
                ScopeReference(path, scope)
            }
        }

        fun releaseScope(reference: ScopeReference) {
            synchronized(scopesLock) {
                reference.scope.references -= 1
                if (reference.scope.references == 0 && scopes[reference.path] === reference.scope) {
                    scopes.remove(reference.path)
                }
            }
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }

    private class LockScope(
        val local: ReentrantLock = ReentrantLock(),
        var references: Int = 0,
    )

    private data class ScopeReference(val path: String, val scope: LockScope)
}

/** Cross-process lease markers with PID-reuse-safe stale-owner reclamation. */
internal class CacheProtectionRegistry(
    private val filesystemLock: CacheFilesystemLock,
    private val currentProcessId: Int = currentProcessIdentity().processId,
    private val processIdentity: (Int) -> ProcessIdentity? = ::platformProcessIdentity,
    private val processExists: (Int) -> Boolean = { pid -> File("/proc/$pid").isDirectory },
) {
    private val runPins = RunArtifactPins(filesystemLock)

    /** Unlike a presentation lease, a parked run's pin survives process death.
     * Release it only after its durable completion event/checklist commit. */
    fun retainRun(runKey: String, digests: Set<String>): CacheProtectionLease = runPins.retain(runKey, digests)

    fun releaseRun(runKey: String) = runPins.release(runKey)

    fun retainedRunDigests(runKey: String): Set<String>? = runPins.retainedDigests(runKey)

    fun register(digests: Set<String>): CacheProtectionLease {
        val ownerId = UUID.randomUUID().toString().lowercase()
        val marker = filesystemLock.withLock {
            val identity = processIdentity(currentProcessId)
                ?: throw IOException("Could not resolve cache protection process identity")
            filesystemLock.protectionDirectory.mkdirs()
            val destination = File(
                filesystemLock.protectionDirectory,
                "$ownerId.json",
            )
            val temporary = File.createTempFile("protection-", ".tmp", filesystemLock.protectionDirectory)
            try {
                temporary.writeText(
                    buildJsonObject {
                        put("processId", JsonPrimitive(identity.processId))
                        put("processStartTimeTicks", JsonPrimitive(identity.startTimeTicks))
                        put("ownerId", JsonPrimitive(ownerId))
                        put("digests", buildJsonArray {
                            digests.sorted().forEach { add(JsonPrimitive(it)) }
                        })
                    }.toString(),
                )
                if (!temporary.renameTo(destination)) {
                    throw IOException("Could not publish cache protection marker")
                }
            } finally {
                temporary.delete()
            }
            destination
        }
        return CacheProtectionLease(ownerId) {
            filesystemLock.withLock { marker.delete() }
        }
    }

    fun protectedDigests(excludingOwnerId: String? = null): Set<String> = filesystemLock.withLock {
        val directory = filesystemLock.protectionDirectory
        val protected = runPins.digests(excludingOwnerId).toMutableSet()
        if (!directory.isDirectory) return@withLock protected
        directory.listFiles { file -> file.extension == "json" }
            ?.forEach { markerFile ->
                val marker = readMarker(markerFile)
                if (marker == null || !isOwned(marker)) {
                    markerFile.delete()
                } else if (marker.ownerId != excludingOwnerId) {
                    protected += marker.digests
                }
            }
        protected
    }

    private fun readMarker(file: File): Marker? = runCatching {
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        Marker(
            processId = json.getValue("processId").jsonPrimitive.content.toInt(),
            processStartTimeTicks = json.getValue("processStartTimeTicks").jsonPrimitive.content.toLong(),
            ownerId = json["ownerId"]?.jsonPrimitive?.content ?: file.nameWithoutExtension,
            digests = json.getValue("digests").jsonArray.mapTo(mutableSetOf()) {
                it.jsonPrimitive.content
            },
        )
    }.getOrNull()

    private fun isOwned(marker: Marker): Boolean {
        if (marker.processId <= 0) return false
        val actual = processIdentity(marker.processId)
        if (actual != null) return actual.startTimeTicks == marker.processStartTimeTicks
        // If proc metadata is denied for a live sibling, preserve its lease.
        return processExists(marker.processId)
    }

    private data class Marker(
        val processId: Int,
        val processStartTimeTicks: Long,
        val ownerId: String,
        val digests: Set<String>,
    )
}

private fun linuxProcessIdentity(processId: Int): ProcessIdentity? = runCatching {
    val stat = File("/proc/$processId/stat").readText()
    val closingName = stat.lastIndexOf(')')
    if (closingName < 0) return@runCatching null
    val fieldsFromState = stat.substring(closingName + 1).trim().split(Regex("\\s+"))
    val startTimeTicks = fieldsFromState.getOrNull(19)?.toLongOrNull()
        ?: return@runCatching null
    ProcessIdentity(processId, startTimeTicks)
}.getOrNull()

private fun platformProcessIdentity(processId: Int): ProcessIdentity? =
    linuxProcessIdentity(processId) ?: currentProcessIdentity().takeIf { it.processId == processId }

private fun currentProcessIdentity(): ProcessIdentity = currentIdentity

private val currentIdentity: ProcessIdentity by lazy {
    val linuxPid = runCatching {
        File("/proc/self/stat").readText().substringBefore(' ').toInt()
    }.getOrNull()
    linuxPid?.let(::linuxProcessIdentity) ?: run {
        // Robolectric runs on a host without /proc. Reflection keeps these
        // host-only management classes out of Android's linked surface.
        val runtimeBean = runCatching {
            val management = Class.forName("java.lang.management.ManagementFactory")
            management.getMethod("getRuntimeMXBean").invoke(null)
        }.getOrNull()
        val runtimeName = runCatching {
            runtimeBean?.javaClass?.getMethod("getName")?.invoke(runtimeBean) as? String
        }.getOrNull()
        val startMillis = runCatching {
            runtimeBean?.javaClass?.getMethod("getStartTime")?.invoke(runtimeBean) as? Long
        }.getOrNull() ?: 0L
        val pid = runtimeName?.substringBefore('@')?.toIntOrNull()
            ?: android.os.Process.myPid().coerceAtLeast(1)
        ProcessIdentity(pid, startMillis)
    }
}
