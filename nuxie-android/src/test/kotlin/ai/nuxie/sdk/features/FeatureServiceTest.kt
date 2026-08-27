package ai.nuxie.sdk.features

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.commerce.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.commerce.PurchaseEvidence
import ai.nuxie.sdk.commerce.PurchaseEvidenceStore
import ai.nuxie.sdk.commerce.StoredLocalPurchaseGrant
import ai.nuxie.sdk.commerce.StoredPurchaseState
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
    fun authoritativeAllowRetiresDurableAndCachedRevocationsForAffectedFeature() = runBlocking {
        val store = InMemoryPurchaseEvidenceStore()
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_features_revocation",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = FakeTransport(),
                registerLifecycle = false,
                purchaseEvidenceStore = store,
            ),
        )
        val customer = core.identity.distinctId()
        val scope = core.features.captureAuthoritativeUseScope(customer)
        store.upsert(revokedEvidence(customer, "restored"))
        val grant = listOf(LocalPurchaseGrant("restored", FeatureType.BOOLEAN))
        core.features.applyLocalPurchase(grant, "revoked-token")
        core.features.removePurchase("revoked-token")
        assertFalse(core.features.getCached("restored", null)!!.allowed)

        core.features.applyAuthoritativeUse(
            result = authoritativeResult(
                customerId = customer,
                featureId = "restored",
                unlimited = true,
                balance = null,
                type = FeatureType.BOOLEAN,
            ),
            requestedFeatureId = "restored",
            distinctId = customer,
            entityId = null,
            expectedScope = scope,
        )

        assertTrue(core.features.getCached("restored", null)!!.allowed)
        assertTrue(core.featureInfo.isAllowed("restored"))
        assertTrue(store.load().getValue("revoked-token").localFeatureGrants.isEmpty())
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
        core.features.handleUserChange(customer, "customer-b")
        core.identity.setDistinctId(customer)
        core.features.handleUserChange("customer-b", customer)

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
    fun authoritativeUseRetirementFailureStillFencesOlderInFlightCheck() = runBlocking {
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
        core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_features_retirement_failure",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = transport,
                registerLifecycle = false,
                purchaseEvidenceStore = FailingRevocationStore,
            ),
        )
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
        assertEquals(null, core.features.getCached("exports", null))
        core.stop()
    }

    private fun revokedEvidence(distinctId: String, featureId: String) = PurchaseEvidence(
        purchaseToken = "revoked-token",
        packageName = "com.example.app",
        storeProductIds = listOf("play-product"),
        purchaseState = StoredPurchaseState.PURCHASED,
        syncAttributionDistinctId = distinctId,
        ownerDistinctId = distinctId,
        acknowledged = true,
        firstSeenMillis = now,
        localFeatureGrants = listOf(
            StoredLocalPurchaseGrant(featureId, FeatureType.BOOLEAN.name, false),
        ),
        catalogResolved = true,
        nuxieManaged = true,
        authorityScope = "scope-a",
        revoked = true,
    )

    private object FailingRevocationStore : PurchaseEvidenceStore {
        override fun load(): Map<String, PurchaseEvidence> = emptyMap()
        override fun upsert(evidence: PurchaseEvidence): Boolean = true
        override fun retireRevokedGrants(distinctId: String, featureIds: Set<String>): Boolean = false
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
    fun optimisticPurchaseProjectsOnlyBooleanAndUnlimitedAndRevokesAboveProfile() = runBlocking {
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

        core.features.applyLocalPurchase(
            listOf(
                LocalPurchaseGrant("pro", FeatureType.BOOLEAN),
                LocalPurchaseGrant("exports", FeatureType.METERED),
                LocalPurchaseGrant("unlimited-exports", FeatureType.METERED, unlimited = true),
                LocalPurchaseGrant("credits", FeatureType.CREDIT_SYSTEM),
            ),
            "token-1",
        )

        assertTrue(core.featureInfo.isAllowed("pro"))
        assertFalse(core.featureInfo.isAllowed("exports"))
        assertTrue(core.featureInfo.isAllowed("unlimited-exports"))
        assertFalse(core.featureInfo.isAllowed("credits"))

        core.features.removePurchase("token-1")
        assertFalse(core.featureInfo.isAllowed("pro"))
        assertFalse(core.featureInfo.isAllowed("unlimited-exports"))
        core.stop()
    }

    @Test
    fun cachedAccessUsesRevokedThenLocalPurchaseThenRealTimeThenProfileOrdering() = runBlocking {
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

        val grant = listOf(LocalPurchaseGrant("exports", FeatureType.METERED, unlimited = true))
        core.features.applyLocalPurchase(grant, "local-token")
        assertTrue(core.features.getCached("exports", null)!!.allowed)
        assertTrue(core.features.checkWithCache("exports").allowed)

        assertFalse(core.features.check("exports").allowed)
        assertFalse(core.features.getCached("exports", null)!!.allowed)

        core.features.applyLocalPurchase(grant, "other-token")
        assertTrue(core.features.getCached("exports", null)!!.allowed)
        core.features.removePurchase("other-token")
        core.features.applyLocalPurchase(grant, "new-token")
        assertFalse(core.features.getCached("exports", null)!!.allowed)
        core.stop()
    }

    @Test
    fun profileFetchStartedBeforePurchaseDoesNotEraseTheNewerOptimisticGrant() = runBlocking {
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
        core.features.applyLocalPurchase(
            listOf(LocalPurchaseGrant("exports", FeatureType.METERED, unlimited = true)),
            "mid-fetch-token",
        )
        releaseFetch.countDown()

        assertTrue(refresh.await())
        assertTrue(core.features.getCached("exports", null)!!.allowed)
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
    fun remoteCheckCancelsWhenAnOptimisticGrantLandsMidRequest() = runBlocking {
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
        core.features.applyLocalPurchase(
            listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN)),
            "mid-check-token",
        )
        releaseCheck.countDown()

        assertTrue(runCatching { check.await() }.exceptionOrNull() is CancellationException)
        assertTrue(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun revocationLandingMidForcedRefreshOutranksTheCompletedResponse() = runBlocking {
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
        core.features.applyLocalPurchase(
            listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN)),
            "mid-check-token",
        )

        val check = async(Dispatchers.Default) {
            core.features.checkWithCache("pro", forceRefresh = true)
        }
        assertTrue(checkStarted.await(5, TimeUnit.SECONDS))
        core.features.removePurchase("mid-check-token")
        releaseCheck.countDown()

        assertFalse(check.await().allowed)
        assertFalse(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun purchaseResponseReconcilesItsGrantWithoutReplacingUnrelatedProfileFeatures() = runBlocking {
        val core = core(FakeTransport())
        val customer = core.identity.distinctId()
        core.features.hydrateProfile(
            customer,
            Json.parseToJsonElement(
                profile("""[{"id":"existing","type":"boolean","unlimited":false}]"""),
            ).jsonObject,
        )
        core.features.applyLocalPurchase(
            listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN)),
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
        core.features.removePurchase("token-1")
        assertTrue(core.featureInfo.isAllowed("existing"))
        assertFalse(core.featureInfo.isAllowed("pro"))
        core.stop()
    }

    @Test
    fun userChangeDropsOptimisticPurchaseProjection() = runBlocking {
        val core = core(FakeTransport())
        val oldId = core.identity.distinctId()
        core.features.applyLocalPurchase(
            listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN)),
            "token-1",
        )
        assertTrue(core.featureInfo.isAllowed("pro"))

        core.identity.setDistinctId("customer-2")
        core.features.handleUserChange(oldId, "customer-2")

        assertFalse(core.featureInfo.isAllowed("pro"))
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
