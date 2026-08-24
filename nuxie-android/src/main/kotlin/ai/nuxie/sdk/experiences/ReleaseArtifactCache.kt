package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.HttpTransport
import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest

/**
 * Content-addressed release artifact store + bounded acquisition, porting the
 * iOS `ExperienceReleaseAcquisitionStore` policies:
 *
 * - Files are named by their SHA-256; a cache hit never re-downloads.
 * - Downloads are size-bounded per artifact kind and verified on size +
 *   SHA-256 before entering the cache; a failed verification caches nothing.
 * - Artifact URLs are confined to the signed delivery origin — a descriptor
 *   key can never route acquisition to an arbitrary host.
 * - LRU pruning to 256 MiB, protecting every digest of the release being
 *   assembled (in-flight protection registry).
 */
internal class ReleaseArtifactCache(
    context: Context,
    private val transport: HttpTransport,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {
    private val lock = Any()
    private val baseDir = File((context.applicationContext ?: context).cacheDir, "nuxie/release_objects")
    private val protectedDigests = mutableSetOf<String>()

    init {
        baseDir.mkdirs()
    }

    class AcquisitionException(message: String) : IOException(message)

    /** Protect a release's digests while it is being assembled. */
    fun <T> withProtection(digests: Collection<String>, block: () -> T): T {
        synchronized(lock) { protectedDigests.addAll(digests) }
        try {
            return block()
        } finally {
            synchronized(lock) { protectedDigests.removeAll(digests.toSet()) }
        }
    }

    fun cachedFile(sha256: String): File? {
        val file = fileFor(sha256)
        return file.takeIf { it.exists() }?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * Acquire one artifact: cache hit or a bounded, verified download from
     * the signed delivery origin.
     */
    fun acquire(
        key: String,
        sha256: String,
        expectedSizeBytes: Long,
        maxBytes: Long,
        signedBaseUrl: String,
    ): File {
        cachedFile(sha256)?.let { return it }
        if (expectedSizeBytes > maxBytes) throw AcquisitionException("artifact exceeds size limit")

        val base = URL(signedBaseUrl)
        val url = URL(base, key)
        // Origin confinement: the resolved URL must stay on the signed origin
        // and under its path.
        if (url.protocol != base.protocol || url.host != base.host || url.port != base.port ||
            !url.path.startsWith(base.path)
        ) {
            throw AcquisitionException("artifact url escapes the signed delivery origin")
        }

        val response = transport.execute(
            HttpTransport.Request(url = url, headers = emptyMap(), body = ByteArray(0)),
        )
        if (response.statusCode !in 200..299) {
            throw AcquisitionException("artifact fetch failed: ${response.statusCode}")
        }
        val bytes = response.body
        if (bytes.size.toLong() != expectedSizeBytes) {
            throw AcquisitionException("artifact size mismatch")
        }
        if (sha256Hex(bytes) != sha256) throw AcquisitionException("artifact digest mismatch")

        synchronized(lock) {
            val file = fileFor(sha256)
            if (!file.exists()) {
                val temporary = File(baseDir, "$sha256.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(file)) {
                    file.writeBytes(bytes)
                    temporary.delete()
                }
            }
            prune()
            return file
        }
    }

    private fun prune() {
        val files = baseDir.listFiles { f -> !f.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxTotalBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxTotalBytes) return
            if (file.name in synchronizedProtected()) return@forEach
            total -= file.length()
            if (!file.delete()) {
                Log.w(LOG_TAG, "Failed to prune release artifact ${file.name}")
            }
        }
    }

    private fun synchronizedProtected(): Set<String> = protectedDigests.toSet()

    private fun fileFor(sha256: String): File {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "invalid digest" }
        return File(baseDir, sha256)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
    }
}
