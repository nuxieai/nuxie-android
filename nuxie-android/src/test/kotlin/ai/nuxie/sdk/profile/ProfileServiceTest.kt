package ai.nuxie.sdk.profile

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.segments.SegmentService
import ai.nuxie.sdk.testsupport.FakeTransport
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            applyJourneyFacts = { _, body, isCurrent ->
                if (isCurrent()) fanout.facts += body.snapshotLabel()
            },
            stageFeatureProfile = { _, body, _, _, isCurrent ->
                if (isCurrent()) fanout.features += body.snapshotLabel()
                null
            },
            captureFeaturePurchaseRevision = { 0L },
            reserveFeatureAuthoritativeRevision = featureRevision::incrementAndGet,
            scope = scope,
            localeSettings = locales,
            nowMillis = { now },
        )

        suspend fun close(deleteDisk: Boolean = true) {
            service.close()
            scope.cancel()
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

    private fun profileBody(label: String): String =
        """{"snapshot":"$label","userProperties":{"snapshot":"$label"},"segments":[],"segmentMemberships":{"evaluatedAt":null,"memberships":[{"segmentId":"segment-$label","enteredAt":null}]},"features":[{"id":"feature-$label","type":"boolean","unlimited":false}],"facts":[{"id":"fact-$label","event":"${'$'}journey_superseded","properties":{}}]}"""

    private fun profileResponse(label: String, etag: String? = null) = HttpTransport.Response(
        200,
        profileBody(label).encodeToByteArray(),
        etag?.let { mapOf("ETag" to it) } ?: emptyMap(),
    )

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
            fixture.locales.setLocaleIdentifier("fr_FR")
            transport.release(0)

            assertFalse(withTimeout(5_000L) { oldFetch.await() })
            assertNull(fixture.service.currentProfile())
            fixture.fanout.all().forEach { assertTrue(it.isEmpty()) }

            val replacement = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(1)
            assertTrue(transport.request(0).body.decodeToString().contains("\"locale\":\"en_US\""))
            assertTrue(transport.request(1).body.decodeToString().contains("\"locale\":\"fr_FR\""))
            assertNull(transport.request(1).headers["If-None-Match"])
            transport.release(1)

            assertTrue(withTimeout(5_000L) { replacement.await() })
            assertEquals("new-locale", fixture.service.currentProfile()!!.body.snapshotLabel())
            fixture.fanout.all().forEach { assertEquals(listOf("new-locale"), it) }
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
            fixture.locales.setLocaleIdentifier("fr_FR")
            transport.release(1)

            assertFalse(withTimeout(5_000L) { revalidation.await() })
            assertNull(fixture.service.currentProfile())
            fixture.fanout.all().forEach { assertEquals(listOf("english"), it) }

            val replacement = async(Dispatchers.Default) { fixture.service.refreshAndWait() }
            transport.awaitStarted(2)
            assertNull(transport.request(2).headers["If-None-Match"])
            transport.release(2)
            assertTrue(withTimeout(5_000L) { replacement.await() })

            assertEquals("french", fixture.service.currentProfile()!!.body.snapshotLabel())
            fixture.fanout.all().forEach { assertEquals(listOf("english", "french"), it) }
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
