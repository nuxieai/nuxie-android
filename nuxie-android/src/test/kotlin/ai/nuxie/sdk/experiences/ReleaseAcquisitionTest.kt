package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.HttpTransport
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReleaseAcquisitionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class ScriptedTransport(
        private val respond: (HttpTransport.Request) -> HttpTransport.Response,
    ) : HttpTransport {
        val urls = mutableListOf<String>()
        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            urls.add(request.url.toString())
            return respond(request)
        }
    }

    @Test
    fun acquisitionVerifiesDigestAndCaches() {
        val content = "riv-bytes".encodeToByteArray()
        val digest = sha(content)
        val transport = ScriptedTransport { HttpTransport.Response(200, content) }
        val cache = JourneyReleaseArtifactCache(RuntimeEnvironment.getApplication(), transport)

        val file = cache.acquire(
            key = "renders/sha256/$digest",
            sha256 = digest,
            expectedSizeBytes = content.size.toLong(),
            maxBytes = 64L * 1024 * 1024,
            signedBaseUrl = "https://cdn.nuxie.test/renders/",
        )
        assertTrue(file.exists())
        assertEquals(1, transport.urls.size)

        // Second acquire: cache hit, no network.
        cache.acquire(
            key = "renders/sha256/$digest",
            sha256 = digest,
            expectedSizeBytes = content.size.toLong(),
            maxBytes = 64L * 1024 * 1024,
            signedBaseUrl = "https://cdn.nuxie.test/renders/",
        )
        assertEquals(1, transport.urls.size)
        assertNotNull(cache.cachedFile(digest))
    }

    @Test
    fun digestMismatchCachesNothing() {
        val content = "corrupted".encodeToByteArray()
        val claimed = sha("expected".encodeToByteArray())
        val cache = JourneyReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            ScriptedTransport { HttpTransport.Response(200, content) },
        )
        assertThrows(JourneyReleaseArtifactCache.AcquisitionException::class.java) {
            cache.acquire("k", claimed, content.size.toLong(), 1024, "https://cdn.nuxie.test/renders/")
        }
        assertEquals(null, cache.cachedFile(claimed))
    }

    @Test
    fun urlsAreConfinedToTheSignedOrigin() {
        val cache = JourneyReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            ScriptedTransport { HttpTransport.Response(200, ByteArray(0)) },
        )
        val digest = sha(ByteArray(1))
        assertThrows(JourneyReleaseArtifactCache.AcquisitionException::class.java) {
            cache.acquire(
                key = "https://evil.example/steal",
                sha256 = digest,
                expectedSizeBytes = 1,
                maxBytes = 1024,
                signedBaseUrl = "https://cdn.nuxie.test/renders/",
            )
        }
        assertThrows(JourneyReleaseArtifactCache.AcquisitionException::class.java) {
            cache.acquire(
                key = "../../escape",
                sha256 = digest,
                expectedSizeBytes = 1,
                maxBytes = 1024,
                signedBaseUrl = "https://cdn.nuxie.test/renders/",
            )
        }
    }

    @Test
    fun oversizedDeclarationsAreRejectedBeforeDownload() {
        val transport = ScriptedTransport { HttpTransport.Response(200, ByteArray(0)) }
        val cache = JourneyReleaseArtifactCache(RuntimeEnvironment.getApplication(), transport)
        assertThrows(JourneyReleaseArtifactCache.AcquisitionException::class.java) {
            cache.acquire(
                "k", sha(ByteArray(1)), expectedSizeBytes = 100, maxBytes = 10,
                signedBaseUrl = "https://cdn.nuxie.test/renders/",
            )
        }
        assertEquals(0, transport.urls.size)
    }

    @Test
    fun pruneEvictsOldestUnprotectedFirst() {
        val contentA = ByteArray(600) { 1 }
        val contentB = ByteArray(600) { 2 }
        val shaA = sha(contentA)
        val shaB = sha(contentB)
        var body = contentA
        val cache = JourneyReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            ScriptedTransport { HttpTransport.Response(200, body) },
            maxTotalBytes = 1000,
        )
        val fileA = cache.acquire("a", shaA, 600, 1024, "https://cdn.nuxie.test/")
        fileA.setLastModified(System.currentTimeMillis() - 60_000)
        body = contentB
        cache.acquire("b", shaB, 600, 1024, "https://cdn.nuxie.test/")

        // Over budget: the older A is pruned, B survives.
        assertEquals(null, cache.cachedFile(shaA))
        assertNotNull(cache.cachedFile(shaB))
    }

    @Test
    fun prunedDigestDoesNotRetainAnUnreferencedLock() {
        val content = "pruned".encodeToByteArray()
        val digest = sha(content)
        val cache = JourneyReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            ScriptedTransport { HttpTransport.Response(200, content) },
            maxTotalBytes = 0,
        )

        val file = cache.acquire(
            "artifact",
            digest,
            content.size.toLong(),
            content.size.toLong(),
            "https://cdn.nuxie.test/",
        )

        assertEquals(false, file.exists())
        assertEquals(0, cache.digestLockCount())
    }

    @Test
    fun cachedDigestDoesNotRetainAnUnreferencedLock() {
        val content = "cached".encodeToByteArray()
        val digest = sha(content)
        val cache = JourneyReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            ScriptedTransport { HttpTransport.Response(200, content) },
        )

        val file = cache.acquire(
            "artifact",
            digest,
            content.size.toLong(),
            content.size.toLong(),
            "https://cdn.nuxie.test/",
        )

        assertTrue(file.exists())
        assertEquals(0, cache.digestLockCount())
    }

    @Test
    fun differentActiveLeaseWithMismatchingSizeCannotDeleteProtectedDigest() {
        val content = "leased-release".encodeToByteArray()
        val digest = sha(content)
        val cacheDirectory = temporaryFolder.newFolder("protected-mismatch")
        val cache = JourneyReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            ScriptedTransport { HttpTransport.Response(200, content) },
            cacheDirectory = cacheDirectory,
        )
        val leasedFile = cache.acquire(
            "first-release",
            digest,
            content.size.toLong(),
            content.size.toLong(),
            "https://cdn.nuxie.test/",
        )
        val firstProtection = cache.protect(setOf(digest))
        val secondCache = JourneyReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            ScriptedTransport { error("protected mismatch must not fetch") },
            cacheDirectory = cacheDirectory,
        )
        val secondProtection = secondCache.protect(setOf(digest))
        try {
            val failure = assertThrows(JourneyReleaseArtifactAcquisitionException::class.java) {
                secondCache.acquire(
                    "second-release",
                    digest,
                    content.size + 1L,
                    content.size + 1L,
                    "https://cdn.nuxie.test/",
                    protection = secondProtection,
                )
            }

            assertEquals(JourneyReleaseArtifactAcquisitionException.Reason.SIZE_MISMATCH, failure.reason)
            assertTrue(leasedFile.exists())
            assertEquals(content.toList(), leasedFile.readBytes().toList())
        } finally {
            secondProtection.close()
            firstProtection.close()
        }
    }
}
