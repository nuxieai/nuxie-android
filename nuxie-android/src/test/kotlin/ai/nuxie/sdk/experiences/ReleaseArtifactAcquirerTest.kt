package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.network.HttpTransport
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
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
        try {
            assertEquals(1, requestCount)
            assertEquals(TEST_IDENTITY, first.identity)
            assertSame(
                first.rivFile,
                first.artifactsByKey.getValue(riv.getValue("key").jsonPrimitive.content),
            )
            assertArrayEquals(rivBytes, first.rivFile.readBytes())
            assertEquals(first.rivFile, second.rivFile)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun sameAcquirerRepairsCorruptCachedObjectWithVerifiedBytes() = runTest {
        val rivBytes = "repairable-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            context = RuntimeEnvironment.getApplication(),
            transport = HttpTransport {
                requestCount += 1
                HttpTransport.Response(
                    statusCode = 200,
                    body = rivBytes,
                    headers = mapOf("Content-Type" to "application/vnd.rive"),
                )
            },
            cacheDirectory = temporaryFolder.newFolder("repair-corrupt-cache"),
        )
        val acquirer = ReleaseArtifactAcquirer(cache)
        val cachedFile = acquirer.acquire(release(riv), delivery()).use { acquired ->
            acquired.rivFile
        }
        cachedFile.writeBytes(ByteArray(rivBytes.size) { 0x7f })

        acquirer.acquire(release(riv), delivery()).use { repaired ->
            assertEquals(2, requestCount)
            assertArrayEquals(rivBytes, repaired.rivFile.readBytes())
        }
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
    fun oversizedRivDeclarationIsRejectedBeforeDownload() = runTest {
        val rivBytes = "oversized-riv".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
            declaredSizeBytes = ExperienceReleaseLimits.RIV_ARTIFACT_BYTES + 1,
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                HttpTransport.Response(200, rivBytes)
            },
            cacheDirectory = temporaryFolder.newFolder("oversized-riv"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(release(riv), delivery())
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(0, requestCount)
    }

    @Test
    fun oversizedExternalAssetDeclarationIsRejectedBeforeDownload() = runTest {
        val rivBytes = "asset-limit-riv".encodeToByteArray()
        val assetBytes = "oversized-asset".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val asset = artifact(
            key = "assets/sha256/${sha256(assetBytes)}.png",
            bytes = assetBytes,
            contentType = "image/png",
            declaredSizeBytes = ExperienceReleaseLimits.EXTERNAL_ASSET_BYTES + 1,
            kind = "image",
            required = false,
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                HttpTransport.Response(200, ByteArray(0))
            },
            cacheDirectory = temporaryFolder.newFolder("oversized-asset"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, assets = listOf(asset)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(0, requestCount)
    }

    @Test
    fun scriptAboveExternalAssetCeilingIsRejectedBeforeDownload() = runTest {
        val rivBytes = "script-limit-riv".encodeToByteArray()
        val scriptBytes = "oversized-script".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val script = artifact(
            key = "screen-behavior/sha256/${sha256(scriptBytes)}.bin",
            bytes = scriptBytes,
            contentType = "application/octet-stream",
            declaredSizeBytes = ExperienceReleaseLimits.EXTERNAL_ASSET_BYTES + 1,
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                HttpTransport.Response(200, ByteArray(0))
            },
            cacheDirectory = temporaryFolder.newFolder("oversized-script"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, scripts = listOf(script)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(0, requestCount)
    }

    @Test
    fun scriptsHaveTheCorrectedSixteenMiBAggregateCeiling() = runTest {
        val rivBytes = "script-aggregate-riv".encodeToByteArray()
        val firstBytes = "script-aggregate-one".encodeToByteArray()
        val secondBytes = "script-aggregate-two".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val scripts = listOf(firstBytes, secondBytes).map { bytes ->
            artifact(
                key = "screen-behavior/sha256/${sha256(bytes)}.bin",
                bytes = bytes,
                contentType = "application/octet-stream",
                declaredSizeBytes = 8 * 1024 * 1024 + 1,
            )
        }
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                HttpTransport.Response(200, ByteArray(0))
            },
            cacheDirectory = temporaryFolder.newFolder("script-aggregate"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(release(riv, scripts = scripts), delivery())
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(0, requestCount)
    }

    @Test
    fun scriptAboveFourMiBPassesPerArtifactValidation() = runTest {
        val rivBytes = "corrected-script-limit-riv".encodeToByteArray()
        val scriptBytes = "corrected-script-limit".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val script = artifact(
            key = "screen-behavior/sha256/${sha256(scriptBytes)}.bin",
            bytes = scriptBytes,
            contentType = "application/octet-stream",
            declaredSizeBytes = 5 * 1024 * 1024,
        )
        val requested = mutableListOf<URL>()
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                requested += request.url
                if (request.url.path.startsWith("/renders/")) {
                    HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                } else {
                    HttpTransport.Response(
                        200,
                        scriptBytes,
                        mapOf("Content-Type" to "application/octet-stream"),
                    )
                }
            },
            cacheDirectory = temporaryFolder.newFolder("corrected-script-limit"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, scripts = listOf(script)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.SIZE_MISMATCH, failure.reason)
        assertEquals(2, requested.size)
        assertTrue(requested.last().path.startsWith("/assets/screen-behavior/"))
    }

    @Test
    fun aggregateArtifactCeilingIsRejectedBeforeDownload() = runTest {
        val rivBytes = "aggregate-riv".encodeToByteArray()
        val assetBytes = listOf("aggregate-a", "aggregate-b", "aggregate-c")
            .map(String::encodeToByteArray)
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
            declaredSizeBytes = ExperienceReleaseLimits.RIV_ARTIFACT_BYTES,
        )
        val assets = assetBytes.mapIndexed { index, bytes ->
            artifact(
                key = "assets/sha256/${sha256(bytes)}.png",
                bytes = bytes,
                contentType = "image/png",
                declaredSizeBytes = if (index < 2) {
                    ExperienceReleaseLimits.EXTERNAL_ASSET_BYTES
                } else {
                    1
                },
                kind = "image",
            )
        }
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                HttpTransport.Response(200, ByteArray(0))
            },
            cacheDirectory = temporaryFolder.newFolder("aggregate-limit"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(release(riv, assets), delivery())
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(0, requestCount)
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
    fun screenBehaviorScriptIsAcquiredAsARequiredInput() = runTest {
        val rivBytes = "scripted-riv".encodeToByteArray()
        val scriptBytes = "script-bytecode".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val script = artifact(
            key = "screen-behavior/sha256/${sha256(scriptBytes)}.bin",
            bytes = scriptBytes,
            contentType = "application/octet-stream",
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
                    "/assets/screen-behavior/sha256/${sha256(scriptBytes)}.bin" ->
                        HttpTransport.Response(
                            200,
                            scriptBytes,
                            mapOf("Content-Type" to "application/octet-stream"),
                        )
                    else -> error("Unexpected URL ${request.url}")
                }
            },
            cacheDirectory = temporaryFolder.newFolder("screen-script"),
        )

        ReleaseArtifactAcquirer(cache).acquire(
            release(riv, scripts = listOf(script)),
            delivery(),
        ).use { acquired ->
            assertEquals(2, acquired.artifactsByKey.size)
            assertArrayEquals(
                scriptBytes,
                acquired.artifactsByKey.getValue(script.getValue("key").jsonPrimitive.content).readBytes(),
            )
            assertEquals(2, requested.size)
        }
    }

    @Test
    fun screenBehaviorScriptServerFailureIsFatal() = runTest {
        val rivBytes = "required-script-riv".encodeToByteArray()
        val scriptBytes = "required-script-bytecode".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val script = artifact(
            key = "screen-behavior/sha256/${sha256(scriptBytes)}.bin",
            bytes = scriptBytes,
            contentType = "application/octet-stream",
        )
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                if (request.url.path.startsWith("/renders/")) {
                    HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                } else {
                    HttpTransport.Response(503, ByteArray(0))
                }
            },
            cacheDirectory = temporaryFolder.newFolder("required-script-failure"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, scripts = listOf(script)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.HTTP_STATUS, failure.reason)
        assertEquals(script.getValue("key").jsonPrimitive.content, failure.artifactKey)
    }

    @Test
    fun twoScreensSharingOneScriptAcquireTheDigestOnce() = runTest {
        val rivBytes = "shared-script-riv".encodeToByteArray()
        val scriptBytes = "shared-script-bytecode".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val script = artifact(
            key = "screen-behavior/sha256/${sha256(scriptBytes)}.bin",
            bytes = scriptBytes,
            contentType = "application/octet-stream",
        )
        val requestCount = AtomicInteger()
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                requestCount.incrementAndGet()
                when (request.url.path) {
                    "/renders/sha256/${sha256(rivBytes)}.riv" -> HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                    "/assets/screen-behavior/sha256/${sha256(scriptBytes)}.bin" ->
                        HttpTransport.Response(
                            200,
                            scriptBytes,
                            mapOf("Content-Type" to "application/octet-stream"),
                        )
                    else -> error("Unexpected URL ${request.url}")
                }
            },
            cacheDirectory = temporaryFolder.newFolder("shared-script"),
        )

        ReleaseArtifactAcquirer(cache).acquire(
            release(riv, scripts = listOf(script, script)),
            delivery(),
        ).use {
            assertEquals(2, requestCount.get())
            assertEquals(1, cache.protectionCount(sha256(scriptBytes)))
        }
        assertEquals(0, cache.protectionCount(sha256(scriptBytes)))
    }

    @Test
    fun conflictingMetadataForOneDigestIsRejectedWithoutTouchingALeasedFile() = runTest {
        val rivBytes = "metadata-conflict-riv".encodeToByteArray()
        val scriptBytes = "metadata-conflict-script".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val scriptKey = "screen-behavior/sha256/${sha256(scriptBytes)}.bin"
        val script = artifact(
            key = scriptKey,
            bytes = scriptBytes,
            contentType = "application/octet-stream",
        )
        val conflictingScript = artifact(
            key = scriptKey,
            bytes = scriptBytes,
            contentType = "application/octet-stream",
            declaredSizeBytes = scriptBytes.size + 1,
        )
        val requestCount = AtomicInteger()
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                requestCount.incrementAndGet()
                if (request.url.path.startsWith("/renders/")) {
                    HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                } else {
                    HttpTransport.Response(
                        200,
                        scriptBytes,
                        mapOf("Content-Type" to "application/octet-stream"),
                    )
                }
            },
            cacheDirectory = temporaryFolder.newFolder("metadata-conflict"),
        )
        val acquirer = ReleaseArtifactAcquirer(cache)
        val acquired = acquirer.acquire(release(riv, scripts = listOf(script)), delivery())
        val leasedScript = acquired.artifactsByKey.getValue(scriptKey)
        val completedRequests = requestCount.get()
        try {
            val failure = acquisitionFailure {
                acquirer.acquire(
                    release(riv, scripts = listOf(script, conflictingScript)),
                    delivery(),
                )
            }

            assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
            assertEquals(completedRequests, requestCount.get())
            assertTrue(leasedScript.exists())
            assertArrayEquals(scriptBytes, leasedScript.readBytes())
            assertEquals(1, cache.protectionCount(sha256(scriptBytes)))
        } finally {
            acquired.close()
        }
    }

    @Test
    fun conflictingContentTypeForOneDigestIsRejectedBeforeAnyRequest() = runTest {
        val rivBytes = "content-conflict-riv".encodeToByteArray()
        val assetBytes = "content-conflict-asset".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val assetKey = "assets/sha256/${sha256(assetBytes)}.bin"
        val first = artifact(
            key = assetKey,
            bytes = assetBytes,
            contentType = "application/octet-stream",
            kind = "script",
        )
        val conflicting = artifact(
            key = assetKey,
            bytes = assetBytes,
            contentType = "application/x-nuxie-script",
            kind = "script",
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                error("No request is expected")
            },
            cacheDirectory = temporaryFolder.newFolder("content-conflict"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, assets = listOf(first, conflicting)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(0, requestCount)
    }

    @Test
    fun anyRequiredReferenceMakesTheSharedDigestRequired() = runTest {
        val rivBytes = "required-merge-riv".encodeToByteArray()
        val assetBytes = "required-merge-asset".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val assetKey = "assets/sha256/${sha256(assetBytes)}.png"
        val optionalAsset = artifact(
            key = assetKey,
            bytes = assetBytes,
            contentType = "image/png",
            kind = "image",
            required = false,
        )
        val requiredAsset = artifact(
            key = assetKey,
            bytes = assetBytes,
            contentType = "image/png",
            kind = "image",
            required = true,
        )
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                if (request.url.path.startsWith("/renders/")) {
                    HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                } else {
                    HttpTransport.Response(404, ByteArray(0))
                }
            },
            cacheDirectory = temporaryFolder.newFolder("required-merge"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, assets = listOf(optionalAsset, requiredAsset)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.HTTP_STATUS, failure.reason)
        assertEquals(assetKey, failure.artifactKey)
    }

    @Test
    fun optionalTransportFailureIsOmitted() = runTest {
        assertAssetFailure(required = false, name = "optional-transport") {
            throw IOException("offline")
        }
    }

    @Test
    fun optionalDigestMismatchIsOmitted() = runTest {
        assertAssetFailure(required = false, name = "optional-digest") { assetBytes ->
            HttpTransport.Response(
                200,
                assetBytes.reversedArray(),
                mapOf("Content-Type" to "image/png"),
            )
        }
    }

    @Test
    fun optionalContentTypeMismatchIsOmitted() = runTest {
        assertAssetFailure(required = false, name = "optional-content-type") { assetBytes ->
            HttpTransport.Response(
                200,
                assetBytes,
                mapOf("Content-Type" to "text/plain"),
            )
        }
    }

    @Test
    fun requiredTransportFailureIsFatal() = runTest {
        assertAssetFailure(
            required = true,
            name = "required-transport",
            expectedReason = ReleaseArtifactAcquisitionException.Reason.TRANSPORT,
        ) {
            throw IOException("offline")
        }
    }

    @Test
    fun optionalServerFailureIsOmitted() = runTest {
        assertAssetFailure(required = false, name = "optional-server") {
            HttpTransport.Response(503, ByteArray(0))
        }
    }

    @Test
    fun requiredServerFailureIsFatal() = runTest {
        assertAssetFailure(
            required = true,
            name = "required-server",
            expectedReason = ReleaseArtifactAcquisitionException.Reason.HTTP_STATUS,
        ) {
            HttpTransport.Response(503, ByteArray(0))
        }
    }

    @Test
    fun optionalShortBodyIsOmitted() = runTest {
        assertAssetFailure(required = false, name = "optional-short-body") {
            HttpTransport.Response(200, ByteArray(0), mapOf("Content-Type" to "image/png"))
        }
    }

    @Test
    fun requiredShortBodyIsFatal() = runTest {
        assertAssetFailure(
            required = true,
            name = "required-short-body",
            expectedReason = ReleaseArtifactAcquisitionException.Reason.SIZE_MISMATCH,
        ) {
            HttpTransport.Response(200, ByteArray(0), mapOf("Content-Type" to "image/png"))
        }
    }

    @Test
    fun cancellationRemainsFatalForAnOptionalAsset() = runTest {
        try {
            assertAssetFailure(required = false, name = "optional-cancellation") {
                throw CancellationException("cancelled")
            }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun optionalAssetNotFoundIsOmitted() = runTest {
        val rivBytes = "optional-riv".encodeToByteArray()
        val assetBytes = "optional-asset".encodeToByteArray()
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
            required = false,
        )
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                if (request.url.path.startsWith("/renders/")) {
                    HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                } else {
                    HttpTransport.Response(404, ByteArray(0))
                }
            },
            cacheDirectory = temporaryFolder.newFolder("optional-not-found"),
        )

        ReleaseArtifactAcquirer(cache).acquire(
            release(riv, assets = listOf(asset)),
            delivery(),
        ).use { acquired ->
            assertEquals(setOf(riv.getValue("key").jsonPrimitive.content), acquired.artifactsByKey.keys)
        }
    }

    @Test
    fun requiredAssetNotFoundFailsClosed() = runTest {
        val rivBytes = "required-riv".encodeToByteArray()
        val assetBytes = "required-asset".encodeToByteArray()
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
            required = true,
        )
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                if (request.url.path.startsWith("/renders/")) {
                    HttpTransport.Response(200, rivBytes, mapOf("Content-Type" to "application/vnd.rive"))
                } else {
                    HttpTransport.Response(404, ByteArray(0))
                }
            },
            cacheDirectory = temporaryFolder.newFolder("required-not-found"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, assets = listOf(asset)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.HTTP_STATUS, failure.reason)
        assertEquals(asset.getValue("key").jsonPrimitive.content, failure.artifactKey)
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
        val requestCount = AtomicInteger()
        lateinit var firstCache: ReleaseArtifactCache
        val transport = HttpTransport {
            requestCount.incrementAndGet()
            requestStarted.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (firstCache.protectionCount(sha256(rivBytes)) < 2 &&
                System.nanoTime() < deadline
            ) {
                Thread.yield()
            }
            assertEquals(2, firstCache.protectionCount(sha256(rivBytes)))
            HttpTransport.Response(
                200,
                rivBytes,
                mapOf("Content-Type" to "application/vnd.rive"),
            )
        }
        val cacheDirectory = temporaryFolder.newFolder("concurrent")
        firstCache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            transport,
            cacheDirectory = cacheDirectory,
        )
        val firstAcquirer = ReleaseArtifactAcquirer(
            firstCache,
        )
        val secondAcquirer = ReleaseArtifactAcquirer(
            ReleaseArtifactCache(
                RuntimeEnvironment.getApplication(),
                transport,
                cacheDirectory = cacheDirectory,
            ),
        )

        val first = async(Dispatchers.IO) { firstAcquirer.acquire(release(riv), delivery()) }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { secondAcquirer.acquire(release(riv), delivery()) }
        val results = awaitAll(first, second)
        try {
            assertEquals(1, requestCount.get())
            assertEquals(results[0].rivFile, results[1].rivFile)
            assertArrayEquals(rivBytes, results[0].rivFile.readBytes())
        } finally {
            results.forEach(AcquiredRelease::close)
        }
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
        try {
            assertEquals(2, acquired.artifactsByKey.size)
            assertTrue(acquired.artifactsByKey.values.all { it.exists() })
            assertEquals(
                listOf(
                    URL("https://cdn.nuxie.test/renders/sha256/${sha256(rivBytes)}.riv"),
                    URL("https://cdn.nuxie.test/assets/sha256/${sha256(assetBytes)}.png"),
                ),
                requested,
            )
        } finally {
            acquired.close()
        }
    }

    @Test
    fun parkedRunPinSurvivesAcquiredLeaseAndCacheReconstruction() = runTest {
        val retainedBytes = "retained".encodeToByteArray()
        val cacheDirectory = temporaryFolder.newFolder("parked-pin")
        val cache = ReleaseArtifactCache(RuntimeEnvironment.getApplication(), HttpTransport {
            HttpTransport.Response(200, retainedBytes, mapOf("Content-Type" to "application/vnd.rive"))
        }, maxTotalBytes = 12, cacheDirectory = cacheDirectory)
        val riv = artifact("renders/sha256/${sha256(retainedBytes)}.riv", retainedBytes, "application/vnd.rive")
        val acquired = ReleaseArtifactAcquirer(cache).acquire(release(riv), delivery())
        cache.retainForRun("customer/journey/generation", listOf(sha256(retainedBytes))).close()
        acquired.close()
        acquired.rivFile.setLastModified(System.currentTimeMillis() - 60_000)

        var outsider = "outsider".encodeToByteArray()
        val restarted = ReleaseArtifactCache(RuntimeEnvironment.getApplication(), HttpTransport {
            HttpTransport.Response(200, outsider)
        }, maxTotalBytes = 12, cacheDirectory = cacheDirectory)
        restarted.acquire("first", sha256(outsider), 8, 8, "https://cdn.nuxie.test/")
        assertArrayEquals(retainedBytes, acquired.rivFile.readBytes())

        restarted.releaseRun("customer/journey/generation")
        outsider = "another!".encodeToByteArray()
        restarted.acquire("second", sha256(outsider), 8, 8, "https://cdn.nuxie.test/")
        assertEquals(false, acquired.rivFile.exists())
    }

    @Test
    fun acquiredReleaseRemainsProtectedUntilItsLeaseIsClosed() = runTest {
        val rivBytes = "12345678".encodeToByteArray()
        val firstOutsiderBytes = "abcdefgh".encodeToByteArray()
        val secondOutsiderBytes = "ABCDEFGH".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val cacheDirectory = temporaryFolder.newFolder("consumption-lease")
        val releaseCache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                HttpTransport.Response(
                    200,
                    rivBytes,
                    mapOf("Content-Type" to "application/vnd.rive"),
                )
            },
            maxTotalBytes = 12,
            cacheDirectory = cacheDirectory,
        )
        var outsiderBytes = firstOutsiderBytes
        val pruningCache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { HttpTransport.Response(200, outsiderBytes) },
            maxTotalBytes = 12,
            cacheDirectory = cacheDirectory,
        )

        val acquired = ReleaseArtifactAcquirer(releaseCache).acquire(release(riv), delivery())
        try {
            acquired.rivFile.setLastModified(System.currentTimeMillis() - 60_000)
            pruningCache.acquire(
                "outsider-one",
                sha256(firstOutsiderBytes),
                firstOutsiderBytes.size.toLong(),
                firstOutsiderBytes.size.toLong(),
                "https://cdn.nuxie.test/",
            )
            assertTrue(acquired.rivFile.exists())

            acquired.close()
            outsiderBytes = secondOutsiderBytes
            pruningCache.acquire(
                "outsider-two",
                sha256(secondOutsiderBytes),
                secondOutsiderBytes.size.toLong(),
                secondOutsiderBytes.size.toLong(),
                "https://cdn.nuxie.test/",
            )

            assertEquals(false, acquired.rivFile.exists())
        } finally {
            acquired.close()
        }
    }

    @Test
    fun everyArtifactRoleIsValidatedBeforeTheFirstRequest() = runTest {
        val rivBytes = "preflight-riv".encodeToByteArray()
        val assetBytes = "preflight-asset".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val asset = artifact(
            key = "renders/sha256/${sha256(assetBytes)}.riv",
            bytes = assetBytes,
            contentType = "image/png",
            kind = "image",
            required = false,
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

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(asset.getValue("key").jsonPrimitive.content, failure.artifactKey)
        assertEquals(0, requestCount)
    }

    @Test
    fun screenBehaviorScriptCannotClaimARenderKey() = runTest {
        val rivBytes = "script-role-riv".encodeToByteArray()
        val scriptBytes = "script-role-script".encodeToByteArray()
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val script = artifact(
            key = "renders/sha256/${sha256(scriptBytes)}.riv",
            bytes = scriptBytes,
            contentType = "application/octet-stream",
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                error("No request is expected")
            },
            cacheDirectory = temporaryFolder.newFolder("script-role"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, scripts = listOf(script)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
        assertEquals(script.getValue("key").jsonPrimitive.content, failure.artifactKey)
        assertEquals(0, requestCount)
    }

    @Test
    fun oneDigestDeclaredAcrossAssetAndScriptRolesIsRejected() = runTest {
        val rivBytes = "cross-role-riv".encodeToByteArray()
        val sharedBytes = "cross-role-external".encodeToByteArray()
        val digest = sha256(sharedBytes)
        val riv = artifact(
            key = "renders/sha256/${sha256(rivBytes)}.riv",
            bytes = rivBytes,
            contentType = "application/vnd.rive",
        )
        val asset = artifact(
            key = "assets/sha256/$digest.bin",
            bytes = sharedBytes,
            contentType = "application/octet-stream",
            kind = "data",
        )
        val script = artifact(
            key = "screen-behavior/sha256/$digest.bin",
            bytes = sharedBytes,
            contentType = "application/octet-stream",
        )
        var requestCount = 0
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport {
                requestCount += 1
                error("No request is expected")
            },
            cacheDirectory = temporaryFolder.newFolder("cross-role-digest"),
        )

        val failure = acquisitionFailure {
            ReleaseArtifactAcquirer(cache).acquire(
                release(riv, assets = listOf(asset), scripts = listOf(script)),
                delivery(),
            )
        }

        assertEquals(ReleaseArtifactAcquisitionException.Reason.INVALID_DESCRIPTOR, failure.reason)
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
        acquirer.acquire(release(riv), delivery()).use { acquired ->
            assertEquals(ReleaseArtifactAcquisitionException.Reason.TRANSPORT, failure.reason)
            assertEquals(2, requestCount)
            assertArrayEquals(rivBytes, acquired.rivFile.readBytes())
            assertEquals(1, cacheDirectory.list()?.size)
        }
    }

    @Test
    fun cacheInitializationSweepsCrashOrphanedTemporaryFiles() {
        val cacheDirectory = temporaryFolder.newFolder("orphaned-temporary")
        val orphan = File(cacheDirectory, "${"a".repeat(64)}-orphan.tmp")
        orphan.writeBytes("partial".encodeToByteArray())
        orphan.setLastModified(System.currentTimeMillis() - 120_000)

        ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { error("No request is expected") },
            cacheDirectory = cacheDirectory,
        )

        assertEquals(false, orphan.exists())
    }

    @Test
    fun lockedTemporaryIsPreservedAndCountedDuringPrune() {
        val cacheDirectory = temporaryFolder.newFolder("locked-temporary")
        val temporary = File(cacheDirectory, "${"b".repeat(64)}-active.tmp")
        temporary.writeBytes("12345678".encodeToByteArray())
        temporary.setLastModified(System.currentTimeMillis() - 120_000)
        val owner = RandomAccessFile(temporary, "rw")
        val ownership = owner.channel.lock()
        try {
            val content = "abcdefgh".encodeToByteArray()
            val cache = ReleaseArtifactCache(
                RuntimeEnvironment.getApplication(),
                HttpTransport { HttpTransport.Response(200, content) },
                maxTotalBytes = 8,
                cacheDirectory = cacheDirectory,
            )

            val published = cache.acquire(
                "artifact",
                sha256(content),
                content.size.toLong(),
                content.size.toLong(),
                "https://cdn.nuxie.test/",
            )

            assertTrue(temporary.exists())
            assertEquals(false, published.exists())
        } finally {
            ownership.release()
            owner.close()
        }
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
        val acquired = acquisition.await()
        try {
            assertTrue(acquired.rivFile.exists())
        } finally {
            acquired.close()
        }
    }

    private fun release(
        riv: JsonObject,
        assets: List<JsonObject> = emptyList(),
        scripts: List<JsonObject> = emptyList(),
    ) = AuthenticatedRelease(
        keyId = "test-key",
        descriptorSha256 = "0".repeat(64),
        identity = TEST_IDENTITY,
        descriptorBytes = ByteArray(0),
        descriptor = buildJsonObject {
            put("screenBehaviors", buildJsonArray {
                scripts.forEachIndexed { index, script ->
                    add(buildJsonObject {
                        put("screenId", JsonPrimitive("screen-$index"))
                        put("controls", buildJsonArray { })
                        put("script", buildJsonObject {
                            put("protocol", JsonPrimitive("screen-actions"))
                            put("artifact", script)
                            put("exportedActionIds", buildJsonArray { })
                        })
                    })
                }
            })
            put("render", buildJsonObject {
                put("renderer", JsonPrimitive("rive"))
                put("riv", riv)
                put("screens", buildJsonArray { })
                put("transitions", buildJsonArray { })
                put("textInputs", buildJsonArray { })
                put("assets", buildJsonArray { assets.forEach(::add) })
            })
        },
        releaseSequenceToPromote = null,
    )

    private fun artifact(
        key: String,
        bytes: ByteArray,
        contentType: String,
        declaredSha256: String = sha256(bytes),
        declaredSizeBytes: Int = bytes.size,
        kind: String? = null,
        required: Boolean = true,
    ) = buildJsonObject {
        put("key", JsonPrimitive(key))
        put("sha256", JsonPrimitive(declaredSha256))
        put("sizeBytes", JsonPrimitive(declaredSizeBytes))
        put("contentType", JsonPrimitive(contentType))
        kind?.let {
            put("kind", JsonPrimitive(it))
            put("required", JsonPrimitive(required))
        }
    }

    private suspend fun acquisitionFailure(
        block: suspend () -> Unit,
    ): ReleaseArtifactAcquisitionException = try {
        block()
        throw AssertionError("Expected acquisition to fail")
    } catch (error: ReleaseArtifactAcquisitionException) {
        error
    }

    private suspend fun assertAssetFailure(
        required: Boolean,
        name: String,
        expectedReason: ReleaseArtifactAcquisitionException.Reason? = null,
        response: (ByteArray) -> HttpTransport.Response,
    ) {
        val rivBytes = "$name-riv".encodeToByteArray()
        val assetBytes = "$name-asset".encodeToByteArray()
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
            required = required,
        )
        val cache = ReleaseArtifactCache(
            RuntimeEnvironment.getApplication(),
            HttpTransport { request ->
                if (request.url.path.startsWith("/renders/")) {
                    HttpTransport.Response(
                        200,
                        rivBytes,
                        mapOf("Content-Type" to "application/vnd.rive"),
                    )
                } else {
                    response(assetBytes)
                }
            },
            cacheDirectory = temporaryFolder.newFolder(name),
        )

        if (expectedReason == null) {
            val acquired = ReleaseArtifactAcquirer(cache).acquire(
                release(riv, assets = listOf(asset)),
                delivery(),
            )
            try {
                assertEquals(setOf(riv.getValue("key").jsonPrimitive.content), acquired.artifactsByKey.keys)
            } finally {
                acquired.close()
            }
        } else {
            val failure = acquisitionFailure {
                ReleaseArtifactAcquirer(cache).acquire(
                    release(riv, assets = listOf(asset)),
                    delivery(),
                )
            }
            assertEquals(expectedReason, failure.reason)
        }
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
            releaseCreatedAt = "2026-08-24T00:00:00Z",
            releaseSequence = 1,
        )
    }
}
