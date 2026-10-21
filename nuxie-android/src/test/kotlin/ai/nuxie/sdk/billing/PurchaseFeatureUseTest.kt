package ai.nuxie.sdk.billing

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.testsupport.featureCommandResponse
import ai.nuxie.sdk.testsupport.FakeTransport
import ai.nuxie.sdk.testsupport.canonicalJourneyProfileResponse
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import ai.nuxie.sdk.identity.CustomerKind

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseFeatureUseTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cores = mutableListOf<NuxieCore>()
    private var coreSequence = 0

    @After
    fun closeCores() {
        cores.asReversed().forEach(NuxieCore::stop)
        cores.clear()
    }

    @Test
    fun identityChangeMidFlightCommitsForTheOwnerThenCancelsTheCaller() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        fixture.core.identity.setDistinctId("customer-b")
        fixture.transport.releaseFirst.countDown()

        val failure = use.await().exceptionOrNull()
        assertTrue(failure is CancellationException)
        val accepted = fixture.store.load().getValue("token-1")
        assertTrue(accepted.synced)
        assertNotNull(accepted.backendSyncedAtMillis)
        assertFalse(fixture.core.featureInfo.isAllowed("credits"))
        fixture.core.stop()
    }

    @Test
    fun evidencePayloadChangeMidFlightDoesNotMarkTheReplacementSynchronized() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        fixture.store.upsert(evidence(packageName = "com.example.replaced"))
        fixture.transport.releaseFirst.countDown()

        assertTrue(use.await().isFailure)
        val replacement = fixture.store.load().getValue("token-1")
        assertEquals("com.example.replaced", replacement.packageName)
        assertFalse(replacement.synced)
        assertEquals(null, replacement.backendSyncedAtMillis)
        fixture.core.stop()
    }

    @Test
    fun missingTokenRestoreDoesNotRevokeAClaimedAtomicSpend() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { fixture.use() }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        // Play no longer reports the purchase.
        fixture.reportedByPlay.clear()
        assertEquals(RestoreResult.NoPurchases, fixture.service.restorePurchases())
        assertNotNull("A claimed spend keeps its owed send", fixture.store.load().getValue("token-1").pendingSend)

        fixture.transport.releaseFirst.countDown()
        assertNotNull(use.await())
        val accepted = fixture.store.load().getValue("token-1")
        assertTrue(accepted.synced)
        assertFalse(accepted.refused)
        fixture.core.stop()
    }

    @Test
    fun restoreWaitsForAClaimedSpendBeforeTakingOverItsEntry() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))

        // A Restore claims the same purchase while the spend holds it.
        val restore = async(Dispatchers.Default) { fixture.service.restorePurchases() }
        val tookOver = withTimeoutOrNull(1_000) {
            while (fixture.store.load().getValue("token-1").pendingSend?.reason != PurchaseSyncReason.RESTORE) {
                delay(10)
            }
        }
        assertNull("The claim waits for the spend", tookOver)
        fixture.transport.releaseFirst.countDown()

        val spent = use.await()
        assertTrue("The server consumed the Feature: ${spent.exceptionOrNull()}", spent.getOrNull() != null)
        restore.await()
        fixture.core.stop()
    }

    @Test
    fun aDueEntryASpendHoldsCountsAFailedAttemptAndMovesOn() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200, outboxRetryMillis = 500)
        // The entry gets no answer, so it is due again in 500 ms.
        fixture.service.recover()
        assertEquals(1, fixture.store.load().getValue("token-1").syncAttempts)

        val use = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))
        // The spend holds the purchase when the entry falls due.
        assertEquals(1, fixture.store.load().getValue("token-1").syncAttempts)
        val movedOn = withTimeoutOrNull(3_000) {
            while (fixture.store.load().getValue("token-1").syncAttempts < 2) delay(10)
        }
        assertNotNull("The due entry it could not send counts one more attempt", movedOn)
        fixture.transport.releaseFirst.countDown()
        assertNotNull(use.await().getOrNull())
        fixture.core.stop()
    }

    @Test
    fun concurrentSpendWaitsThenReevaluatesAfterTheFirstSucceeds() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 200)
        val first = async(Dispatchers.Default) { fixture.use() }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))
        fixture.store.observeNextLoad()
        val second = async(Dispatchers.Default) { fixture.use() }
        assertTrue(fixture.store.nextLoadObserved.await(5, TimeUnit.SECONDS))

        assertFalse(second.isCompleted)
        assertEquals(1, fixture.transport.requestCount())
        fixture.transport.releaseFirst.countDown()

        assertNotNull(first.await())
        assertEquals(null, second.await())
        assertEquals(1, fixture.transport.requestCount())
        fixture.core.stop()
    }

    @Test
    fun failedAtomicSpendReleasesTheClaimForAWaitingCaller() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 503)
        val first = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))
        fixture.store.observeNextLoad()
        val second = async(Dispatchers.Default) { fixture.use() }
        assertTrue(fixture.store.nextLoadObserved.await(5, TimeUnit.SECONDS))

        fixture.transport.releaseFirst.countDown()

        assertTrue(first.await().isFailure)
        assertNotNull(second.await())
        assertEquals(2, fixture.transport.requestCount())
        val eventIds = fixture.transport.requestBodies().map { body ->
            Json.parseToJsonElement(body).jsonObject.getValue("operationId").toString().trim('"')
        }
        assertEquals(1, eventIds.distinct().size)
        fixture.core.stop()
    }

    @Test
    fun waitingCallerRechecksExactlyOneAfterClaimHandoff() = runBlocking {
        val fixture = concurrentFixture(firstStatus = 503, reportedByPlay = mutableSetOf("token-1", "token-2"))
        val first = async(Dispatchers.Default) { runCatching { fixture.use() } }
        assertTrue(fixture.transport.firstRequestStarted.await(5, TimeUnit.SECONDS))
        fixture.store.observeNextLoad()
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { fixture.use() }
        assertTrue(fixture.store.nextLoadObserved.await(5, TimeUnit.SECONDS))
        assertFalse(second.isCompleted)

        fixture.store.serveCurrentSnapshotOnNextLoad()
        fixture.store.upsert(evidence(token = "token-2"))
        fixture.transport.releaseFirst.countDown()
        assertTrue(first.await().isFailure)

        assertEquals(null, second.await())
        assertEquals(1, fixture.transport.requestCount())
        fixture.core.stop()
    }

    @Test
    fun inFlightSuccessfulSyncIsAwaitedThenFallsBack() = runTest {
        val fixture = syncFixture(PurchaseSyncOutcome.Accepted(purchaseResponse("customer-a")))
        val recovery = async { fixture.service.recover() }
        fixture.synchronizer.started.await()

        val use = async { fixture.use() }
        runCurrent()
        assertFalse(use.isCompleted)
        fixture.synchronizer.release.complete(Unit)

        recovery.await()
        assertEquals(null, use.await())
        assertTrue(fixture.transport.requests.none { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun inFlightFailedSyncIsAwaitedThenTheAtomicGateRuns() = runTest {
        val fixture = syncFixture(PurchaseSyncOutcome.Rejected())
        val recovery = async { fixture.service.recover() }
        fixture.synchronizer.started.await()

        val use = async { fixture.use() }
        runCurrent()
        assertFalse(use.isCompleted)
        fixture.synchronizer.release.complete(Unit)

        recovery.await()
        assertNotNull(use.await())
        assertEquals(1, fixture.transport.requests.count { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun multipleEligiblePurchasesFallBackInsteadOfChoosingFirst() = runTest {
        val fixture = fallbackFixture(reportedByPlay = mutableSetOf("token-1", "token-2"))
        fixture.store.upsert(evidence(token = "token-1"))
        fixture.store.upsert(evidence(token = "token-2"))

        assertEquals(null, fixture.use())
        assertTrue(fixture.transport.requests.none { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun foreignOwnerEvidenceIsNotEligible() = assertDisqualified(
        evidence(buyerDistinctId = "customer-b"),
    )

    @Test
    fun foreignAuthorityScopeEvidenceIsNotEligible() = assertDisqualified(
        evidence(authorityScope = "scope-b"),
    )

    @Test
    fun anOwedPurchasePlayDoesNotReportIsNotEligible() = assertDisqualified(
        // Its entry is still owed, but Play's latest query does not report it.
        evidence(token = "not-reported-by-play"),
    )

    @Test
    fun permanentlyRejectedEvidenceIsNotEligible() = assertDisqualified(
        evidence(refused = true),
    )

    @Test
    fun synchronizedEvidenceIsNotEligible() = assertDisqualified(
        evidence(synced = true),
    )

    @Test
    fun evidenceWithBackendSyncTimestampIsNotEligible() = assertDisqualified(
        evidence(backendSyncedAtMillis = 1_784_462_350_000L),
    )

    @Test
    fun evidenceWithoutAPlayPayloadIsNotEligible() = assertDisqualified(
        evidence(token = ""),
    )

    @Test
    fun evidenceWithoutAPackageNameIsNotEligible() = assertDisqualified(
        evidence(packageName = ""),
    )

    @Test
    fun evidenceWithoutAProductIdIsNotEligible() = assertDisqualified(
        evidence(storeProductIds = listOf("")),
    )

    @Test
    fun evidenceWithoutTheRequestedFeatureIsNotEligible() = assertDisqualified(
        evidence(storeProductIds = listOf("play-other-pack")),
    )

    @Test
    fun appManagedEvidenceIsEligibleForAtomicGateWithoutManagedCompletion() = runTest {
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/feature/consume") {
                    featureCommandResponse(request, balance = 1.0)
                } else {
                    canonicalJourneyProfileResponse()
                }
            }
        }
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore().also {
            it.upsert(evidence(nuxieManaged = false))
        }
        val billing = RecordingBilling()
        val service = service(core, store, billing = billing)

        assertNotNull(
            service.useFeatureWithPendingPurchase(
                distinctId = "customer-a",
                featureId = "credits",
                amount = 1.0,
                entityId = null,
                metadata = null,
            ),
        )

        assertEquals(1, transport.requests.count { it.url.path == "/feature/consume" })
        assertTrue(store.load().getValue("token-1").synced)
        assertEquals(0, billing.acknowledgeCalls)
        assertEquals(0, billing.consumeCalls)
        core.stop()
    }

    @Test
    fun pendingEvidenceIsNotEligible() = assertDisqualified(
        evidence(purchaseState = StoredPurchaseState.PENDING),
    )

    @Test
    fun unverifiedRequiredSignatureEvidenceIsNotEligible() = assertDisqualified(
        evidence(signatureVerificationRequired = true, signatureVerified = false),
    )

    @Test
    fun noLicensingKeyEvidenceUsesThePendingPurchaseSpendPathWhenUnverified() = runTest {
        val fixture = fallbackFixture()
        fixture.transport.respond = { request ->
            if (request.url.path == "/feature/consume") {
                featureCommandResponse(request, balance = 1.0)
            } else {
                canonicalJourneyProfileResponse()
            }
        }
        fixture.store.upsert(
            evidence(signatureVerificationRequired = false, signatureVerified = false),
        )

        assertNotNull(fixture.use())
        assertEquals(1, fixture.transport.requests.count { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    @Test
    fun singleEligiblePurchaseIsVerifiedAndSpentAtomically() = runTest {
        val contract = Json.parseToJsonElement(
            File(FixtureRunner.fixturesRoot(), "events/atomic-purchase-sync.json").readText(),
        ).jsonObject.getValue("acceptance").jsonObject
        val postUseAccess = contract.getValue("post_use_access").jsonObject
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/feature/consume" -> featureCommandResponse(request, balance = 0.0)
                    else -> canonicalJourneyProfileResponse()
                }
            }
        }
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore()
        store.upsert(evidence(context = StoredPurchaseContext("placement-1", "experience-1", "version-1")))
        val captured = mutableListOf<CapturedEvent>()
        val service = service(core, store, capture = { name, properties, eventId, distinctId ->
            captured += CapturedEvent(name, properties, eventId, distinctId)
            true
        })

        val result = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 2.0,
            entityId = "workspace-1",
            metadata = mapOf("source" to "export"),
        )

        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("credits", result.featureId)
        assertEquals(2.0, result.amountUsed, 0.0)
        assertEquals(
            postUseAccess.getValue("allowed_after_final_finite_unit").jsonPrimitive.boolean,
            result.authoritativeAccess!!.allowed,
        )
        assertEquals(
            postUseAccess.getValue("balance_after_final_finite_unit").jsonPrimitive.double,
            result.authoritativeAccess!!.balance!!,
            0.0,
        )
        assertFalse(contract.getValue("ordinary_usage_fallback").jsonPrimitive.boolean)
        val accepted = store.load().getValue("token-1")
        assertTrue(accepted.synced)
        assertNotNull(accepted.backendSyncedAtMillis)
        assertTrue(accepted.syncedEventEmitted)
        assertEquals(
            mapOf(
                "transaction_id" to "token-1",
                "original_transaction_id" to "token-1",
                "product_id" to "play-credit-pack",
                "customer_id" to "customer-a",
                "experience_id" to "experience-1",
                "experience_version_id" to "version-1",
            ),
            captured.single().properties,
        )
        assertEquals("\$purchase_synced", captured.single().name)
        assertEquals("customer-a", captured.single().distinctId)
        assertTrue(captured.single().eventId.startsWith("purchase-synced:"))
        val request = transport.requests.single { it.url.path == "/feature/consume" }
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        assertTrue(body.getValue("operationId").jsonPrimitive.content.startsWith("purchase-use:"))
        assertEquals("token-1", body.getValue("purchase").jsonObject.getValue("purchaseToken").jsonPrimitive.content)
        core.stop()
    }

    private fun core(transport: HttpTransport): NuxieCore {
        val storageDirectory = temporaryFolder.newFolder("core-${++coreSequence}")
        return NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_purchase_feature_use",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = transport,
                registerLifecycle = false,
                purchaseEvidenceStore = InMemoryPurchaseEvidenceStore(),
                eventDatabaseFile = File(storageDirectory, "events.db"),
                profileCacheDirectory = File(storageDirectory, "profiles"),
            ),
        ).also {
            it.identity.setDistinctId("customer-a")
            runBlocking { it.purchases.awaitInitialProjection() }
            cores += it
        }
    }

    private fun service(
        core: NuxieCore,
        store: PurchaseEvidenceStore,
        capture: suspend (String, Map<String, Any?>, String, String) -> Boolean = { _, _, _, _ -> true },
        synchronizer: PurchaseSynchronizer = PurchaseSynchronizer { _, _ ->
            PurchaseSyncOutcome.Rejected()
        },
        billing: PlayBillingGateway = NoopBilling,
        reportedByPlay: MutableSet<String> = mutableSetOf("token-1"),
        outboxRetryMillis: Long = 30_000,
    ): PurchaseService {
        kotlinx.coroutines.runBlocking { store.upsertProductMapping(featureDescriptor()) }
        return PurchaseService(
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            billing = ReportingBilling(billing, reportedByPlay),
            evidenceStore = store,
            synchronizer = synchronizer,
            features = core.features,
            distinctId = core.identity::distinctId,
            customerKind = core.identity::customerKind,
            emit = { _, _ -> },
            settings = PurchaseSettings(null, PurchaseHandlingMode.NUXIE_MANAGED),
            scope = core.scope,
            nowMillis = { 1_784_462_400_000L },
            api = core.api,
            purchaseStorageScope = "scope-a",
            capturePurchaseSynced = capture,
            outboxRetryInitialDelayMillis = outboxRetryMillis,
            outboxRetryMaxDelayMillis = outboxRetryMillis * 4,
        ).also {
            // The launch query: the early unlock needs Play to report the purchase.
            kotlinx.coroutines.runBlocking { it.refreshPlayReport() }
        }
    }

    private fun evidence(
        token: String = "token-1",
        authorityScope: String = "scope-a",
        buyerDistinctId: String = "customer-a",
        packageName: String = "com.example.app",
        storeProductIds: List<String> = listOf("play-credit-pack"),
        purchaseState: StoredPurchaseState = StoredPurchaseState.PURCHASED,
        nuxieManaged: Boolean = true,
        refused: Boolean = false,
        synced: Boolean = false,
        backendSyncedAtMillis: Long? = null,
        signatureVerificationRequired: Boolean = false,
        signatureVerified: Boolean = true,
        context: StoredPurchaseContext? = null,
    ) = PurchaseEvidence(
        purchaseToken = token,
        authorityScope = authorityScope,
        packageName = packageName,
        storeProductIds = storeProductIds,
        nuxieProductId = "credit-pack",
        productType = BillingClient.ProductType.INAPP,
        purchaseState = purchaseState,
        obfuscatedAccountId = playAccountId(buyerDistinctId),
        buyerDistinctId = buyerDistinctId,
        // A backend sync time means the server accepted it for its buyer.
        ownerDistinctId = buyerDistinctId.takeIf { synced || backendSyncedAtMillis != null },
        refused = refused,
        pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, buyerDistinctId, CustomerKind.ANONYMOUS)
            .takeUnless { refused || synced || backendSyncedAtMillis != null },
        acknowledged = false,
        firstSeenMillis = 1_784_462_300_000L,
        catalogResolved = true,
        nuxieManaged = nuxieManaged,
        backendSyncedAtMillis = backendSyncedAtMillis,
        signatureVerificationRequired = signatureVerificationRequired,
        signatureVerified = signatureVerified,
        context = context,
    )

    private fun featureDescriptor() = StoredProductMapping(
        storeProductId = "play-credit-pack",
        nuxieProductId = "credit-pack",
        productType = BillingClient.ProductType.INAPP,
        consumable = true,
        featureAllowances = listOf(
            StoredFeatureAllowance("credits", FeatureType.CREDIT_SYSTEM.name, false, allowance = 1.0),
        ),
    )

    private fun assertDisqualified(disqualified: PurchaseEvidence) = runTest {
        val fixture = fallbackFixture()
        fixture.store.upsert(disqualified)

        assertEquals(null, fixture.use())
        assertTrue(fixture.transport.requests.none { it.url.path == "/feature/consume" })
        fixture.core.stop()
    }

    private fun fallbackFixture(reportedByPlay: MutableSet<String> = mutableSetOf("token-1")): FallbackFixture {
        val transport = FakeTransport()
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore()
        return FallbackFixture(core, transport, store, service(core, store, reportedByPlay = reportedByPlay))
    }

    private suspend fun syncFixture(outcome: PurchaseSyncOutcome): SyncFixture {
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/feature/consume") {
                    featureCommandResponse(request, balance = 1.0)
                } else {
                    canonicalJourneyProfileResponse()
                }
            }
        }
        val core = core(transport)
        val store = InMemoryPurchaseEvidenceStore().also { it.upsert(evidence()) }
        val synchronizer = ControlledSynchronizer(outcome)
        return SyncFixture(
            core,
            transport,
            service(core, store, synchronizer = synchronizer, billing = ActiveBilling),
            synchronizer,
        )
    }

    private fun concurrentFixture(
        firstStatus: Int,
        reportedByPlay: MutableSet<String> = mutableSetOf("token-1"),
        outboxRetryMillis: Long = 30_000,
    ): ConcurrentFixture {
        val transport = ControlledAtomicTransport(firstStatus)
        val core = core(transport)
        val store = ObservingEvidenceStore(evidence())
        return ConcurrentFixture(
            core,
            transport,
            store,
            service(core, store, reportedByPlay = reportedByPlay, outboxRetryMillis = outboxRetryMillis),
            reportedByPlay,
        )
    }

    private data class FallbackFixture(
        val core: NuxieCore,
        val transport: FakeTransport,
        val store: InMemoryPurchaseEvidenceStore,
        val service: PurchaseService,
    ) {
        suspend fun use() = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 1.0,
            entityId = null,
            metadata = null,
        )
    }

    private data class SyncFixture(
        val core: NuxieCore,
        val transport: FakeTransport,
        val service: PurchaseService,
        val synchronizer: ControlledSynchronizer,
    ) {
        suspend fun use() = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 1.0,
            entityId = null,
            metadata = null,
        )
    }

    private data class ConcurrentFixture(
        val core: NuxieCore,
        val transport: ControlledAtomicTransport,
        val store: ObservingEvidenceStore,
        val service: PurchaseService,
        /** The tokens Play reports to its next query. */
        val reportedByPlay: MutableSet<String>,
    ) {
        suspend fun use() = service.useFeatureWithPendingPurchase(
            distinctId = "customer-a",
            featureId = "credits",
            amount = 1.0,
            entityId = null,
            metadata = null,
        )
    }

    private class ObservingEvidenceStore(initial: PurchaseEvidence) : PurchaseEvidenceStore {
        private val entries = linkedMapOf(initial.purchaseToken to initial)
        @Volatile private var observe = false
        private var nextLoadSnapshot: Map<String, PurchaseEvidence>? = null
        val nextLoadObserved = CountDownLatch(1)

        fun observeNextLoad() {
            observe = true
        }

        fun serveCurrentSnapshotOnNextLoad() = synchronized(entries) {
            nextLoadSnapshot = entries.toMap()
        }

        override suspend fun load(): Map<String, PurchaseEvidence> = synchronized(entries) {
            if (observe) {
                observe = false
                nextLoadObserved.countDown()
            }
            val snapshot = nextLoadSnapshot
            nextLoadSnapshot = null
            snapshot ?: entries.toMap()
        }

        override suspend fun upsert(evidence: PurchaseEvidence): Boolean = synchronized(entries) {
            entries[evidence.purchaseToken] = evidence
            true
        }

        private var mapping: StoredProductMapping? = null

        override suspend fun loadProductMappings(): List<StoredProductMapping> = listOfNotNull(mapping)

        override suspend fun upsertProductMapping(mapping: StoredProductMapping): Boolean {
            this.mapping = mapping
            return true
        }
    }

    private class ControlledAtomicTransport(
        private val firstStatus: Int,
    ) : HttpTransport {
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        private val requests = mutableListOf<HttpTransport.Request>()

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            if (request.url.path != "/feature/consume") {
                return canonicalJourneyProfileResponse()
            }
            val requestNumber = synchronized(requests) {
                requests += request
                requests.size
            }
            if (requestNumber == 1) {
                firstRequestStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release atomic request" }
                if (firstStatus !in 200..299) {
                    return HttpTransport.Response(firstStatus, ByteArray(0))
                }
            }
            return featureCommandResponse(request, balance = 1.0)
        }

        fun requestCount(): Int = synchronized(requests) { requests.size }
        fun requestBodies(): List<String> = synchronized(requests) {
            requests.map { it.body.decodeToString() }
        }
    }

    private class ControlledSynchronizer(
        private val outcome: PurchaseSyncOutcome,
    ) : PurchaseSynchronizer {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun sync(
            evidence: PurchaseEvidence,
            request: PurchaseSyncRequest,
        ): PurchaseSyncOutcome {
            started.complete(Unit)
            release.await()
            return outcome
        }
    }

    private fun purchaseResponse(customerId: String) = NuxieApi.PurchaseResponse(
        body = Json.parseToJsonElement(
            """{"success":true,"customer_id":"$customerId","features":[]}""",
        ).jsonObject,
        success = true,
        customerId = customerId,
        catalogProduct = NuxieApi.VerifiedCatalogProduct(
            productId = "credit-pack",
            storeProductId = "play-credit-pack",
            basePlanId = null,
            purchaseOptionId = null,
            offerId = null,
            storeProductType = "nonConsumable",
        ),
    )

    private data class CapturedEvent(
        val name: String,
        val properties: Map<String, Any?>,
        val eventId: String,
        val distinctId: String,
    )

    /** Play reports [reported] as active one-time purchases, besides what [delegate] reports. */
    private class ReportingBilling(
        private val delegate: PlayBillingGateway,
        private val reported: MutableSet<String>,
    ) : PlayBillingGateway by delegate {
        override suspend fun queryActive(productType: String): ActivePurchasesResult {
            val result = delegate.queryActive(productType)
            if (result !is ActivePurchasesResult.Success || productType != BillingClient.ProductType.INAPP) return result
            val known = result.purchases.mapTo(mutableSetOf()) { it.purchaseToken }
            return ActivePurchasesResult.Success(
                result.purchases + (reported - known).map { token ->
                    PlayPurchase(
                        purchaseToken = token,
                        packageName = "com.example.app",
                        products = listOf("play-credit-pack"),
                        state = StoredPurchaseState.PURCHASED,
                        acknowledged = false,
                        obfuscatedAccountId = playAccountId("customer-a"),
                        originalJson = "{}",
                        signature = "",
                    )
                },
            )
        }
    }

    private object NoopBilling : PlayBillingGateway {
        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult = ok()
        override suspend fun queryActive(productType: String): ActivePurchasesResult =
            ActivePurchasesResult.Success(emptyList())
        override suspend fun acknowledge(purchaseToken: String): BillingResult = ok()
        override suspend fun consume(purchaseToken: String): BillingResult = ok()

        private fun ok() = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()
    }

    private class RecordingBilling : PlayBillingGateway {
        var acknowledgeCalls = 0
        var consumeCalls = 0

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult = ok()
        override suspend fun queryActive(productType: String): ActivePurchasesResult =
            ActivePurchasesResult.Success(emptyList())
        override suspend fun acknowledge(purchaseToken: String): BillingResult {
            acknowledgeCalls += 1
            return ok()
        }
        override suspend fun consume(purchaseToken: String): BillingResult {
            consumeCalls += 1
            return ok()
        }

        private fun ok() = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()
    }

    private object ActiveBilling : PlayBillingGateway {
        private val purchase = PlayPurchase(
            purchaseToken = "token-1",
            packageName = "com.example.app",
            products = listOf("play-credit-pack"),
            state = StoredPurchaseState.PURCHASED,
            acknowledged = false,
            // The buyer's own purchase names the account the SDK set at their checkout.
            obfuscatedAccountId = playAccountId("customer-a"),
            originalJson = "{}",
            signature = "",
        )

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult = ok()
        override suspend fun queryActive(productType: String): ActivePurchasesResult =
            ActivePurchasesResult.Success(
                if (productType == BillingClient.ProductType.INAPP) listOf(purchase) else emptyList(),
            )
        override suspend fun acknowledge(purchaseToken: String): BillingResult = ok()
        override suspend fun consume(purchaseToken: String): BillingResult = ok()

        private fun ok() = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()
    }
}
