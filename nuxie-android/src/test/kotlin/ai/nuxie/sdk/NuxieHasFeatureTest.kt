package ai.nuxie.sdk

import ai.nuxie.sdk.billing.OptimisticFeatureOverlay
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.features.FeatureCheckPolicy
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import ai.nuxie.sdk.testsupport.canonicalJourneyProfileResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

@RunWith(RobolectricTestRunner::class)
class NuxieHasFeatureTest {
    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
    }

    @Test
    fun defaultsToCacheFirstWithARequiredBalanceOfOne() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/profile" -> canonicalJourneyProfileResponse(
                        """[{"id":"exports","type":"metered","balance":3.5,"unlimited":false}]""",
                    )
                    else -> error("Unexpected request: ${request.url.path}")
                }
            }
        }
        setup(transport)
        assertTrue(requireNotNull(Nuxie.core).profile.refreshAndWait())

        val access = Nuxie.hasFeature(featureId = "exports")

        assertTrue(access.allowed)
        assertEquals(3.5, access.balance!!, 0.0)
        assertTrue(transport.requests.none { it.url.path == "/entitled" })
    }

    @Test
    fun remoteAlwaysRequestsAuthoritativeAccessWithTheRequestedScope() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/profile" -> canonicalJourneyProfileResponse(
                        """[{"id":"exports","type":"metered","balance":9,"unlimited":false}]""",
                    )
                    "/entitled" -> HttpTransport.Response(
                        200,
                        """{"customerId":"${requireNotNull(Nuxie.core).identity.distinctId()}","featureId":"exports","requiredBalance":2.25,"code":"insufficient_balance","allowed":false,"unlimited":false,"balance":0,"type":"metered"}"""
                            .encodeToByteArray(),
                    )
                    else -> error("Unexpected request: ${request.url.path}")
                }
            }
        }
        setup(transport)
        assertTrue(requireNotNull(Nuxie.core).profile.refreshAndWait())

        val access = Nuxie.hasFeature(
            featureId = "exports",
            requiredBalance = 2.25,
            entityId = "workspace-1",
            policy = FeatureCheckPolicy.REMOTE,
        )

        assertFalse(access.allowed)
        val request = transport.requests.single { it.url.path == "/entitled" }
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertEquals("exports", body.getValue("featureId").jsonPrimitive.content)
        assertEquals(2.25, body.getValue("requiredBalance").jsonPrimitive.double, 0.0)
        assertEquals("workspace-1", body.getValue("entityId").jsonPrimitive.content)
    }

    @Test
    fun remoteReturnsRawTransitiveCreditSystemAccess() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/entitled" -> HttpTransport.Response(
                        200,
                        """{"customerId":"${requireNotNull(Nuxie.core).identity.distinctId()}","featureId":"wallet","requiredBalance":2.0,"code":"allowed","allowed":true,"unlimited":false,"balance":5,"type":"creditSystem"}"""
                            .encodeToByteArray(),
                    )
                    else -> error("Unexpected request: ${request.url.path}")
                }
            }
        }
        setup(transport)

        val access = Nuxie.hasFeature(
            featureId = "exports",
            requiredBalance = 2.0,
            policy = FeatureCheckPolicy.REMOTE,
        )

        assertTrue(access.allowed)
        assertEquals(FeatureType.CREDIT_SYSTEM, access.type)
        assertEquals(5.0, access.balance!!, 0.0)
    }

    @Test
    fun bothPoliciesPropagateFeatureRequestFailures() {
        val failure = IOException("network unavailable")
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/profile" -> canonicalJourneyProfileResponse()
                    "/entitled" -> throw failure
                    else -> error("Unexpected request: ${request.url.path}")
                }
            }
        }
        setup(transport)
        runBlocking { assertTrue(requireNotNull(Nuxie.core).profile.refreshAndWait()) }

        FeatureCheckPolicy.entries.forEach { policy ->
            val thrown = assertThrows(IOException::class.java) {
                runBlocking { Nuxie.hasFeature("uncached", policy = policy) }
            }
            assertSame(failure, thrown)
        }
    }

    @Test
    fun remoteReturnsTheServerResultWithoutFlickeringTheVisibleOverlay() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/profile" -> canonicalJourneyProfileResponse()
                    "/entitled" -> HttpTransport.Response(
                        200,
                        """{"customerId":"${requireNotNull(Nuxie.core).identity.distinctId()}","featureId":"pro","requiredBalance":1.0,"code":"denied","allowed":false,"unlimited":false,"balance":null,"type":"boolean"}"""
                            .encodeToByteArray(),
                    )
                    else -> error("Unexpected request: ${request.url.path}")
                }
            }
        }
        setup(transport)
        val core = requireNotNull(Nuxie.core)
        core.features.applyOptimisticPurchaseProjection(
            core.identity.distinctId(),
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )
        assertTrue(Nuxie.features.isAllowed("pro"))

        val access = Nuxie.hasFeature("pro", policy = FeatureCheckPolicy.REMOTE)

        assertFalse(access.allowed)
        assertTrue(Nuxie.features.isAllowed("pro"))
    }

    @Test
    fun remoteNetworkCallNeverExecutesOnTheCallerThread() = runBlocking {
        val callerThread = AtomicReference<Thread>()
        val networkThread = AtomicReference<Thread>()
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/entitled" -> {
                        networkThread.set(Thread.currentThread())
                        HttpTransport.Response(
                            200,
                            """{"customerId":"${requireNotNull(Nuxie.core).identity.distinctId()}","featureId":"pro","requiredBalance":1.0,"code":"allowed","allowed":true,"unlimited":true,"balance":null,"type":"boolean"}"""
                                .encodeToByteArray(),
                        )
                    }
                    else -> canonicalJourneyProfileResponse()
                }
            }
        }
        setup(transport)
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "has-feature-caller")
        }.asCoroutineDispatcher().use { callerDispatcher ->
            withContext(callerDispatcher) {
                callerThread.set(Thread.currentThread())
                assertTrue(Nuxie.hasFeature("pro", policy = FeatureCheckPolicy.REMOTE).allowed)
            }
        }

        assertTrue(networkThread.get() !== callerThread.get())
    }

    private fun setup(transport: FakeTransport) {
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = transport,
            registerLifecycle = false,
        )
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_has_feature").apply {
                environment = NuxieEnvironment.DEVELOPMENT
                logLevel = LogLevel.NONE
            },
        )
    }
}
