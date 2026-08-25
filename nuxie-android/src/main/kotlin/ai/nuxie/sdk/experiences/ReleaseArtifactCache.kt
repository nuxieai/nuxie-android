package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.experiences.ReleaseArtifactAcquisitionException.Reason
import ai.nuxie.sdk.network.HttpTransport
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URL
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A retryable artifact acquisition failure tied to one authenticated key. */
internal class ReleaseArtifactAcquisitionException(
    val artifactKey: String,
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
    val httpStatusCode: Int? = null,
) : ReleaseArtifactCache.AcquisitionException(message, cause) {
    enum class Reason {
        INVALID_DESCRIPTOR,
        INVALID_URL,
        HTTP_STATUS,
        REDIRECT_ESCAPED_ORIGIN,
        CONTENT_TYPE_MISMATCH,
        SIZE_MISMATCH,
        SIZE_OVERRUN,
        DIGEST_MISMATCH,
        TRANSPORT,
        CACHE_IO,
    }
}

/** Content-addressed storage for authenticated Experience release artifacts. */
internal class ReleaseArtifactCache(
    context: Context,
    private val transport: HttpTransport,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    cacheDirectory: File? = null,
) {
    private val baseDir = cacheDirectory
        ?: File((context.applicationContext ?: context).cacheDir, "nuxie/release_objects")
    private val cacheScope = cacheScope(baseDir)

    init {
        baseDir.mkdirs()
        synchronized(cacheScope.lock) {
            sweepTemporaryFilesLocked()
        }
    }

    open class AcquisitionException(
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    /** Protects a complete release set from pruning until the returned lease is closed. */
    fun protect(digests: Collection<String>): Closeable {
        val uniqueDigests = digests.toSet()
        synchronized(cacheScope.lock) {
            uniqueDigests.forEach { digest ->
                cacheScope.protectedDigestCounts[digest] =
                    (cacheScope.protectedDigestCounts[digest] ?: 0) + 1
            }
        }
        val closed = AtomicBoolean(false)
        return Closeable {
            if (!closed.compareAndSet(false, true)) return@Closeable
            synchronized(cacheScope.lock) {
                uniqueDigests.forEach { digest ->
                    val remaining = (cacheScope.protectedDigestCounts[digest] ?: 0) - 1
                    if (remaining <= 0) cacheScope.protectedDigestCounts.remove(digest)
                    else cacheScope.protectedDigestCounts[digest] = remaining
                }
            }
        }
    }

    fun cachedFile(sha256: String): File? {
        val file = fileFor(sha256)
        return file.takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * Returns a verified cache entry or downloads, verifies, and atomically
     * publishes one. An in-process per-cache-directory digest lock avoids
     * platform-specific filesystem locking for this app-local cache; a
     * same-directory rename ensures readers can never observe partial bytes.
     */
    fun acquire(
        key: String,
        sha256: String,
        expectedSizeBytes: Long,
        maxBytes: Long,
        signedBaseUrl: String,
        expectedContentType: String? = null,
    ): File {
        val baseUrl = validatedBaseUrl(key, signedBaseUrl)
        val sourceUrl = composeUrl(key, baseUrl)
        if (!isDigest(sha256)) fail(key, Reason.INVALID_URL, "invalid artifact digest")
        if (expectedSizeBytes < 0 || maxBytes < 0 || expectedSizeBytes > maxBytes) {
            fail(key, Reason.SIZE_OVERRUN, "artifact exceeds size limit")
        }

        val digestLock = synchronized(cacheScope.lock) {
            cacheScope.digestLocks.getOrPut(sha256, ::DigestLock).also {
                it.references += 1
            }
        }
        try {
            synchronized(digestLock.monitor) {
                verifiedCachedFile(key, sha256, expectedSizeBytes)?.let { return it }
                return downloadAndPublish(
                    key = key,
                    sha256 = sha256,
                    expectedSizeBytes = expectedSizeBytes,
                    maximumBytes = minOf(expectedSizeBytes, maxBytes),
                    expectedContentType = expectedContentType,
                    baseUrl = baseUrl,
                    initialUrl = sourceUrl,
                )
            }
        } finally {
            synchronized(cacheScope.lock) {
                digestLock.references -= 1
                if (!fileFor(sha256).isFile) {
                    dropDigestLockIfUnreferencedLocked(sha256)
                }
            }
        }
    }

    /** Preflights a descriptor key without performing I/O. */
    fun validateLocation(key: String, signedBaseUrl: String) {
        composeUrl(key, validatedBaseUrl(key, signedBaseUrl))
    }

    fun validateDeliveryOrigin(key: String, signedBaseUrl: String) {
        validatedBaseUrl(key, signedBaseUrl)
    }

    internal fun digestLockCount(): Int =
        synchronized(cacheScope.lock) { cacheScope.digestLocks.size }

    internal fun protectionCount(sha256: String): Int =
        synchronized(cacheScope.lock) { cacheScope.protectedDigestCounts[sha256] ?: 0 }

    private fun downloadAndPublish(
        key: String,
        sha256: String,
        expectedSizeBytes: Long,
        maximumBytes: Long,
        expectedContentType: String?,
        baseUrl: URL,
        initialUrl: URL,
    ): File {
        var requestUrl = initialUrl
        var redirectCount = 0
        while (true) {
            val response = try {
                transport.open(
                    HttpTransport.Request(
                        url = requestUrl,
                        headers = emptyMap(),
                        body = ByteArray(0),
                        method = "GET",
                        followRedirects = false,
                    ),
                )
            } catch (error: IOException) {
                fail(key, Reason.TRANSPORT, "artifact fetch failed", error)
            }
            response.use {
                if (!sameOrigin(response.finalUrl, baseUrl)) {
                    fail(key, Reason.REDIRECT_ESCAPED_ORIGIN, "artifact redirect escaped origin")
                }
                if (response.statusCode in 300..399) {
                    val location = response.header("location")
                        ?: fail(key, Reason.HTTP_STATUS, "artifact fetch failed: ${response.statusCode}")
                    val redirected = runCatching { URL(requestUrl, location) }.getOrElse {
                        fail(key, Reason.REDIRECT_ESCAPED_ORIGIN, "invalid artifact redirect", it)
                    }
                    if (!sameOrigin(redirected, baseUrl)) {
                        fail(key, Reason.REDIRECT_ESCAPED_ORIGIN, "artifact redirect escaped origin")
                    }
                    redirectCount += 1
                    if (redirectCount > MAX_REDIRECTS) {
                        fail(key, Reason.HTTP_STATUS, "too many artifact redirects")
                    }
                    requestUrl = redirected
                    return@use
                }
                if (response.statusCode !in 200..299) {
                    fail(
                        key,
                        Reason.HTTP_STATUS,
                        "artifact fetch failed: ${response.statusCode}",
                        httpStatusCode = response.statusCode,
                    )
                }
                validateContentType(key, expectedContentType, response.header("content-type"))
                response.declaredContentLength?.let { declared ->
                    if (declared > maximumBytes) {
                        fail(key, Reason.SIZE_OVERRUN, "artifact stream exceeds size limit")
                    }
                }
                return publishStream(
                    key,
                    sha256,
                    expectedSizeBytes,
                    maximumBytes,
                    response.body,
                )
            }
        }
    }

    private fun publishStream(
        key: String,
        sha256: String,
        expectedSizeBytes: Long,
        maximumBytes: Long,
        input: InputStream,
    ): File {
        baseDir.mkdirs()
        val temporary = synchronized(cacheScope.lock) {
            try {
                File.createTempFile("$sha256-", TEMPORARY_SUFFIX, baseDir).also {
                    cacheScope.liveTemporaryOwnership[it.absolutePath] = ownTemporary(it)
                }
            } catch (error: IOException) {
                fail(key, Reason.CACHE_IO, "could not create artifact temporary file", error)
            }
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            try {
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val remaining = maximumBytes - received
                        val requestBytes = if (remaining >= buffer.size) {
                            buffer.size
                        } else {
                            (remaining + 1).coerceAtLeast(1).toInt()
                        }
                        val read = input.read(buffer, 0, requestBytes)
                        if (read < 0) break
                        received += read
                        if (received > maximumBytes) {
                            fail(key, Reason.SIZE_OVERRUN, "artifact stream exceeds size limit")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            } catch (error: ReleaseArtifactAcquisitionException) {
                throw error
            } catch (error: IOException) {
                fail(key, Reason.TRANSPORT, "artifact stream failed", error)
            }
            if (received != expectedSizeBytes) {
                fail(
                    key,
                    Reason.SIZE_MISMATCH,
                    "artifact size mismatch: expected $expectedSizeBytes, received $received",
                )
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha256 != sha256) {
                fail(
                    key,
                    Reason.DIGEST_MISMATCH,
                    "artifact digest mismatch: expected $sha256, received $actualSha256",
                )
            }

            val destination = fileFor(sha256)
            if (!temporary.renameTo(destination)) {
                verifiedCachedFile(key, sha256, expectedSizeBytes)?.let { return it }
                fail(key, Reason.CACHE_IO, "could not publish verified artifact")
            }
            synchronized(cacheScope.lock) { pruneLocked() }
            return destination
        } finally {
            synchronized(cacheScope.lock) {
                cacheScope.liveTemporaryOwnership.remove(temporary.absolutePath)?.close()
                if (temporary.exists() && !temporary.delete()) {
                    Log.w(LOG_TAG, "Failed to remove artifact temporary file ${temporary.name}")
                }
            }
        }
    }

    private fun verifiedCachedFile(
        key: String,
        sha256: String,
        expectedSizeBytes: Long,
    ): File? {
        val file = fileFor(sha256)
        if (!file.isFile) return null
        val matches = file.length() == expectedSizeBytes && runCatching {
            file.inputStream().buffered().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) } == sha256
            }
        }.getOrDefault(false)
        if (!matches) {
            if (!file.delete()) fail(key, Reason.CACHE_IO, "could not remove invalid cache entry")
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    private fun validateContentType(key: String, expected: String?, actual: String?) {
        if (expected == null) return
        val expectedMediaType = expected.substringBefore(';').trim().lowercase()
        val actualMediaType = actual?.substringBefore(';')?.trim()?.lowercase()
        if (actualMediaType != expectedMediaType) {
            fail(
                key,
                Reason.CONTENT_TYPE_MISMATCH,
                "artifact content type mismatch: expected $expectedMediaType, received $actualMediaType",
            )
        }
    }

    private fun validatedBaseUrl(key: String, value: String): URL {
        val base = runCatching { URL(value) }.getOrElse {
            fail(key, Reason.INVALID_URL, "invalid delivery origin", it)
        }
        if (base.protocol.lowercase() != "https" || base.host.isBlank() ||
            base.userInfo != null || base.query != null || base.ref != null ||
            !base.path.endsWith('/')
        ) {
            fail(key, Reason.INVALID_URL, "invalid delivery origin")
        }
        return base
    }

    private fun composeUrl(key: String, base: URL): URL {
        val root = key.substringBefore('/', missingDelimiterValue = "")
        val relativeKey = if (root.isNotEmpty() && base.path.endsWith("/$root/") &&
            key.startsWith("$root/")
        ) {
            key.removePrefix("$root/")
        } else {
            key
        }
        if (relativeKey.isEmpty() || relativeKey.startsWith('/') ||
            runCatching { URL(relativeKey) }.isSuccess
        ) {
            fail(key, Reason.INVALID_URL, "invalid artifact url")
        }
        val resolved = runCatching { URL(base, relativeKey) }.getOrElse {
            fail(key, Reason.INVALID_URL, "invalid artifact url", it)
        }
        if (!sameOrigin(resolved, base) || !resolved.path.startsWith(base.path)) {
            fail(key, Reason.INVALID_URL, "artifact url escapes the signed delivery origin")
        }
        return resolved
    }

    private fun sameOrigin(left: URL, right: URL): Boolean =
        left.protocol.equals(right.protocol, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(url: URL): Int = when {
        url.port >= 0 -> url.port
        url.protocol.equals("https", ignoreCase = true) -> 443
        else -> url.defaultPort
    }

    private fun pruneLocked() {
        sweepTemporaryFilesLocked()
        val entries = baseDir.listFiles() ?: return
        val files = entries.filter { isDigest(it.name) }
        val temporaryBytes = entries
            .filter { it.name.endsWith(TEMPORARY_SUFFIX) }
            .sumOf { it.length() }
        var total = files.sumOf { it.length() } + temporaryBytes
        if (total <= maxTotalBytes) return
        files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).forEach { file ->
            if (total <= maxTotalBytes) return
            if (cacheScope.protectedDigestCounts.containsKey(file.name)) return@forEach
            val length = file.length()
            if (file.delete()) {
                total -= length
                dropDigestLockIfUnreferencedLocked(file.name)
            }
            else Log.w(LOG_TAG, "Failed to prune release artifact ${file.name}")
        }
    }

    /**
     * Creation, reference changes, and removal all hold [CacheScope.lock]. A
     * caller takes its reference before waiting on the digest monitor, so zero
     * references proves no waiter can later enter an otherwise replaced lock.
     */
    private fun dropDigestLockIfUnreferencedLocked(sha256: String) {
        val digestLock = cacheScope.digestLocks[sha256] ?: return
        if (digestLock.references == 0) cacheScope.digestLocks.remove(sha256)
    }

    private fun sweepTemporaryFilesLocked() {
        val staleBefore = System.currentTimeMillis() - TEMPORARY_STALE_AGE_MS
        baseDir.listFiles { file -> file.name.endsWith(TEMPORARY_SUFFIX) }
            ?.filterNot { it.absolutePath in cacheScope.liveTemporaryOwnership }
            ?.filter { it.lastModified() <= staleBefore }
            ?.forEach { file ->
                val deleted = withTemporaryClaim(file) { file.delete() }
                if (deleted == false) {
                    Log.w(LOG_TAG, "Failed to remove artifact temporary file ${file.name}")
                }
            }
    }

    /** Holds an OS-visible lock so sibling app processes recognize an active writer. */
    private fun ownTemporary(file: File): TemporaryOwnership {
        val randomAccessFile = RandomAccessFile(file, "rw")
        return try {
            TemporaryOwnership(randomAccessFile, randomAccessFile.channel.lock())
        } catch (error: Throwable) {
            randomAccessFile.close()
            throw error
        }
    }

    /** Returns null when another process (or this one) still owns the temporary. */
    private fun <T> withTemporaryClaim(file: File, block: () -> T): T? {
        val randomAccessFile = runCatching { RandomAccessFile(file, "rw") }.getOrNull()
            ?: return null
        val fileLock = try {
            randomAccessFile.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            null
        }
        if (fileLock == null) {
            randomAccessFile.close()
            return null
        }
        return try {
            block()
        } finally {
            fileLock.release()
            randomAccessFile.close()
        }
    }

    private fun fileFor(sha256: String): File {
        require(isDigest(sha256)) { "invalid digest" }
        return File(baseDir, sha256)
    }

    private fun isDigest(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun fail(
        key: String,
        reason: Reason,
        message: String,
        cause: Throwable? = null,
        httpStatusCode: Int? = null,
    ): Nothing = throw ReleaseArtifactAcquisitionException(
        key,
        reason,
        message,
        cause,
        httpStatusCode,
    )

    private companion object {
        private val cacheScopesLock = Any()
        private val cacheScopes = mutableMapOf<String, CacheScope>()
        const val LOG_TAG = "Nuxie"
        const val MAX_REDIRECTS = 5
        const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val TEMPORARY_SUFFIX = ".tmp"
        const val TEMPORARY_STALE_AGE_MS = 60_000L

        fun cacheScope(baseDir: File): CacheScope {
            val path = runCatching { baseDir.canonicalPath }.getOrElse { baseDir.absolutePath }
            return synchronized(cacheScopesLock) {
                cacheScopes[path] ?: CacheScope().also { cacheScopes[path] = it }
            }
        }
    }

    private class CacheScope {
        val lock = Any()
        val digestLocks = mutableMapOf<String, DigestLock>()
        val protectedDigestCounts = mutableMapOf<String, Int>()
        val liveTemporaryOwnership = mutableMapOf<String, TemporaryOwnership>()
    }

    private class DigestLock(
        val monitor: Any = Any(),
        var references: Int = 0,
    )

    private class TemporaryOwnership(
        private val randomAccessFile: RandomAccessFile,
        private val fileLock: FileLock,
    ) : Closeable {
        override fun close() {
            fileLock.release()
            randomAccessFile.close()
        }
    }
}
