package ai.nuxie.sdk.features

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    private fun featureResponse(
        customerId: String,
        featureId: String,
        requiredBalance: Double,
        allowed: Boolean = true,
        unlimited: Boolean = false,
        balance: String = "5",
        type: String = "metered",
    ): String = """{"customerId":"$customerId","featureId":"$featureId","requiredBalance":$requiredBalance,"code":"allowed","allowed":$allowed,"unlimited":$unlimited,"balance":$balance,"type":"$type"}"""

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
                        featureResponse("customer", "exports", 2.0).encodeToByteArray(),
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
                        featureResponse("customer", "exports", 1.0, balance = "1").encodeToByteArray(),
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
    fun entityScopedLookupFallsBackToGlobalAccessWhenProfileHasNoEntitiesMap() = runBlocking {
        val core = core(FakeTransport())
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile("""[{"id":"pro","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
        )

        assertTrue(core.features.getCached("pro", "project-1")!!.allowed)
        core.stop()
    }

    @Test
    fun entityScopedLookupIsDeniedWhenProfileEntitiesMapLacksTheEntity() = runBlocking {
        val core = core(FakeTransport())
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile(
                    """[{"id":"pro","type":"boolean","unlimited":false,"entities":{"project-2":{"balance":1}}}]""",
                ),
            ).jsonObject,
        )

        assertFalse(core.features.getCached("pro", "project-1")!!.allowed)
        core.stop()
    }

    @Test
    fun transitiveWalletResponseKeepsNonZeroBalanceOpaqueAndPreservesZero() = runBlocking {
        var checks = 0
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checks += 1
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = "unused",
                            featureId = "wallet",
                            requiredBalance = 2.0,
                            balance = if (checks == 1) "5" else "0",
                        ).encodeToByteArray(),
                    )
                } else HttpTransport.Response(200, profile("[]").encodeToByteArray())
            }
        }
        val core = core(transport)

        assertEquals(null, core.features.check("exports", requiredBalance = 2.0).balance)
        assertEquals(0.0, core.features.check("exports", requiredBalance = 2.0).balance)
        assertEquals(2, checks)
        core.stop()
    }

    @Test
    fun opaqueSnapshotIsUsedOnlyForItsExactRequiredBalance() = runBlocking {
        var checks = 0
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checks += 1
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            core.identity.distinctId(),
                            "wallet",
                            if (checks == 1) 2.0 else 3.0,
                            balance = "5",
                        ).encodeToByteArray(),
                    )
                } else HttpTransport.Response(200, profile("[]").encodeToByteArray())
            }
        }
        core = core(transport)

        assertTrue(core.features.checkWithCache("exports", requiredBalance = 2.0).allowed)
        assertTrue(core.features.checkWithCache("exports", requiredBalance = 2.0).allowed)
        assertEquals(1, checks)
        assertTrue(core.features.checkWithCache("exports", requiredBalance = 3.0).allowed)
        assertEquals(2, checks)
        core.stop()
    }

    @Test
    fun laterCheckWinsWhenAnEarlierCheckCompletesAfterIt() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var checks = 0
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                checks += 1
                if (checks == 1) {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(200, featureResponse(core.identity.distinctId(), "exports", 1.0, balance = "1").encodeToByteArray())
                } else {
                    HttpTransport.Response(200, featureResponse(core.identity.distinctId(), "exports", 1.0, balance = "2").encodeToByteArray())
                }
            }
        }
        core = core(transport)

        val first = async(Dispatchers.Default) { core.features.check("exports") }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { core.features.check("exports") }
        second.await()
        releaseFirst.countDown()
        assertTrue(runCatching { first.await() }.isFailure)
        assertEquals(2.0, core.features.getCached("exports", null)!!.balance)
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
