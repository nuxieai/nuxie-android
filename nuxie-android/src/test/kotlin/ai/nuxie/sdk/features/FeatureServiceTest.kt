package ai.nuxie.sdk.features

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.billing.OptimisticFeatureOverlay
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class FeatureServiceTest {
    private var now = 1_784_462_400_000L
    private val testPurchaseAllowances = mutableMapOf<Pair<NuxieCore, String>, List<FeatureAllowance>>()

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

    private suspend fun applyTestPurchase(
        core: NuxieCore,
        allowances: List<FeatureAllowance>,
        purchaseToken: String,
    ) {
        testPurchaseAllowances[core to purchaseToken] = allowances
        publishTestProjection(core)
    }

    private suspend fun removeTestPurchase(core: NuxieCore, purchaseToken: String) {
        testPurchaseAllowances.remove(core to purchaseToken)
        publishTestProjection(core)
    }

    private suspend fun publishTestProjection(core: NuxieCore) {
        val overlays = linkedMapOf<String, OptimisticFeatureOverlay>()
        testPurchaseAllowances
            .filterKeys { it.first === core }
            .values
            .flatten()
            .forEach { allowance ->
                val next = OptimisticFeatureOverlay(
                    type = allowance.type,
                    unlimited = allowance.unlimited,
                    balanceIncrease = allowance.allowance,
                )
                val current = overlays[allowance.featureId]
                overlays[allowance.featureId] = if (current == null || current.type != next.type) {
                    next
                } else {
                    current.copy(
                        unlimited = current.unlimited || next.unlimited,
                        balanceIncrease = when {
                            current.unlimited || next.unlimited -> null
                            else -> (current.balanceIncrease ?: 0.0) + (next.balanceIncrease ?: 0.0)
                        },
                    )
                }
            }
        core.features.applyOptimisticPurchaseProjection(
            core.identity.distinctId(),
            overlays.takeIf { it.isNotEmpty() },
        )
    }

    private fun featureResponse(
        customerId: String,
        featureId: String,
        requiredBalance: Double,
        allowed: Boolean = true,
        unlimited: Boolean = false,
        balance: String = "5",
        type: String = "metered",
    ): String = """{"customerId":"$customerId","featureId":"$featureId","requiredBalance":$requiredBalance,"code":"allowed","allowed":$allowed,"unlimited":$unlimited,"balance":$balance,"type":"$type"}"""

    private fun authoritativeResult(
        customerId: String,
        featureId: String,
        requiredBalance: Double = 1.0,
        allowed: Boolean = true,
        unlimited: Boolean = false,
        balance: Double? = 1.0,
        type: FeatureType = FeatureType.METERED,
    ) = NuxieApi.FeatureCheckResult(
        customerId = customerId,
        featureId = featureId,
        requiredBalance = requiredBalance,
        code = "allowed",
        allowed = allowed,
        unlimited = unlimited,
        balance = balance,
        type = type,
    )

    @Test
    fun authoritativeUseUpdatesRequestedAndBalanceSourceCachesAndFeatureInfo() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()
        val scope = core.features.captureAuthoritativeUseScope(customer)

        core.features.applyAuthoritativeUse(
            result = authoritativeResult(
                customerId = customer,
                featureId = "credit-wallet",
                requiredBalance = 2.0,
                allowed = false,
                balance = 8.0,
                type = FeatureType.CREDIT_SYSTEM,
            ),
            requestedFeatureId = "exports",
            distinctId = customer,
            entityId = null,
            expectedScope = scope,
        )

        val requested = core.features.getCached("exports", requiredBalance = 2.0, entityId = null)
        val balanceSource = core.features.getCached("credit-wallet", null)
        assertFalse(requested!!.allowed)
        assertEquals(null, requested.balance)
        assertEquals(FeatureType.METERED, requested.type)
        assertTrue(balanceSource!!.allowed)
        assertEquals(8.0, balanceSource.balance!!, 0.0)
        assertEquals(FeatureType.CREDIT_SYSTEM, balanceSource.type)
        assertFalse(core.featureInfo.all.value.getValue("exports").allowed)
        assertTrue(core.featureInfo.all.value.getValue("credit-wallet").allowed)
        core.stop()
    }

    @Test
    fun backendAcknowledgementPublishesAuthorityAndOverlayRetirementAtomically() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()
        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf("credits" to OptimisticFeatureOverlay(FeatureType.CREDIT_SYSTEM, false, 10.0)),
        )
        assertEquals(10.0, core.featureInfo.balance("credits")!!, 0.0)
        val observed = mutableListOf<Double?>()
        core.featureInfo.onFeatureChange = { featureId, _, access, _ ->
            if (featureId == "credits") observed += access.balance
        }
        val scope = core.features.captureAuthoritativeUseScope(customer)

        core.features.applyAuthoritativeUse(
            result = authoritativeResult(
                customerId = customer,
                featureId = "credits",
                balance = 8.0,
                type = FeatureType.CREDIT_SYSTEM,
            ),
            requestedFeatureId = "credits",
            distinctId = customer,
            entityId = null,
            expectedScope = scope,
            reconciledOptimisticProjection = null,
            reconcileOptimisticProjection = true,
        )

        assertEquals(listOf(8.0), observed)
        assertEquals(8.0, core.featureInfo.balance("credits")!!, 0.0)
        core.stop()
    }

    @Test
    fun authoritativeUsageBalanceRecomposesBeneathTheOptimisticOverlay() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile(
                    """[{"id":"credits","type":"creditSystem","balance":5,"unlimited":false}]""",
                ),
            ).jsonObject,
        )
        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf("credits" to OptimisticFeatureOverlay(FeatureType.CREDIT_SYSTEM, false, 10.0)),
        )

        core.features.applyAuthoritativeUsageBalance("credits", balance = 4.0, entityId = null)

        assertEquals(14.0, core.featureInfo.balance("credits")!!, 0.0)
        core.features.applyOptimisticPurchaseProjection(customer, null)
        assertEquals(4.0, core.featureInfo.balance("credits")!!, 0.0)
        core.stop()
    }

    @Test
    fun authoritativeUsageBalanceRevisionFencesAnOlderInFlightCheck() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "credits",
                            requiredBalance = 1.0,
                            balance = "3",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile(
                    """[{"id":"credits","type":"metered","balance":5,"unlimited":false}]""",
                ),
            ).jsonObject,
        )
        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf("credits" to OptimisticFeatureOverlay(FeatureType.METERED, false, 10.0)),
        )

        val olderCheck = async(Dispatchers.Default) { runCatching { core.features.check("credits") } }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        core.features.applyAuthoritativeUsageBalance("credits", balance = 4.0, entityId = null)
        releaseCheck.countDown()

        assertTrue(olderCheck.await().exceptionOrNull() is CancellationException)
        assertEquals(14.0, core.featureInfo.balance("credits")!!, 0.0)
        core.stop()
    }

    @Test
    fun authoritativeUsageBalanceRejectsAStaleIdentityScopeAfterAnIdentityRoundTrip() = runBlocking {
        val core = core(FakeTransport())
        val originalId = core.identity.distinctId()
        val staleScope = core.identity.captureScope()

        core.identity.setDistinctId("customer-b")
        core.featureInfo.publish(core.features.handleUserChange(originalId, "customer-b"))
        core.identity.setDistinctId(originalId)
        core.featureInfo.publish(core.features.handleUserChange("customer-b", originalId))

        val result = runCatching {
            core.features.applyAuthoritativeUsageBalance(
                featureId = "credits",
                balance = 4.0,
                entityId = null,
                expectedScope = staleScope,
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(null, core.featureInfo.all.value["credits"])
        core.stop()
    }

    @Test
    fun authoritativeUsageBalanceRejectsScopeAcrossASameIdIdentityModeChange() = runBlocking {
        val core = core(FakeTransport())
        val anonymousId = core.identity.distinctId()
        core.features.hydrateProfile(
            anonymousId,
            Json.parseToJsonElement(
                profile(
                    """[{"id":"credits","type":"creditSystem","balance":5,"unlimited":false}]""",
                ),
            ).jsonObject,
        )
        val anonymousScope = core.identity.captureScope()

        core.identity.setDistinctId(anonymousId)
        val result = runCatching {
            core.features.applyAuthoritativeUsageBalance(
                featureId = "credits",
                balance = 4.0,
                entityId = null,
                expectedScope = anonymousScope,
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(5.0, core.featureInfo.balance("credits")!!, 0.0)
        core.stop()
    }

    @Test
    fun authoritativeUsagePublicationRunsOutsideTheIdentityFenceAndDropsWhenStale() = runBlocking {
        val core = core(FakeTransport())
        val originalId = core.identity.distinctId()
        core.features.hydrateProfile(
            originalId,
            Json.parseToJsonElement(
                profile(
                    """[{"id":"credits","type":"creditSystem","balance":5,"unlimited":false}]""",
                ),
            ).jsonObject,
        )
        val expectedScope = core.identity.captureScope()
        core.featureInfo.onFeatureChange = { featureId, _, access, _ ->
            if (featureId == "credits" && access.balance == 4.0) {
                core.identity.setDistinctId("customer-b")
                core.featureInfo.publish(core.features.handleUserChange(originalId, "customer-b"))
            }
        }

        core.features.applyAuthoritativeUsageBalance(
            featureId = "credits",
            balance = 4.0,
            entityId = null,
            expectedScope = expectedScope,
        )

        assertEquals("customer-b", core.identity.distinctId())
        assertEquals(null, core.featureInfo.all.value["credits"])
        core.stop()
    }

    @Test
    fun authoritativeUseRevisionGuardsRejectOlderInFlightCheckWrites() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "exports",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "0",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        val customer = core.identity.distinctId()
        val olderCheck = async(Dispatchers.Default) { runCatching { core.features.check("exports") } }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        val scope = core.features.captureAuthoritativeUseScope(customer)

        core.features.applyAuthoritativeUse(
            result = authoritativeResult(customer, "exports", balance = 5.0),
            requestedFeatureId = "exports",
            distinctId = customer,
            entityId = null,
            expectedScope = scope,
        )
        releaseCheck.countDown()

        assertTrue(olderCheck.await().exceptionOrNull() is CancellationException)
        assertEquals(5.0, core.features.getCached("exports", null)!!.balance!!, 0.0)
        core.stop()
    }

    @Test
    fun authoritativeUseScopeGenerationRejectsReturnToOriginalIdentity() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()
        val staleScope = core.features.captureAuthoritativeUseScope(customer)

        core.identity.setDistinctId("customer-b")
        core.featureInfo.publish(core.features.handleUserChange(customer, "customer-b"))
        core.identity.setDistinctId(customer)
        core.featureInfo.publish(core.features.handleUserChange("customer-b", customer))

        val result = runCatching {
            core.features.applyAuthoritativeUse(
                result = authoritativeResult(customer, "exports", balance = 5.0),
                requestedFeatureId = "exports",
                distinctId = customer,
                entityId = null,
                expectedScope = staleScope,
            )
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(null, core.features.getCached("exports", null))
        core.stop()
    }

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
    fun fractionalProfileBalanceIsVisibleAccessButNotDefaultGateAuthority() = runBlocking {
        val core = core(FakeTransport())
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile(
                    """[{"id":"credits","type":"metered","balance":0.5,"unlimited":false}]""",
                ),
            ).jsonObject,
        )

        assertTrue(core.featureInfo.isAllowed("credits"))
        assertEquals(0.5, core.featureInfo.balance("credits")!!, 0.0)
        assertFalse(core.features.getCached("credits", null)!!.allowed)
        core.stop()
    }

    @Test
    fun optimisticPurchaseWidensDescriptorAllowancesAndClearsBackToProfile() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile(
                    """[{"id":"pro","type":"boolean","unlimited":false},{"id":"exports","type":"metered","balance":0,"unlimited":false}]""",
                ),
            ).jsonObject,
        )

        applyTestPurchase(
            core,
            listOf(
                FeatureAllowance("pro", FeatureType.BOOLEAN),
                FeatureAllowance("exports", FeatureType.METERED, allowance = 3.0),
                FeatureAllowance("unlimited-exports", FeatureType.METERED, unlimited = true),
                FeatureAllowance("credits", FeatureType.CREDIT_SYSTEM, allowance = 2.5),
            ),
            "token-1",
        )

        assertTrue(core.featureInfo.isAllowed("pro"))
        assertTrue(core.featureInfo.isAllowed("exports"))
        assertEquals(3.0, core.featureInfo.balance("exports")!!, 0.0)
        assertTrue(core.featureInfo.isAllowed("unlimited-exports"))
        assertTrue(core.featureInfo.isAllowed("credits"))
        assertEquals(2.5, core.featureInfo.balance("credits")!!, 0.0)

        removeTestPurchase(core, "token-1")
        assertTrue(core.featureInfo.isAllowed("pro"))
        assertFalse(core.featureInfo.isAllowed("exports"))
        assertFalse(core.featureInfo.isAllowed("unlimited-exports"))
        core.stop()
    }

    @Test
    fun optimisticOverlayIsDisplayOnlyForCacheFirstChecksAndJourneyGates() = runBlocking {
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/entitled" -> HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "null",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                    "/event" -> HttpTransport.Response(
                        200,
                        """{"status":"ok","payload":{"gate":{"decision":"require_feature","featureId":"pro","policy":"cache_only"}}}"""
                            .encodeToByteArray(),
                    )
                    else -> HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(profile("[]")).jsonObject,
        )
        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )

        assertTrue(core.featureInfo.isAllowed("pro"))
        assertTrue(core.features.getAllCached().isEmpty())
        assertFalse(core.features.checkWithCache("pro").allowed)
        assertTrue(core.featureInfo.isAllowed("pro"))

        val updates = mutableListOf<ai.nuxie.sdk.TriggerUpdate>()
        core.triggers.trigger("moment", null) { updates += it }

        assertEquals(
            ai.nuxie.sdk.FeatureAccessUpdate.Denied,
            (updates.single() as ai.nuxie.sdk.TriggerUpdate.FeatureAccess).update,
        )
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun stagedOptimisticProjectionPublishesOnlyAfterItsCoordinationLockIsReleased() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()

        val publication = core.features.stageOptimisticPurchaseProjection(
            customer,
            mapOf("pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null)),
        )

        assertFalse(core.featureInfo.isAllowed("pro"))
        core.features.publishStaged(publication)
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun cachedAccessStaysAuthoritativeWhileFeatureInfoKeepsTheOverlayVisible() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = "customer",
                            featureId = "wallet",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "0",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        val core = core(transport)
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile("""[{"id":"exports","type":"metered","balance":1,"unlimited":false}]"""),
            ).jsonObject,
        )
        assertTrue(core.features.getCached("exports", null)!!.allowed)

        core.features.check("exports")
        assertFalse(core.features.getCached("exports", null)!!.allowed)

        val allowance = listOf(FeatureAllowance("exports", FeatureType.METERED, unlimited = true))
        applyTestPurchase(core, allowance, "local-token")
        assertFalse(core.features.getCached("exports", null)!!.allowed)
        assertFalse(core.features.checkWithCache("exports").allowed)
        assertTrue(core.featureInfo.isAllowed("exports"))

        assertFalse(core.features.check("exports").allowed)
        assertFalse(core.features.getCached("exports", null)!!.allowed)
        assertTrue(core.featureInfo.isAllowed("exports"))

        applyTestPurchase(core, allowance, "other-token")
        assertFalse(core.features.getCached("exports", null)!!.allowed)
        removeTestPurchase(core, "other-token")
        applyTestPurchase(core, allowance, "new-token")
        assertFalse(core.features.getCached("exports", null)!!.allowed)
        assertTrue(core.featureInfo.isAllowed("exports"))
        core.stop()
    }

    @Test
    fun profileFetchStartedBeforePurchaseDoesNotEraseTheNewerOptimisticOverlay() = runBlocking {
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/profile") {
                    fetchStarted.countDown()
                    assertTrue(releaseFetch.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        profile(
                            """[{"id":"exports","type":"metered","balance":0,"unlimited":false}]""",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, "{}".encodeToByteArray())
                }
            }
        }
        val core = core(transport)

        val refresh = async(Dispatchers.Default) { core.profile.refreshAndWait() }
        assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
        applyTestPurchase(core,
            listOf(FeatureAllowance("exports", FeatureType.METERED, unlimited = true)),
            "mid-fetch-token",
        )
        releaseFetch.countDown()

        assertTrue(refresh.await())
        assertFalse(core.features.getCached("exports", null)!!.allowed)
        assertTrue(core.featureInfo.isAllowed("exports"))
        core.stop()
    }

    @Test
    fun profileFetchStartedBeforePurchaseResponseDoesNotEraseTheNewerFeatureUpdate() = runBlocking {
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/profile") {
                    fetchStarted.countDown()
                    assertTrue(releaseFetch.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        profile("[]").encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, "{}".encodeToByteArray())
                }
            }
        }
        val core = core(transport)
        val customer = core.identity.distinctId()

        val refresh = async(Dispatchers.Default) { core.profile.refreshAndWait() }
        assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
        core.features.updateFromPurchase(
            customer,
            Json.parseToJsonElement(
                """{"success":true,"features":[{"id":"internal-pro","ext_id":"pro","type":"boolean","allowed":true,"unlimited":false,"balance":null}]}""",
            ).jsonObject,
            "mid-fetch-token",
        )
        releaseFetch.countDown()

        assertTrue(refresh.await())
        assertTrue(core.features.getCached("pro", null)!!.allowed)
        core.stop()
    }

    @Test
    fun profileFetchStartedAfterPurchaseResponseReconcilesTheOlderFeatureUpdate() = runBlocking {
        val core = core(
            FakeTransport().apply {
                respond = { request ->
                    if (request.url.path == "/profile") {
                        HttpTransport.Response(200, profile("[]").encodeToByteArray())
                    } else {
                        HttpTransport.Response(200, "{}".encodeToByteArray())
                    }
                }
            },
        )
        val customer = core.identity.distinctId()
        core.features.updateFromPurchase(
            customer,
            Json.parseToJsonElement(
                """{"success":true,"features":[{"id":"internal-pro","ext_id":"pro","type":"boolean","allowed":true,"unlimited":false,"balance":null}]}""",
            ).jsonObject,
            "before-fetch-token",
        )
        assertTrue(core.features.getCached("pro", null)!!.allowed)

        assertTrue(core.profile.refreshAndWait())

        assertEquals(null, core.features.getCached("pro", null))
        core.stop()
    }

    @Test
    fun remoteCheckCommitsBeneathAnOverlayThatLandsMidRequest() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "0",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)

        val check = async(Dispatchers.Default) { core.features.check("pro") }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "mid-check-token",
        )
        releaseCheck.countDown()

        assertFalse(check.await().allowed)
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun remoteCheckCommitsAfterAnOverlayIsRemovedMidRequest() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = true,
                            balance = "1",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "mid-check-token",
        )

        val check = async(Dispatchers.Default) { core.features.check("pro") }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        removeTestPurchase(core, "mid-check-token")
        releaseCheck.countDown()

        assertTrue(check.await().allowed)
        assertTrue(core.features.getCached("pro", null)!!.allowed)
        core.stop()
    }

    @Test
    fun remoteCheckCancelsWhenPurchaseUpdateLandsMidRequest() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "0",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "mid-check-token",
        )

        val check = async(Dispatchers.Default) { core.features.check("pro") }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        core.features.updateFromPurchase(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                """{"success":true,"features":[{"id":"internal-pro","ext_id":"pro","type":"boolean","allowed":true,"unlimited":false,"balance":null}]}""",
            ).jsonObject,
            "mid-check-token",
        )
        releaseCheck.countDown()

        assertTrue(runCatching { check.await() }.exceptionOrNull() is CancellationException)
        assertTrue(core.features.getCached("pro", null)!!.allowed)
        core.stop()
    }

    @Test
    fun remoteCheckStillCancelsAfterHydrationRetiresTheMidRequestPurchaseUpdate() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "null",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)

        val check = async(Dispatchers.Default) { core.features.check("pro") }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        core.features.updateFromPurchase(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                """{"success":true,"features":[{"id":"internal-pro","ext_id":"pro","type":"boolean","allowed":true,"unlimited":false,"balance":null}]}""",
            ).jsonObject,
            "mid-check-token",
        )
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile("""[{"id":"pro","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
        )
        releaseCheck.countDown()

        assertTrue(runCatching { check.await() }.exceptionOrNull() is CancellationException)
        assertTrue(core.features.getCached("pro", null)!!.allowed)
        core.stop()
    }

    @Test
    fun profileSnapshotRevisionFencesAnOlderRemoteCheck() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "null",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)

        val check = async(Dispatchers.Default) { core.features.check("pro") }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "mid-check-token",
        )
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile("""[{"id":"pro","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
        )
        releaseCheck.countDown()

        assertTrue(runCatching { check.await() }.exceptionOrNull() is CancellationException)
        assertTrue(core.features.getCached("pro", null)!!.allowed)
        core.stop()
    }

    @Test
    fun newerProfileRevisionFencesAnOlderProfileCommit() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()
        val purchaseRevision = core.features.capturePurchaseRevision()
        val olderRevision = core.features.reserveAuthoritativeRevision()
        val newerRevision = core.features.reserveAuthoritativeRevision()

        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile("""[{"id":"newer","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
            snapshotPurchaseRevision = purchaseRevision,
            snapshotAuthoritativeRevision = newerRevision,
        )
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile("""[{"id":"older","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
            snapshotPurchaseRevision = purchaseRevision,
            snapshotAuthoritativeRevision = olderRevision,
        )

        assertTrue(core.features.getCached("newer", null)!!.allowed)
        assertEquals(null, core.features.getCached("older", null))
        assertTrue(core.featureInfo.isAllowed("newer"))
        assertFalse(core.featureInfo.isAllowed("older"))
        core.stop()
    }

    @Test
    fun profileCommitMarkerFencesAnOlderCheckWhenANewerCheckOnlyMintsAToken() = runBlocking {
        val checks = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    when (checks.incrementAndGet()) {
                        1 -> {
                            firstStarted.countDown()
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                        }
                        2 -> {
                            secondStarted.countDown()
                            assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
                        }
                    }
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "null",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        val customer = core.identity.distinctId()

        val first = async(Dispatchers.Default) { runCatching { core.features.check("pro") } }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val profileRevision = core.features.reserveAuthoritativeRevision()
        val second = async(Dispatchers.Default) { core.features.check("pro") }
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile("""[{"id":"pro","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
            snapshotPurchaseRevision = core.features.capturePurchaseRevision(),
            snapshotAuthoritativeRevision = profileRevision,
        )

        releaseFirst.countDown()

        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertTrue(core.features.getCached("pro", null)!!.allowed)
        assertTrue(core.featureInfo.isAllowed("pro"))

        releaseSecond.countDown()
        assertFalse(second.await().allowed)
        core.stop()
    }

    @Test
    fun cacheFirstSupersessionDoesNotFallBackToThePreRequestProfile() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "null",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        val customer = core.identity.distinctId()
        val allowedProfile = Json.parseToJsonElement(
            profile("""[{"id":"pro","type":"boolean","unlimited":false}]"""),
        ).jsonObject
        core.features.hydrateProfile(customer, allowedProfile)

        val check = async(Dispatchers.Default) {
            core.features.checkWithCache("pro", forceRefresh = true)
        }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "mid-check-token",
        )
        core.features.hydrateProfile(customer, allowedProfile)
        releaseCheck.countDown()

        assertTrue(check.await().allowed)
        assertTrue(core.features.getCached("pro", null)!!.allowed)
        core.stop()
    }

    @Test
    fun purchaseMutationForAnotherFeatureDoesNotSupersedeRemoteCheck() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "exports",
                            requiredBalance = 1.0,
                            balance = "3",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)

        val check = async(Dispatchers.Default) { core.features.check("exports") }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "unrelated-token",
        )
        releaseCheck.countDown()

        assertEquals(3.0, check.await().balance!!, 0.0)
        assertTrue(core.features.getCached("exports", null)!!.allowed)
        core.stop()
    }

    @Test
    fun globalPurchaseWidensFeatureInfoWithoutSupersedingEntityScopedAuthority() = runBlocking {
        val checksStarted = CountDownLatch(2)
        val releaseChecks = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checksStarted.countDown()
                    assertTrue(releaseChecks.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "exports",
                            requiredBalance = 1.0,
                            balance = "3",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile(
                    """[{"id":"exports","type":"metered","balance":0,"unlimited":false,"entities":{"other-project":{"balance":0}}}]""",
                ),
            ).jsonObject,
        )

        val remote = async(Dispatchers.Default) {
            core.features.check("exports", entityId = "remote-project")
        }
        val cacheFirst = async(Dispatchers.Default) {
            core.features.checkWithCache(
                "exports",
                entityId = "cache-first-project",
                forceRefresh = true,
            )
        }
        assertTrue(checksStarted.await(5, TimeUnit.SECONDS))
        applyTestPurchase(core,
            listOf(FeatureAllowance("exports", FeatureType.METERED, unlimited = true)),
            "global-purchase",
        )
        releaseChecks.countDown()

        assertEquals(3.0, remote.await().balance!!, 0.0)
        assertEquals(3.0, cacheFirst.await().balance!!, 0.0)
        val cachedEntityChecks = listOf(
            core.features.getCached("exports", "remote-project")!!,
            core.features.getCached("exports", "cache-first-project")!!,
        )
        assertEquals(2, cachedEntityChecks.count { it.allowed })
        assertTrue(cachedEntityChecks.none { it.unlimited })
        assertTrue(core.featureInfo.all.value.getValue("exports").unlimited)
        core.stop()
    }

    @Test
    fun entityScopedCheckUpdatesPublicReactiveFeatureState() = runBlocking {
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "null",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile("""[{"id":"pro","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
        )
        assertTrue(core.featureInfo.isAllowed("pro"))

        val entityAccess = core.features.check("pro", entityId = "workspace-1")

        assertFalse(entityAccess.allowed)
        assertFalse(core.featureInfo.all.value.getValue("pro").allowed)
        assertFalse(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun overlayRecompositionRetainsTheLatestEntityScopedAuthority() = runBlocking {
        val checks = AtomicInteger()
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "credits",
                            requiredBalance = 1.0,
                            balance = if (checks.incrementAndGet() == 1) "2" else "3",
                            type = "creditSystem",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile(
                    """[{"id":"credits","type":"creditSystem","balance":5,"unlimited":false}]""",
                ),
            ).jsonObject,
        )

        core.features.check("credits", entityId = "workspace-1")
        assertEquals(2.0, core.featureInfo.balance("credits")!!, 0.0)
        core.features.check("credits", entityId = "workspace-2")
        assertEquals(3.0, core.featureInfo.balance("credits")!!, 0.0)

        core.features.applyOptimisticPurchaseProjection(
            customer,
            mapOf(
                "credits" to OptimisticFeatureOverlay(
                    FeatureType.CREDIT_SYSTEM,
                    unlimited = false,
                    balanceIncrease = 10.0,
                ),
            ),
        )
        assertEquals(13.0, core.featureInfo.balance("credits")!!, 0.0)

        core.features.applyOptimisticPurchaseProjection(customer, null)
        assertEquals(3.0, core.featureInfo.balance("credits")!!, 0.0)
        core.stop()
    }

    @Test
    fun entityScopedDenialCannotNarrowAnActiveOptimisticOverlay() = runBlocking {
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = false,
                            balance = "null",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(profile("[]")).jsonObject,
        )
        applyTestPurchase(
            core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "entity-overlay-token",
        )

        val authoritative = core.features.check("pro", entityId = "workspace-1")

        assertFalse(authoritative.allowed)
        assertTrue(core.featureInfo.isAllowed("pro"))
        assertEquals(FeatureInfo.State.Reconciling, core.featureInfo.state.value)
        core.stop()
    }

    @Test
    fun staleEntityResultKeepsTheNewerSameEntityCommitAfterPurchaseRevisionChanges() = runBlocking {
        val olderCheckStarted = CountDownLatch(1)
        val releaseOlderCheck = CountDownLatch(1)
        val checks = AtomicInteger()
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    val balance = if (checks.incrementAndGet() == 1) {
                        olderCheckStarted.countDown()
                        assertTrue(releaseOlderCheck.await(5, TimeUnit.SECONDS))
                        "1"
                    } else {
                        "2"
                    }
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "exports",
                            requiredBalance = 1.0,
                            balance = balance,
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        core.features.updateFromPurchase(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                """{"success":true,"features":[{"id":"exports","type":"metered","allowed":true,"unlimited":true,"balance":null}]}""",
            ).jsonObject,
            "purchase-before-checks",
        )

        val older = async(Dispatchers.Default) {
            core.features.checkWithCache(
                "exports",
                entityId = "project-a",
                forceRefresh = true,
            )
        }
        assertTrue(olderCheckStarted.await(5, TimeUnit.SECONDS))
        assertEquals(
            2.0,
            core.features.checkWithCache(
                "exports",
                entityId = "project-a",
                forceRefresh = true,
            ).balance!!,
            0.0,
        )
        removeTestPurchase(core, "purchase-before-checks")
        releaseOlderCheck.countDown()

        assertEquals(2.0, older.await().balance!!, 0.0)
        assertEquals(2.0, core.features.getCached("exports", "project-a")!!.balance!!, 0.0)
        core.stop()
    }

    @Test
    fun overlayRemovalLetsTheCompletedAuthoritativeResponseStand() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "pro",
                            requiredBalance = 1.0,
                            allowed = true,
                            balance = "1",
                            type = "boolean",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "mid-check-token",
        )

        val check = async(Dispatchers.Default) {
            core.features.checkWithCache("pro", forceRefresh = true)
        }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        removeTestPurchase(core, "mid-check-token")
        releaseCheck.countDown()

        assertTrue(check.await().allowed)
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun purchaseResponseReconcilesItsAccessWithoutReplacingUnrelatedProfileFeatures() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile("""[{"id":"existing","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
        )
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "token-1",
        )

        core.features.updateFromPurchase(
            customer,
            Json.parseToJsonElement(
                """{"success":true,"features":[{"id":"internal-pro","ext_id":"pro","type":"boolean","allowed":true,"unlimited":false,"balance":null}]}""",
            ).jsonObject,
            "token-1",
        )

        assertTrue(core.featureInfo.isAllowed("existing"))
        assertTrue(core.featureInfo.isAllowed("pro"))
        removeTestPurchase(core, "token-1")
        assertTrue(core.featureInfo.isAllowed("existing"))
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun userChangeDropsOptimisticPurchaseProjection() = runBlocking {
        val core = core(FakeTransport())
        val oldId = core.identity.distinctId()
        applyTestPurchase(core,
            listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            "token-1",
        )
        assertTrue(core.featureInfo.isAllowed("pro"))

        core.identity.setDistinctId("customer-2")
        core.featureInfo.publish(core.features.handleUserChange(oldId, "customer-2"))

        assertFalse(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun userChangePublishesTheDestinationCustomersRetainedProjection() = runBlocking {
        val core = core(FakeTransport())
        val owner = core.identity.distinctId()
        val retainedProjection = mapOf(
            "pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null),
        )
        core.features.applyOptimisticPurchaseProjection(owner, retainedProjection)
        assertTrue(core.featureInfo.isAllowed("pro"))

        core.identity.setDistinctId("customer-2")
        core.featureInfo.publish(
            core.features.handleUserChange(owner, "customer-2", destinationProjection = null),
        )
        assertFalse(core.featureInfo.isAllowed("pro"))

        core.identity.setDistinctId(owner)
        core.featureInfo.publish(
            core.features.handleUserChange(
                "customer-2",
                owner,
                destinationProjection = retainedProjection,
            ),
        )

        assertTrue(core.featureInfo.isAllowed("pro"))
        assertEquals(FeatureInfo.State.Unknown, core.featureInfo.state.value)
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
        core.featureInfo.publish(core.features.handleUserChange(oldId, "customer-2"))
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
    fun cacheFirstNetworkFallbackReturnsRawTransitiveCreditAccess() = runBlocking {
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "credit-wallet",
                            requiredBalance = 2.0,
                            balance = "5",
                            type = "creditSystem",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile("""[{"id":"exports","type":"metered","balance":0,"unlimited":false}]"""),
            ).jsonObject,
        )

        val access = core.features.checkWithCache("exports", requiredBalance = 2.0)

        assertTrue(access.allowed)
        assertFalse(access.unlimited)
        assertEquals(5.0, access.balance)
        assertEquals(FeatureType.CREDIT_SYSTEM, access.type)
        assertEquals(1, transport.requests.count { it.url.path == "/entitled" })
        core.stop()
    }

    @Test
    fun cacheFirstCheckNeverReturnsTheVisibleOverlayWhenAuthoritySupersedesIt() = runBlocking {
        val checkStarted = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/entitled") {
                    checkStarted.countDown()
                    assertTrue(releaseCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "credit-wallet",
                            requiredBalance = 2.0,
                            balance = "5",
                            type = "creditSystem",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
            }
        }
        core = core(transport)

        val check = async(Dispatchers.Default) {
            core.features.checkWithCache(
                "exports",
                requiredBalance = 2.0,
                forceRefresh = true,
            )
        }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        applyTestPurchase(core,
            listOf(FeatureAllowance("exports", FeatureType.BOOLEAN)),
            "mid-check-token",
        )
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile("""[{"id":"exports","type":"metered","balance":0,"unlimited":false}]"""),
            ).jsonObject,
        )
        assertFalse(core.features.getCached("exports", null)!!.allowed)
        assertTrue(core.featureInfo.isAllowed("exports"))
        releaseCheck.countDown()

        val access = check.await()
        assertTrue(access.allowed)
        assertFalse(access.unlimited)
        assertEquals(5.0, access.balance)
        assertEquals(FeatureType.CREDIT_SYSTEM, access.type)
        assertTrue(core.featureInfo.isAllowed("exports"))
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
    fun missingEntityOnCachedMeteredFeatureReturnsBooleanNotFoundWithoutRemoteCheck() = runBlocking {
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                HttpTransport.Response(
                    200,
                    featureResponse(
                        customerId = core.identity.distinctId(),
                        featureId = "exports",
                        requiredBalance = 1.0,
                        balance = "5",
                    ).encodeToByteArray(),
                )
            }
        }
        core = core(transport)
        core.features.hydrateProfile(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                profile(
                    """[{"id":"exports","type":"metered","balance":5,"unlimited":false,"entities":{"other-project":{"balance":5}}}]""",
                ),
            ).jsonObject,
        )

        val access = core.features.checkWithCache("exports", entityId = "missing-project")

        assertFalse(access.allowed)
        assertFalse(access.unlimited)
        assertEquals(null, access.balance)
        assertEquals(FeatureType.BOOLEAN, access.type)
        assertTrue(transport.requests.none { it.url.path == "/entitled" })
        core.stop()
    }

    @Test
    fun transitiveRemoteResponsePreservesWalletBalanceIncludingZero() = runBlocking {
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

        assertEquals(5.0, core.features.check("exports", requiredBalance = 2.0).balance!!, 0.0)
        assertEquals(0.0, core.features.check("exports", requiredBalance = 2.0).balance)
        assertEquals(2, checks)
        core.stop()
    }

    @Test
    fun newerBalanceSourceCommitSupersedesOlderDependentResponse() = runBlocking {
        val exportsStarted = CountDownLatch(1)
        val walletStarted = CountDownLatch(1)
        val releaseExports = CountDownLatch(1)
        val releaseWallet = CountDownLatch(1)
        val exportsChecks = AtomicInteger()
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                val body = request.body.decodeToString()
                if (body.contains("\"featureId\":\"exports\"")) {
                    val checkNumber = exportsChecks.incrementAndGet()
                    if (checkNumber == 1) {
                        exportsStarted.countDown()
                        assertTrue(releaseExports.await(5, TimeUnit.SECONDS))
                    }
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "wallet",
                            requiredBalance = 2.0,
                            balance = if (checkNumber == 1) "8" else "1",
                            type = "creditSystem",
                        ).encodeToByteArray(),
                    )
                } else {
                    walletStarted.countDown()
                    assertTrue(releaseWallet.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "wallet",
                            requiredBalance = 1.0,
                            balance = "1",
                            type = "creditSystem",
                        ).encodeToByteArray(),
                    )
                }
            }
        }
        core = core(transport)

        val exports = async(Dispatchers.Default) {
            core.features.checkWithCache(
                "exports",
                requiredBalance = 2.0,
                forceRefresh = true,
            )
        }
        assertTrue(exportsStarted.await(5, TimeUnit.SECONDS))
        val wallet = async(Dispatchers.Default) { core.features.check("wallet") }
        assertTrue(walletStarted.await(5, TimeUnit.SECONDS))
        releaseWallet.countDown()
        assertEquals(1.0, wallet.await().balance!!, 0.0)
        releaseExports.countDown()

        assertTrue(runCatching { exports.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1.0, core.features.getCached("wallet", null)!!.balance!!, 0.0)
        assertEquals(
            1.0,
            core.features.checkWithCache("exports", requiredBalance = 2.0).balance!!,
            0.0,
        )
        assertEquals(2, exportsChecks.get())
        core.stop()
    }

    @Test
    fun newerPurchaseAuthoritySupersedesOlderDependentResponse() = runBlocking {
        val exportsStarted = CountDownLatch(1)
        val releaseExports = CountDownLatch(1)
        val exportsChecks = AtomicInteger()
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = respond@{ request ->
                if (request.url.path != "/entitled") {
                    return@respond HttpTransport.Response(200, profile("[]").encodeToByteArray())
                }
                val checkNumber = exportsChecks.incrementAndGet()
                if (checkNumber == 1) {
                    exportsStarted.countDown()
                    assertTrue(releaseExports.await(5, TimeUnit.SECONDS))
                }
                HttpTransport.Response(
                    200,
                    featureResponse(
                        customerId = core.identity.distinctId(),
                        featureId = "wallet",
                        requiredBalance = 2.0,
                        balance = if (checkNumber == 1) "8" else "1",
                        type = "creditSystem",
                    ).encodeToByteArray(),
                )
            }
        }
        core = core(transport)

        val exports = async(Dispatchers.Default) {
            core.features.check("exports", requiredBalance = 2.0)
        }
        assertTrue(exportsStarted.await(5, TimeUnit.SECONDS))
        core.features.updateFromPurchase(
            core.identity.distinctId(),
            Json.parseToJsonElement(
                """{"success":true,"features":[{"id":"internal-wallet","ext_id":"wallet","type":"creditSystem","allowed":true,"unlimited":false,"balance":1}]}""",
            ).jsonObject,
            "newer-wallet-purchase",
        )
        releaseExports.countDown()

        assertTrue(runCatching { exports.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1.0, core.features.getCached("wallet", null)!!.balance!!, 0.0)
        assertEquals(
            1.0,
            core.features.checkWithCache("exports", requiredBalance = 2.0).balance!!,
            0.0,
        )
        assertEquals(2, exportsChecks.get())
        core.stop()
    }

    @Test
    fun supersedingOpaqueDecisionIsAppliedOnlyToItsExactRequiredBalance() = runBlocking {
        val olderCheckStarted = CountDownLatch(1)
        val releaseOlderCheck = CountDownLatch(1)
        lateinit var core: NuxieCore
        val transport = FakeTransport().apply {
            respond = { request ->
                val body = request.body.decodeToString()
                if (body.contains("\"requiredBalance\":2.0")) {
                    olderCheckStarted.countDown()
                    assertTrue(releaseOlderCheck.await(5, TimeUnit.SECONDS))
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "exports",
                            requiredBalance = 2.0,
                            allowed = true,
                            balance = "5",
                        ).encodeToByteArray(),
                    )
                } else {
                    HttpTransport.Response(
                        200,
                        featureResponse(
                            customerId = core.identity.distinctId(),
                            featureId = "wallet",
                            requiredBalance = 3.0,
                            allowed = true,
                            balance = "8",
                            type = "creditSystem",
                        ).encodeToByteArray(),
                    )
                }
            }
        }
        core = core(transport)

        val older = async(Dispatchers.Default) {
            core.features.checkWithCache(
                "exports",
                requiredBalance = 2.0,
                forceRefresh = true,
            )
        }
        assertTrue(olderCheckStarted.await(5, TimeUnit.SECONDS))
        val newer = core.features.checkWithCache(
            "exports",
            requiredBalance = 3.0,
            forceRefresh = true,
        )
        releaseOlderCheck.countDown()

        assertTrue(newer.allowed)
        assertFalse(older.await().allowed)
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
