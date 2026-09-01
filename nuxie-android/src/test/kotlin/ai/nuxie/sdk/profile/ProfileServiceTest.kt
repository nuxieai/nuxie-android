package ai.nuxie.sdk.profile

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.experiences.DeviceLegProfileCatalog
import ai.nuxie.sdk.experiences.ExperienceTrustRoots
import ai.nuxie.sdk.experiences.ReleaseHighWaterStore
import ai.nuxie.sdk.experiences.SupportedRuntime
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.identity.IdentityScope
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.segments.SegmentService
import ai.nuxie.sdk.testsupport.FakeTransport
import android.util.Base64
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
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
import kotlinx.coroutines.selects.select
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
        journeyRuntime: (() -> SupportedRuntime?)? = null,
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

    private class LockOrderIdentity(
        private val id: String,
    ) : IdentityProvider {
        class ScopeGate {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val acquired = CompletableDeferred<Unit>()
        }

        private val decisionLock = ReentrantLock()
        private val gateLock = Any()
        private var nextWithCurrentGate: ScopeGate? = null
        private var nextIsCurrentGate: ScopeGate? = null
        val identityLockTimedOut = AtomicBoolean(false)

        fun armNextWithCurrent(): ScopeGate = ScopeGate().also { gate ->
            synchronized(gateLock) { nextWithCurrentGate = gate }
        }

        fun armNextIsCurrent(): ScopeGate = ScopeGate().also { gate ->
            synchronized(gateLock) { nextIsCurrentGate = gate }
        }

        fun disarmIsCurrent(gate: ScopeGate) {
            synchronized(gateLock) {
                if (nextIsCurrentGate === gate) nextIsCurrentGate = null
            }
        }

        override fun distinctId(): String = id

        override fun anonymousId(): String = id

        override fun rawDistinctId(): String? = id

        override val isIdentified: Boolean = true

        override fun captureScope(): IdentityScope {
            decisionLock.lock()
            return try {
                IdentityScope(id, 0L)
            } finally {
                decisionLock.unlock()
            }
        }

        override fun isCurrentScope(scope: IdentityScope): Boolean {
            val gate = synchronized(gateLock) {
                nextIsCurrentGate.also { nextIsCurrentGate = null }
            }
            gate?.started?.complete(Unit)
            if (gate != null) runBlocking { gate.release.await() }
            if (!decisionLock.tryLock(1, TimeUnit.SECONDS)) {
                identityLockTimedOut.set(true)
                return false
            }
            return try {
                scope.distinctId == id && scope.revision == 0L
            } finally {
                decisionLock.unlock()
            }
        }

        override fun <T> withCurrentScope(scope: IdentityScope, block: () -> T): T? {
            val gate = synchronized(gateLock) {
                nextWithCurrentGate.also { nextWithCurrentGate = null }
            }
            gate?.started?.complete(Unit)
            if (gate != null) runBlocking { gate.release.await() }
            decisionLock.lock()
            return try {
                gate?.acquired?.complete(Unit)
                if (scope.distinctId == id && scope.revision == 0L) block() else null
            } finally {
                decisionLock.unlock()
            }
        }
    }

    private class FanoutRecorder {
        val properties = CopyOnWriteArrayList<String>()
        val releases = CopyOnWriteArrayList<String>()
        val features = CopyOnWriteArrayList<String>()
        val facts = CopyOnWriteArrayList<String>()

        fun all(): List<List<String>> = listOf(properties, releases, features, facts)
    }

    private inner class ProfileFixture(
        transport: HttpTransport,
        val distinctId: String,
        localeIdentifier: String,
        clearDisk: Boolean = true,
        deviceLegProfiles: DeviceLegProfileCatalog? = null,
        refreshIntervalMillis: Long = 30L * 60L * 1000L,
    ) {
        private val context = RuntimeEnvironment.getApplication()
        val identity = IdentityService(context).apply { setDistinctId(distinctId) }
        val segments = SegmentService(context)
        val locales = ProfileLocaleSettings(localeIdentifier) { "device_TEST" }
        val fanout = FanoutRecorder()
        private val featureRevision = AtomicLong()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val profileFile = File(context.cacheDir, "nuxie/profiles/$distinctId.json")

        init {
            if (clearDisk) {
                profileFile.delete()
                segments.clearSegments(distinctId)
            }
        }

        val service = ProfileService(
            context = context,
            api = NuxieApi("pk_test_profile_fixture", NuxieEnvironment.DEVELOPMENT, transport),
            identity = identity,
            segments = segments,
            applyUserProperties = { properties ->
                fanout.properties += (properties["snapshot"] as JsonPrimitive).content
            },
            applyJourneyProfile = { _, body -> fanout.releases += body.snapshotLabel() },
            applyJourneyFacts = { _, body ->
                fanout.facts += body.snapshotLabel()
            },
            deviceLegProfiles = deviceLegProfiles,
            stageFeatureProfile = { _, body, _, _, isCurrent ->
                if (isCurrent()) {
                    (body["snapshot"] as? JsonPrimitive)?.content?.let {
                        fanout.features += it
                    }
                }
                null
            },
            captureFeaturePurchaseRevision = { 0L },
            reserveFeatureAuthoritativeRevision = featureRevision::incrementAndGet,
            scope = scope,
            localeSettings = locales,
            nowMillis = { now },
            refreshIntervalMillis = refreshIntervalMillis,
        )

        fun hasDiskProfile(): Boolean = profileFile.exists()

        suspend fun close(deleteDisk: Boolean = true) {
            service.close()
            scope.coroutineContext[Job]?.cancelAndJoin()
            if (deleteDisk) {
                profileFile.delete()
                segments.clearSegments(distinctId)
            }
        }
    }

    private fun profileTransport(
        body: String = """{"segments":[],"userProperties":{"plan":"pro"},""" +
            """"segmentMemberships":{"evaluatedAt":null,"memberships":[""" +
            """{"segmentId":"seg-1","enteredAt":"2026-07-19T12:00:00Z"}]}}""",
        etag: String? = "\"v1\"",
    ): FakeTransport = FakeTransport().apply {
        respond = { request ->
            if (request.url.path == "/profile") {
                if (request.headers["If-None-Match"] == etag && etag != null) {
                    HttpTransport.Response(304, ByteArray(0))
                } else {
                    HttpTransport.Response(
                        200,
                        body.encodeToByteArray(),
                        headers = etag?.let { mapOf("ETag" to it) } ?: emptyMap(),
                    )
                }
            } else {
                HttpTransport.Response(200, ByteArray(0))
            }
        }
    }

    @Test
    fun matchesTheCrossSdkLocaleAdmissionFixture() = runBlocking {
        val fixtureJson = Json.parseToJsonElement(
            File(
                ai.nuxie.sdk.fixtures.FixtureRunner.fixturesRoot(),
                "profile/locale-admission.json",
            ).readText(),
        ).jsonObject
        assertEquals(
            "profile/locale-admission",
            (fixtureJson.getValue("suite") as JsonPrimitive).content,
        )
        assertEquals("1", (fixtureJson.getValue("version") as JsonPrimitive).content)

        for (element in fixtureJson.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val name = (case.getValue("name") as JsonPrimitive).content
            val expect = case.getValue("expect").jsonObject
            val localeScopedAdmitted =
                (expect.getValue("localeScopedAdmitted") as JsonPrimitive).content.toBoolean()

            when ((case.getValue("flow") as JsonPrimitive).content) {
                "network" -> {
                    val fetchLocale = (case.getValue("fetchLocale") as JsonPrimitive).content
                    val changes = case["localeChangesDuringFetch"]?.jsonArray
                        ?.map { (it as JsonPrimitive).content }
                        .orEmpty()
                    val transport = GatedProfileTransport(
                        listOf(profileResponse("fixture"), profileResponse("fixture")),
                    )
                    val fixture = ProfileFixture(
                        transport = transport,
                        distinctId = "fixture_${name.hashCode().toUInt()}",
                        localeIdentifier = fetchLocale,
                    )
                    try {
                        val fetch = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
                        transport.awaitStarted(0)
                        changes.forEach { fixture.service.setLocaleIdentifier(it) }
                        transport.release(0)
                        val admitted = withTimeout(5_000L) { fetch.await() }

                        assertEquals(name, localeScopedAdmitted, admitted)
                        assertEquals(
                            name,
                            localeScopedAdmitted,
                            fixture.service.currentProfile() != null,
                        )
                        assertEquals(
                            name,
                            localeScopedAdmitted,
                            fixture.fanout.releases.isNotEmpty(),
                        )
                        expect["customerScopedCommitted"]?.let {
                            val committed = (it as JsonPrimitive).content.toBoolean()
                            assertEquals(name, committed, fixture.fanout.facts.isNotEmpty())
                            assertEquals(name, committed, fixture.fanout.properties.isNotEmpty())
                        }
                        expect["nextRequestLocale"]?.let { next ->
                            val replacement = async(Dispatchers.Default) {
                                fixture.service.refreshAndWait()
                            }
                            transport.awaitStarted(1)
                            transport.release(1)
                            assertTrue(name, withTimeout(5_000L) { replacement.await() })
                            val body = transport.request(1).body.decodeToString()
                            val locale = Regex("\"locale\":\"([^\"]+)\"")
                                .find(body)?.groupValues?.get(1)
                            assertEquals(name, (next as JsonPrimitive).content, locale)
                        }
                    } finally {
                        transport.releaseAll()
                        fixture.close()
                    }
                }

                "disk" -> {
                    val diskLocale = (case.getValue("diskLocale") as JsonPrimitive).content
                    val effectiveLocale =
                        (case.getValue("effectiveLocale") as JsonPrimitive).content
                    val distinctId = "fixture_disk_${name.hashCode().toUInt()}"
                    val writer = ProfileFixture(
                        transport = profileTransport(
                            body = profileBody("fixture"),
                            etag = null,
                        ),
                        distinctId = distinctId,
                        localeIdentifier = diskLocale,
                    )
                    assertTrue(name, writer.service.refreshAndWait())
                    writer.close(deleteDisk = false)

                    val reader = ProfileFixture(
                        transport = FakeTransport().apply {
                            respond = { throw IOException("offline") }
                        },
                        distinctId = distinctId,
                        localeIdentifier = effectiveLocale,
                        clearDisk = false,
                    )
                    try {
                        reader.service.refreshAndWait()
                        assertEquals(
                            name,
                            localeScopedAdmitted,
                            reader.service.currentProfile() != null,
                        )
                        if ((expect["diskEvicted"] as? JsonPrimitive)?.content?.toBoolean() == true) {
                            assertFalse(name, reader.hasDiskProfile())
                        }
                        if ((expect["segmentsCleared"] as? JsonPrimitive)?.content?.toBoolean() == true) {
                            assertFalse(
                                name,
                                reader.segments.isMember(distinctId, "segment-fixture"),
                            )
                        }
                    } finally {
                        reader.close()
                    }
                }

                else -> error("Unsupported fixture flow in $name")
            }
        }
    }

    private fun profileBody(label: String): String =
        """{"snapshot":"$label","userProperties":{"snapshot":"$label"},"segments":[],"segmentMemberships":{"evaluatedAt":null,"memberships":[{"segmentId":"segment-$label","enteredAt":null}]},"features":[{"id":"feature-$label","type":"boolean","unlimited":false}],"facts":[{"id":"fact-$label","event":"${'$'}journey_superseded","properties":{}}]}"""

    private fun profileResponse(label: String, etag: String? = null) = HttpTransport.Response(
        200,
        profileBody(label).encodeToByteArray(),
        etag?.let { mapOf("ETag" to it) } ?: emptyMap(),
    )

    private fun planeProfileFixture(): Pair<String, SupportedRuntime> {
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
            SupportedRuntime("0.1.0", emptySet(), emptyMap(), 1, 0, "unused", "unused", emptySet())
        } else {
            fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
            val luau = requirements.getValue("luau").jsonObject
            val scene = requirements.getValue("sceneFormat").jsonObject
            val timezone = requirements.getValue("timezoneData").jsonObject
            SupportedRuntime(
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

    private fun JsonObject.snapshotLabel(): String =
        (getValue("snapshot") as JsonPrimitive).content

    @Test
    fun fetchAppliesUserPropertiesAndSegmentMirror() = runBlocking {
        val core = core(profileTransport())
        assertTrue(core.profile.refreshAndWait())

        assertEquals("pro", core.identity.userProperty("plan"))
        assertTrue(core.segments.isMember(core.identity.distinctId(), "seg-1"))
        val membership = core.segments.memberships(core.identity.distinctId()).getValue("seg-1")
        assertEquals(1_784_462_400_000L, membership.enteredAtMillis)
        core.stop()
    }

    @Test
    fun conditionalRefetchUses304AndKeepsTheBody() = runBlocking {
        val transport = profileTransport()
        val core = core(transport)
        assertTrue(core.profile.refreshAndWait())
        assertTrue(core.profile.refreshAndWait())

        val profileRequests = transport.requests.filter { it.url.path == "/profile" }
        assertEquals(2, profileRequests.size)
        assertNull(profileRequests[0].headers["If-None-Match"])
        assertEquals("\"v1\"", profileRequests[1].headers["If-None-Match"])
        assertNotNull(core.profile.currentProfile())
        core.stop()
    }

    @Test
    fun expiredCacheIsEvictedNotServed() = runBlocking {
        val transport = profileTransport()
        val core1 = core(transport)
        assertTrue(core1.profile.refreshAndWait())
        core1.stop()

        // 25 hours later, a new core over the same disk must not serve it.
        now += 25L * 60L * 60L * 1000L
        val offlineTransport = FakeTransport().apply {
            respond = { throw java.io.IOException("offline") }
        }
        val core2 = core(offlineTransport)
        assertFalse(core2.profile.refreshAndWait())
        assertNull(core2.profile.currentProfile())
        core2.stop()
    }

    @Test
    fun freshCacheSurvivesRestartOffline() = runBlocking {
        val transport = profileTransport()
        val core1 = core(transport)
        assertTrue(core1.profile.refreshAndWait())
        core1.stop()

        // 1 hour later offline: the fresh snapshot still serves.
        now += 60L * 60L * 1000L
        val offlineTransport = FakeTransport().apply {
            respond = { throw java.io.IOException("offline") }
        }
        val core2 = core(offlineTransport)
        // Wait for the startup disk load by asking the worker to cycle once.
        core2.profile.refreshAndWait()
        assertNotNull(core2.profile.currentProfile())
        core2.stop()
    }

    @Test
    fun duplicateKeyResponsesAreRejected() = runBlocking {
        val transport = FakeTransport().apply {
            respond = {
                HttpTransport.Response(
                    200,
                    """{"segments":[],"segments":[]}""".encodeToByteArray(),
                )
            }
        }
        val core = core(transport)
        assertFalse(core.profile.refreshAndWait())
        assertNull(core.profile.currentProfile())
        core.stop()
    }

    @Test
    fun planeProfilePublishesAuthenticatedDeviceLegAuthorityAtProfileCommit() = runBlocking {
        val fixture = planeProfileFixture()
        val transport = profileTransport(body = fixture.first, etag = "\"plane-v1\"")
        val core = core(
            transport,
            journeyRuntime = { fixture.second },
        )
        assertTrue(core.profile.refreshAndWait())
        assertTrue(core.profile.refreshAndWait())

        val snapshot = requireNotNull(core.deviceLegProfiles.snapshot(core.identity.distinctId()))
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
    fun canonicalPlaneProfileUsesOnlyExplicitLaunchAndForegroundRefreshSignals() = runBlocking {
        val plane = planeProfileFixture()
        val transport = profileTransport(body = plane.first, etag = "\"plane-v1\"")
        val context = RuntimeEnvironment.getApplication()
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "canonical-sync-points",
            localeIdentifier = "en_US",
            deviceLegProfiles = DeviceLegProfileCatalog(
                trustedKeys = ExperienceTrustRoots.keys(NuxieEnvironment.DEVELOPMENT),
                highWater = ReleaseHighWaterStore(context),
                supportedRuntime = { plane.second },
            ),
            refreshIntervalMillis = 20L,
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
    fun legacyProfileRetainsPeriodicRefreshDuringTransition() = runBlocking {
        val transport = profileTransport(body = profileBody("legacy-periodic-refresh"))
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "legacy-periodic-refresh",
            localeIdentifier = "en_US",
            refreshIntervalMillis = 20L,
        )
        try {
            assertTrue(withTimeout(5_000L) { fixture.service.refreshAndWait() })
            withTimeout(2_000L) {
                while (transport.requests.count { it.url.path == "/profile" } < 2) {
                    delay(5L)
                }
            }
        } finally {
            withTimeout(5_000L) { fixture.close() }
        }
    }

    @Test
    fun cachedPlaneProfileRehydratesAuthenticatedDeviceLegAuthorityOffline() = runBlocking {
        val fixture = planeProfileFixture()
        val context = RuntimeEnvironment.getApplication()
        val distinctId = IdentityService(context).distinctId()
        val profileFile = File(
            context.cacheDir,
            "nuxie/profiles/" + distinctId.map {
                if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_'
            }.joinToString("") + ".json",
        )
        profileFile.delete()

        val writer = core(
            profileTransport(body = fixture.first, etag = "\"plane-v1\""),
            journeyRuntime = { fixture.second },
        )
        assertTrue(writer.profile.refreshAndWait())
        writer.stop()

        val offline = FakeTransport().apply {
            respond = { throw IOException("offline") }
        }
        val reader = core(offline, journeyRuntime = { fixture.second })
        assertFalse(reader.profile.refreshAndWait())
        val snapshot = requireNotNull(reader.deviceLegProfiles.snapshot(distinctId))
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
        val request = AtomicInteger()
        val transport = FakeTransport().apply {
            respond = { httpRequest ->
                if (httpRequest.url.path == "/profile") {
                    val body = when (request.getAndIncrement()) {
                        0 -> fixture.first
                        1 -> badBody
                        else -> profileBody("legacy")
                    }
                    HttpTransport.Response(200, body.encodeToByteArray())
                } else {
                    HttpTransport.Response(200, ByteArray(0))
                }
            }
        }
        val core = core(transport, journeyRuntime = { fixture.second })
        assertTrue(core.profile.refreshAndWait())

        val distinctId = core.identity.distinctId()
        val currentProfile = requireNotNull(core.profile.currentProfile())
        val currentAuthority = requireNotNull(core.deviceLegProfiles.snapshot(distinctId))
        val authenticated = currentAuthority.releasesByDigest.values.single()
        val highWater = ReleaseHighWaterStore(RuntimeEnvironment.getApplication())
        val currentFloor = highWater.floor(authenticated.identity.streamKey)

        assertFalse(core.profile.refreshAndWait())
        assertEquals(currentProfile.body, core.profile.currentProfile()?.body)
        assertEquals(currentAuthority, core.deviceLegProfiles.snapshot(distinctId))
        assertEquals(currentFloor, highWater.floor(authenticated.identity.streamKey))

        assertTrue(core.profile.refreshAndWait())
        assertNull(core.deviceLegProfiles.snapshot(distinctId))
        assertEquals(currentFloor, highWater.floor(authenticated.identity.streamKey))
        core.stop()
    }

    @Test
    fun expiredPlaneProfileDropsDeviceLegAuthorityBeforeOfflineRefresh() = runBlocking {
        val fixture = planeProfileFixture()
        val request = AtomicInteger()
        val transport = FakeTransport().apply {
            respond = { httpRequest ->
                if (httpRequest.url.path != "/profile") {
                    HttpTransport.Response(200, ByteArray(0))
                } else if (request.getAndIncrement() == 0) {
                    HttpTransport.Response(200, fixture.first.encodeToByteArray())
                } else {
                    throw IOException("offline")
                }
            }
        }
        val core = core(transport, journeyRuntime = { fixture.second })
        assertTrue(core.profile.refreshAndWait())
        val distinctId = core.identity.distinctId()
        assertNotNull(core.deviceLegProfiles.snapshot(distinctId))

        now += 25L * 60L * 60L * 1000L
        assertFalse(core.profile.refreshAndWait())
        assertNull(core.profile.currentProfile())
        assertNull(core.deviceLegProfiles.snapshot(distinctId))
        core.stop()
    }

    @Test
    fun emptyMembershipSnapshotClearsTheMirror() = runBlocking {
        val core1 = core(profileTransport(etag = null))
        assertTrue(core1.profile.refreshAndWait())
        assertTrue(core1.segments.isMember(core1.identity.distinctId(), "seg-1"))
        core1.stop()

        val clearingTransport = profileTransport(
            body = """{"segments":[],"segmentMemberships":{"evaluatedAt":null,"memberships":[]}}""",
            etag = null,
        )
        val core2 = core(clearingTransport)
        assertTrue(core2.profile.refreshAndWait())
        assertFalse(core2.segments.isMember(core2.identity.distinctId(), "seg-1"))
        core2.stop()
    }

    @Test
    fun absentMembershipFieldMakesNoClaim() = runBlocking {
        val core1 = core(profileTransport(etag = null))
        assertTrue(core1.profile.refreshAndWait())
        core1.stop()

        val noClaimTransport = profileTransport(body = """{"segments":[]}""", etag = null)
        val core2 = core(noClaimTransport)
        assertTrue(core2.profile.refreshAndWait())
        // Mirror untouched: seg-1 membership survives from the earlier snapshot.
        assertTrue(core2.segments.isMember(core2.identity.distinctId(), "seg-1"))
        core2.stop()
    }

    @Test
    fun newerConcurrentProfileWinsWhenFetchesCompleteInReverseOrder() = runBlocking {
        val transport = GatedProfileTransport(
            listOf(profileResponse("old"), profileResponse("new")),
        )
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "profile_reverse_completion",
            localeIdentifier = "en_US",
        )

        try {
            val older = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(0)
            val newer = async(Dispatchers.Default) {
                fixture.service.transitionObserver.handleUserChange(
                    UserTransitionCoordinator.Kind.IDENTIFY,
                    fixture.distinctId,
                    fixture.distinctId,
                )
            }
            transport.awaitStarted(1)

            transport.release(1)
            withTimeout(5_000L) { newer.await() }
            transport.release(0)

            assertFalse(withTimeout(5_000L) { older.await() })
            assertEquals("new", fixture.service.currentProfile()!!.body.snapshotLabel())
            assertTrue(fixture.segments.isMember(fixture.distinctId, "segment-new"))
            assertFalse(fixture.segments.isMember(fixture.distinctId, "segment-old"))
            fixture.fanout.all().forEach { assertEquals(listOf("new"), it) }
        } finally {
            transport.releaseAll()
            fixture.close()
        }
    }

    @Test
    fun workerRefreshAndTransitionHydrationUseIdentityLocaleProfileLockOrder() = runBlocking {
        val distinctId = "profile_lock_order"
        val transport = GatedProfileTransport(
            listOf(profileResponse("seed"), profileResponse("worker")),
        )
        val identity = LockOrderIdentity(distinctId)
        val context = RuntimeEnvironment.getApplication()
        val segments = SegmentService(context)
        val featureRevision = AtomicLong()
        val armTransitionApply = AtomicBoolean(false)
        val transitionGateReady = CompletableDeferred<LockOrderIdentity.ScopeGate>()
        File(context.cacheDir, "nuxie/profiles/$distinctId.json").delete()
        segments.clearSegments(distinctId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = ProfileService(
            context = context,
            api = NuxieApi("pk_test_profile_lock_order", NuxieEnvironment.DEVELOPMENT, transport),
            identity = identity,
            segments = segments,
            applyUserProperties = {},
            captureFeaturePurchaseRevision = { 0L },
            reserveFeatureAuthoritativeRevision = {
                if (armTransitionApply.compareAndSet(true, false)) {
                    transitionGateReady.complete(identity.armNextWithCurrent())
                }
                featureRevision.incrementAndGet()
            },
            scope = scope,
            localeSettings = ProfileLocaleSettings("en_US") { "device_TEST" },
            nowMillis = { now },
        )
        var transitionGate: LockOrderIdentity.ScopeGate? = null
        var validationGate: LockOrderIdentity.ScopeGate? = null

        try {
            val seed = async(Dispatchers.Default) { service.refreshAndWait() }
            transport.awaitStarted(0)
            transport.release(0)
            assertTrue(withTimeout(5_000L) { seed.await() })

            armTransitionApply.set(true)
            val transition = async(Dispatchers.Default) {
                service.transitionObserver.handleUserChange(
                    UserTransitionCoordinator.Kind.IDENTIFY,
                    distinctId,
                    distinctId,
                )
            }
            val armedTransitionGate = withTimeout(5_000L) { transitionGateReady.await() }
            transitionGate = armedTransitionGate
            withTimeout(5_000L) { armedTransitionGate.started.await() }

            val armedValidationGate = identity.armNextIsCurrent()
            validationGate = armedValidationGate
            val workerRefresh = async(Dispatchers.Default) { service.refreshAndWait() }
            val workerHeldProfileBeforeIdentity = withTimeout(5_000L) {
                select {
                    armedValidationGate.started.onAwait { true }
                    transport.started(1).onAwait { false }
                }
            }
            if (!workerHeldProfileBeforeIdentity) {
                identity.disarmIsCurrent(armedValidationGate)
            }

            armedTransitionGate.release.complete(Unit)
            withTimeout(5_000L) { armedTransitionGate.acquired.await() }
            if (workerHeldProfileBeforeIdentity) {
                armedValidationGate.release.complete(Unit)
            } else {
                withTimeout(5_000L) { transition.await() }
                transport.release(1)
            }

            withTimeout(5_000L) { transition.await() }
            withTimeout(5_000L) { workerRefresh.await() }
            assertFalse(
                "profile lock must never be held while waiting for the identity lock",
                identity.identityLockTimedOut.get(),
            )
        } finally {
            transitionGate?.release?.complete(Unit)
            validationGate?.release?.complete(Unit)
            transport.releaseAll()
            service.close()
            scope.coroutineContext[Job]?.cancelAndJoin()
            File(context.cacheDir, "nuxie/profiles/$distinctId.json").delete()
            segments.clearSegments(distinctId)
        }
    }

    @Test
    fun identityRoundTripRejectsAnInFlightProfileAcrossEveryFanout() = runBlocking {
        val transport = GatedProfileTransport(listOf(profileResponse("stale")))
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "profile_identity_aba",
            localeIdentifier = "en_US",
        )

        try {
            val fetch = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(0)

            fixture.identity.setDistinctId("profile_identity_other")
            fixture.identity.setDistinctId(fixture.distinctId)
            transport.release(0)

            assertFalse(withTimeout(5_000L) { fetch.await() })
            assertNull(fixture.service.currentProfile())
            assertFalse(fixture.segments.isMember(fixture.distinctId, "segment-stale"))
            fixture.fanout.all().forEach { assertTrue(it.isEmpty()) }
        } finally {
            transport.releaseAll()
            fixture.close()
        }
    }

    @Test
    fun supersededResetStillRemovesTheOldIdentityProfileAndSegments() = runBlocking {
        val oldDistinctId = "profile_reset_old"
        val fixture = ProfileFixture(
            transport = profileTransport(body = profileBody("old"), etag = null),
            distinctId = oldDistinctId,
            localeIdentifier = "en_US",
        )

        try {
            assertTrue(fixture.service.refreshAndWait())
            assertTrue(fixture.hasDiskProfile())
            assertTrue(fixture.segments.isMember(oldDistinctId, "segment-old"))

            fixture.identity.setDistinctId("profile_reset_destination")
            fixture.identity.setDistinctId("profile_reset_newer")
            fixture.service.transitionObserver.handleUserChange(
                UserTransitionCoordinator.Kind.RESET,
                oldDistinctId,
                "profile_reset_destination",
            )

            assertFalse(fixture.hasDiskProfile())
            assertFalse(fixture.segments.isMember(oldDistinctId, "segment-old"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun localeChangeRejectsTheOldResponseAndScopesTheReplacementRequest() = runBlocking {
        val transport = GatedProfileTransport(
            listOf(profileResponse("old-locale", etag = "\"en\""), profileResponse("new-locale")),
        )
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "profile_locale_change",
            localeIdentifier = "en_US",
        )

        try {
            val oldFetch = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(0)
            fixture.service.setLocaleIdentifier("fr_FR")
            transport.release(0)

            assertFalse(withTimeout(5_000L) { oldFetch.await() })
            assertNull(fixture.service.currentProfile())
            assertEquals(listOf("old-locale"), fixture.fanout.properties)
            assertTrue(fixture.fanout.releases.isEmpty())
            assertTrue(fixture.fanout.features.isEmpty())
            assertEquals(listOf("old-locale"), fixture.fanout.facts)

            val replacement = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(1)
            assertTrue(transport.request(0).body.decodeToString().contains("\"locale\":\"en_US\""))
            assertTrue(transport.request(1).body.decodeToString().contains("\"locale\":\"fr_FR\""))
            assertNull(transport.request(1).headers["If-None-Match"])
            transport.release(1)

            assertTrue(withTimeout(5_000L) { replacement.await() })
            assertEquals("new-locale", fixture.service.currentProfile()!!.body.snapshotLabel())
            assertEquals(listOf("old-locale", "new-locale"), fixture.fanout.properties)
            assertEquals(listOf("new-locale"), fixture.fanout.releases)
            assertEquals(listOf("new-locale"), fixture.fanout.features)
            assertEquals(listOf("old-locale", "new-locale"), fixture.fanout.facts)
        } finally {
            transport.releaseAll()
            fixture.close()
        }
    }

    @Test
    fun staleLocale304CannotRevalidateThePriorSnapshot() = runBlocking {
        val transport = GatedProfileTransport(
            listOf(
                profileResponse("english", etag = "\"en\""),
                HttpTransport.Response(304, ByteArray(0)),
                profileResponse("french", etag = "\"fr\""),
            ),
        )
        val fixture = ProfileFixture(
            transport = transport,
            distinctId = "profile_locale_304",
            localeIdentifier = "en_US",
        )

        try {
            val initial = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(0)
            transport.release(0)
            assertTrue(withTimeout(5_000L) { initial.await() })

            val revalidation = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(1)
            assertEquals("\"en\"", transport.request(1).headers["If-None-Match"])
            fixture.service.setLocaleIdentifier("fr_FR")
            transport.release(1)

            assertFalse(withTimeout(5_000L) { revalidation.await() })
            assertNull(fixture.service.currentProfile())
            assertEquals(listOf("english", "english"), fixture.fanout.properties)
            assertEquals(listOf("english"), fixture.fanout.releases)
            assertEquals(listOf("english"), fixture.fanout.features)
            assertEquals(listOf("english", "english"), fixture.fanout.facts)

            val replacement = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(2)
            assertNull(transport.request(2).headers["If-None-Match"])
            transport.release(2)
            assertTrue(withTimeout(5_000L) { replacement.await() })

            assertEquals("french", fixture.service.currentProfile()!!.body.snapshotLabel())
            assertEquals(listOf("english", "english", "french"), fixture.fanout.properties)
            assertEquals(listOf("english", "french"), fixture.fanout.releases)
            assertEquals(listOf("english", "french"), fixture.fanout.features)
            assertEquals(listOf("english", "english", "french"), fixture.fanout.facts)
        } finally {
            transport.releaseAll()
            fixture.close()
        }
    }

    @Test
    fun freshDiskProfileForAnotherLocaleIsNotAdmitted() = runBlocking {
        val distinctId = "profile_disk_locale"
        val writer = ProfileFixture(
            transport = profileTransport(body = profileBody("english"), etag = null),
            distinctId = distinctId,
            localeIdentifier = "en_US",
        )
        assertTrue(writer.service.refreshAndWait())
        writer.close(deleteDisk = false)

        val reader = ProfileFixture(
            transport = FakeTransport().apply {
                respond = { throw IOException("offline") }
            },
            distinctId = distinctId,
            localeIdentifier = "fr_FR",
            clearDisk = false,
        )
        try {
            assertFalse(reader.service.refreshAndWait())
            assertNull(reader.service.currentProfile())
            reader.fanout.all().forEach { assertTrue(it.isEmpty()) }
        } finally {
            reader.close()
        }
    }
}
