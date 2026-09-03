package ai.nuxie.sdk.profile

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.experiences.JourneyArtifactManager
import ai.nuxie.sdk.experiences.JourneyProfileCatalog
import ai.nuxie.sdk.experiences.PreparedJourneyArtifacts
import ai.nuxie.sdk.experiences.JourneyTrustRoots
import ai.nuxie.sdk.experiences.JourneyReleaseHighWaterStore
import ai.nuxie.sdk.experiences.JourneyReleaseSupportedRuntime
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import ai.nuxie.sdk.journey.JourneyProfileConsumer
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import ai.nuxie.sdk.testsupport.FakeTransport
import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProfileServiceTest {
    private var now = 1_784_462_400_000L

    private fun core(
        transport: HttpTransport,
        localeIdentifier: String? = null,
        deviceLocaleIdentifier: String = "device_TEST",
        journeyRuntime: (() -> JourneyReleaseSupportedRuntime?)? = null,
    ): NuxieCore = NuxieCore(
        context = RuntimeEnvironment.getApplication(),
        apiKey = "pk_test_profile",
        environment = NuxieEnvironment.DEVELOPMENT,
        logLevel = LogLevel.NONE,
        beforeSend = null,
        localeIdentifier = localeIdentifier,
        overrides = NuxieCore.Overrides(
            transport = transport,
            nowMillis = { now },
            registerLifecycle = false,
            deviceLocaleIdentifier = { deviceLocaleIdentifier },
            journeySupportedRuntime = journeyRuntime,
        ),
    )

    private class GatedProfileTransport(
        private val responses: List<HttpTransport.Response>,
    ) : HttpTransport {
        private class Gate {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
        }

        private val requestIndex = AtomicInteger()
        private val gates = responses.map { Gate() }
        private val recordedRequests = CopyOnWriteArrayList<HttpTransport.Request>()

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            if (request.url.path != "/profile") {
                return HttpTransport.Response(200, ByteArray(0))
            }
            val index = requestIndex.getAndIncrement()
            check(index < gates.size) { "Unexpected profile request ${index + 1}" }
            recordedRequests += request
            gates[index].started.complete(Unit)
            runBlocking { gates[index].release.await() }
            return responses[index]
        }

        suspend fun awaitStarted(index: Int) {
            withTimeout(5_000L) { gates[index].started.await() }
        }

        fun release(index: Int) {
            gates[index].release.complete(Unit)
        }

        fun releaseAll() {
            gates.forEach { it.release.complete(Unit) }
        }

        fun request(index: Int): HttpTransport.Request = recordedRequests[index]

        fun started(index: Int): CompletableDeferred<Unit> = gates[index].started
    }

    private class RuntimePublicationRecorder : JourneyProfileConsumer {
        data class Publication(val kind: String, val generation: Long?)

        val publications = CopyOnWriteArrayList<Publication>()

        override suspend fun profileDidCommit(
            snapshot: JourneyProfileCatalog.Snapshot,
            authority: ProfileDeliveryAuthority,
            distinctId: String,
            admissionGeneration: Long,
            artifacts: PreparedJourneyArtifacts?,
        ) {
            artifacts?.close()
            publications += Publication("commit", admissionGeneration)
        }

        override suspend fun profileDidWithdraw(
            distinctId: String,
            admissionGeneration: Long,
        ) {
            publications += Publication("withdraw", admissionGeneration)
        }

        override suspend fun profileDidClear(
            distinctId: String,
            admissionGeneration: Long,
        ) {
            publications += Publication("clear", admissionGeneration)
        }

        override suspend fun profileDidClearAll() {
            publications += Publication("clear-all", null)
        }
    }

    private class FailingArtifactManager : JourneyArtifactManager {
        var preparations = 0

        override suspend fun prepareJourneys(
            snapshot: JourneyProfileCatalog.Snapshot,
        ): PreparedJourneyArtifacts {
            preparations += 1
            throw IOException("artifact unavailable")
        }

        override fun retainForRun(runKey: String, digests: Set<String>) = Unit
        override fun releaseRun(runKey: String) = Unit
        override fun retainedRunDigests(runKey: String): Set<String>? = null
    }

    private inner class ProfileFixture(
        transport: HttpTransport,
        val distinctId: String,
        localeIdentifier: String,
        apiKey: String = "pk_test_profile_fixture",
        clearDisk: Boolean = true,
        journeyProfiles: JourneyProfileCatalog,
        journeyRuntime: JourneyProfileConsumer? = null,
        journeyArtifacts: JourneyArtifactManager? = null,
        publishFeatureProfile: suspend (FeatureInfo.Mutation?) -> Unit = {},
    ) {
        private val context = RuntimeEnvironment.getApplication()
        val identity = IdentityService(context).apply { setDistinctId(distinctId) }
        val locales = ProfileLocaleSettings(localeIdentifier) { "device_TEST" }
        private val featureRevision = AtomicLong()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val storageScope = ProfileStorageScope(
            apiKey,
            NuxieEnvironment.DEVELOPMENT,
        )
        private val profileFile = profileFile(context, storageScope, distinctId)

        init {
            if (clearDisk) {
                profileFile.delete()
            }
        }

        val service = ProfileService(
            context = context,
            storageScope = storageScope,
            api = NuxieApi(apiKey, NuxieEnvironment.DEVELOPMENT, transport),
            identity = identity,
            journeyProfiles = journeyProfiles,
            journeys = journeyRuntime ?: RuntimePublicationRecorder(),
            journeyArtifacts = journeyArtifacts,
            stageFeatureProfile = { _, _, _, _, isCurrent ->
                isCurrent()
                null
            },
            publishFeatureProfile = publishFeatureProfile,
            captureFeaturePurchaseRevision = { 0L },
            reserveFeatureAuthoritativeRevision = featureRevision::incrementAndGet,
            scope = scope,
            localeSettings = locales,
            nowMillis = { now },
        )

        fun hasDiskProfile(): Boolean = profileFile.exists()

        suspend fun close(deleteDisk: Boolean = true) {
            service.close()
            scope.coroutineContext[Job]?.cancelAndJoin()
            if (deleteDisk) {
                profileFile.delete()
            }
        }
    }

    private fun profileTransport(
        body: String,
        etag: String? = "\"v1\"",
    ): FakeTransport = FakeTransport().apply {
        val responseHeaders = profileHeaders(body, etag)
        respond = { request ->
            if (request.url.path == "/profile") {
                if (request.headers["If-None-Match"] == etag && etag != null) {
                    HttpTransport.Response(304, ByteArray(0), responseHeaders)
                } else {
                    HttpTransport.Response(
                        200,
                        body.encodeToByteArray(),
                        headers = responseHeaders,
                    )
                }
            } else {
                HttpTransport.Response(200, ByteArray(0))
            }
        }
    }

    private fun profileHeaders(body: String, etag: String?): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        etag?.let { headers["ETag"] = it }
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return headers
        if (root["schemaVersion"] != JsonPrimitive("nuxie.journey-plane-profile.v1")) {
            return headers
        }
        val locator = root["releases"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("locator")?.jsonObject
        headers["Nuxie-App-Id"] =
            locator?.get("appId")?.jsonPrimitive?.content ?: "app_golden"
        headers["Nuxie-App-Environment"] =
            locator?.get("environment")?.jsonPrimitive?.content ?: "live"
        return headers
    }

    private fun canonicalProfileResponse(body: String, etag: String): HttpTransport.Response =
        HttpTransport.Response(
            200,
            body.encodeToByteArray(),
            profileHeaders(body, etag),
        )

    private fun profileFile(
        context: Context,
        storageScope: ProfileStorageScope,
        distinctId: String,
    ): File = File(
        storageScope.cacheDirectory(context.cacheDir),
        distinctId.map {
            if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_'
        }.joinToString("") + ".json",
    )


    private fun planeProfileFixture(): Pair<String, JourneyReleaseSupportedRuntime> {
        val fixture = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText(),
        ).jsonObject
        val entry = fixture.getValue("entry").jsonObject
        val locator = entry.getValue("locator").jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(
            Base64.decode(
                envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
                Base64.NO_WRAP,
            ).decodeToString(),
        ).jsonObject
        val requirements = descriptor["requirements"] as? JsonObject
        val runtime = if (requirements == null) {
            JourneyReleaseSupportedRuntime("0.1.0", emptySet(), emptyMap(), 1, 0, "unused", "unused", emptySet())
        } else {
            fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
            val luau = requirements.getValue("luau").jsonObject
            val scene = requirements.getValue("sceneFormat").jsonObject
            val timezone = requirements.getValue("timezoneData").jsonObject
            JourneyReleaseSupportedRuntime(
                currentSdkVersion = requirements.string("minimumSdkVersion"),
                supportedRuntimeRevisions = setOf(requirements.string("runtimeRevision")),
                supportedLuauRevisions = mapOf(
                    luau.string("revision") to luau.getValue("bytecodeVersions").jsonArray
                        .map { it.jsonPrimitive.int }
                        .toSet(),
                ),
                sceneFormatMajor = scene.getValue("major").jsonPrimitive.int,
                sceneFormatMinor = scene.getValue("minor").jsonPrimitive.int,
                timezoneDataRevision = timezone.string("revision"),
                timezoneDataSha256 = timezone.string("sha256"),
                supportedCapabilities = requirements.getValue("requiredCapabilities").jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet(),
            )
        }
        val body = buildJsonObject {
            put("schemaVersion", "nuxie.journey-plane-profile.v1")
            put("status", "ok")
            putJsonObject("delivery") {
                put("renderBaseUrl", "https://renders.example.com/")
                put("assetBaseUrl", "https://assets.example.com/")
            }
            putJsonArray("features") {}
            putJsonObject("facts") {
                putJsonObject("properties") {
                    putJsonObject("ready") {
                        put("present", true)
                        put("value", true)
                    }
                }
                putJsonObject("memberships") {}
                putJsonObject("assignments") {}
            }
            put("releases", JsonArray(listOf(entry)))
            putJsonArray("armedLegs") {
                addJsonObject {
                    putJsonObject("reference") {
                        put("experienceId", locator.getValue("experienceId"))
                        put("versionId", locator.getValue("experienceVersionId"))
                        put("legId", locator.getValue("legId"))
                        put("descriptorSha256", envelope.getValue("descriptorSha256"))
                    }
                    putJsonObject("binding") { put("type", "new") }
                    putJsonObject("entryCondition") { put("type", "app_foregrounded") }
                    putJsonObject("context") {
                        putJsonObject("event") {}
                        putJsonObject("responses") {}
                    }
                }
            }
        }
        return body.toString() to runtime
    }



    @Test
    fun canonical304RefreshesFreshnessWithoutRepublishingTheRuntime() = runBlocking {
        val plane = planeProfileFixture()
        val transport = profileTransport(body = plane.first, etag = "\"plane-v1\"")
        val runtime = RuntimePublicationRecorder()
        val context = RuntimeEnvironment.getApplication()
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "canonical-304-runtime",
            localeIdentifier = "en_US",
            apiKey = "pk_canonical_304_runtime",
            journeyProfiles = JourneyProfileCatalog(
                trustedKeys = JourneyTrustRoots.keys(NuxieEnvironment.DEVELOPMENT),
                highWater = JourneyReleaseHighWaterStore(context),
                supportedRuntime = { plane.second },
            ),
            journeyRuntime = runtime,
        )

        try {
            assertTrue(fixture.service.refreshAndWait())
            assertTrue(fixture.service.refreshAndWait())

            assertEquals(listOf("commit"), runtime.publications.map { it.kind })
            assertEquals(2, transport.requests.count { it.url.path == "/profile" })
        } finally {
            fixture.close()
        }
    }



    @Test
    fun duplicateKeyResponsesAreRejected() = runBlocking {
        val transport = FakeTransport().apply {
            respond = {
                HttpTransport.Response(
                    200,
                    """{"schemaVersion":"nuxie.journey-plane-profile.v1","status":"ok","delivery":{"renderBaseUrl":"https://render.example/","assetBaseUrl":"https://assets.example/"},"features":[],"facts":{"properties":{},"memberships":{},"assignments":{}},"armedLegs":[],"armedLegs":[],"releases":[]}""".encodeToByteArray(),
                )
            }
        }
        val core = core(transport)
        assertFalse(core.profile.refreshAndWait())
        assertNull(core.profile.currentProfile())
        core.stop()
    }

    @Test
    fun planeProfilePublishesAuthenticatedJourneyAuthorityAtProfileCommit() = runBlocking {
        val fixture = planeProfileFixture()
        val transport = profileTransport(body = fixture.first, etag = "\"plane-v1\"")
        val core = core(
            transport,
            journeyRuntime = { fixture.second },
        )
        assertTrue(core.profile.refreshAndWait())
        assertTrue(core.profile.refreshAndWait())

        val snapshot = requireNotNull(core.journeyProfiles.snapshot(core.identity.distinctId()))
        assertEquals(1, snapshot.profile.armedLegs.size)
        assertEquals(1, snapshot.releasesByDigest.size)
        assertEquals(
            snapshot.profile.armedLegs.single().reference.getValue("descriptorSha256"),
            JsonPrimitive(snapshot.releasesByDigest.keys.single()),
        )
        val profileRequests = transport.requests.filter { it.url.path == "/profile" }
        assertEquals("\"plane-v1\"", profileRequests.single { "If-None-Match" in it.headers }
            .headers["If-None-Match"])
        core.stop()
    }

    @Test
    fun planeProfileDoesNotCommitWhenItsArtifactsCannotBePrepared() = runBlocking {
        val plane = planeProfileFixture()
        val catalog = JourneyProfileCatalog(
            trustedKeys = JourneyTrustRoots.keys(NuxieEnvironment.DEVELOPMENT),
            highWater = JourneyReleaseHighWaterStore(RuntimeEnvironment.getApplication()),
            supportedRuntime = { plane.second },
        )
        val runtime = RuntimePublicationRecorder()
        val artifacts = FailingArtifactManager()
        val fixture = ProfileFixture(
            transport = profileTransport(body = plane.first, etag = "\"plane-v1\""),
            distinctId = "artifact-preparation-failure",
            localeIdentifier = "en_US",
            journeyProfiles = catalog,
            journeyRuntime = runtime,
            journeyArtifacts = artifacts,
        )
        try {
            assertFalse(fixture.service.refreshAndWait())
            assertEquals(1, artifacts.preparations)
            assertNull(catalog.snapshot(fixture.distinctId))
            assertTrue(runtime.publications.isEmpty())
            assertNull(fixture.service.currentProfile())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun canonicalPlaneProfileUsesOnlyExplicitLaunchAndForegroundRefreshSignals() = runBlocking {
        val plane = planeProfileFixture()
        val transport = profileTransport(body = plane.first, etag = "\"plane-v1\"")
        val context = RuntimeEnvironment.getApplication()
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "canonical-sync-points",
            localeIdentifier = "en_US",
            journeyProfiles = JourneyProfileCatalog(
                trustedKeys = JourneyTrustRoots.keys(NuxieEnvironment.DEVELOPMENT),
                highWater = JourneyReleaseHighWaterStore(context),
                supportedRuntime = { plane.second },
            ),
        )
        try {
            assertTrue(withTimeout(5_000L) { fixture.service.refreshAndWait() })
            delay(100L)
            assertEquals(
                1,
                transport.requests.count { it.url.path == "/profile" },
            )

            assertTrue(withTimeout(5_000L) { fixture.service.refreshAndWait() })
            assertEquals(
                2,
                transport.requests.count { it.url.path == "/profile" },
            )
        } finally {
            withTimeout(5_000L) { fixture.close() }
        }
    }

    @Test
    fun failedRefreshCompletesItsWaiterAndTheWorkerProcessesTheNextSignal() = runBlocking {
        val plane = planeProfileFixture()
        val publications = AtomicInteger()
        val responses = AtomicInteger()
        val fixture = ProfileFixture(
            transport = FakeTransport().apply {
                respond = { request ->
                    if (request.url.path == "/profile") {
                        canonicalProfileResponse(
                            plane.first,
                            "\"plane-${responses.incrementAndGet()}\"",
                        )
                    } else {
                        HttpTransport.Response(200, ByteArray(0))
                    }
                }
            },
            distinctId = "refresh-processing-failure",
            localeIdentifier = "en_US",
            journeyProfiles = JourneyProfileCatalog(
                trustedKeys = JourneyTrustRoots.keys(NuxieEnvironment.DEVELOPMENT),
                highWater = JourneyReleaseHighWaterStore(RuntimeEnvironment.getApplication()),
                supportedRuntime = { plane.second },
            ),
            publishFeatureProfile = {
                if (publications.incrementAndGet() == 1) {
                    throw IOException("publication failed")
                }
            },
        )

        try {
            assertFalse(withTimeout(5_000L) { fixture.service.refreshAndWait() })
            assertTrue(withTimeout(5_000L) { fixture.service.refreshAndWait() })
            assertEquals(2, publications.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun identityChangeWaitsForTheNextProfileSyncPoint() = runBlocking {
        val plane = planeProfileFixture()
        val transport = GatedProfileTransport(
            listOf(
                canonicalProfileResponse(plane.first, "\"plane-current\""),
            ),
        )
        val runtime = RuntimePublicationRecorder()
        val context = RuntimeEnvironment.getApplication()
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "canonical-runtime-publication-race",
            localeIdentifier = "en_US",
            apiKey = "pk_canonical_runtime_publication_race",
            journeyProfiles = JourneyProfileCatalog(
                trustedKeys = JourneyTrustRoots.keys(NuxieEnvironment.DEVELOPMENT),
                highWater = JourneyReleaseHighWaterStore(context),
                supportedRuntime = { plane.second },
            ),
            journeyRuntime = runtime,
        )

        try {
            fixture.service.transitionObserver.handleUserChange(
                UserTransitionCoordinator.Kind.IDENTIFY,
                fixture.distinctId,
                fixture.distinctId,
            )

            assertFalse(transport.started(0).isCompleted)
            assertNull(fixture.service.currentProfile())

            val foreground = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(0)
            transport.release(0)

            assertTrue(withTimeout(5_000L) { foreground.await() })
            assertNotNull(fixture.service.currentProfile())
            assertEquals(listOf("commit"), runtime.publications.map { it.kind })
        } finally {
            transport.releaseAll()
            fixture.close()
        }
    }


    @Test
    fun cachedPlaneProfileRehydratesAuthenticatedJourneyAuthorityOffline() = runBlocking {
        val fixture = planeProfileFixture()
        val context = RuntimeEnvironment.getApplication()
        val distinctId = IdentityService(context).distinctId()
        val profileFile = profileFile(
            context,
            ProfileStorageScope("pk_test_profile", NuxieEnvironment.DEVELOPMENT),
            distinctId,
        )
        profileFile.delete()

        val writer = core(
            profileTransport(body = fixture.first, etag = "\"plane-v1\""),
            journeyRuntime = { fixture.second },
        )
        assertTrue(writer.profile.refreshAndWait())
        writer.stop()

        now += 25L * 60L * 60L * 1000L
        val offline = FakeTransport().apply {
            respond = { throw IOException("offline") }
        }
        val reader = core(offline, journeyRuntime = { fixture.second })
        assertFalse(reader.profile.refreshAndWait())
        val snapshot = requireNotNull(reader.journeyProfiles.snapshot(distinctId))
        assertEquals(1, snapshot.profile.armedLegs.size)
        assertEquals(1, snapshot.releasesByDigest.size)
        reader.stop()
        profileFile.delete()
        Unit
    }



    @Test
    fun rejectedPlaneProfileReplacementPreservesCurrentAuthorityAndReplayFloor() = runBlocking {
        val fixture = planeProfileFixture()
        val root = Json.parseToJsonElement(fixture.first).jsonObject
        val entry = root.getValue("releases").jsonArray.single().jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val signature = envelope.getValue("signature").jsonObject
        val encoded = signature.getValue("signatureBase64").jsonPrimitive.content
        val changed = (if (encoded.first() == 'A') "B" else "A") + encoded.drop(1)
        val badSignature = JsonObject(signature + ("signatureBase64" to JsonPrimitive(changed)))
        val badEnvelope = JsonObject(envelope + ("signature" to badSignature))
        val badEntry = JsonObject(entry + ("envelope" to badEnvelope))
        val badBody = JsonObject(root + ("releases" to JsonArray(listOf(badEntry)))).toString()
        val emptyBody = JsonObject(
            root + mapOf(
                "releases" to JsonArray(emptyList()),
                "armedLegs" to JsonArray(emptyList()),
            ),
        ).toString()
        val request = AtomicInteger()
        val transport = FakeTransport().apply {
            respond = { httpRequest ->
                if (httpRequest.url.path == "/profile") {
                    val body = when (request.getAndIncrement()) {
                        0 -> fixture.first
                        1 -> badBody
                        else -> emptyBody
                    }
                    canonicalProfileResponse(body, "\"plane-${request.get()}\"")
                } else {
                    HttpTransport.Response(200, ByteArray(0))
                }
            }
        }
        val core = core(transport, journeyRuntime = { fixture.second })
        assertTrue(core.profile.refreshAndWait())

        val distinctId = core.identity.distinctId()
        val currentProfile = requireNotNull(core.profile.currentProfile())
        val currentAuthority = requireNotNull(core.journeyProfiles.snapshot(distinctId))
        val authenticated = currentAuthority.releasesByDigest.values.single()
        val highWater = JourneyReleaseHighWaterStore(RuntimeEnvironment.getApplication())
        val currentFloor = highWater.floor(authenticated.identity.streamKey)

        assertFalse(core.profile.refreshAndWait())
        assertEquals(currentProfile.body, core.profile.currentProfile()?.body)
        assertEquals(currentAuthority, core.journeyProfiles.snapshot(distinctId))
        assertEquals(currentFloor, highWater.floor(authenticated.identity.streamKey))

        assertTrue(core.profile.refreshAndWait())
        val emptyAuthority = requireNotNull(core.journeyProfiles.snapshot(distinctId))
        assertTrue(emptyAuthority.profile.armedLegs.isEmpty())
        assertTrue(emptyAuthority.releasesByDigest.isEmpty())
        assertEquals(currentFloor, highWater.floor(authenticated.identity.streamKey))
        core.stop()
    }

    @Test
    fun expiredPlaneProfileRemainsUsableWhenForegroundRevalidationIsOffline() = runBlocking {
        val fixture = planeProfileFixture()
        val request = AtomicInteger()
        val transport = FakeTransport().apply {
            respond = { httpRequest ->
                if (httpRequest.url.path != "/profile") {
                    HttpTransport.Response(200, ByteArray(0))
                } else if (request.getAndIncrement() == 0) {
                    canonicalProfileResponse(fixture.first, "\"plane-v1\"")
                } else {
                    throw IOException("offline")
                }
            }
        }
        val core = core(transport, journeyRuntime = { fixture.second })
        assertTrue(core.profile.refreshAndWait())
        val distinctId = core.identity.distinctId()
        assertNotNull(core.journeyProfiles.snapshot(distinctId))

        now += 25L * 60L * 60L * 1000L
        assertFalse(core.profile.refreshAndWait())
        assertNotNull(core.profile.currentProfile())
        assertNotNull(core.journeyProfiles.snapshot(distinctId))
        core.stop()
    }




    @Test
    fun supersededCanonicalResponseCannotBindProfileAuthority() = runBlocking {
        val plane = planeProfileFixture()
        val apiKey = "pk_superseded_profile_authority"
        val context = RuntimeEnvironment.getApplication()
        val storageScope = ProfileStorageScope(apiKey, NuxieEnvironment.DEVELOPMENT)
        val bindingFile = context.filesDir.resolve(
            "nuxie/profile-authorities-v1/${storageScope.authorityBindingFilename}",
        )
        bindingFile.delete()
        val goodAuthority = plane.first.let {
            val locator = Json.parseToJsonElement(it).jsonObject
                .getValue("releases").jsonArray.single().jsonObject
                .getValue("locator").jsonObject
            ProfileDeliveryAuthority(
                locator.getValue("appId").jsonPrimitive.content,
                locator.getValue("environment").jsonPrimitive.content,
            )
        }
        val staleHeaders = profileHeaders(plane.first, "\"stale\"").toMutableMap().apply {
            this["Nuxie-App-Id"] = "superseded-app"
        }
        val transport = GatedProfileTransport(
            listOf(
                HttpTransport.Response(
                    200,
                    plane.first.encodeToByteArray(),
                    staleHeaders,
                ),
                canonicalProfileResponse(plane.first, "\"current\""),
            ),
        )
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "profile_authority_race",
            localeIdentifier = "en_US",
            apiKey = apiKey,
            journeyProfiles = JourneyProfileCatalog(
                trustedKeys = JourneyTrustRoots.keys(NuxieEnvironment.DEVELOPMENT),
                highWater = JourneyReleaseHighWaterStore(context),
                supportedRuntime = { plane.second },
            ),
        )

        try {
            val older = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(0)
            fixture.service.transitionObserver.handleUserChange(
                UserTransitionCoordinator.Kind.IDENTIFY,
                fixture.distinctId,
                fixture.distinctId,
            )
            assertFalse(transport.started(1).isCompleted)

            transport.release(0)
            assertFalse(withTimeout(5_000L) { older.await() })
            val newer = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(1)
            transport.release(1)
            assertTrue(withTimeout(5_000L) { newer.await() })

            assertNotNull(fixture.service.currentProfile())
            assertEquals(
                goodAuthority,
                ProfileAuthorityBindingStore(context, storageScope).authority(),
            )
        } finally {
            transport.releaseAll()
            fixture.close()
            bindingFile.delete()
        }
    }






}
