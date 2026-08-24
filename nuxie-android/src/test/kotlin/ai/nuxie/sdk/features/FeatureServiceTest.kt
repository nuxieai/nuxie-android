package ai.nuxie.sdk.features

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FeatureServiceTest {
    private var now = 1_784_462_400_000L

    private fun core(transport: FakeTransport, ttlMillis: Long = 5 * 60 * 1000L): NuxieCore = NuxieCore(
        context = RuntimeEnvironment.getApplication(),
        apiKey = "pk_test_features_${now}",
        environment = NuxieEnvironment.DEVELOPMENT,
        logLevel = LogLevel.NONE,
        beforeSend = null,
        featureCacheTtlMillis = ttlMillis,
        overrides = NuxieCore.Overrides(nowMillis = { now }, transport = transport, registerLifecycle = false),
    )

    private fun profile(features: String): String = """{"segments":[],"features":$features}"""

    @Test
    fun profileHydrationMakesFeatureInfoReadyAndPopulatesTheCache() = runBlocking {
        val core = core(FakeTransport())
        assertEquals(FeatureInfo.State.Unknown, core.featureInfo.state.value)

        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile("""[{"id":"pro","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
        )

        assertEquals(FeatureInfo.State.Ready, core.featureInfo.state.value)
        assertTrue(core.featureInfo.isAllowed("pro"))
        assertTrue(core.features.getCached("pro", null)!!.allowed)
        core.stop()
    }

    @Test
    fun userChangeResetsReadinessUntilTheNewProfileHydrates() = runBlocking {
        val core = core(FakeTransport())
        val oldId = core.identity.distinctId()
        core.features.hydrateProfile(
            oldId,
            Json.parseToJsonElement(profile("""[{"id":"pro","type":"boolean","unlimited":false}]""")).jsonObject,
        )

        core.identity.setDistinctId("customer-2")
        core.features.handleUserChange(oldId, "customer-2")
        assertEquals(FeatureInfo.State.Unknown, core.featureInfo.state.value)
        assertFalse(core.featureInfo.isAllowed("pro"))

        core.features.hydrateProfile(
            "customer-2",
            Json.parseToJsonElement(profile("[]")).jsonObject,
        )
        assertEquals(FeatureInfo.State.Ready, core.featureInfo.state.value)
        core.stop()
    }

    @Test
    fun booleanCacheFirstShortCircuitsTheNetwork() = runBlocking {
        val transport = FakeTransport()
        val core = core(transport)
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(profile("""[{"id":"pro","type":"boolean","unlimited":false}]""")).jsonObject,
        )

        assertTrue(core.features.checkWithCache("pro").allowed)
        assertTrue(transport.requests.none { it.url.path == "/entitled" })
        core.stop()
    }

    @Test
    fun meteredCacheFirstVerifiesInsufficientBalanceRemotely() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/entitled" -> HttpTransport.Response(
                        200,
                        """{"allowed":true,"unlimited":false,"balance":5,"type":"metered"}""".encodeToByteArray(),
                    )
                    else -> HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        val core = core(transport)
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(profile("""[{"id":"exports","type":"metered","balance":0,"unlimited":false}]""")).jsonObject,
        )

        assertTrue(core.features.checkWithCache("exports", requiredBalance = 2.0).allowed)
        val request = transport.requests.single { it.url.path == "/entitled" }
        assertEquals(
            """{"apiKey":"pk_test_features_${now}","customerId":"${core.identity.distinctId()}","featureId":"exports","requiredBalance":2.0}""",
            request.body.decodeToString(),
        )
        core.stop()
    }

    @Test
    fun expiredRealTimeCacheForcesAnotherCheck() = runBlocking {
        var checks = 0
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checks += 1
                    HttpTransport.Response(
                        200,
                        """{"allowed":true,"unlimited":false,"balance":1,"type":"metered"}""".encodeToByteArray(),
                    )
                } else HttpTransport.Response(200, profile("[]").encodeToByteArray())
            }
        }
        val core = core(transport, ttlMillis = 10)

        core.features.checkWithCache("exports")
        now += 11
        core.features.checkWithCache("exports")

        assertEquals(2, checks)
        core.stop()
    }

    @Test
    fun triggerFeatureGateUsesCachedThenCheckedFeatureAccess() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/event" -> HttpTransport.Response(
                        200,
                        """{"status":"ok","payload":{"gate":{"decision":"require_feature","featureId":"pro"}}}"""
                            .encodeToByteArray(),
                    )
                    else -> HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        val core = core(transport)
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(profile("""[{"id":"pro","type":"boolean","unlimited":false}]""")).jsonObject,
        )
        val updates = mutableListOf<ai.nuxie.sdk.TriggerUpdate>()

        core.triggers.trigger("moment", null) { updates += it }

        assertEquals(ai.nuxie.sdk.FeatureAccessUpdate.Allowed, (updates.single() as ai.nuxie.sdk.TriggerUpdate.FeatureAccess).update)
        assertTrue(transport.requests.none { it.url.path == "/entitled" })
        core.stop()
    }
}
