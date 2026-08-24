package ai.nuxie.sdk.profile

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.runBlocking
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

    private fun core(transport: FakeTransport): NuxieCore = NuxieCore(
        context = RuntimeEnvironment.getApplication(),
        apiKey = "pk_test_profile",
        environment = NuxieEnvironment.DEVELOPMENT,
        logLevel = LogLevel.NONE,
        beforeSend = null,
        overrides = NuxieCore.Overrides(
            transport = transport,
            nowMillis = { now },
            registerLifecycle = false,
        ),
    )

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
}
