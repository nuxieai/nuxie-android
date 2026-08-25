package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.HttpTransport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReleaseArtifactAcquirerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun successfulDownloadPublishesVerifiedFilesAndCacheHitSkipsRequest() = runTest {
        val rivBytes = "verified-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        var requestCount = 0
        val transport = HttpTransport { request ->
            requestCount += 1
            assertEquals("GET", request.method)
            HttpTransport.Response(
                statusCode = 200,
                body = rivBytes,
                headers = mapOf("Content-Type" to "application/vnd.rive"),
            )
        }
        val cache = ReleaseArtifactCache(
            context = RuntimeEnvironment.getApplication(),
            transport = transport,
            cacheDirectory = temporaryFolder.newFolder("cache"),
        )
        val acquirer = ReleaseArtifactAcquirer(cache)

        val first = acquirer.acquire(release(riv), delivery())
        val second = acquirer.acquire(release(riv), delivery())

        assertEquals(1, requestCount)
        assertEquals(TEST_IDENTITY, first.identity)
        assertSame(
            first.rivFile,
            first.artifactsByKey.getValue(riv.getValue("key").jsonPrimitive.content),
        )
        assertArrayEquals(rivBytes, first.rivFile.readBytes())
        assertEquals(first.rivFile, second.rivFile)
    }

    @Test
    fun digestMismatchIsTypedAndLeavesNoCacheFile() = runTest {
        val received = "corrupt-riv".encodeToByteArray()
        val expected = "expected-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(expected)}.riv",
            bytes = received,
            contentType = "application/vnd.rive",
            declaredSha256 = sha256(expected),
        )
        val cacheDirectory = temporaryFolder.newFolder("digest-mismatch")
        val cache = ReleaseArtifactCache(
            context = RuntimeEnvironment.getApplication(),
            transport = HttpTransport {
                HttpTransport.Response(
                    200,
                    received,
                    mapOf("Content-Type" to "application/vnd.rive"),
                )
            },
            cacheDirectory = cacheDirectory,
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(release(riv), delivery())
        }

        assertEquals(riv.getValue("key").jsonPrimitive.content, failure.artifactKey)
        assertEquals(
            ReleaseArtifactAcquisitionException.Reason.DIGEST_MISMATCH,
            failure.reason,
        )
        assertEquals(emptyList<String>(), cacheDirectory.list()?.toList().orEmpty())
    }

    @Test
    fun sizeOverrunAbortsAfterTheFirstExcessByte() = runTest {
        val declared = "four".encodeToByteArray()
        val body = "four-and-more".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(declared)}.riv",
            bytes = declared,
            contentType = "application/vnd.rive",
        )
        val tracking = TrackingInputStream(body)
        val transport = object : HttpTransport {
            override fun execute(request: HttpTransport.Request): HttpTransport.Response =
                error("Buffered transport must not be used")

            override fun open(request: HttpTransport.Request) = HttpTransport.StreamingResponse(
                statusCode = 200,
                body = tracking,
                headers = mapOf("Content-Type" to "application/vnd.rive"),
                finalUrl = request.url,
            )
        }
        val cacheDirectory = temporaryFolder.newFolder("size-overrun")
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            transport,
            cacheDirectory = cacheDirectory,
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(release(riv), delivery())
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.SIZE_OVERRUN, failure.reason)
        assertEquals(declared.size + 1, tracking.bytesRead)
        assertEquals(true, tracking.closed)
        assertEquals(emptyList<String>(), cacheDirectory.list()?.toList().orEmpty())
    }

    @Test
    fun nonSuccessStatusIsTypedAndNamesTheArtifact() = runTest {
        val rivBytes = "status-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { HttpTransport.Response(503, ByteArray(0)) },
            cacheDirectory = temporaryFolder.newFolder("status"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(release(riv), delivery())
        }

        assertEquals(riv.getValue("key").jsonPrimitive.content, failure.artifactKey)
        assertEquals(ReleaseArtifactAcquisitionException.Reason.HTTP_STATUS, failure.reason)
    }

    @Test
    fun offOriginRedirectIsRejectedWithoutFollowingIt() = runTest {
        val rivBytes = "redirect-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val requested = mutableListOf<URL>()
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                requested += request.url
                HttpTransport.Response(
                    302,
                    ByteArray(0),
                    mapOf("Location" to "https://evil.example/artifact.riv"),
                )
            },
            cacheDirectory = temporaryFolder.newFolder("redirect"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(release(riv), delivery())
        }

        assertEquals(
            ReleaseArtifactAcquisitionException.Reason.REDIRECT_ESCAPED_ORIGIN,
            failure.reason,
        )
        assertEquals(listOf(URL("https://cdn.nuxie.test/renders/sha256/${sha256(rivBytes)}.riv")), requested)
    }

    @Test
    fun contradictoryContentTypeIsRejected() = runTest {
        val rivBytes = "content-type-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                HttpTransport.Response(
                    200,
                    rivBytes,
                    mapOf("Content-Type" to "text/html; charset=utf-8"),
                )
            },
            cacheDirectory = temporaryFolder.newFolder("content-type"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(release(riv), delivery())
        }

        assertEquals(
            ReleaseArtifactAcquisitionException.Reason.CONTENT_TYPE_MISMATCH,
            failure.reason,
        )
    }

    @Test
    fun concurrentAcquisitionsOfOneDigestDownloadOnce() = runTest {
        val rivBytes = "concurrent-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val requestStarted = CountDownLatch(1)
        val secondRequestStarted = CountDownLatch(1)
        val completeRequest = CountDownLatch(1)
        val requestCount = AtomicInteger()
        val transport = HttpTransport {
                if (requestCount.incrementAndGet() == 2) secondRequestStarted.countDown()
                requestStarted.countDown()
                completeRequest.await(5, TimeUnit.SECONDS)
                HttpTransport.Response(
                    200,
                    rivBytes,
                    mapOf("Content-Type" to "application/vnd.rive"),
                )
            }
        val cacheDirectory = temporaryFolder.newFolder("concurrent")
        val firstAcquirer = ReleaseArtifactAcquirer(
            ReleaseArtifactCache(
                RuntimeEnvironment.getApplication(),
                transport,
                cacheDirectory = cacheDirectory,
            ),
        )
        val secondAcquirer = ReleaseArtifactAcquirer(
            ReleaseArtifactCache(
                RuntimeEnvironment.getApplication(),
                transport,
                cacheDirectory = cacheDirectory,
            ),
        )

        val first = async { firstAcquirer.acquire(release(riv), delivery()) }
        yield()
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        val second = async { secondAcquirer.acquire(release(riv), delivery()) }
        secondRequestStarted.await(250, TimeUnit.MILLISECONDS)
        completeRequest.countDown()
        val results = awaitAll(first, second)

        assertEquals(1, requestCount.get())
        assertEquals(results[0].rivFile, results[1].rivFile)
        assertArrayEquals(rivBytes, results[0].rivFile.readBytes())
    }

    @Test
    fun completeReleaseIsProtectedWhileAcquisitionPrunes() = runTest {
        val rivBytes = "12345678".encodeToByteArray()
        val assetBytes = "abcdefgh".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val asset = artifact(
            key = "assets/sha256/${sha256(assetBytes)}.png",
            bytes = assetBytes,
            contentType = "image/png",
            kind = "image",
        )
        val requested = mutableListOf<URL>()
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                requested += request.url
                when (request.url.path) {
                    "/renders/sha256/${sha256(rivBytes)}.riv" -> HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                    "/assets/sha256/${sha256(assetBytes)}.png" -> HttpTransport.Response(
                        200,
                        assetBytes,
                        mapOf("Content-Type" to "image/png"),
                    )
                    else -> error("Unexpected URL ${request.url}")
                }
            },
            maxTotalBytes = 10,
            cacheDirectory = temporaryFolder.newFolder("protected"),
        )

        val acquired = ReleaseArtifactAcquirer(cache).acquire(
            release(riv, assets = listOf(asset)),
            delivery(),
        )

        assertEquals(2, acquired.artifactsByKey.size)
        assertTrue(acquired.artifactsByKey.values.all { it.exists() })
        assertEquals(
            listOf(
                URL("https://cdn.nuxie.test/renders/sha256/${sha256(rivBytes)}.riv"),
                URL("https://cdn.nuxie.test/assets/sha256/${sha256(assetBytes)}.png"),
            ),
            requested,
        )
    }

    @Test
    fun everyArtifactUrlIsValidatedBeforeTheFirstRequest() = runTest {
        val rivBytes = "preflight-riv".encodeToByteArray()
        val assetBytes = "preflight-asset".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val asset = artifact(
            key = "https://evil.example/${sha256(assetBytes)}.png",
            bytes = assetBytes,
            contentType = "image/png",
            kind = "image",
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                error("No request is expected")
            },
            cacheDirectory = temporaryFolder.newFolder("preflight"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, assets = listOf(asset)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_URL, failure.reason)
        assertEquals(asset.getValue("key").jsonPrimitive.content, failure.artifactKey)
        assertEquals(0, requestCount)
    }

    @Test
    fun transportFailureCanBeRetriedWithoutAStalePartialFile() = runTest {
        val rivBytes = "retry-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        var requestCount = 0
        val cacheDirectory = temporaryFolder.newFolder("retry")
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                if (requestCount == 1) throw IOException("offline")
                HttpTransport.Response(
                    200,
                    rivBytes,
                    mapOf("Content-Type" to "application/vnd.rive"),
                )
            },
            cacheDirectory = cacheDirectory,
        )
        val acquirer = ReleaseArtifactAcquirer(cache)

        val failure = acquisitionFailure { acquirer.acquire(release(riv), delivery()) }
        val acquired = acquirer.acquire(release(riv), delivery())

        assertEquals(ReleaseArtifactAcquisitionException.Reason.TRANSPORT, failure.reason)
        assertEquals(2, requestCount)
        assertArrayEquals(rivBytes, acquired.rivFile.readBytes())
        assertEquals(1, cacheDirectory.list()?.size)
    }

    @Test
    fun conflictingDuplicateArtifactKeyIsRejectedBeforeAnyRequest() = runTest {
        val rivBytes = "duplicate-riv".encodeToByteArray()
        val assetBytes = "duplicate-asset".encodeToByteArray()
        val duplicateKey = "renders/sha256/${sha256(rivBytes)}.riv"
        val riv = artifact(
            key = duplicateKey,
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val asset = artifact(
            key = duplicateKey,
            bytes = assetBytes,
            contentType = "image/png",
            kind = "image",
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                error("No request is expected")
            },
            cacheDirectory = temporaryFolder.newFolder("duplicate-key"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, assets = listOf(asset)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(duplicateKey, failure.artifactKey)
        assertEquals(0, requestCount)
    }

    @Test
    fun protectionIsSharedByCacheInstancesUsingTheSameDirectory() = runTest {
        val rivBytes = "12345678".encodeToByteArray()
        val assetBytes = "abcdefgh".encodeToByteArray()
        val outsiderBytes = "ABCDEFGH".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val asset = artifact(
            key = "assets/sha256/${sha256(assetBytes)}.png",
            bytes = assetBytes,
            contentType = "image/png",
            kind = "image",
        )
        val assetRequestStarted = CountDownLatch(1)
        val completeAssetRequest = CountDownLatch(1)
        val cacheDirectory = temporaryFolder.newFolder("shared-protection")
        val releaseCache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                when (request.url.path) {
                    "/renders/sha256/${sha256(rivBytes)}.riv" -> HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                    "/assets/sha256/${sha256(assetBytes)}.png" -> {
                        assetRequestStarted.countDown()
                        completeAssetRequest.await(5, TimeUnit.SECONDS)
                        HttpTransport.Response(
                            200,
                            assetBytes,
                            mapOf("Content-Type" to "image/png"),
                        )
                    }
                    else -> error("Unexpected URL ${request.url}")
                }
            },
            maxTotalBytes = 12,
            cacheDirectory = cacheDirectory,
        )
        val pruningCache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                HttpTransport.Response(200, outsiderBytes)
            },
            maxTotalBytes = 12,
            cacheDirectory = cacheDirectory,
        )

        val acquisition = async {
            ReleaseArtifactAcquirer(releaseCache).acquire(
                release(riv, assets = listOf(asset)),
                delivery(),
            )
        }
        yield()
        assertTrue(assetRequestStarted.await(5, TimeUnit.SECONDS))
        val rivFile = requireNotNull(releaseCache.cachedFile(sha256(rivBytes)))
        rivFile.setLastModified(System.currentTimeMillis() - 60_000)
        try {
            pruningCache.acquire(
                key = "outsider",
                sha256 = sha256(outsiderBytes),
                expectedSizeBytes = outsiderBytes.size.toLong(),
                maxBytes = outsiderBytes.size.toLong(),
                signedBaseUrl = "https://cdn.nuxie.test/",
            )
            assertTrue(rivFile.exists())
        } finally {
            completeAssetRequest.countDown()
        }
        assertTrue(acquisition.await().rivFile.exists())
    }

    private fun release(riv: JsonObject, assets: List<JsonObject> = emptyList()) = AuthenticatedRelease(
        keyId = "test-key",
        descriptorSha256 = "0".repeat(64),
        identity = TEST_IDENTITY,
        descriptorBytes = ByteArray(0),
        descriptor = buildJsonObject {
            put("render", buildJsonObject {
                put("renderer", JsonPrimitive("rive"))
                put("riv", riv)
                put("screens", buildJsonArray { })
                put("transitions", buildJsonArray { })
                put("textInputs", buildJsonArray { })
                put("assets", buildJsonArray { assets.forEach(::add) })
            })
        },
        publishedAtSeqToPromote = null,
    )

    private fun artifact(
        key: String,
        bytes: ByteArray,
        contentType: String,
        declaredSha256: String = sha256(bytes),
        declaredSizeBytes: Int = bytes.size,
        kind: String? = null,
    ) = buildJsonObject {
        put("key", JsonPrimitive(key))
        put("sha256", JsonPrimitive(declaredSha256))
        put("sizeBytes", JsonPrimitive(declaredSizeBytes))
        put("contentType", JsonPrimitive(contentType))
        kind?.let { put("kind", JsonPrimitive(it)) }
    }

    private suspend fun acquisitionFailure(
        block: suspend () -> Unit,
    ): ReleaseArtifactAcquisitionException = try {
        block()
        throw AssertionError("Expected acquisition to fail")
    } catch (error: ReleaseArtifactAcquisitionException) {
        error
    }

    private class TrackingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var bytesRead = 0
            private set
        var closed = false
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead += 1 }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            delegate.read(bytes, offset, length).also { if (it > 0) bytesRead += it }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private fun delivery() = Delivery(
        renderBaseUrl = "https://cdn.nuxie.test/renders/",
        assetBaseUrl = "https://cdn.nuxie.test/assets/",
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val TEST_IDENTITY = ExperienceReleaseIdentity(
            appId = "app",
            environment = "development",
            experienceId = "experience",
            experienceVersionId = "version",
            buildId = "build",
            versionNumber = 1,
            publishedAt = "2026-08-24T00:00:00Z",
            publishedAtSeq = 1,
        )
    }
}
