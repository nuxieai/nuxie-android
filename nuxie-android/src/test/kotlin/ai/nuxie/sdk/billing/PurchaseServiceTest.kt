package ai.nuxie.sdk.billing

import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.ActivityCuration
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.identity.CustomerKind
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.testsupport.FakeTransport
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The server grants every Test Store purchase, with no Feature access to apply. */
private val grantedTestStore = TestStoreGranting { _, _, _ ->
    kotlinx.serialization.json.buildJsonObject { put("success", kotlinx.serialization.json.JsonPrimitive(true)) }
}

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fixtures = mutableListOf<Fixture>()

    @After
    fun closeFixtures() {
        fixtures.asReversed().forEach(Fixture::close)
        fixtures.clear()
    }

    @Test
    fun hostRestoreReadsEvidenceOffMain() = runTest {
        val hostMain = AtomicReference<Thread>()
        val store = RecordingEvidenceStore {
            assertTrue("Restore evidence reads must leave host-main", Thread.currentThread() !== hostMain.get())
        }
        val fixture = fixture(this, store = store, ioDispatcher = Dispatchers.IO)
        runCurrent()
        withHostMain(hostMain) {
            assertEquals(RestoreResult.NoPurchases, fixture.service.restorePurchases())
        }
        assertEquals(listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP), fixture.billing.queries)
    }

    @Test
    fun hostPurchasePersistsEvidenceOffMainBeforeLaunchingBilling() = runTest {
        val hostMain = AtomicReference<Thread>()
        val store = RecordingEvidenceStore {
            assertTrue("Purchase evidence writes must leave host-main", Thread.currentThread() !== hostMain.get())
        }
        val fixture = fixture(this, store = store, ioDispatcher = Dispatchers.IO)
        fixture.billing.launchCode = BillingClient.BillingResponseCode.USER_CANCELED
        val hostActivity = activity()
        runCurrent()
        withHostMain(hostMain) {
            assertEquals(PurchaseResult.Cancelled, fixture.service.purchase(hostActivity, product(), null))
        }
        assertEquals("play-pro", fixture.billing.launched?.product?.storeProductId)
        assertEquals("play-pro", store.loadProductMappings().single().storeProductId)
        assertEquals("play-pro", store.loadBindings().single().storeProductId)
    }

    // iOS parity (UNIV-5182): iOS built its purchase token from the service
    // environment while the server used the key's mode, so a Sandbox purchase
    // went unclaimed. Play's account ID carries no environment or key mode:
    // it is the hex SHA-256 of the customer's distinct ID, which the server
    // recomputes from the purchase's customer (sha256Hex(distinctId) in
    // nuxie-dev's Google Play SDK purchase runtime).
    @Test
    fun playAccountIdIsTheHexSha256OfTheCustomersDistinctId() = runTest {
        val fixture = fixture(this)
        fixture.core.identity.setDistinctId("rider_5182")
        fixture.billing.launchCode = BillingClient.BillingResponseCode.USER_CANCELED
        runCurrent()
        assertEquals(PurchaseResult.Cancelled, fixture.service.purchase(activity(), product(), null))
        assertEquals(
            "9713f2e17270f72c6370d1be6dc9d80cfeedeac119b304099a074e57049a3560",
            fixture.billing.launched?.obfuscatedAccountId,
        )
    }

    @Test
    fun onlyThePlayBillingLaunchRunsOnMain() = runTest {
        val hostMain = AtomicReference<Thread>()
        val hostActivity = activity()
        val checkoutProduct = product(rawProduct = oneTimeProductDetails())
        val cancel = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED).build()
        val adapter = object : BillingClientAdapter by InertBillingClientAdapter {
            override val isReady: Boolean = true
            override fun launchBillingFlow(activity: Activity, params: com.android.billingclient.api.BillingFlowParams): BillingResult {
                assertTrue("Only launching the Play UI needs Main", Thread.currentThread() === hostMain.get())
                return cancel
            }
        }
        val connection = PlayBillingConnection(BillingClientAdapterFactory { adapter }, backgroundScope)
        try {
            withHostMain(hostMain) {
                val result = withContext(Dispatchers.IO) {
                    connection.launch(hostActivity, CheckoutRequest(checkoutProduct, "account", null))
                }
                assertEquals(BillingClient.BillingResponseCode.USER_CANCELED, result.responseCode)
            }
        } finally {
            connection.close()
        }
    }

    private suspend fun withHostMain(hostMain: AtomicReference<Thread>, block: suspend () -> Unit) {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "host-main").also(hostMain::set) }
            .asCoroutineDispatcher().use { main ->
                Dispatchers.setMain(main)
                try {
                    withContext(Dispatchers.Main.immediate) { block() }
                } finally {
                    Dispatchers.resetMain()
                }
            }
    }

    @Test
    fun stoppingCheckoutIntakeReleasesPlayWaiterWithoutInventingAPurchaseOutcome() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        try {
            runCurrent()
            val bindings = fixture.store.loadBindings()
            val purchase = playPurchase("after-shutdown").forCheckout(fixture)
            fixture.service.stopCheckoutIntake()
            runCurrent()
            assertTrue("shutdown must release a checkout waiting for Play UI", checkout.isCompleted)
            assertTrue(checkout.isCancelled)
            assertTrue(emissions.isEmpty())
            assertEquals(bindings, fixture.store.loadBindings())
            // A late observation still has durable ownership and product context.
            fixture.service.onPurchasesUpdated(okUpdate(purchase))
            assertTrue(fixture.store.load().getValue("after-shutdown").completionEmitted)
            assertTrue(runCatching {
                fixture.service.purchase(activity(), product(), null)
            }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            fixture.service.stopCheckoutIntake()
        } finally {
            checkout.cancel()
            fixture.close()
        }
    }

    @Test
    fun stoppingCheckoutIntakeDoesNotReplaceAnAdmittedDurableCompletion() = runTest {
        val captureStarted = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val fixture = fixture(this, capturePurchaseEventOverride = { _, _, _, _ ->
            captureStarted.complete(Unit)
            releaseCapture.await()
            true
        })
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        val update = async { fixture.service.onPurchasesUpdated(okUpdate(playPurchase("committing").forCheckout(fixture))) }
        try {
            captureStarted.await()
            fixture.service.stopCheckoutIntake()
            runCurrent()
            assertFalse(checkout.isCompleted)
            releaseCapture.complete(Unit)
            update.await()
            assertEquals(PurchaseResult.Purchased, checkout.await())
            assertTrue(fixture.store.load().getValue("committing").completionEmitted)
        } finally {
            releaseCapture.complete(Unit)
            update.join()
            checkout.cancel()
            fixture.close()
        }
    }

    @Test
    fun failedCaptureDuringCheckoutClosureReleasesWaiterAndRetainsEvidenceForRecovery() = runTest {
        for (cancelCapture in listOf(false, true)) {
            val captureStarted = CompletableDeferred<Unit>()
            val releaseCapture = CompletableDeferred<Unit>()
            var captureSucceeds = false
            val fixture = fixture(this, capturePurchaseEventOverride = { _, _, _, _ ->
                captureStarted.complete(Unit)
                releaseCapture.await()
                if (!captureSucceeds && cancelCapture) throw kotlinx.coroutines.CancellationException("capture cancelled")
                captureSucceeds
            })
            val checkout = async { fixture.service.purchase(activity(), product(), null) }
            runCurrent()
            val purchase = playPurchase("shutdown-capture-retry").forCheckout(fixture)
            val update = async { runCatching { fixture.service.onPurchasesUpdated(okUpdate(purchase)) } }
            try {
                captureStarted.await()
                fixture.service.stopCheckoutIntake()
                releaseCapture.complete(Unit)
                assertTrue(update.await().isFailure)
                runCurrent()
                assertTrue(checkout.isCompleted)
                assertTrue(checkout.isCancelled)
                assertFalse(fixture.store.load().getValue("shutdown-capture-retry").completionEmitted)
                assertTrue(fixture.store.loadBindings().isNotEmpty())
                captureSucceeds = true
                fixture.service.onPurchasesUpdated(okUpdate(purchase))
                assertTrue(fixture.store.load().getValue("shutdown-capture-retry").completionEmitted)
                assertEquals(1, fixture.purchaseCompletionEventIds.size)
            } finally {
                releaseCapture.complete(Unit)
                update.join()
                checkout.cancel()
                fixture.close()
            }
        }
    }

    @Test
    fun checkoutRemovedForOutcomeDeliveryStillSettlesWhenCaptureFailsDuringClosure() = runTest {
        val captureStarted = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val fixture = fixture(this, capturePurchaseEventOverride = { _, _, _, _ ->
            captureStarted.complete(Unit)
            releaseCapture.await()
            false
        })
        val checkout = async {
            runCatching {
                fixture.service.purchase(
                    activity(), product(), null,
                    outcomeCorrelation = CommerceOutcomeCorrelation("closing-outcome", fixture.core.identity.distinctId()),
                )
            }
        }
        runCurrent()
        val update = async {
            runCatching {
                fixture.service.onPurchasesUpdated(PurchaseUpdate(result(BillingClient.BillingResponseCode.USER_CANCELED), null))
            }
        }
        try {
            captureStarted.await()
            fixture.service.stopCheckoutIntake()
            assertFalse(checkout.isCompleted)
            releaseCapture.complete(Unit)
            assertTrue(update.await().isFailure)
            runCurrent()
            assertTrue("a removed checkout still needs its delivery owner to settle it", checkout.isCompleted)
            assertTrue(checkout.await().exceptionOrNull() is IllegalStateException)
        } finally {
            releaseCapture.complete(Unit)
            update.join()
            checkout.cancel()
            fixture.close()
        }
    }

    @Test
    fun ownershipQueryCrossingCheckoutClosureCannotLaunchPlayUi() = runTest {
        val fixture = fixture(this)
        val releaseQuery = CompletableDeferred<Unit>()
        fixture.billing.releaseQueries = releaseQuery
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        try {
            runCurrent()
            fixture.service.stopCheckoutIntake()
            releaseQuery.complete(Unit)
            runCurrent()
            assertNull(fixture.billing.launched)
            assertTrue(checkout.isCompleted)
            assertTrue(checkout.isCancelled)
        } finally {
            releaseQuery.complete(Unit)
            checkout.cancel()
            fixture.close()
        }
    }

    @Test
    fun billingClientJourneyReleaseDeliveryIsTheTrustBoundaryWhenNoLicensingKeyIsConfigured() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(
                    allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
                    licensingPublicKey = null,
                ),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("no-licensing-key").forCheckout(fixture)),
        )

        assertEquals(PurchaseResult.Purchased, checkout.await())
        val retained = fixture.store.load().getValue("no-licensing-key")
        assertFalse(retained.signatureVerified)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun configuredLicensingKeyRejectsBeforeAVerifiedOutcomeCanCommit() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(
            this,
            actions = actions,
            verifyPurchaseSignature = { _, _, _ -> false },
        )
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("invalid-signature").forCheckout(fixture)),
        )

        assertTrue(checkout.await() is PurchaseResult.Failed)
        val evidence = fixture.store.load().getValue("invalid-signature")
        assertFalse(evidence.signatureVerified)
        assertNull("An unverified purchase owes no send", evidence.pendingSend)
        assertTrue(fixture.purchaseEventCaptureAttempts.isEmpty())
        assertFalse("sync" in actions)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun purchasedPersistsAndProjectsBeforeSyncThenAcknowledgesAfterAcceptance() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val product = product(
            allowances = listOf(
                FeatureAllowance("pro", FeatureType.BOOLEAN),
                FeatureAllowance("exports", FeatureType.METERED),
                FeatureAllowance("unlimited", FeatureType.METERED, unlimited = true),
                FeatureAllowance("credits", FeatureType.CREDIT_SYSTEM),
            ),
        )
        fixture.synchronizer = { evidence ->
            actions += "sync"
            assertTrue(fixture.core.featureInfo.isAllowed("pro"))
            assertTrue(fixture.core.featureInfo.isAllowed("unlimited"))
            assertFalse(fixture.core.featureInfo.isAllowed("exports"))
            assertFalse(fixture.core.featureInfo.isAllowed("credits"))
            accepted(evidence.requester, evidence)
        }
        val purchase = async { fixture.service.purchase(activity(), product, null) }
        runCurrent()

        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("token-1").forCheckout(fixture)))

        assertEquals(PurchaseResult.Purchased, purchase.await())
        assertTrue(actions.indexOf("persist") < actions.indexOf("sync"))
        assertTrue(actions.indexOf("sync") < actions.indexOf("ack"))
        assertTrue(fixture.store.load().getValue("token-1").acknowledged)
        fixture.close()
    }

    @Test
    fun completedCheckoutCarriesTheLocalizedPlayPrice() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(rawProduct = oneTimeProductDetails()),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("priced-token").forCheckout(fixture)))

        assertEquals(PurchaseResult.Purchased, checkout.await())
        val properties = emissions.single {
            it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED
        }.second
        assertEquals("checkout", properties["source"])
        assertEquals(1_200.0, properties["price"])
        assertTrue(properties["price"] is Double)
        assertEquals("¥1,200", properties["display_price"])
        assertForwardedPrice(
            ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED,
            properties,
            "1200",
            "¥1,200",
        )
        fixture.close()
    }

    @Test
    fun cancelledCheckoutCarriesTheLocalizedPlayPrice() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        fixture.billing.launchCode = BillingClient.BillingResponseCode.USER_CANCELED

        val result = fixture.service.purchase(
            activity(),
            product(rawProduct = oneTimeProductDetails()),
            null,
        )

        assertEquals(PurchaseResult.Cancelled, result)
        val properties = emissions.single {
            it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_CANCELLED
        }.second
        assertEquals(1_200.0, properties["price"])
        assertEquals("¥1,200", properties["display_price"])
        assertForwardedPrice(
            ai.nuxie.sdk.events.SystemEventNames.PURCHASE_CANCELLED,
            properties,
            "1200",
            "¥1,200",
        )
        fixture.close()
    }

    @Test
    fun cancelledPlayCallbackCompletesCheckoutAndEmitsItsOutcomeExactlyOnce() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()

        fixture.service.onPurchasesUpdated(
            PurchaseUpdate(
                result(BillingClient.BillingResponseCode.USER_CANCELED),
                purchases = null,
            ),
        )

        assertEquals(PurchaseResult.Cancelled, checkout.await())
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_CANCELLED },
        )
        fixture.close()
    }

    @Test
    fun failedCheckoutCarriesTheLocalizedPlayPrice() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        fixture.billing.failQueries = true

        val result = fixture.service.purchase(
            activity(),
            product(rawProduct = oneTimeProductDetails()),
            null,
        )

        assertTrue(result is PurchaseResult.Failed)
        val properties = emissions.single {
            it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_FAILED
        }.second
        assertEquals(1_200.0, properties["price"])
        assertEquals("¥1,200", properties["display_price"])
        assertForwardedPrice(
            ai.nuxie.sdk.events.SystemEventNames.PURCHASE_FAILED,
            properties,
            "1200",
            "¥1,200",
        )
        fixture.close()
    }

    @Test
    fun pendingPersistsButDoesNotProjectSyncOrAcknowledge() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, actions = actions, emissions = emissions)
        val purchase = async {
            fixture.service.purchase(
                activity(),
                product(
                    allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
                    rawProduct = oneTimeProductDetails(),
                ),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("pending-token", state = StoredPurchaseState.PENDING).forCheckout(fixture)),
        )

        assertEquals(PurchaseResult.Pending, purchase.await())
        assertEquals(StoredPurchaseState.PENDING, fixture.store.load().getValue("pending-token").purchaseState)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertFalse("sync" in actions)
        assertFalse("ack" in actions)
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_PENDING },
        )
        val properties = emissions.single {
            it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_PENDING
        }.second
        assertEquals(1_200.0, properties["price"])
        assertEquals("¥1,200", properties["display_price"])
        assertForwardedPrice(
            ai.nuxie.sdk.events.SystemEventNames.PURCHASE_PENDING,
            properties,
            "1200",
            "¥1,200",
        )
        fixture.close()
    }

    @Test
    fun deferredUpdateCommitsThePendingPurchaseExactlyOnce() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, actions = actions, emissions = emissions)
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val pending = playPurchase(
            "deferred-token",
            state = StoredPurchaseState.PENDING,
        ).forCheckout(fixture)

        fixture.service.onPurchasesUpdated(okUpdate(pending))
        assertEquals(PurchaseResult.Pending, checkout.await())
        assertFalse(ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED in actions)

        val purchased = pending.copy(state = StoredPurchaseState.PURCHASED)
        fixture.service.onPurchasesUpdated(okUpdate(purchased))
        fixture.service.onPurchasesUpdated(okUpdate(purchased))

        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_PENDING },
        )
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, actions.count { it == "sync" })
        assertEquals(1, actions.count { it == "ack" })
        assertEquals(
            "deferred_update",
            emissions.single {
                it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED
            }.second["source"],
        )
        val evidence = fixture.store.load().getValue("deferred-token")
        assertEquals(StoredPurchaseState.PURCHASED, evidence.purchaseState)
        assertTrue(evidence.completionEmitted)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun identityChangeBeforePlayUpdateSuppressesTheForwardedCheckoutOutcome() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()

        fixture.core.identity.setDistinctId("replacement-customer")
        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("pending-token", state = StoredPurchaseState.PENDING).forCheckout(fixture)),
        )

        assertEquals(PurchaseResult.Pending, checkout.await())
        assertFalse(ai.nuxie.sdk.events.SystemEventNames.PURCHASE_PENDING in actions)
        fixture.close()
    }

    @Test
    fun permanentVerificationFailureRevokesOptimisticAccess() = runTest {
        val sharedStore = RecordingEvidenceStore()
        val fixture = fixture(this, store = sharedStore)
        fixture.synchronizer = {
            assertTrue(fixture.core.featureInfo.isAllowed("pro"))
            PurchaseSyncOutcome.Rejected(refused = true)
        }
        val purchase = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("rejected-token").forCheckout(fixture)))

        assertEquals(PurchaseResult.Purchased, purchase.await())
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertNull(fixture.store.load().getValue("rejected-token").pendingSend)

        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase(
                "rejected-token",
                obfuscatedAccountId = accountHash(fixture.core.identity.distinctId()),
            ),
        )
        fixture.service.recover()
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()

        val restarted = fixture(this, store = sharedStore)
        restarted.core.features.hydrateProfile(
            restarted.core.identity.distinctId(),
            Json.parseToJsonElement(
                """{"features":[{"id":"pro","type":"boolean","unlimited":false}]}""",
            ).jsonObject,
        )
        assertTrue(restarted.core.featureInfo.isAllowed("pro"))
        restarted.service.recover()
        assertTrue(restarted.core.featureInfo.isAllowed("pro"))
        restarted.close()
    }

    @Test
    fun mixedPendingAndVerifiedUpdatePublishesInReservationOrder() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        // One update mixes a PENDING purchase that completes the checkout at
        // decision time (reserving a publication barrier, the earlier FIFO
        // position) with a verified purchase whose completion capture stages
        // a post-capture projection (a later reservation). The drain must
        // publish in reservation order, or the later mutation waits forever
        // on the earlier, still-unpublished barrier.
        withTimeout(10_000) {
            fixture.service.onPurchasesUpdated(
                okUpdate(
                    playPurchase(
                        "mixed-pending",
                        state = StoredPurchaseState.PENDING,
                    ).forCheckout(fixture),
                    playPurchase("mixed-verified").forCheckout(fixture),
                ),
            )
            assertEquals(PurchaseResult.Pending, checkout.await())
        }
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun aPurchasePlayStopsReturningKeepsItsEarlyUnlockUntilTheServerAnswers() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("failed-revocation").forCheckout(fixture)),
        )
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // Play's queries no longer return the purchase (a refund, say).
        assertEquals(RestoreResult.NoPurchases, fixture.service.restorePurchases())
        fixture.service.recover()

        assertNotNull(
            "Every unanswered entry is sent until the server answers",
            fixture.store.load().getValue("failed-revocation").pendingSend,
        )
        assertTrue("Only the server's answer ends the early unlock", fixture.core.featureInfo.isAllowed("pro"))

        // The server refuses it: the early unlock ends at once.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        fixture.service.recover()
        assertNull(fixture.store.load().getValue("failed-revocation").pendingSend)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun restoreQueriesAndDecisionDoNotWaitForConcurrentBackendSync() = runTest {
        val fixture = fixture(this)
        val syncStarted = CompletableDeferred<Unit>()
        val finishSync = CompletableDeferred<Unit>()
        fixture.synchronizer = { evidence ->
            syncStarted.complete(Unit)
            finishSync.await()
            accepted(evidence.requester, evidence)
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val purchaseUpdate = async {
            fixture.service.onPurchasesUpdated(
                okUpdate(playPurchase("revoked-during-sync").forCheckout(fixture)),
            )
        }
        syncStarted.await()
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        val queryCountBeforeRestore = fixture.billing.queries.size

        val restore = async { fixture.service.restorePurchases() }
        runCurrent()

        assertEquals(queryCountBeforeRestore + 2, fixture.billing.queries.size)
        assertEquals(RestoreResult.NoPurchases, restore.await())
        assertNotNull(
            "The entry stays owed while its send is in flight",
            fixture.store.load().getValue("revoked-during-sync").pendingSend,
        )
        finishSync.complete(Unit)
        purchaseUpdate.await()

        assertFalse(fixture.store.load().getValue("revoked-during-sync").refused)
        assertTrue(fixture.store.load().getValue("revoked-during-sync").synced)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun slowRestoreQueryDoesNotStarveAnInteractivePurchaseCallback() = runTest {
        val fixture = fixture(this)
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQueries = CompletableDeferred<Unit>()
        fixture.billing.queryStarted = queryStarted
        fixture.billing.releaseQueries = releaseQueries

        val restore = async { fixture.service.restorePurchases() }
        queryStarted.await()
        val purchaseUpdate = async {
            fixture.service.onPurchasesUpdated(
                okUpdate(playPurchase("purchase-during-restore-query").forCheckout(fixture)),
            )
        }
        runCurrent()

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(purchaseUpdate.isCompleted)
        assertTrue(fixture.store.load().getValue("purchase-during-restore-query").synced)

        releaseQueries.complete(Unit)
        purchaseUpdate.await()
        assertEquals(RestoreResult.NoPurchases, restore.await())
        assertTrue(fixture.store.load().getValue("purchase-during-restore-query").synced)
        fixture.close()
    }

    @Test
    fun destinationProjectionInstallationOwnsTheRefreshMutexUntilInstallReturns() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        // Play returns a purchase of this customer's account: the Restore commits it,
        // which republishes the projection.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("restored", obfuscatedAccountId = accountHash(fixture.core.identity.distinctId())),
        )
        val installStarted = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val installing = async(Dispatchers.Default) {
            fixture.service.withOptimisticProjectionSnapshot(fixture.core.identity.distinctId()) {
                installStarted.countDown()
                check(releaseInstall.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release destination projection installation"
                }
            }
        }
        assertTrue(installStarted.await(5, TimeUnit.SECONDS))
        val queryCountBeforeRestore = fixture.billing.queries.size

        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.service.restorePurchases()
        }

        // Restore asks Play, then waits for the installation before it republishes.
        assertFalse(restore.isCompleted)
        releaseInstall.countDown()
        installing.await()
        assertTrue("No answer yet", restore.await() is RestoreResult.Failed)
        assertEquals(queryCountBeforeRestore + 2, fixture.billing.queries.size)
        fixture.close()
    }

    @Test
    fun inlineDestinationSnapshotDuringProjectionPublicationDoesNotDeadlock() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val callbackCompleted = CompletableDeferred<Unit>()
        fixture.core.featureInfo.onFeatureChange = { featureId, _, _, _ ->
            if (featureId == "pro") {
                fixture.service.withOptimisticProjectionSnapshot(
                    fixture.core.identity.distinctId(),
                ) {}
                callbackCompleted.complete(Unit)
            }
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("inline-identity-snapshot").forCheckout(fixture)),
        )

        callbackCompleted.await()
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun duplicateCallbacksReturnWhileCheckoutWaitsForItsStagedProjection() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        fixture.core.featureInfo.onFeatureChange = { featureId, _, _, _ ->
            if (featureId == "publication-blocker") {
                blockerStarted.complete(Unit)
                releaseBlocker.await()
            }
        }
        val olderPublication = async {
            fixture.core.features.applyOptimisticPurchaseProjection(
                fixture.core.identity.distinctId(),
                mapOf(
                    "publication-blocker" to OptimisticFeatureOverlay(
                        FeatureType.BOOLEAN,
                        unlimited = false,
                        balanceIncrease = null,
                    ),
                ),
            )
        }
        blockerStarted.await()
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val playUpdate = okUpdate(playPurchase("projection-before-return").forCheckout(fixture))
        val update = async { fixture.service.onPurchasesUpdated(playUpdate) }
        runCurrent()
        val duplicateUpdate = async { fixture.service.onPurchasesUpdated(playUpdate) }
        runCurrent()
        val cancellationUpdate = async {
            fixture.service.onPurchasesUpdated(
                PurchaseUpdate(
                    result(BillingClient.BillingResponseCode.USER_CANCELED),
                    purchases = null,
                ),
            )
        }
        runCurrent()

        assertFalse(checkout.isCompleted)
        assertFalse(update.isCompleted)
        assertTrue(duplicateUpdate.isCompleted)
        assertTrue(cancellationUpdate.isCompleted)
        assertEquals(
            0,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_CANCELLED },
        )

        releaseBlocker.complete(Unit)
        olderPublication.await()
        duplicateUpdate.await()
        cancellationUpdate.await()
        update.await()

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun equivalentProjectionStagedByAnotherRefreshStillBlocksCheckoutCompletion() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val owner = fixture.core.identity.distinctId()
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        fixture.core.featureInfo.onFeatureChange = { featureId, _, _, _ ->
            if (featureId == "publication-blocker") {
                blockerStarted.complete(Unit)
                releaseBlocker.await()
            }
        }
        val olderPublication = async {
            fixture.core.features.applyOptimisticPurchaseProjection(
                owner,
                mapOf(
                    "publication-blocker" to OptimisticFeatureOverlay(
                        FeatureType.BOOLEAN,
                        unlimited = false,
                        balanceIncrease = null,
                    ),
                ),
            )
        }
        blockerStarted.await()
        val equivalentRefresh = async {
            fixture.core.features.applyOptimisticPurchaseProjection(
                owner,
                mapOf(
                    "pro" to OptimisticFeatureOverlay(
                        FeatureType.BOOLEAN,
                        unlimited = false,
                        balanceIncrease = null,
                    ),
                ),
            )
        }
        runCurrent()
        assertFalse(equivalentRefresh.isCompleted)

        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val update = async {
            fixture.service.onPurchasesUpdated(
                okUpdate(playPurchase("equivalent-staged-projection").forCheckout(fixture)),
            )
        }
        runCurrent()

        assertFalse(checkout.isCompleted)
        assertFalse(update.isCompleted)

        releaseBlocker.complete(Unit)
        olderPublication.await()
        equivalentRefresh.await()
        update.await()

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun featurePublicationRunsAfterThePurchaseDecisionMutexIsReleased() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val processingWasAvailable = CompletableDeferred<Boolean>()
        fixture.core.featureInfo.onFeatureChange = { featureId, _, _, _ ->
            if (featureId == "pro" && !processingWasAvailable.isCompleted) {
                val reentrantCallback = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.service.onPurchasesUpdated(okUpdate())
                }
                yield()
                processingWasAvailable.complete(reentrantCallback.isCompleted)
            }
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("publication-outside-processing").forCheckout(fixture)),
        )

        assertTrue(processingWasAvailable.await())
        assertEquals(PurchaseResult.Purchased, checkout.await())
        fixture.close()
    }

    @Test
    fun featurePublicationCanReenterTheSameTokenWithoutJoiningItsOwnCommit() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        lateinit var purchase: PlayPurchase
        var reentered = false
        fixture.core.featureInfo.onFeatureChange = { featureId, _, _, _ ->
            if (featureId == "pro" && !reentered) {
                reentered = true
                fixture.service.onPurchasesUpdated(okUpdate(purchase))
            }
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        purchase = playPurchase("reentrant-publication").forCheckout(fixture)

        withTimeout(1_000) {
            fixture.service.onPurchasesUpdated(okUpdate(purchase))
        }

        assertTrue(reentered)
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun unsolicitedUpdateRunsPipelineAndAppManagedNeverCompletesPlayPurchase() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(
            this,
            mode = PurchaseHandlingMode.APP_MANAGED,
            actions = actions,
            emissions = emissions,
        )
        fixture.store.upsertBinding(
            product().bindingFor(fixture.core.identity.distinctId(), nuxieManaged = false),
        )
        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "unsolicited",
                    obfuscatedAccountId = accountHash(fixture.core.identity.distinctId()),
                ),
            ),
        )

        assertTrue(fixture.store.load().getValue("unsolicited").synced)
        assertFalse("ack" in actions)
        assertFalse("consume" in actions)
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        assertEquals(
            "transaction_stream",
            emissions.single {
                it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED
            }.second["source"],
        )
        fixture.close()
    }

    @Test
    fun recoveryFirstVerifiedPurchaseCommitsOnceAcrossLaterStreamObservations() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, actions = actions, emissions = emissions)
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        val owner = fixture.core.identity.distinctId()
        val mappedProduct = product()
        fixture.service.rememberProduct(mappedProduct)
        fixture.store.upsertBinding(mappedProduct.bindingFor(owner))
        val purchase = playPurchase(
            "recovery-first",
            obfuscatedAccountId = accountHash(owner),
        )
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)

        fixture.service.recover()
        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        assertEquals(setOf("recovery-first"), fixture.store.load().keys)
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, actions.count { it == "sync" })
        assertEquals(1, actions.count { it == "ack" })
        assertEquals(
            "startup_recovery",
            emissions.single {
                it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED
            }.second["source"],
        )
        assertTrue(fixture.store.load().getValue("recovery-first").completionEmitted)
        fixture.close()
    }

    @Test
    fun checkoutStreamAndRecoveryCommitTheSameVerifiedPurchaseOnce() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, actions = actions, emissions = emissions)
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val purchase = playPurchase("all-producers").forCheckout(fixture)

        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        val persistedAfterCommit = actions.count { it == "persist" }
        val purchaseRevisionAfterCommit = fixture.core.features.capturePurchaseRevision()
        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        fixture.service.recover()

        assertEquals(setOf("all-producers"), fixture.store.load().keys)
        assertEquals(persistedAfterCommit, actions.count { it == "persist" })
        assertEquals(
            purchaseRevisionAfterCommit,
            fixture.core.features.capturePurchaseRevision(),
        )
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        assertEquals(1, actions.count { it == "sync" })
        assertEquals(1, actions.count { it == "ack" })
        assertEquals(
            "checkout",
            emissions.single {
                it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED
            }.second["source"],
        )
        assertTrue(fixture.store.load().getValue("all-producers").completionEmitted)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun aClaimWaitsWhileAnAnswerForThePurchaseIsApplied() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("applied-token", obfuscatedAccountId = accountHash(customer)),
        )
        fixture.synchronizer = { accepted(it.requester, it) }
        // The launch scan's send is accepted; applying that answer is held at its write.
        val answerApplying = CompletableDeferred<Unit>()
        val finishApplying = CompletableDeferred<Unit>()
        fixture.store.beforeUpsert = { evidence ->
            if (evidence.purchaseToken == "applied-token" && evidence.pendingSend == null && evidence.synced) {
                answerApplying.complete(Unit)
                finishApplying.await()
            }
        }
        val launch = async { fixture.service.recover() }
        runCurrent()
        answerApplying.await()
        val scanSend = fixture.store.load().getValue("applied-token").pendingSend
        assertEquals(PurchaseSyncReason.SYNC, scanSend?.reason)

        // A Restore starts while that answer is applied: its claim waits.
        val restore = async { fixture.service.restorePurchases() }
        runCurrent()
        assertEquals(
            "No new admission while the answer is applied",
            scanSend,
            fixture.store.load().getValue("applied-token").pendingSend,
        )

        fixture.store.beforeUpsert = null
        finishApplying.complete(Unit)
        launch.await()
        assertEquals(RestoreResult.Restored, restore.await())
        assertEquals(
            listOf(PurchaseSyncReason.SYNC, PurchaseSyncReason.RESTORE),
            fixture.requests.map { it.second.reason },
        )
        assertNull(fixture.store.load().getValue("applied-token").pendingSend)
        fixture.close()
    }

    @Test
    fun aWriteBasedOnASupersededAdmissionIsDropped() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 1_000, maxRetryDelayMillis = 1_000)
        val customer = fixture.core.identity.distinctId()
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("basis-token", obfuscatedAccountId = accountHash(customer)),
        )
        // The launch scan's send gets no answer: the entry keeps one failed attempt.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        fixture.service.recover()
        assertEquals(1, fixture.store.load().getValue("basis-token").syncAttempts)

        // Its retry is out when a recovery reads the entry to write Play's observation.
        val retryOut = CompletableDeferred<Unit>()
        val answerTheRetry = CompletableDeferred<Unit>()
        fixture.synchronizer = {
            retryOut.complete(Unit)
            answerTheRetry.await()
            accepted(it.requester, it)
        }
        advanceTimeBy(1_000)
        runCurrent()
        retryOut.await()
        val observing = CompletableDeferred<Unit>()
        val finishObserving = CompletableDeferred<Unit>()
        fixture.store.afterNextMappingsLoad = {
            observing.complete(Unit)
            finishObserving.await()
        }
        val recovery = async { fixture.service.recover() }
        runCurrent()
        observing.await()

        // The retry is accepted while that observation is being written.
        answerTheRetry.complete(Unit)
        runCurrent()
        finishObserving.complete(Unit)
        recovery.await()
        runCurrent()

        val entry = fixture.store.load().getValue("basis-token")
        assertNull(entry.pendingSend)
        assertEquals(
            "The observation is written over the answered entry, not the attempts it read before",
            0,
            entry.syncAttempts,
        )
        fixture.close()
    }

    @Test
    fun aWriteThatChangesWhatTheSendCarriesIsANewAdmission() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        val purchase = playPurchase("early-token", obfuscatedAccountId = accountHash(customer))
        val sent = mutableListOf<PurchaseEvidence>()
        val firstSendOut = CompletableDeferred<Unit>()
        val answerTheFirst = CompletableDeferred<Unit>()
        fixture.synchronizer = { evidence ->
            sent += evidence
            if (sent.size == 1) {
                firstSendOut.complete(Unit)
                answerTheFirst.await()
            }
            accepted(evidence.requester, evidence)
        }
        // Play delivers the purchase before its catalog mapping has loaded.
        val delivery = async { fixture.service.onPurchasesUpdated(okUpdate(purchase)) }
        runCurrent()
        firstSendOut.await()
        val firstAdmission = fixture.store.load().getValue("early-token").pendingSend
        assertNull(sent.single().productType)

        // The catalog loads and a recovery fills in what the send carries.
        fixture.service.rememberProduct(product(subscription = true, licensingPublicKey = null))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(purchase)
        val recovery = async { fixture.service.recover() }
        runCurrent()
        val changed = fixture.store.load().getValue("early-token")
        assertEquals(BillingClient.ProductType.SUBS, changed.productType)
        assertNotEquals("The changed send is a new admission", firstAdmission, changed.pendingSend)
        assertEquals(0, changed.syncAttempts)

        // The answer to the earlier payload cannot settle the changed entry: it is sent again as it now is.
        answerTheFirst.complete(Unit)
        delivery.await()
        recovery.await()
        runCurrent()
        assertEquals(2, sent.size)
        assertEquals(BillingClient.ProductType.SUBS, sent.last().productType)
        assertEquals("annual", sent.last().basePlanId)
        assertNull(fixture.store.load().getValue("early-token").pendingSend)
        fixture.close()
    }

    @Test
    fun aFinishIsRecordedOnTheEntryThatHoldsThePurchaseNow() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        val purchase = playPurchase("finish-token", obfuscatedAccountId = accountHash(customer))
        fixture.synchronizer = { evidence ->
            if (evidence.requester == customer) accepted(customer, evidence) else PurchaseSyncOutcome.Rejected()
        }
        // The server accepts the purchase; its acknowledgement with Play is out.
        val acknowledging = CompletableDeferred<Unit>()
        val finishAcknowledging = CompletableDeferred<Unit>()
        fixture.billing.acknowledgeStarted = acknowledging
        fixture.billing.holdAcknowledge = finishAcknowledging
        val delivery = async { fixture.service.onPurchasesUpdated(okUpdate(purchase)) }
        runCurrent()
        acknowledging.await()

        // Another customer's Restore admits a new claim for the purchase meanwhile
        // (its send waits for the one still finishing).
        fixture.core.identity.setDistinctId("customer-b")
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        val restore = async { fixture.service.restorePurchases() }
        runCurrent()
        val claim = fixture.store.load().getValue("finish-token").pendingSend
        assertEquals(PurchaseSyncReason.RESTORE, claim?.reason)
        assertEquals("customer-b", claim?.distinctId)

        // The finish is a fact about the purchase: it is recorded on the entry
        // that holds the purchase now, whatever its admission.
        finishAcknowledging.complete(Unit)
        delivery.await()
        restore.await()
        val entry = fixture.store.load().getValue("finish-token")
        assertTrue(entry.acknowledged)
        assertEquals("The claim stays owed", claim, entry.pendingSend)
        assertEquals("customer-b", fixture.requests.last().second.distinctId)
        fixture.close()
    }

    @Test
    fun aCapturedCompletionIsRecordedOnTheEntryThatHoldsThePurchaseNow() = runTest {
        val capturing = CompletableDeferred<Unit>()
        val finishCapturing = CompletableDeferred<Unit>()
        val fixture = fixture(this, capturePurchaseEventOverride = { _, _, _, _ ->
            capturing.complete(Unit)
            finishCapturing.await()
            true
        })
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        // The checkout's completion is being captured.
        val checkout = async { fixture.service.purchase(activity(), product(licensingPublicKey = null), null) }
        runCurrent()
        val purchase = playPurchase("completion-token").forCheckout(fixture)
        val delivery = async { fixture.service.onPurchasesUpdated(okUpdate(purchase)) }
        runCurrent()
        capturing.await()

        // Another customer's Restore admits a new claim for the purchase meanwhile.
        fixture.core.identity.setDistinctId("customer-b")
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        fixture.service.restorePurchases()
        val claim = fixture.store.load().getValue("completion-token").pendingSend
        assertEquals(PurchaseSyncReason.RESTORE, claim?.reason)
        assertEquals("customer-b", claim?.distinctId)

        // The captured completion is a fact about the purchase: it is recorded on
        // the entry that holds the purchase now, whatever its admission.
        finishCapturing.complete(Unit)
        delivery.await()
        checkout.await()
        val entry = fixture.store.load().getValue("completion-token")
        assertTrue(entry.completionEmitted)
        assertEquals("customer-b", entry.pendingSend?.distinctId)
        assertEquals(PurchaseSyncReason.RESTORE, entry.pendingSend?.reason)
        fixture.close()
    }

    @Test
    fun eachRestoreAndAlreadyOwnedClaimIsItsOwnAdmission() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        val kind = fixture.core.identity.customerKind(customer)
        val purchase = playPurchase("refresh-token", obfuscatedAccountId = accountHash(customer))
        // An earlier Restore's claim is still owed, with the same reason and payload:
        // it failed three times and is not due for an hour.
        val earlier = PurchaseSend.admit(PurchaseSyncReason.RESTORE, customer, kind)
        fixture.store.upsert(
            owedRestoreEvidence(customer).copy(
                productType = BillingClient.ProductType.INAPP,
                obfuscatedAccountId = accountHash(customer),
                pendingSend = earlier,
                syncAttempts = 3,
                retryDueAtMillis = Long.MAX_VALUE / 2,
            ),
        )
        val sent = mutableListOf<PurchaseEvidence>()
        fixture.synchronizer = { evidence ->
            sent += evidence
            accepted(evidence.requester, evidence)
        }
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)

        // A new Restore admits its own claim and sends it now; only its answer counts.
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())
        val restoreSend = checkNotNull(sent.single().pendingSend)
        assertEquals(PurchaseSyncReason.RESTORE, restoreSend.reason)
        assertNotEquals("A fresh admission, not the earlier claim", earlier.admissionId, restoreSend.admissionId)
        assertEquals(0, sent.single().syncAttempts)
        assertNull(sent.single().retryDueAtMillis)

        // A checkout that finds the product already owned admits its own claim too.
        fixture.billing.launchCode = BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        val owned = fixture.service.purchase(activity(), product(licensingPublicKey = null), null)
        assertTrue("$owned", (owned as PurchaseResult.Failed).cause is PurchaseAlreadyOwnedException)
        assertEquals(2, sent.size)
        val ownedSend = checkNotNull(sent.last().pendingSend)
        assertEquals(PurchaseSyncReason.PURCHASE, ownedSend.reason)
        assertNotEquals(restoreSend.admissionId, ownedSend.admissionId)
        assertEquals(0, sent.last().syncAttempts)
        fixture.close()
    }

    @Test
    fun aLaterDeliveryReadsTheEntryNotAnEarlierCommit() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        fun count(action: String) = actions.count { it == action }
        // The checkout commits and delivers its completion, but its send gets no answer.
        fixture.synchronizer = {
            actions += "sync"
            PurchaseSyncOutcome.Rejected()
        }
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        val purchase = playPurchase("redelivered").forCheckout(fixture)
        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(1, count("sync"))

        // Play delivers it again in the same process. Its entry is unanswered, so
        // it is sent: no earlier commit stands in for the entry.
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        assertEquals(2, count("sync"))
        assertNull(fixture.store.load().getValue("redelivered").pendingSend)
        val acknowledged = count("ack")

        // Answered, it is the purchase's record: a further delivery reads its flags
        // and sends, finishes and delivers nothing again.
        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        assertEquals(2, count("sync"))
        assertEquals(acknowledged, count("ack"))
        assertEquals(1, count(ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED))
        fixture.close()
    }

    @Test
    fun anotherCustomersSettledRecordGivesWayToThisCustomersCheckout() = runTest {
        val fixture = fixture(this)
        fixture.core.identity.setDistinctId("customer-b")
        // Customer A's purchase is only a record here: answered, acknowledged with
        // Play, and its checkout completion delivered.
        fixture.store.upsert(retainedRestoreEvidence("customer-a"))
        fixture.synchronizer = { accepted(it.requester, it) }

        // Play delivers that purchase to B's checkout.
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("refresh-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())

        assertEquals(
            "B's checkout starts the purchase's entry afresh and sends it as B's purchase",
            listOf("refresh-token" to PurchaseSyncRequest(PurchaseSyncReason.PURCHASE, "customer-b", CustomerKind.IDENTIFIED)),
            fixture.requests.toList(),
        )
        val entry = fixture.store.load().getValue("refresh-token")
        assertEquals("customer-b", entry.ownerDistinctId)
        assertEquals("customer-b", entry.buyerDistinctId)
        assertTrue(entry.completionEmitted)
        fixture.close()
    }

    @Test
    fun failedPurchaseEventCaptureRetriesTheSameCommitBeforeProjectionOrSync() = runTest {
        val actions = mutableListOf<String>()
        val captureResults = mutableListOf(false, true)
        val fixture = fixture(
            this,
            actions = actions,
            purchaseEventCaptureResults = captureResults,
        )
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val purchase = playPurchase("capture-retry").forCheckout(fixture)

        assertTrue(runCatching {
            fixture.service.onPurchasesUpdated(okUpdate(purchase))
        }.isFailure)
        assertFalse(checkout.isCompleted)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertFalse("sync" in actions)

        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(2, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, fixture.purchaseEventCaptureAttempts.distinct().size)
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        assertEquals(1, actions.count { it == "sync" })
        assertTrue(fixture.store.load().getValue("capture-retry").completionEmitted)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun completedCheckoutRoutesOnePurchaseEventAcrossRedelivery() = runTest {
        val journeyEvents = mutableListOf<StoredEvent>()
        val fixture = fixture(this, journeyEvents = journeyEvents)
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        val purchase = playPurchase("journey-routing-checkout").forCheckout(fixture)

        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        assertEquals(1, journeyEvents.size)
        assertEquals(SystemEventNames.PURCHASE_COMPLETED, journeyEvents.single().name)
        fixture.close()
    }

    @Test
    fun JourneyCheckoutUsesItsClaimedEffectIdForTheTerminalPurchaseEvent() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        val catalogProduct = product()
        val checkout = async {
            fixture.service.purchase(
                activity(),
                catalogProduct,
                null,
                expectedOwnerDistinctId = owner,
                outcomeCorrelation = CommerceOutcomeCorrelation("journey-effect", owner, "checkout-journey"),
            )
        }
        runCurrent()
        val purchase = playPurchase("journey-correlated").forCheckout(fixture)

        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(listOf("journey-effect"), fixture.purchaseCompletionEventIds)
        assertEquals("checkout-journey", fixture.store.load().getValue("journey-correlated").context?.journeyId)
        assertNull(fixture.store.loadBindings().single().context?.journeyId)
        assertNull(fixture.store.loadProductMappings().single().context?.journeyId)
        assertNull(catalogProduct.purchaseContext?.journeyId)
        assertEquals(
            "journey-effect",
            fixture.store.load().getValue("journey-correlated").checkoutCompletionEventId,
        )
        assertNull(
            fixture.store.loadBindings().single().outcomeEventId,
        )
        fixture.close()
    }

    @Test
    fun JourneyRestoreUsesItsClaimedEffectIdForNoPurchases() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        val owner = fixture.core.identity.distinctId()

        val result = fixture.service.restorePurchases(
            expectedOwnerDistinctId = owner,
            outcomeCorrelation = CommerceOutcomeCorrelation("restore-effect", owner),
        )

        assertEquals(RestoreResult.NoPurchases, result)
        assertEquals(listOf("restore-effect"), fixture.purchaseCompletionEventIds)
        assertEquals(SystemEventNames.RESTORE_NO_PURCHASES, emissions.single().first)
        fixture.close()
    }

    @Test
    fun duplicateVerifiedObservationReturnsWithoutJoiningTheOwningCapture() = runTest {
        val actions = mutableListOf<String>()
        val captureStarted = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val fixture = fixture(
            this,
            actions = actions,
            capturePurchaseEventOverride = { _, _, _, _ ->
                captureStarted.complete(Unit)
                releaseCapture.await()
                true
            },
        )
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        val purchase = playPurchase("coalesced-commit").forCheckout(fixture)
        val firstObservation = async { fixture.service.onPurchasesUpdated(okUpdate(purchase)) }
        captureStarted.await()

        val duplicateObservation = async { fixture.service.onPurchasesUpdated(okUpdate(purchase)) }
        runCurrent()

        assertFalse(firstObservation.isCompleted)
        assertTrue(duplicateObservation.isCompleted)
        assertFalse("sync" in actions)

        releaseCapture.complete(Unit)
        firstObservation.await()

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, actions.count { it == "sync" })
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        fixture.close()
    }

    @Test
    fun purchaseCaptureCanReenterTheSameTokenWithoutDeadlocking() = runTest {
        lateinit var fixture: Fixture
        lateinit var purchase: PlayPurchase
        var reentered = false
        fixture = fixture(
            this,
            capturePurchaseEventOverride = { _, _, _, _ ->
                if (!reentered) {
                    reentered = true
                    fixture.service.onPurchasesUpdated(okUpdate(purchase))
                }
                true
            },
        )
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        purchase = playPurchase("reentrant-capture").forCheckout(fixture)

        withTimeout(1_000) {
            fixture.service.onPurchasesUpdated(okUpdate(purchase))
        }

        assertTrue(reentered)
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, fixture.purchaseCompletionEventIds.size)
        fixture.close()
    }

    @Test
    fun purchaseCaptureCanReenterANewTokenWithoutAGlobalDrainCycle() = runTest {
        lateinit var fixture: Fixture
        lateinit var nestedPurchase: PlayPurchase
        var reentered = false
        fixture = fixture(
            this,
            capturePurchaseEventOverride = { _, _, _, _ ->
                if (!reentered) {
                    reentered = true
                    fixture.service.onPurchasesUpdated(okUpdate(nestedPurchase))
                }
                true
            },
        )
        val owner = fixture.core.identity.distinctId()
        val nestedProduct = product(
            productId = "nuxie-addon",
            storeProductId = "play-addon",
        )
        fixture.service.rememberProduct(nestedProduct)
        fixture.store.upsertBinding(nestedProduct.bindingFor(owner))
        nestedPurchase = playPurchase(
            "nested-reentrant-capture",
            products = listOf("play-addon"),
            obfuscatedAccountId = accountHash(owner),
        )
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        val checkoutPurchase = playPurchase("outer-reentrant-capture").forCheckout(fixture)

        withTimeout(1_000) {
            fixture.service.onPurchasesUpdated(okUpdate(checkoutPurchase))
        }

        assertTrue(reentered)
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(
            setOf("outer-reentrant-capture", "nested-reentrant-capture"),
            fixture.store.load().keys,
        )
        assertEquals(2, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(2, fixture.purchaseEventCaptureAttempts.distinct().size)
        fixture.close()
    }

    @Test
    fun unsolicitedTokenWithoutBindingSyncsButProjectsOnlyForAMatchingCustomer() = runTest {
        val unmatched = fixture(this)
        unmatched.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        unmatched.synchronizer = {
            assertFalse(unmatched.core.featureInfo.isAllowed("pro"))
            accepted(it.requester, it)
        }
        unmatched.service.onPurchasesUpdated(
            okUpdate(playPurchase("unmatched", obfuscatedAccountId = accountHash("someone-else"))),
        )

        val unmatchedEvidence = unmatched.store.load().getValue("unmatched")
        assertEquals(unmatched.core.identity.distinctId(), unmatched.requests.single().second.distinctId)
        assertTrue(unmatchedEvidence.synced)
        assertNull("No checkout here made it", unmatchedEvidence.buyerDistinctId)
        assertEquals("The server's answer names its owner", unmatched.core.identity.distinctId(), unmatchedEvidence.ownerDistinctId)

        val matching = fixture(this)
        matching.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        matching.synchronizer = {
            assertTrue(matching.core.featureInfo.isAllowed("pro"))
            accepted(it.requester, it)
        }
        matching.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "matching",
                    obfuscatedAccountId = accountHash(matching.core.identity.distinctId()),
                ),
            ),
        )

        assertTrue(matching.store.load().getValue("matching").synced)
        assertTrue(matching.core.featureInfo.isAllowed("pro"))
        unmatched.close()
        matching.close()
    }

    @Test
    fun purchaseDecisionPinsItsCatalogSnapshotBeforeAReplacementCanSwapAllowances() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        val purchaseTimeMapping = StoredProductMapping(
            storeProductId = "play-pro",
            nuxieProductId = "nuxie-pro",
            productType = BillingClient.ProductType.INAPP,
            consumable = false,
            featureAllowances = listOf(
                StoredFeatureAllowance("credits", FeatureType.METERED.name, false, 1.0),
            ),
            licensingPublicKey = "test-public-key",
        )
        fixture.store.upsertProductMapping(purchaseTimeMapping)
        var replacementInstalled = false
        fixture.store.afterNextMappingsLoad = { snapshot ->
            assertEquals(1.0, snapshot.single().featureAllowances.single().allowance!!, 0.0)
            fixture.store.upsertProductMapping(
                purchaseTimeMapping.copy(
                    featureAllowances = listOf(
                        StoredFeatureAllowance(
                            "credits",
                            FeatureType.METERED.name,
                            false,
                            99.0,
                        ),
                    ),
                ),
            )
            replacementInstalled = true
        }
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }

        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "catalog-snapshot-pin",
                    obfuscatedAccountId = accountHash(owner),
                ),
            ),
        )

        assertTrue(replacementInstalled)
        val retained = fixture.store.load().getValue("catalog-snapshot-pin")
        assertEquals(1.0, retained.pinnedFeatureAllowances?.single()?.allowance!!, 0.0)
        assertEquals(1.0, fixture.core.featureInfo.balance("credits")!!, 0.0)
        fixture.close()
    }

    @Test
    fun ambiguousCatalogEntriesForOnePlayProductDoNotResolveOrProject() = runTest {
        val fixture = fixture(this)
        fixture.service.rememberProduct(
            product(
                subscription = true,
                productId = "nuxie-monthly",
                basePlanId = "monthly",
                offerId = "monthly-launch",
                allowances = listOf(FeatureAllowance("monthly-feature", FeatureType.BOOLEAN)),
            ),
        )
        fixture.service.rememberProduct(
            product(
                subscription = true,
                productId = "nuxie-annual",
                basePlanId = "annual",
                offerId = "annual-launch",
                allowances = listOf(FeatureAllowance("annual-feature", FeatureType.BOOLEAN)),
            ),
        )
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }

        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "ambiguous-catalog",
                    obfuscatedAccountId = accountHash(fixture.core.identity.distinctId()),
                ),
            ),
        )

        val evidence = fixture.store.load().getValue("ambiguous-catalog")
        assertEquals(null, evidence.nuxieProductId)
        assertEquals(null, evidence.basePlanId)
        assertEquals(null, evidence.offerId)
        assertFalse(evidence.catalogResolved)
        assertFalse(fixture.core.featureInfo.isAllowed("monthly-feature"))
        assertFalse(fixture.core.featureInfo.isAllowed("annual-feature"))
        fixture.close()
    }

    @Test
    fun stalePurchaseSnapshotCannotEraseConcurrentBackendAcknowledgement() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "stale-purchase-snapshot",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                nuxieProductId = "nuxie-pro",
                purchaseState = StoredPurchaseState.PURCHASED,
                obfuscatedAccountId = accountHash(owner),
                buyerDistinctId = owner,
                pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, owner, CustomerKind.ANONYMOUS),
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
                signatureVerified = true,
            ),
        )
        fixture.store.afterNextLoad = { snapshot ->
            fixture.store.replaceWithoutRecording(
                snapshot.getValue("stale-purchase-snapshot").copy(
                    ownerDistinctId = owner,
                    pendingSend = null,
                    syncedCustomerId = owner,
                    backendSyncedAtMillis = 42L,
                ),
            )
        }
        var syncCalled = false
        fixture.synchronizer = {
            syncCalled = true
            accepted(owner, it)
        }

        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "stale-purchase-snapshot",
                    state = StoredPurchaseState.PENDING,
                    obfuscatedAccountId = accountHash(owner),
                ),
            ),
        )

        val evidence = fixture.store.load().getValue("stale-purchase-snapshot")
        assertEquals(StoredPurchaseState.PURCHASED, evidence.purchaseState)
        assertTrue(evidence.synced)
        assertEquals(42L, evidence.backendSyncedAtMillis)
        assertFalse(syncCalled)
        fixture.close()
    }

    @Test
    fun stalePendingCallbackCannotDowngradeOrPendAnAlreadyPurchasedCheckoutToken() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "stale-pending-checkout",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                nuxieProductId = "nuxie-pro",
                purchaseState = StoredPurchaseState.PURCHASED,
                obfuscatedAccountId = accountHash(owner),
                buyerDistinctId = owner,
                pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, owner, CustomerKind.ANONYMOUS),
                pinnedFeatureAllowances = listOf(
                    StoredFeatureAllowance("pro", FeatureType.BOOLEAN.name, false, null),
                ),
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
                signatureVerified = true,
            ),
        )
        var syncCalled = false
        fixture.synchronizer = {
            syncCalled = true
            accepted(owner, it)
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "stale-pending-checkout",
                    state = StoredPurchaseState.PENDING,
                ).forCheckout(fixture),
            ),
        )

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(
            StoredPurchaseState.PURCHASED,
            fixture.store.load().getValue("stale-pending-checkout").purchaseState,
        )
        assertTrue(syncCalled)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun permanentlyRejectedEvidenceFailsItsCorrelatedCheckoutInsteadOfAbandoningIt() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "rejected-checkout",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                nuxieProductId = "nuxie-pro",
                purchaseState = StoredPurchaseState.PURCHASED,
                obfuscatedAccountId = accountHash(owner),
                buyerDistinctId = owner,
                acknowledged = false,
                refused = true,
                firstSeenMillis = 1L,
                catalogResolved = true,
                signatureVerified = true,
            ),
        )
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("rejected-checkout").forCheckout(fixture)),
        )
        runCurrent()

        assertTrue(checkout.isCompleted)
        val outcome = checkout.await()
        assertTrue(outcome is PurchaseResult.Failed)
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_FAILED },
        )
        fixture.close()
    }

    @Test
    fun aCheckoutBindingNamesTheBuyerButNeverReassignsTheServersOwner() = runTest {
        val fixture = fixture(this)
        val provisionalOwner = fixture.core.identity.distinctId()
        val provenOwner = "customer-b"
        val purchase = playPurchase(
            "adopted-token",
            obfuscatedAccountId = accountHash(provenOwner),
        )
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )

        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        assertTrue("The server accepted it for the provisional customer", fixture.core.featureInfo.isAllowed("pro"))

        fixture.store.upsertBinding(product().bindingFor(provenOwner))
        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        val adoptedEvidence = fixture.store.load().getValue("adopted-token")
        assertEquals(provenOwner, adoptedEvidence.buyerDistinctId)
        assertEquals("Only the server reassigns an owner", provisionalOwner, adoptedEvidence.ownerDistinctId)

        fixture.core.identity.setDistinctId(provenOwner)
        fixture.core.featureInfo.publish(
            fixture.core.features.handleUserChange(provisionalOwner, provenOwner),
        )
        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        assertFalse("The buyer waits for the server, never a replayed grant", fixture.core.featureInfo.isAllowed("pro"))
        assertFalse(fixture.core.featureInfo.isAllowed("unlimited"))
        fixture.close()
    }

    @Test
    fun acceptanceForAnEarlierRequesterGrantsOnlyThatRequester() = runTest {
        val fixture = fixture(this)
        val provisionalOwner = fixture.core.identity.distinctId()
        val provenOwner = "customer-b"
        val purchase = playPurchase(
            "adopted-during-sync-token",
            obfuscatedAccountId = accountHash(provenOwner),
        )
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        val syncStarted = CountDownLatch(1)
        val releaseAcceptance = CountDownLatch(1)
        fixture.synchronizer = {
            syncStarted.countDown()
            assertTrue(releaseAcceptance.await(5, TimeUnit.SECONDS))
            accepted(provenOwner, it)
        }

        val update = async(Dispatchers.Default) {
            fixture.service.onPurchasesUpdated(okUpdate(purchase))
        }
        assertTrue(syncStarted.await(5, TimeUnit.SECONDS))
        fixture.store.upsertBinding(product().bindingFor(provenOwner))
        fixture.core.identity.setDistinctId(provenOwner)
        fixture.core.featureInfo.publish(
            fixture.core.features.handleUserChange(provisionalOwner, provenOwner),
        )
        releaseAcceptance.countDown()
        update.await()

        val adoptedEvidence = fixture.store.load().getValue("adopted-during-sync-token")
        assertEquals(provisionalOwner, fixture.requests.single().second.distinctId)
        assertEquals("The server attached it to the customer that asked", provisionalOwner, adoptedEvidence.ownerDistinctId)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertFalse(fixture.core.featureInfo.isAllowed("unlimited"))
        fixture.close()
    }

    @Test
    fun recoverySyncsProvisionallyAttributedEvidenceWithoutOptimisticOverlay() = runTest {
        val fixture = fixture(this)
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "legacy-blank-owner",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                acknowledged = false,
                firstSeenMillis = 1L,
            ),
        )
        // Play still owns it, so this observation owes it a send.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(playPurchase("legacy-blank-owner"))
        fixture.synchronizer = {
            assertEquals(fixture.core.identity.distinctId(), it.requester)
            assertFalse(fixture.core.featureInfo.isAllowed("pro"))
            accepted(it.requester, it)
        }

        fixture.service.recover()

        val recovered = fixture.store.load().getValue("legacy-blank-owner")
        assertTrue(recovered.synced)
        assertEquals(PurchaseSyncReason.SYNC, fixture.requests.single().second.reason)
        assertNull(recovered.buyerDistinctId)
        fixture.close()
    }

    @Test
    fun recoveryDoesNotProjectEvidenceWhoseRequiredSignatureWasNeverVerified() = runTest {
        val fixture = fixture(this, verifyPurchaseSignature = { _, _, _ -> false })
        val owner = fixture.core.identity.distinctId()
        // The catalog's licensing key requires Play's signature, which never verifies.
        fixture.service.rememberProduct(
            product(licensingPublicKey = "configured-key", allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "unverified-token",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                buyerDistinctId = owner,
                pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, owner, CustomerKind.ANONYMOUS),
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
                signatureVerified = false,
            ),
        )
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("unverified-token", obfuscatedAccountId = accountHash(owner)),
        )

        fixture.service.recover()

        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun anEntryStoredBeforeItsProductGainedALicensingKeyIsNotSentUnverified() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        fixture.service.rememberProduct(product(licensingPublicKey = "configured-key"))
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "legacy-unverified",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                buyerDistinctId = fixture.core.identity.distinctId(),
                pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, fixture.core.identity.distinctId(), CustomerKind.ANONYMOUS),
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
            ),
        )
        fixture.billing.failQueries = true
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }

        // The current catalog's key requires the signature the entry never had.
        fixture.service.recover()

        assertFalse("Nothing is sent", "sync" in actions)
        assertFalse("Nothing is acknowledged", "ack" in actions)
        assertNotNull(fixture.store.load().getValue("legacy-unverified").pendingSend)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun aLaunchNeitherSendsNorFinishesAnEntryTheCatalogNowRequiresSignedFor() = runTest {
        val actions = mutableListOf<String>()
        // The entry was stored before its product had a licensing key; it never verified.
        val store = RecordingEvidenceStore()
        store.upsert(owedRestoreEvidence("stored-customer").copy(signatureVerified = false))
        // The catalog now requires Play's signature for the product.
        store.upsertProductMapping(
            StoredProductMapping(
                storeProductId = "play-pro",
                nuxieProductId = "nuxie-pro",
                productType = BillingClient.ProductType.SUBS,
                consumable = false,
                licensingPublicKey = "catalog-key",
            ),
        )

        // The app starts with Billing unavailable; the launch's due send runs first.
        val launched = fixture(this, store = store, actions = actions)
        launched.billing.failQueries = true
        launched.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        runCurrent()
        launched.service.recover()

        assertFalse("Nothing is sent", "sync" in actions)
        assertFalse("Nothing is acknowledged", "ack" in actions)
        assertFalse("Nothing is consumed", "consume" in actions)
        assertNotNull(store.load().getValue("refresh-token").pendingSend)
        launched.close()
    }

    @Test
    fun anEntryStoredBeforeItsProductGainedALicensingKeyNeverUnlocksUnverified() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "legacy-projected",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                nuxieProductId = "nuxie-pro",
                authorityScope = "test-fixture",
                purchaseState = StoredPurchaseState.PURCHASED,
                buyerDistinctId = owner,
                pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, owner, CustomerKind.ANONYMOUS),
                obfuscatedAccountId = accountHash(owner),
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
            ),
        )
        fixture.service.rememberProduct(
            product(
                licensingPublicKey = "configured-key",
                allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
            ),
        )
        // The current catalog's key requires the signature the entry never had,
        // so it does not unlock early, before recovery or after it.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        runCurrent()
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.billing.failQueries = true

        fixture.service.recover()

        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun appManagedModeCannotBeUpgradedByAnOldManagedBindingForANewToken() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, mode = PurchaseHandlingMode.APP_MANAGED, actions = actions)
        fixture.store.upsertBinding(
            product().bindingFor(fixture.core.identity.distinctId(), nuxieManaged = true),
        )

        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "new-app-managed-token",
                    obfuscatedAccountId = accountHash(fixture.core.identity.distinctId()),
                ),
            ),
        )

        assertFalse(fixture.store.load().getValue("new-app-managed-token").nuxieManaged)
        assertFalse("ack" in actions)
        fixture.close()
    }

    @Test
    fun unmatchedTokenCannotInheritManagedModeFromAnInFlightCheckout() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, mode = PurchaseHandlingMode.NUXIE_MANAGED, actions = actions)
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(playPurchase("prior-token"))
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        fixture.settings.handlingMode = PurchaseHandlingMode.APP_MANAGED

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("prior-token").forCheckout(fixture)),
        )

        assertFalse(fixture.store.load().getValue("prior-token").nuxieManaged)
        assertFalse("ack" in actions)
        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "new-token",
                    state = StoredPurchaseState.PENDING,
                ).forCheckout(fixture),
            ),
        )
        assertEquals(PurchaseResult.Pending, checkout.await())
        fixture.close()
    }

    @Test
    fun managedCompletionRetriesUseTheirOwnExponentialBackoff() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(
            this,
            actions = actions,
            initialRetryDelayMillis = 10,
            maxRetryDelayMillis = 1_000,
        )
        fixture.billing.acknowledgeCodes += listOf(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.OK,
        )
        fixture.store.upsertBinding(product().bindingFor(fixture.core.identity.distinctId()))

        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "completion-backoff",
                    obfuscatedAccountId = accountHash(fixture.core.identity.distinctId()),
                ),
            ),
        )
        assertEquals(1, actions.count { it == "ack" })

        advanceTimeBy(19)
        runCurrent()
        assertEquals(1, actions.count { it == "ack" })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, actions.count { it == "ack" })

        advanceTimeBy(39)
        runCurrent()
        assertEquals(2, actions.count { it == "ack" })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, actions.count { it == "ack" })
        assertTrue(fixture.store.load().getValue("completion-backoff").acknowledged)
        assertEquals(3, fixture.store.load().getValue("completion-backoff").completionAttempts)
        fixture.close()
    }

    @Test
    fun ordinaryRestoreRefreshesSyncedSubscriptionAfterBackendGrantExpires() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, actions = actions, emissions = emissions)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(PurchaseEvidence(
            purchaseToken = "retained-subscription",
            packageName = "com.example.app",
            storeProductIds = listOf("play-pro"),
            nuxieProductId = "nuxie-pro",
            productType = BillingClient.ProductType.SUBS,
            purchaseState = StoredPurchaseState.PURCHASED,
            obfuscatedAccountId = accountHash(owner),
            buyerDistinctId = owner,
            ownerDistinctId = owner,
            acknowledged = true,
            syncedCustomerId = owner,
            backendSyncedAtMillis = 1L,
            firstSeenMillis = 1L,
            catalogResolved = true,
            completionEmitted = true,
            syncedEventEmitted = true,
            signatureVerified = true,
            authorityScope = "test-fixture",
        ))
        // The backend's previous grant has expired while Play still reports ownership.
        fixture.core.features.hydrateProfile(owner, Json.parseToJsonElement(
            """{"features":[]}""",
        ).jsonObject)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("retained-subscription", obfuscatedAccountId = accountHash(owner))
                .copy(acknowledged = true),
        )
        var reconciliations = 0
        fixture.synchronizer = {
            reconciliations += 1
            accepted(owner, it)
        }

        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())

        assertEquals("Restore must refresh provider authority even after an earlier sync", 1, reconciliations)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        assertEquals(null, fixture.billing.launched)
        assertEquals(0, actions.count { it == "ack" || it == "consume" })
        assertEquals(0, emissions.count { it.first == SystemEventNames.PURCHASE_COMPLETED })
        assertEquals(1, emissions.count { it.first == SystemEventNames.RESTORE_COMPLETED })
        assertTrue(fixture.store.load().getValue("retained-subscription").completionEmitted)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val failedRefresh = fixture.service.restorePurchases(expectedOwnerDistinctId = owner)
        assertTrue("A signed Restore must not accept an old sync after refresh fails", failedRefresh is RestoreResult.Failed)
        fixture.synchronizer = { accepted(owner, it) }
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases(expectedOwnerDistinctId = owner))
        assertEquals(0, emissions.count { it.first == SystemEventNames.PURCHASE_COMPLETED })
        fixture.close()
    }

    @Test
    fun cancellingHostRestoreDoesNotDiscardTheAcceptedRefresh() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(retainedRestoreEvidence(owner))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
        )
        val started = CompletableDeferred<Unit>()
        val serverAccepted = CompletableDeferred<Unit>()
        fixture.synchronizer = {
            started.complete(Unit)
            serverAccepted.await()
            accepted(owner, it)
        }
        val host = async { fixture.service.restorePurchases() }
        runCurrent()
        started.await()
        host.cancel()
        runCurrent()
        assertTrue(host.isCancelled)
        serverAccepted.complete(Unit)
        runCurrent()
        assertTrue("The SDK applies the accepted refresh after the host stops waiting", fixture.core.featureInfo.isAllowed("pro"))
        // Only a send without an answer counts as an attempt.
        assertEquals(0, fixture.store.load().getValue("refresh-token").syncAttempts)
        fixture.close()
    }

    @Test
    fun rejectedSyncedRestoreRefreshRetainsEvidenceForLaterRestore() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(retainedRestoreEvidence(owner))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
        )
        // A 401, 403 or 5xx is no answer.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }

        // Play ownership alone is not a restore: the server did not grant this one.
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)
        val retained = fixture.store.load().getValue("refresh-token")
        assertFalse("A rejection that is not a refusal keeps the owner", retained.refused)
        assertEquals(
            "The unanswered claim stays in the outbox",
            PurchaseSyncReason.RESTORE,
            retained.pendingSend?.reason,
        )
        assertTrue(retained.synced)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertTrue(fixture.service.restorePurchases(expectedOwnerDistinctId = owner) is RestoreResult.Failed)
        fixture.synchronizer = { accepted(owner, it) }
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases(expectedOwnerDistinctId = owner))
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        assertEquals(null, fixture.billing.launched)
        assertEquals(0, fixture.purchaseCompletionEventIds.size)
        fixture.close()
    }

    @Test
    fun failedSyncedRefreshCompletesPlayAndKeepsTheClaimQueued() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(retainedRestoreEvidence(owner).copy(acknowledged = false))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)),
        )
        var calls = 0
        fixture.synchronizer = { calls += 1; PurchaseSyncOutcome.Rejected() }
        assertTrue(fixture.service.restorePurchases(expectedOwnerDistinctId = owner) is RestoreResult.Failed)
        assertEquals("Verified evidence still needs Play completion", 1, actions.count { it == "ack" })
        assertTrue(fixture.store.load().getValue("refresh-token").acknowledged)
        // The claim is the purchase's outbox entry, retried until the server answers.
        assertEquals(PurchaseSyncReason.RESTORE, fixture.store.load().getValue("refresh-token").pendingSend?.reason)
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue("An unanswered claim is retried", calls > 1)
        fixture.close()
    }

    @Test
    fun unansweredRestoreRetriesWithBackoffUntilTheServerAnswers() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        // The restorer's own unsent entry takes the Restore's reason and stays in the outbox.
        fixture.store.upsert(owedRestoreEvidence(owner, reason = PurchaseSyncReason.SYNC))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
        )
        var calls = 0
        fixture.synchronizer = { calls += 1; PurchaseSyncOutcome.Rejected() }
        assertTrue(
            "An unanswered refresh is not a restore",
            fixture.service.restorePurchases() is RestoreResult.Failed,
        )
        advanceTimeBy(150_000)
        runCurrent()
        assertTrue("A transient failure keeps the send owed and retries it", calls >= 2)
        assertEquals(
            setOf(PurchaseSyncRequest(PurchaseSyncReason.RESTORE, owner, CustomerKind.ANONYMOUS)),
            fixture.requests.map { it.second }.toSet(),
        )

        fixture.synchronizer = { calls += 1; accepted(owner, it) }
        advanceTimeBy(600_000)
        runCurrent()
        assertNull("An answered send leaves the outbox", fixture.store.load().getValue("refresh-token").pendingSend)
        val answeredAfter = calls
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("Nothing is resent once answered", answeredAfter, calls)
        fixture.close()
    }

    @Test
    fun refusedRestoreOfAnotherCustomersPurchaseLeavesItWithItsHolder() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        val original = retainedRestoreEvidence(owner)
        fixture.store.upsert(original)
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
        )
        fixture.core.identity.setDistinctId("other-customer")
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }

        val result = fixture.service.restorePurchases(expectedOwnerDistinctId = "other-customer")

        assertTrue("A refused Restore is not a restore", result is RestoreResult.Failed)
        assertEquals(
            "Restore asks the server to move the purchase to the restoring customer",
            listOf("refresh-token" to PurchaseSyncRequest(
                PurchaseSyncReason.RESTORE,
                "other-customer",
                CustomerKind.IDENTIFIED,
            )),
            fixture.requests.toList(),
        )
        val retained = fixture.store.load().getValue("refresh-token")
        assertEquals("A refusal of someone else does not change the owner", owner, retained.ownerDistinctId)
        assertEquals(owner, retained.buyerDistinctId)
        assertFalse(retained.refused)
        assertNull(retained.pendingSend)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun syncedRefreshDropsTheOwnerOnlyWhenTheServerRefusesThePurchase() = runTest {
        val responses = listOf(
            Triple(409, """{"code":"google_play_purchase_conflict"}""", true),
            Triple(400, """{"code":"google_play_purchase_invalid"}""", true),
            Triple(400, """{"code":"google_play_purchase_unsupported"}""", true),
            Triple(401, "Invalid API key", false),
            Triple(400, """{"code":"invalid_purchase_token"}""", true),
            // A 2xx that is not a success is not an answer, whatever its body says.
            Triple(200, """{"success":false,"code":"invalid_purchase_token"}""", false),
        )
        for ((status, body, refusesThePurchase) in responses) {
            val fixture = fixture(this)
            val owner = fixture.core.identity.distinctId()
            fixture.store.upsert(retainedRestoreEvidence(owner))
            fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
                playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
            )
            val api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, object : HttpTransport {
                override fun execute(request: HttpTransport.Request) =
                    HttpTransport.Response(status, body.encodeToByteArray())
            })
            val synchronizer = NuxieApiPurchaseSynchronizer(api)
            fixture.synchronizer = { synchronizer.sync(it, fixture.requests.last().second) }
            assertTrue(fixture.service.restorePurchases(expectedOwnerDistinctId = owner) is RestoreResult.Failed)
            val retained = fixture.store.load().getValue("refresh-token")
            assertEquals("Owner after $status $body", owner.takeUnless { refusesThePurchase }, retained.ownerDistinctId)
            assertEquals(refusesThePurchase, retained.refused)
            assertEquals(
                "Only an answer clears the claim: $status $body",
                !refusesThePurchase,
                retained.pendingSend != null,
            )
            fixture.close()
        }
    }

    @Test
    fun checkoutSubmitsAsAPurchaseWhileUpdatesAndRecoveryOnlySync() = runTest {
        val fixture = fixture(this)
        val anonymous = fixture.core.identity.distinctId()
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("checkout-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())

        fixture.core.identity.setDistinctId("signed-in")
        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("update-token", obfuscatedAccountId = accountHash("signed-in"))),
        )
        fixture.store.upsert(
            owedRestoreEvidence("signed-in", PurchaseSyncReason.SYNC, CustomerKind.IDENTIFIED)
                .copy(purchaseToken = "recovered-token"),
        )
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("recovered-token", obfuscatedAccountId = accountHash("signed-in")),
        )
        fixture.service.recover()

        assertEquals(
            listOf(
                "checkout-token" to PurchaseSyncRequest(PurchaseSyncReason.PURCHASE, anonymous, CustomerKind.ANONYMOUS),
                "update-token" to PurchaseSyncRequest(PurchaseSyncReason.SYNC, "signed-in", CustomerKind.IDENTIFIED),
                "recovered-token" to PurchaseSyncRequest(PurchaseSyncReason.SYNC, "signed-in", CustomerKind.IDENTIFIED),
            ),
            fixture.requests.toList(),
        )
        fixture.close()
    }

    @Test
    fun restoreSubmitsAsARestoreForTheRestoringCustomerAndWhetherTheySignedIn() = runTest {
        val fixture = fixture(this)
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("restore-token", obfuscatedAccountId = accountHash("someone-else")),
        )
        fixture.core.identity.setDistinctId("signed-in")
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())

        fixture.core.identity.reset(keepAnonymousId = false)
        val anonymous = fixture.core.identity.distinctId()
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())

        assertEquals(
            listOf(
                "restore-token" to PurchaseSyncRequest(PurchaseSyncReason.RESTORE, "signed-in", CustomerKind.IDENTIFIED),
                "restore-token" to PurchaseSyncRequest(PurchaseSyncReason.RESTORE, anonymous, CustomerKind.ANONYMOUS),
            ),
            fixture.requests.toList(),
        )
        fixture.close()
    }

    @Test
    fun syncOfAnEarlierCustomersPurchaseDeclaresThemIdentified() = runTest {
        val fixture = fixture(this)
        fixture.core.identity.setDistinctId("earlier-customer")
        fixture.store.upsertBinding(product().bindingFor("earlier-customer"))
        fixture.core.identity.reset(keepAnonymousId = false)
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("earlier-token", obfuscatedAccountId = accountHash("earlier-customer")),
        )

        fixture.service.recover()

        assertEquals(
            // Never anonymous: the server merges only an owner declared anonymous.
            listOf("earlier-token" to PurchaseSyncRequest(
                PurchaseSyncReason.SYNC,
                "earlier-customer",
                CustomerKind.IDENTIFIED,
            )),
            fixture.requests.toList(),
        )
        fixture.close()
    }

    @Test
    fun kindRecordedAtAdmissionSurvivesAReset() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 10, maxRetryDelayMillis = 10)
        val anonymous = fixture.core.identity.distinctId()
        val outcomes = mutableListOf<PurchaseSyncOutcome?>(PurchaseSyncOutcome.Rejected(), null)
        fixture.synchronizer = { outcomes.removeAt(0) ?: accepted(anonymous, it) }
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("kind-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())

        // After the reset this device no longer knows the anonymous buyer.
        fixture.core.identity.reset(keepAnonymousId = false)
        advanceTimeBy(100)
        runCurrent()

        val admitted = PurchaseSyncRequest(PurchaseSyncReason.PURCHASE, anonymous, CustomerKind.ANONYMOUS)
        assertEquals(listOf(admitted, admitted), fixture.requests.map { it.second })
        fixture.close()
    }

    @Test
    fun checkoutReasonSurvivesACrashBeforeTheFirstSend() = runTest {
        val captureStarted = CompletableDeferred<Unit>()
        val crash = CompletableDeferred<Unit>()
        val fixture = fixture(
            this,
            capturePurchaseEventOverride = { name, _, _, _ ->
                if (name == SystemEventNames.PURCHASE_COMPLETED) {
                    captureStarted.complete(Unit)
                    crash.await()
                }
                true
            },
        )
        val buyer = fixture.core.identity.distinctId()
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        val bought = playPurchase("crashed-checkout").forCheckout(fixture)
        val update = async { fixture.service.onPurchasesUpdated(okUpdate(bought)) }
        runCurrent()
        captureStarted.await()
        assertTrue("The process stops before anything is sent", fixture.requests.isEmpty())

        val restarted = fixture(this, store = fixture.store)
        restarted.billing.active[BillingClient.ProductType.INAPP] = listOf(bought)
        restarted.service.recover()

        assertEquals(
            "crashed-checkout" to PurchaseSyncRequest(PurchaseSyncReason.PURCHASE, buyer, CustomerKind.ANONYMOUS),
            restarted.requests.first(),
        )
        restarted.close()
        // Let the first process's blocked capture finish so its work can wind down.
        crash.complete(Unit)
        runCurrent()
        checkout.cancel()
        update.cancel()
        fixture.close()
    }

    @Test
    fun passiveScanDoesNotProjectAPurchaseWithoutTheCustomersPlayAccount() = runTest {
        for (playAccount in listOf(null, accountHash("someone-else"))) {
            val fixture = fixture(this)
            val customer = fixture.core.identity.distinctId()
            fixture.service.rememberProduct(
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
            )
            // Retained as this customer's, but Play names no account or another one.
            fixture.store.upsert(owedRestoreEvidence(customer).copy(
                purchaseToken = "not-this-customers",
                productType = BillingClient.ProductType.INAPP,
                obfuscatedAccountId = playAccount,
            ))
            fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
                playPurchase("not-this-customers", obfuscatedAccountId = playAccount),
            )
            val serverAnswered = CompletableDeferred<Unit>()
            var projectedBeforeTheServerAnswered: Boolean? = null
            fixture.synchronizer = {
                projectedBeforeTheServerAnswered = fixture.core.featureInfo.isAllowed("pro")
                serverAnswered.await()
                PurchaseSyncOutcome.Rejected()
            }

            val recovery = async { fixture.service.recover() }
            runCurrent()

            assertEquals("Play account $playAccount", false, projectedBeforeTheServerAnswered)
            assertFalse(fixture.core.featureInfo.isAllowed("pro"))
            serverAnswered.complete(Unit)
            recovery.await()
            fixture.close()
        }
    }

    @Test
    fun aPurchasePlayStopsReturningIsStillSentAndUnlockedUntilTheServerAnswers() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 1_000, maxRetryDelayMillis = 1_000)
        val customer = fixture.core.identity.distinctId()
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        val purchase = playPurchase("returning-token", obfuscatedAccountId = accountHash(customer))
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        // No answer yet, so the entry stays owed and a retry is scheduled.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        fixture.service.recover()
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        assertEquals(1, fixture.requests.size)

        // Play stops returning it (a refund, say): the server still decides, and
        // until it answers the early unlock holds.
        fixture.billing.active[BillingClient.ProductType.INAPP] = emptyList()
        fixture.service.recover()
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        assertNotNull(fixture.store.load().getValue("returning-token").pendingSend)
        val sentBeforeTheRetry = fixture.requests.size
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("A scheduled retry still sends it", fixture.requests.size > sentBeforeTheRetry)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // The server answers: a refusal ends the early unlock.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(fixture.store.load().getValue("returning-token").pendingSend)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun aStoredEntryUnlocksEarlyAtLaunchBeforePlayIsAsked() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("stored-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue("Play delivered it, so the buyer's own purchase unlocks early", fixture.core.featureInfo.isAllowed("pro"))

        val relaunched = fixture(this, store = fixture.store)
        relaunched.synchronizer = { PurchaseSyncOutcome.Rejected() }
        relaunched.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        runCurrent()
        assertTrue("The stored entry alone unlocks it", relaunched.core.featureInfo.isAllowed("pro"))
        assertTrue("Play is not asked", relaunched.billing.queries.isEmpty())
        assertEquals(customer, relaunched.store.load().getValue("stored-token").pendingSend?.distinctId)
        relaunched.close()
        fixture.close()
    }

    @Test
    fun buyersOwnCheckoutUnlocksBeforeTheServerAnswersAndAConflictDropsItAtOnce() = runTest {
        val fixture = fixture(this)
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        val bodies = mutableListOf<String>()
        val conflict = NuxieApiPurchaseSynchronizer(conflictApi(bodies))
        var unlockedBeforeTheServerAnswered = false
        fixture.synchronizer = {
            unlockedBeforeTheServerAnswered = fixture.core.featureInfo.isAllowed("pro")
            conflict.sync(it, fixture.requests.last().second)
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()

        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("conflicted-checkout").forCheckout(fixture)))

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(unlockedBeforeTheServerAnswered)
        assertFalse("A refused purchase stops granting at once", fixture.core.featureInfo.isAllowed("pro"))
        assertTrue(fixture.store.load().getValue("conflicted-checkout").refused)
        assertTrue(bodies.single().contains(""""reason":"purchase","customer_kind":"anonymous"}"""))
        fixture.close()
    }

    @Test
    fun conflictOnRestoreLeavesAccessToTheProfileItRequests() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(retainedRestoreEvidence(owner))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
        )
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // The purchase has since moved to a signed-in customer on another phone.
        val bodies = mutableListOf<String>()
        val conflict = NuxieApiPurchaseSynchronizer(conflictApi(bodies))
        fixture.synchronizer = { conflict.sync(it, fixture.requests.last().second) }
        val result = fixture.service.restorePurchases()

        assertTrue(result is RestoreResult.Failed)
        val retained = fixture.store.load().getValue("refresh-token")
        assertNull("The server says it is no longer theirs", retained.ownerDistinctId)
        assertTrue(retained.refused)
        assertTrue("Every answer asks for a fresh profile", fixture.authorityRefreshes >= 2)
        assertTrue(bodies.single().contains(""""reason":"restore","customer_kind":"anonymous"}"""))
        // The earlier 200's Features hold only until the profile requested after the answers lands.
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.core.features.hydrateProfile(owner, Json.parseToJsonElement("""{"features":[]}""").jsonObject)
        assertFalse("The fresh profile decides", fixture.core.featureInfo.isAllowed("pro"))

        // After a restart, seeing the purchase in Play again replays nothing.
        val restarted = fixture(this, store = fixture.store)
        restarted.billing.active[BillingClient.ProductType.SUBS] = fixture.billing.active.getValue(
            BillingClient.ProductType.SUBS,
        )
        restarted.service.recover()
        assertFalse(restarted.core.featureInfo.isAllowed("pro"))
        restarted.close()
        fixture.close()
    }

    @Test
    fun restoreTransferToACustomerNoLongerCurrentWithdrawsTheCurrentOwnersOverlay() = runTest {
        val fixture = fixture(this)
        val proProduct = product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)))
        fixture.service.rememberProduct(proProduct)
        fixture.core.identity.setDistinctId("customer-a")
        // A's checkout is not verified yet, so A keeps an optimistic overlay.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val checkout = async { fixture.service.purchase(activity(), proProduct, null) }
        runCurrent()
        val bought = playPurchase("transferred-token").forCheckout(fixture)
        fixture.service.onPurchasesUpdated(okUpdate(bought))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(bought.copy(acknowledged = true))

        fixture.core.identity.setDistinctId("customer-b")
        fixture.core.featureInfo.publish(fixture.core.features.handleUserChange("customer-a", "customer-b"))
        val verificationStarted = CompletableDeferred<Unit>()
        val serverAnswers = CompletableDeferred<Unit>()
        fixture.synchronizer = {
            verificationStarted.complete(Unit)
            serverAnswers.await()
            accepted("customer-b", it)
        }
        val restore = async { fixture.service.restorePurchases() }
        runCurrent()
        verificationStarted.await()

        // The app returns to A before the server moves the purchase to B.
        fixture.core.identity.setDistinctId("customer-a")
        fixture.core.featureInfo.publish(
            fixture.core.features.handleUserChange(
                "customer-b",
                "customer-a",
                fixture.service.withOptimisticProjectionSnapshot("customer-a") { it },
            ),
        )
        // B's Restore took the purchase's outbox entry before it was sent, so
        // the unsent purchase is no longer A's own: A's early unlock has ended.
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        serverAnswers.complete(Unit)
        restore.await()

        assertEquals("customer-b", fixture.store.load().getValue("transferred-token").ownerDistinctId)
        assertFalse("A purchase moved to B grants A nothing", fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun observationFinishingAfterARestoreConflictDoesNotReplayTheRefusedGrant() = runTest {
        val captureStarted = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val fixture = fixture(
            this,
            capturePurchaseEventOverride = { name, _, _, _ ->
                if (name == SystemEventNames.PURCHASE_COMPLETED) {
                    captureStarted.complete(Unit)
                    releaseCapture.await()
                }
                true
            },
        )
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(retainedRestoreEvidence(owner).copy(completionEmitted = false))
        val purchase = playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true)
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(purchase)

        // A Play update reads the retained grant, then waits on its completion capture.
        val update = async { fixture.service.onPurchasesUpdated(okUpdate(purchase)) }
        runCurrent()
        captureStarted.await()
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)
        assertTrue(fixture.store.load().getValue("refresh-token").refused)

        releaseCapture.complete(Unit)
        update.await()

        assertFalse("The refused grant must not come back", fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun staleObservationKeepsTheOwnerARestoreTransferredTo() = runTest {
        val fixture = fixture(this)
        // A's synced purchase, seen on this phone while A was not the current customer.
        fixture.store.upsert(retainedRestoreEvidence("customer-a").copy(completionEmitted = false))
        val purchase = playPurchase("refresh-token", obfuscatedAccountId = accountHash("customer-a"))
            .copy(acknowledged = true)
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(purchase)
        fixture.core.identity.setDistinctId("customer-b")
        val verificationStarted = CompletableDeferred<Unit>()
        val serverAnswers = CompletableDeferred<Unit>()
        fixture.synchronizer = {
            verificationStarted.complete(Unit)
            serverAnswers.await()
            accepted("customer-b", it)
        }
        val restore = async { fixture.service.restorePurchases() }
        runCurrent()
        verificationStarted.await()

        // The server's answer moving the purchase to B arrives while a Play update
        // is being written. The update is built over the entry as it stands and
        // written in the same step, so the answer applies after it, not under it.
        fixture.store.afterNextMappingsLoad = { serverAnswers.complete(Unit) }
        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        assertEquals(RestoreResult.Restored, restore.await())

        val retained = fixture.store.load().getValue("refresh-token")
        assertEquals("customer-b", retained.ownerDistinctId)
        assertEquals("The binding still names the buyer", "customer-a", retained.buyerDistinctId)
        fixture.core.identity.setDistinctId("customer-a")
        val restarted = fixture(this, store = fixture.store)
        restarted.billing.active[BillingClient.ProductType.SUBS] = listOf(purchase)
        restarted.service.recover()
        assertFalse("A purchase moved to B grants A nothing", restarted.core.featureInfo.isAllowed("pro"))
        restarted.close()
        fixture.close()
    }

    @Test
    fun restoreReportsAFailureForAPurchaseWhoseSignatureFailed() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions, verifyPurchaseSignature = { _, _, _ -> false })
        val owner = fixture.core.identity.distinctId()
        fixture.service.rememberProduct(product())
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("forged", obfuscatedAccountId = accountHash(owner)),
        )

        val host = fixture.service.restorePurchases()
        val journey = fixture.service.restorePurchases(expectedOwnerDistinctId = owner)

        for (result in listOf(host, journey)) {
            assertTrue("A purchase that failed verification is not NoPurchases: $result", result is RestoreResult.Failed)
            assertTrue((result as RestoreResult.Failed).cause.message.orEmpty().contains("could not be verified"))
        }
        assertNull("An unverified purchase owes no send", fixture.store.load().getValue("forged").pendingSend)
        assertEquals(0, actions.count { it == SystemEventNames.RESTORE_NO_PURCHASES })
        assertTrue(fixture.requests.isEmpty())
        fixture.close()
    }

    @Test
    fun restoreRefusalAsksTheServerForAFreshProfileWhichDecidesAccess() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(retainedRestoreEvidence(owner))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
        )
        val proProfile = Json.parseToJsonElement(
            """{"features":[{"id":"pro","type":"boolean","unlimited":false},{"id":"other","type":"boolean","unlimited":false}]}""",
        ).jsonObject
        // The profile was fetched while the purchase still granted "pro".
        fixture.core.features.hydrateProfile(owner, proProfile)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)

        assertEquals("A refusal asks for a fresh profile", 1, fixture.authorityRefreshes)
        // Until it lands (or while offline), the cached profile keeps showing the
        // purchase, the way it shows a refund this device has not heard about.
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.core.features.hydrateProfile(
            owner,
            Json.parseToJsonElement("""{"features":[{"id":"other","type":"boolean","unlimited":false}]}""").jsonObject,
        )
        assertFalse("The server's profile decides", fixture.core.featureInfo.isAllowed("pro"))
        assertTrue(fixture.core.featureInfo.isAllowed("other"))
        fixture.close()
    }

    @Test
    fun restoreTransferAwayFromTheCurrentCustomerAsksForTheirFreshProfile() = runTest {
        val fixture = fixture(this)
        fixture.core.identity.setDistinctId("customer-a")
        fixture.store.upsert(retainedRestoreEvidence("customer-a"))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash("customer-a")).copy(acknowledged = true),
        )
        fixture.core.identity.setDistinctId("customer-b")
        fixture.core.featureInfo.publish(fixture.core.features.handleUserChange("customer-a", "customer-b"))
        val verificationStarted = CompletableDeferred<Unit>()
        val serverAnswers = CompletableDeferred<Unit>()
        fixture.synchronizer = {
            verificationStarted.complete(Unit)
            serverAnswers.await()
            accepted("customer-b", it)
        }
        val restore = async { fixture.service.restorePurchases() }
        runCurrent()
        verificationStarted.await()

        // The app returns to A, whose cached profile still carries the purchase's grant.
        fixture.core.identity.setDistinctId("customer-a")
        fixture.core.featureInfo.publish(fixture.core.features.handleUserChange("customer-b", "customer-a"))
        fixture.core.features.hydrateProfile(
            "customer-a",
            Json.parseToJsonElement("""{"features":[{"id":"pro","type":"boolean","unlimited":false}]}""").jsonObject,
        )
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        serverAnswers.complete(Unit)
        assertEquals(RestoreResult.Restored, restore.await())

        assertEquals("customer-b", fixture.store.load().getValue("refresh-token").ownerDistinctId)
        // Only the current customer's profile is fetched after an answer; B is not current.
        // Until A's next profile lands, A's cached profile keeps showing the moved purchase.
        assertEquals(0, fixture.authorityRefreshes)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.core.features.hydrateProfile(
            "customer-a",
            Json.parseToJsonElement("""{"features":[]}""").jsonObject,
        )
        assertFalse("A's fresh profile no longer grants the moved purchase", fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun refusalAfterTheRequesterStoppedBeingCurrentAsksForNoProfile() = runTest {
        val fixture = fixture(this)
        fixture.core.identity.setDistinctId("customer-a")
        fixture.store.upsert(retainedRestoreEvidence("customer-a"))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash("customer-a")).copy(acknowledged = true),
        )
        val verificationStarted = CompletableDeferred<Unit>()
        val serverAnswers = CompletableDeferred<Unit>()
        fixture.synchronizer = {
            verificationStarted.complete(Unit)
            serverAnswers.await()
            PurchaseSyncOutcome.Rejected(refused = true)
        }
        val restore = async { fixture.service.restorePurchases() }
        runCurrent()
        verificationStarted.await()

        // The app moves to B before the server refuses A's Restore.
        fixture.core.identity.setDistinctId("customer-b")
        serverAnswers.complete(Unit)
        assertTrue(restore.await() is RestoreResult.Failed)

        // A is no longer current: their profile is fetched when they return, not now.
        assertEquals(0, fixture.authorityRefreshes)
        assertNull("The server says A no longer owns it", fixture.store.load().getValue("refresh-token").ownerDistinctId)
        fixture.close()
    }

    @Test
    fun checkoutRetryKeepsThePurchaseReason() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 10, maxRetryDelayMillis = 10)
        val buyer = fixture.core.identity.distinctId()
        val outcomes = mutableListOf<PurchaseSyncOutcome?>(PurchaseSyncOutcome.Rejected(), null)
        fixture.synchronizer = { outcomes.removeAt(0) ?: accepted(buyer, it) }
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("retried-checkout").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())

        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            "A transient failure must not turn a checkout into a sync",
            listOf(PurchaseSyncReason.PURCHASE, PurchaseSyncReason.PURCHASE),
            fixture.requests.map { it.second.reason },
        )
        assertTrue(fixture.store.load().getValue("retried-checkout").synced)
        fixture.close()
    }

    @Test
    fun checkoutReasonSurvivesARestart() = runTest {
        val fixture = fixture(this)
        val buyer = fixture.core.identity.distinctId()
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        val bought = playPurchase("restarted-checkout").forCheckout(fixture)
        fixture.service.onPurchasesUpdated(okUpdate(bought))
        assertEquals(PurchaseResult.Purchased, checkout.await())

        val restarted = fixture(this, store = fixture.store)
        restarted.billing.active[BillingClient.ProductType.INAPP] = listOf(bought)
        restarted.service.recover()

        assertEquals(
            "restarted-checkout" to PurchaseSyncRequest(PurchaseSyncReason.PURCHASE, buyer, CustomerKind.ANONYMOUS),
            restarted.requests.first(),
        )
        restarted.close()
        fixture.close()
    }

    @Test
    fun restoreRetryKeepsTheRestoreReasonAndItsRequester() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 10, maxRetryDelayMillis = 10)
        // Another customer's unsent purchase: the Restore is written into the
        // outbox as the restoring customer's claim before it is sent.
        fixture.store.upsert(owedRestoreEvidence("customer-a", kind = CustomerKind.IDENTIFIED))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash("customer-a")).copy(acknowledged = true),
        )
        fixture.core.identity.setDistinctId("customer-b")
        val outcomes = mutableListOf<PurchaseSyncOutcome?>(PurchaseSyncOutcome.Rejected(), null)
        fixture.synchronizer = { outcomes.removeAt(0) ?: accepted("customer-b", it) }
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)

        advanceTimeBy(100)
        runCurrent()

        val restore = PurchaseSyncRequest(PurchaseSyncReason.RESTORE, "customer-b", CustomerKind.IDENTIFIED)
        assertEquals(listOf(restore, restore), fixture.requests.map { it.second })
        assertEquals("customer-b", fixture.store.load().getValue("refresh-token").ownerDistinctId)
        fixture.close()
    }

    @Test
    fun restoreOfAPurchaseNeverRecordedHereAdmitsTheRestore() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 10, maxRetryDelayMillis = 10)
        // Another install bought it: this device has no record of the purchase.
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash("customer-a")).copy(acknowledged = true),
        )
        fixture.core.identity.setDistinctId("customer-b")
        val outcomes = mutableListOf<PurchaseSyncOutcome?>(PurchaseSyncOutcome.Rejected(), null)
        fixture.synchronizer = { outcomes.removeAt(0) ?: accepted("customer-b", it) }
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)

        advanceTimeBy(100)
        runCurrent()

        val restore = PurchaseSyncRequest(PurchaseSyncReason.RESTORE, "customer-b", CustomerKind.IDENTIFIED)
        assertEquals(listOf(restore, restore), fixture.requests.map { it.second })
        assertEquals("customer-b", fixture.store.load().getValue("refresh-token").ownerDistinctId)
        fixture.close()
    }

    @Test
    fun restoreOfARefusedPurchaseGrantsNothingUntilTheServerAccepts() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 10, maxRetryDelayMillis = 10)
        val customer = fixture.core.identity.distinctId()
        refuseThenRestoreWithATransientFailure(fixture, customer)
        assertFalse("An unanswered Restore claim grants nothing", fixture.core.featureInfo.isAllowed("pro"))

        // The unanswered claim stays in the outbox and its retry is accepted.
        fixture.synchronizer = { accepted(customer, it) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(
            listOf(PurchaseSyncReason.SYNC, PurchaseSyncReason.RESTORE, PurchaseSyncReason.RESTORE),
            fixture.requests.map { it.second.reason },
        )
        val restored = fixture.store.load().getValue("revoked-then-restored")
        assertTrue(restored.synced)
        assertFalse(restored.refused)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun anUnansweredRestoreClaimIsSentAgainAfterARestart() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        val purchase = refuseThenRestoreWithATransientFailure(fixture, customer)

        val restarted = fixture(this, store = fixture.store)
        restarted.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        restarted.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        runCurrent()
        assertFalse(restarted.core.featureInfo.isAllowed("pro"))
        restarted.synchronizer = { accepted(customer, it) }
        restarted.service.recover()

        assertEquals(
            "The queued claim goes out at launch, as it was admitted",
            "revoked-then-restored" to PurchaseSyncRequest(PurchaseSyncReason.RESTORE, customer, CustomerKind.ANONYMOUS),
            restarted.requests.single(),
        )
        assertTrue(restarted.store.load().getValue("revoked-then-restored").synced)
        restarted.close()
        fixture.close()
    }

    /** An app-start sync is refused, then a Restore of the same purchase fails transiently. */
    private suspend fun TestScope.refuseThenRestoreWithATransientFailure(
        fixture: Fixture,
        customer: String,
    ): PlayPurchase {
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        val purchase = playPurchase("revoked-then-restored", obfuscatedAccountId = accountHash(customer))
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        fixture.service.recover()
        assertTrue(fixture.store.load().getValue("revoked-then-restored").refused)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)
        runCurrent()
        return purchase
    }

    @Test
    fun aRefusedRestoreIsNotRetriedInTheBackground() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 10, maxRetryDelayMillis = 10)
        fixture.store.upsert(owedRestoreEvidence("customer-a", kind = CustomerKind.IDENTIFIED))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash("customer-a")).copy(acknowledged = true),
        )
        fixture.core.identity.setDistinctId("customer-b")
        val outcomes = mutableListOf<PurchaseSyncOutcome>(
            PurchaseSyncOutcome.Rejected(),
            PurchaseSyncOutcome.Rejected(refused = true),
        )
        fixture.synchronizer = { outcomes.removeFirstOrNull() ?: PurchaseSyncOutcome.Rejected() }
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)
        advanceTimeBy(100)
        runCurrent()

        fixture.service.recover()

        advanceTimeBy(100)
        runCurrent()

        val restore = PurchaseSyncRequest(PurchaseSyncReason.RESTORE, "customer-b", CustomerKind.IDENTIFIED)
        assertEquals(
            // The Restore of another customer's purchase is the outbox entry: its
            // failed send is retried; once refused, it is over.
            listOf(restore, restore),
            fixture.requests.map { it.second },
        )
        val refused = fixture.store.load().getValue("refresh-token")
        assertNull(refused.pendingSend)
        assertTrue(refused.refused)
        fixture.close()
    }

    @Test
    fun aRestoreThatCannotSaveStillSendsEachPurchaseOnceAndReportsItsAnswer() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val owner = fixture.core.identity.distinctId()
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("unsaved-restore", obfuscatedAccountId = accountHash(owner)),
        )
        // A full disk: nothing can be saved, so the outbox is off.
        fixture.store.failAllWrites = true

        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())
        assertEquals(
            listOf("unsaved-restore" to PurchaseSyncReason.RESTORE),
            fixture.requests.map { it.first to it.second.reason },
        )
        assertEquals("The answer refreshes the profile", 1, fixture.authorityRefreshes)
        assertTrue("Nothing is saved", fixture.store.load().isEmpty())
        assertFalse("Nothing is acknowledged", "ack" in actions)
        // A Journey's Restore also needs the purchase saved and completed with Play,
        // which waits for the next launch.
        assertTrue(fixture.service.restorePurchases(expectedOwnerDistinctId = owner) is RestoreResult.Failed)
        assertEquals(0, actions.count { it == SystemEventNames.RESTORE_NO_PURCHASES })
        fixture.close()
    }

    @Test
    fun refusalThatCannotBeWrittenEndsAccessAndIsAnsweredAgainAtTheNextLaunch() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        val owned = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
        )
        fixture.billing.active[BillingClient.ProductType.SUBS] = owned
        // The launch scan gets no answer: the buyer's own unanswered purchase unlocks early.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        fixture.service.recover()
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        val conflict = NuxieApiPurchaseSynchronizer(conflictApi(mutableListOf()))
        fixture.synchronizer = { conflict.sync(it, fixture.requests.last().second) }
        // Only the write that records the refusal fails, which turns the outbox off.
        fixture.store.rejectEvidenceUpsert = { it.purchaseToken == "refresh-token" && it.refused }
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)

        assertFalse("While the outbox is off nothing unlocks early", fixture.core.featureInfo.isAllowed("pro"))
        assertNotNull("The unwritten answer leaves the entry owed", fixture.store.load().getValue("refresh-token").pendingSend)

        // It stays off for the session: a store that saves again changes nothing, and
        // no timer sends the entry.
        fixture.store.rejectEvidenceUpsert = null
        val sent = fixture.requests.size
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(sent, fixture.requests.size)
        assertNotNull(fixture.store.load().getValue("refresh-token").pendingSend)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()

        // The next launch sends it again and saves the refusal.
        val restarted = fixture(this, store = fixture.store)
        restarted.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        restarted.billing.active[BillingClient.ProductType.SUBS] = owned
        restarted.service.recover()
        assertEquals(listOf("refresh-token"), restarted.requests.map { it.first }.distinct())
        val answered = fixture.store.load().getValue("refresh-token")
        assertNull(answered.pendingSend)
        assertTrue(answered.refused)
        assertFalse(restarted.core.featureInfo.isAllowed("pro"))
        restarted.close()

        val again = fixture(this, store = fixture.store)
        again.billing.active[BillingClient.ProductType.SUBS] = owned
        again.service.recover()
        assertTrue("A refused purchase is not sent again on its own", again.requests.isEmpty())
        again.close()
    }

    @Test
    fun restoreReportsRestoredOnlyForAPurchaseTheServerRestored() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("owned-by-play", obfuscatedAccountId = accountHash("someone-else")),
        )

        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        val refused = fixture.service.restorePurchases()
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val unanswered = fixture.service.restorePurchases()
        fixture.synchronizer = { accepted(fixture.core.identity.distinctId(), it) }
        val restored = fixture.service.restorePurchases()

        assertTrue("Play ownership alone is not a restore", refused is RestoreResult.Failed)
        assertTrue(unanswered is RestoreResult.Failed)
        assertEquals(RestoreResult.Restored, restored)
        assertEquals(2, actions.count { it == SystemEventNames.RESTORE_FAILED })
        assertEquals(1, actions.count { it == SystemEventNames.RESTORE_COMPLETED })
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun restoreAsksAgainForAPurchaseAnEarlierSyncRefused() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        val purchase = playPurchase("bought-on-another-phone", obfuscatedAccountId = accountHash("previous-install"))
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        // App start may not move another customer's purchase.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        fixture.service.recover()
        assertTrue(fixture.store.load().getValue("bought-on-another-phone").refused)

        fixture.synchronizer = { accepted(customer, it) }
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())

        val restored = fixture.store.load().getValue("bought-on-another-phone")
        assertFalse(restored.refused)
        assertTrue(restored.synced)
        assertEquals(customer, restored.ownerDistinctId)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        assertEquals(
            listOf(PurchaseSyncReason.SYNC, PurchaseSyncReason.RESTORE),
            fixture.requests.map { it.second.reason },
        )
        assertEquals(0, fixture.purchaseCompletionEventIds.size)
        fixture.close()
    }

    @Test
    fun restoreMovesAnotherCustomersPurchaseAndThePreviousOwnerLosesItHere() = runTest {
        val fixture = fixture(this)
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        fixture.core.identity.setDistinctId("customer-a")
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val bought = playPurchase("moved-token").forCheckout(fixture)
        fixture.service.onPurchasesUpdated(okUpdate(bought))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals("customer-a", fixture.store.load().getValue("moved-token").ownerDistinctId)
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(bought.copy(acknowledged = true))

        fixture.core.identity.setDistinctId("customer-b")
        fixture.core.featureInfo.publish(fixture.core.features.handleUserChange("customer-a", "customer-b"))
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())

        assertEquals(
            "moved-token" to PurchaseSyncRequest(PurchaseSyncReason.RESTORE, "customer-b", CustomerKind.IDENTIFIED),
            fixture.requests.last(),
        )
        val moved = fixture.store.load().getValue("moved-token")
        assertEquals("customer-b", moved.ownerDistinctId)
        assertEquals("The buyer is still A", "customer-a", moved.buyerDistinctId)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // Customer A's checkout binding still names this Play account; a passive
        // scan after a restart must not hand A the access the server moved to B.
        fixture.core.identity.setDistinctId("customer-a")
        val restarted = fixture(this, store = fixture.store)
        assertEquals("customer-a", restarted.core.identity.distinctId())
        restarted.billing.active[BillingClient.ProductType.INAPP] = listOf(bought.copy(acknowledged = true))
        restarted.service.recover()

        assertEquals("customer-b", fixture.store.load().getValue("moved-token").ownerDistinctId)
        assertFalse(restarted.core.featureInfo.isAllowed("pro"))
        restarted.close()
        fixture.close()
    }

    @Test
    fun onlyAConflictOrAnInvalidPurchaseIsARefusal() = runTest {
        val request = PurchaseSyncRequest(PurchaseSyncReason.RESTORE, "customer", CustomerKind.IDENTIFIED)
        val evidence = retainedRestoreEvidence("customer")
        val refusals = listOf(409, 400, 401, 403, 503).associateWith { status ->
            val api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, object : HttpTransport {
                override fun execute(request: HttpTransport.Request) =
                    HttpTransport.Response(status, """{"success":false}""".encodeToByteArray())
            })
            (NuxieApiPurchaseSynchronizer(api).sync(evidence, request) as PurchaseSyncOutcome.Rejected).refused
        }
        assertEquals(mapOf(409 to true, 400 to true, 401 to false, 403 to false, 503 to false), refusals)
    }

    // purchases/purchase-outbox.json: the outbox contract iOS defines, driven
    // through Android's production entry points against a recording server.

    private val outboxContract by lazy {
        Json.parseToJsonElement(
            File(FixtureRunner.fixturesRoot(), "purchases/purchase-outbox.json").readText(),
        ).jsonObject.also {
            assertEquals("purchases/purchase-outbox", it.getValue("suite").jsonPrimitive.content)
            assertEquals(1, it.getValue("version").jsonPrimitive.int)
        }
    }

    private fun outboxAnswer(name: String): JsonObject = outboxContract.getValue("answers").jsonArray
        .map { it.jsonObject }
        .single { it.getValue("name").jsonPrimitive.content == name }

    /**
     * A `/purchase` server answering each request body with [answerFor]'s
     * answer (a null status is a transport failure), recording every body.
     */
    private fun outboxApi(bodies: MutableList<JsonObject>, answerFor: (JsonObject) -> JsonObject) = NuxieApi(
        "pk_test",
        NuxieEnvironment.DEVELOPMENT,
        object : HttpTransport {
            override fun execute(request: HttpTransport.Request): HttpTransport.Response {
                val path = outboxContract.getValue("request").jsonObject
                assertEquals(path.getValue("path").jsonPrimitive.content, request.url.path)
                assertEquals(path.getValue("method").jsonPrimitive.content, request.method)
                val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
                bodies += body
                val answer = answerFor(body)
                val status = (answer.getValue("status") as? JsonPrimitive)?.intOrNull
                    ?: throw java.io.IOException("offline")
                val reply = if (status == 200) {
                    """{"success":true,"customer_id":${body.getValue("distinct_id")},"features":[{"id":"pro","type":"boolean","allowed":true,"unlimited":false}],"catalog_product":{"id":"nuxie-pro","store_product_id":"play-pro","store_product_type":"nonConsumable"}}"""
                } else {
                    """{"success":false,"code":${answer["code"] ?: JsonPrimitive("unavailable")}}"""
                }
                return HttpTransport.Response(status, reply.encodeToByteArray())
            }
        },
        // Answer on the test's clock, so a backoff retry lands within advanceTimeBy.
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun Fixture.answerThroughTheApi(bodies: MutableList<JsonObject>, answerFor: (JsonObject) -> JsonObject) {
        val api = NuxieApiPurchaseSynchronizer(outboxApi(bodies, answerFor))
        synchronizer = { api.sync(it, requests.last().second) }
    }

    /** What the contract checks in each request body. */
    private fun JsonObject.outboxFields(): Triple<String, String, String> = Triple(
        getValue("distinct_id").jsonPrimitive.content,
        getValue("reason").jsonPrimitive.content,
        getValue("customer_kind").jsonPrimitive.content,
    )

    @Test
    fun outboxContractRecordsEachSendWhenItIsAdmitted() = runTest {
        val accepted = outboxAnswer("accepted")
        val offline = outboxAnswer("offline")
        val sends = outboxContract.getValue("sends").jsonArray.map { it.jsonObject }
        val drivenProducers = mutableSetOf<String>()
        for (case in sends) {
            val name = case.getValue("name").jsonPrimitive.content
            val producer = case.getValue("producer").jsonPrimitive.content
            val customer = case.getValue("customer").jsonObject
            val fixture = fixture(this, initialRetryDelayMillis = 10, maxRetryDelayMillis = 10)
            // Identity persists across fixtures in one test: every case starts anonymous.
            fixture.core.identity.reset(keepAnonymousId = false)
            val anonymousId = fixture.core.identity.anonymousId()
            if (customer.getValue("identified").jsonPrimitive.boolean) {
                fixture.core.identity.setDistinctId(customer.getValue("distinctId").jsonPrimitive.content)
            }
            val current = fixture.core.identity.distinctId()
            val bodies = mutableListOf<JsonObject>()
            var answer = accepted
            fixture.answerThroughTheApi(bodies) { answer }
            val owned = playPurchase("outbox-token", obfuscatedAccountId = accountHash(current))
            when (producer) {
                "checkout" -> {
                    val checkout = async { fixture.service.purchase(activity(), product(), null) }
                    runCurrent()
                    fixture.service.onPurchasesUpdated(okUpdate(playPurchase("outbox-token").forCheckout(fixture)))
                    assertEquals(name, PurchaseResult.Purchased, checkout.await())
                }
                "already_owned" -> {
                    fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(owned)
                    fixture.billing.launchCode = BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
                    val result = fixture.service.purchase(activity(), product(), null)
                    assertTrue(name, (result as PurchaseResult.Failed).cause is PurchaseAlreadyOwnedException)
                }
                "restore" -> {
                    fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(owned)
                    assertEquals(name, RestoreResult.Restored, fixture.service.restorePurchases())
                }
                "transaction_stream" -> fixture.service.onPurchasesUpdated(okUpdate(owned))
                "launch_scan" -> {
                    fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(owned)
                    fixture.service.recover()
                }
                "retry_after_reset" -> {
                    answer = offline
                    val checkout = async { fixture.service.purchase(activity(), product(), null) }
                    runCurrent()
                    fixture.service.onPurchasesUpdated(okUpdate(playPurchase("outbox-token").forCheckout(fixture)))
                    assertEquals(name, PurchaseResult.Purchased, checkout.await())
                    fixture.core.identity.reset(keepAnonymousId = false)
                    answer = accepted
                    advanceTimeBy(100)
                    runCurrent()
                    assertEquals(name, 2, bodies.size)
                }
                else -> error("Unknown outbox producer $producer in $name")
            }
            drivenProducers += producer
            val expected = case.getValue("expectedRequest").jsonObject
            val expectedFields = Triple(
                expected.getValue("distinct_id").jsonPrimitive.content
                    .replace("<anonymousId>", anonymousId),
                expected.getValue("reason").jsonPrimitive.content,
                expected.getValue("customer_kind").jsonPrimitive.content,
            )
            assertTrue("$name sends", bodies.isNotEmpty())
            for (body in bodies) assertEquals(name, expectedFields, body.outboxFields())
            assertNull(name, fixture.store.load().getValue("outbox-token").pendingSend)
            fixture.close()
        }
        assertEquals(sends.map { it.getValue("producer").jsonPrimitive.content }.toSet(), drivenProducers)
    }

    @Test
    fun outboxContractClearsAnEntryOnlyWhenTheServerAnswers() = runTest {
        for (answer in outboxContract.getValue("answers").jsonArray.map { it.jsonObject }) {
            val name = answer.getValue("name").jsonPrimitive.content
            val fixture = fixture(this)
            fixture.core.identity.setDistinctId("buyer")
            val bodies = mutableListOf<JsonObject>()
            fixture.answerThroughTheApi(bodies) { answer }

            val checkout = async { fixture.service.purchase(activity(), product(), null) }
            runCurrent()
            fixture.service.onPurchasesUpdated(okUpdate(playPurchase("outbox-token").forCheckout(fixture)))
            checkout.await()

            val entry = fixture.store.load().getValue("outbox-token")
            assertEquals(name, 1, bodies.size)
            when (answer.getValue("entry").jsonPrimitive.content) {
                "cleared" -> assertNull(name, entry.pendingSend)
                "kept" -> assertNotNull(name, entry.pendingSend)
                else -> error("Unknown entry state in $name")
            }
            assertEquals(name, answer.getValue("sendAttempts").jsonPrimitive.int, entry.syncAttempts)
            assertEquals(name, answer.getValue("profileRefreshes").jsonPrimitive.int, fixture.authorityRefreshes)
            fixture.close()
        }
    }

    @Test
    fun outboxContractBacksOffAKeptEntry() = runTest {
        val retry = outboxContract.getValue("retry").jsonObject
        val initial = retry.getValue("initialDelaySeconds").jsonPrimitive.long * 1_000
        val maximum = retry.getValue("maximumDelaySeconds").jsonPrimitive.long * 1_000
        assertEquals(2, retry.getValue("multiplier").jsonPrimitive.int)
        for (row in retry.getValue("delayAfterAttempts").jsonArray.map { it.jsonObject }) {
            assertEquals(
                row.getValue("delaySeconds").jsonPrimitive.long * 1_000,
                outboxRetryDelayMillis(row.getValue("attempts").jsonPrimitive.int),
            )
        }

        // A kept entry goes out again after exactly that delay.
        val fixture = fixture(this, initialRetryDelayMillis = initial, maxRetryDelayMillis = maximum)
        val customer = fixture.core.identity.distinctId()
        val offline = outboxAnswer("offline")
        val bodies = mutableListOf<JsonObject>()
        fixture.answerThroughTheApi(bodies) { offline }
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("outbox-token", obfuscatedAccountId = accountHash(customer)),
        )
        fixture.service.recover()
        for ((attempts, sends) in listOf(1 to 1, 2 to 2, 3 to 3)) {
            assertEquals(sends, bodies.size)
            assertEquals(attempts, fixture.store.load().getValue("outbox-token").syncAttempts)
            advanceTimeBy(outboxRetryDelayMillis(attempts) - 1)
            runCurrent()
            assertEquals("Not before the backoff", sends, bodies.size)
            advanceTimeBy(1)
            runCurrent()
        }
        fixture.close()
    }

    @Test
    fun aRestoreWithARefusedAndAnUnansweredClaimIsUnconfirmed() = runTest {
        for (authenticated in listOf(false, true)) {
            val fixture = fixture(this)
            val customer = fixture.core.identity.distinctId()
            fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
                playPurchase("refused-token", obfuscatedAccountId = accountHash(customer)),
                playPurchase("unanswered-token", obfuscatedAccountId = accountHash(customer)),
            )
            fixture.synchronizer = { evidence ->
                if (evidence.purchaseToken == "refused-token") {
                    PurchaseSyncOutcome.Rejected(refused = true)
                } else {
                    PurchaseSyncOutcome.Rejected()
                }
            }

            val result = fixture.service.restorePurchases(expectedOwnerDistinctId = customer.takeIf { authenticated })

            assertEquals(
                "One claim is still unanswered, so the Restore is unconfirmed",
                RestoreClaimError.UNCONFIRMED,
                ((result as RestoreResult.Failed).cause as RestoreClaimException).error,
            )
            assertEquals(PurchaseSyncReason.RESTORE, fixture.store.load().getValue("unanswered-token").pendingSend?.reason)
            assertNull(fixture.store.load().getValue("refused-token").pendingSend)
            fixture.close()
        }
    }

    @Test
    fun outboxContractReportsARestoreOnlyWhenTheServerGrantsOne() = runTest {
        for (case in outboxContract.getValue("restore").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val answers = case.getValue("answers").jsonArray.map { outboxAnswer(it.jsonPrimitive.content) }
            val fixture = fixture(this)
            fixture.core.identity.setDistinctId("restorer")
            val bodies = mutableListOf<JsonObject>()
            fixture.answerThroughTheApi(bodies) { body ->
                answers[body.getValue("purchase_token").jsonPrimitive.content.removePrefix("restore-").toInt()]
            }
            fixture.billing.active[BillingClient.ProductType.INAPP] = answers.indices.map {
                playPurchase("restore-$it", obfuscatedAccountId = accountHash("restorer"))
            }

            val result = fixture.service.restorePurchases()

            when (case.getValue("result").jsonPrimitive.content) {
                "restored" -> assertEquals(name, RestoreResult.Restored, result)
                "no_purchases" -> assertEquals(name, RestoreResult.NoPurchases, result)
                "failed" -> assertEquals(
                    name,
                    when (case.getValue("error").jsonPrimitive.content) {
                        "owned_by_another_account" -> RestoreClaimError.OWNED_BY_ANOTHER_ACCOUNT
                        "unconfirmed" -> RestoreClaimError.UNCONFIRMED
                        else -> error("Unknown Restore error in $name")
                    },
                    ((result as RestoreResult.Failed).cause as RestoreClaimException).error,
                )
                else -> error("Unknown Restore result in $name")
            }
            assertEquals(name, answers.size, bodies.size)
            assertTrue(name, bodies.all { it.outboxFields() == Triple("restorer", "restore", "identified") })
            assertEquals(
                "$name: Restore claims still in the outbox",
                case.getValue("queuedClaims").jsonPrimitive.int,
                fixture.store.load().values.count {
                    it.pendingSend?.reason == PurchaseSyncReason.RESTORE && it.pendingSend?.distinctId == "restorer"
                },
            )
            fixture.close()
        }
    }

    private fun conflictApi(bodies: MutableList<String>) = NuxieApi(
        "pk_test",
        NuxieEnvironment.DEVELOPMENT,
        object : HttpTransport {
            override fun execute(request: HttpTransport.Request): HttpTransport.Response {
                bodies += request.body.decodeToString()
                return HttpTransport.Response(
                    409,
                    """{"success":false,"error":"Google Play purchase conflicts with retained ownership","code":"google_play_purchase_conflict"}""".encodeToByteArray(),
                )
            }
        },
    )

    /** [owner]'s own purchase the server already accepted for them. */
    private fun retainedRestoreEvidence(owner: String) = PurchaseEvidence(
        purchaseToken = "refresh-token", packageName = "com.example.app",
        storeProductIds = listOf("play-pro"), nuxieProductId = "nuxie-pro",
        productType = BillingClient.ProductType.SUBS, purchaseState = StoredPurchaseState.PURCHASED,
        obfuscatedAccountId = accountHash(owner), buyerDistinctId = owner,
        ownerDistinctId = owner, nuxieManaged = true, acknowledged = true, syncedCustomerId = owner,
        backendSyncedAtMillis = 1L, firstSeenMillis = 1L, catalogResolved = true,
        completionEmitted = true, syncedEventEmitted = true, signatureVerified = true,
        authorityScope = "test-fixture",
    )

    /** [buyer]'s own purchase still owing its send to the server. */
    private fun owedRestoreEvidence(
        buyer: String,
        reason: PurchaseSyncReason = PurchaseSyncReason.PURCHASE,
        kind: CustomerKind = CustomerKind.ANONYMOUS,
    ) = retainedRestoreEvidence(buyer).copy(
        ownerDistinctId = null,
        syncedCustomerId = null,
        backendSyncedAtMillis = null,
        pendingSend = PurchaseSend(reason, buyer, kind),
    )

    /** The requester of the send the synchronizer is answering. */
    private val PurchaseEvidence.requester: String get() = checkNotNull(pendingSend).distinctId

    @Test
    fun recoveryDoesNotBypassAPendingManagedCompletionBackoff() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(
            this,
            actions = actions,
            initialRetryDelayMillis = 10,
            maxRetryDelayMillis = 1_000,
        )
        val owner = fixture.core.identity.distinctId()
        fixture.billing.acknowledgeCodes += listOf(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.OK,
        )
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "recovery-completion",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                buyerDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
                syncedEventEmitted = true,
                nuxieManaged = true,
            ),
        )
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("recovery-completion", obfuscatedAccountId = accountHash(owner)),
        )

        fixture.service.recover()

        assertEquals(1, actions.count { it == "ack" })
        assertFalse(fixture.store.load().getValue("recovery-completion").acknowledged)
        advanceTimeBy(20)
        runCurrent()
        assertEquals(2, actions.count { it == "ack" })
        assertTrue(fixture.store.load().getValue("recovery-completion").acknowledged)
        fixture.close()
    }

    @Test
    fun recoveryCapturesRetainedCompletionAfterSyncAndPlayCompletionAreTerminal() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, actions = actions, emissions = emissions)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "retained-unemitted-completion",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                nuxieProductId = "nuxie-pro",
                purchaseState = StoredPurchaseState.PURCHASED,
                obfuscatedAccountId = accountHash(owner),
                buyerDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = true,
                firstSeenMillis = 1L,
                catalogResolved = true,
                syncedEventEmitted = true,
                signatureVerified = true,
                authorityScope = "test-fixture",
            ),
        )

        fixture.service.recover()

        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, fixture.purchaseCompletionEventIds.size)
        assertEquals(listOf(owner), fixture.purchaseEventDistinctIds)
        assertEquals(
            "startup_recovery",
            emissions.single {
                it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED
            }.second["source"],
        )
        assertTrue(
            fixture.store.load().getValue("retained-unemitted-completion").completionEmitted,
        )

        fixture.service.recover()

        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        fixture.close()
    }

    @Test
    fun testStoreIgnoresRetainedNativeEvidenceAndPlayCallbacks() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions, testStore = NuxieTestStore(object : TestStoreChoices {
            override suspend fun purchase(product: StoreProduct) = TestStorePurchaseChoice.PURCHASED
            override suspend fun restore() = TestStoreRestoreChoice.RESTORED
        }, grantedTestStore))
        val owner = fixture.core.identity.distinctId()
        val retained = PurchaseEvidence(
            purchaseToken = "real-token", packageName = "com.example.app",
            storeProductIds = listOf("play-pro"), nuxieProductId = "nuxie-pro",
            authorityScope = "test-fixture", purchaseState = StoredPurchaseState.PURCHASED,
            buyerDistinctId = owner,
            pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, owner, CustomerKind.ANONYMOUS),
            acknowledged = false, firstSeenMillis = 1L, catalogResolved = true,
        )
        fixture.store.upsert(retained)
        fixture.service.rememberProduct(product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))))
        fixture.synchronizer = { error("Test Store synchronized native evidence") }
        actions.clear()
        runCurrent()
        fixture.service.awaitInitialProjection()
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.service.withOptimisticProjectionSnapshot(owner) { assertNull(it) }
        assertNull(fixture.service.useFeatureWithPendingPurchase(owner, "pro", 1.0, null, null))
        fixture.service.recover()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("incoming-real-token")))
        fixture.service.onPurchasesUpdated(PurchaseUpdate(result(BillingClient.BillingResponseCode.USER_CANCELED), null))
        assertTrue(fixture.billing.queries.isEmpty())
        assertTrue(actions.isEmpty())
        assertEquals(mapOf("real-token" to retained), fixture.store.load())
    }

    @Test
    fun testStoreCheckoutPrecedesDelegateAndNeverCreatesNativeEvidence() = runTest {
        for (choice in TestStorePurchaseChoice.entries) {
            val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
            val store = NuxieTestStore(object : TestStoreChoices {
                override suspend fun purchase(product: StoreProduct) = choice
                override suspend fun restore() = TestStoreRestoreChoice.RESTORED
            }, grantedTestStore)
            val fixture = fixture(this, emissions = emissions, testStore = store)
            fixture.settings.delegate = object : NuxiePurchaseDelegate {
                override suspend fun purchase(product: StoreProduct): PurchaseResult = error("Delegate called")
                override suspend fun restorePurchases(): RestoreResult = error("Delegate called")
            }
            fixture.synchronizer = { error("Simulated purchase reached Play sync") }
            val owner = fixture.core.identity.distinctId()
            val result = fixture.service.purchase(
                activity(), product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))), null,
                outcomeCorrelation = CommerceOutcomeCorrelation("test-store-${choice.name}", owner),
            )
            when (choice) {
                TestStorePurchaseChoice.PURCHASED -> assertEquals(PurchaseResult.Purchased, result)
                TestStorePurchaseChoice.PENDING -> assertEquals(PurchaseResult.Pending, result)
                TestStorePurchaseChoice.CANCELLED -> assertEquals(PurchaseResult.Cancelled, result)
                TestStorePurchaseChoice.FAILED -> assertTrue(result is PurchaseResult.Failed)
            }
            val properties = emissions.single().second
            assertEquals(true, properties["test_store"])
            if (choice == TestStorePurchaseChoice.PURCHASED) {
                assertEquals("checkout", properties["source"])
                assertTrue((properties["transaction_id"] as String).startsWith("nuxie-test-"))
            } else {
                assertFalse("transaction_id" in properties)
            }
            assertEquals(if (choice == TestStorePurchaseChoice.PENDING) emptyList<String>() else listOf("test-store-${choice.name}"), fixture.purchaseCompletionEventIds)
            assertEquals(if (choice == TestStorePurchaseChoice.PENDING) emptyList<String>() else listOf(owner), fixture.purchaseEventDistinctIds)
            assertTrue(fixture.billing.queries.isEmpty())
            assertNull(fixture.billing.launched)
            assertTrue(fixture.store.load().isEmpty())
            assertTrue(fixture.store.loadBindings().isEmpty())
            assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        }
    }

    @Test
    fun testStorePurchaseUnlocksTheFeaturesTheServerGranted() = runTest {
        val grants = mutableListOf<String>()
        val store = NuxieTestStore(object : TestStoreChoices {
            override suspend fun purchase(product: StoreProduct) = TestStorePurchaseChoice.PURCHASED
            override suspend fun restore() = TestStoreRestoreChoice.RESTORED
        }) { catalogProductId, _, _ ->
            grants += catalogProductId
            Json.parseToJsonElement(
                """{"success":true,"customer_id":"customer-1","features":[{"id":"feature_pro","ext_id":"pro","type":"boolean","allowed":true,"balance":null,"unlimited":false}]}""",
            ).jsonObject
        }
        val fixture = fixture(this, testStore = store)
        val boolean = product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)))
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))

        assertEquals(PurchaseResult.Purchased, fixture.service.purchase(activity(), boolean, null))

        assertEquals(listOf(boolean.productId), grants)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        assertTrue(fixture.store.load().isEmpty())
    }

    @Test
    fun correlatedTestStoreOutcomesRetainCustomerAcrossIdentityChanges() = runTest {
        for (choice in TestStorePurchaseChoice.entries) {
            val decision = CompletableDeferred<TestStorePurchaseChoice>()
            val fixture = fixture(this, testStore = NuxieTestStore(object : TestStoreChoices {
                override suspend fun purchase(product: StoreProduct) = decision.await()
                override suspend fun restore() = TestStoreRestoreChoice.RESTORED
            }, grantedTestStore))
            val owner = fixture.core.identity.distinctId()
            val pending = async {
                fixture.service.purchase(activity(), product(), null,
                    outcomeCorrelation = CommerceOutcomeCorrelation("retained-${choice.name}", owner))
            }
            runCurrent()
            fixture.core.identity.setDistinctId("replacement-customer")
            decision.complete(choice)
            pending.await()
            assertEquals(if (choice == TestStorePurchaseChoice.PENDING) emptyList<String>() else listOf(owner), fixture.purchaseEventDistinctIds)
            assertEquals(if (choice == TestStorePurchaseChoice.PENDING) emptyList<String>() else listOf("retained-${choice.name}"), fixture.purchaseCompletionEventIds)
            assertTrue(fixture.store.load().isEmpty())
        }
        for (choice in TestStoreRestoreChoice.entries) {
            val decision = CompletableDeferred<TestStoreRestoreChoice>()
            val fixture = fixture(this, testStore = NuxieTestStore(object : TestStoreChoices {
                override suspend fun purchase(product: StoreProduct) = TestStorePurchaseChoice.PURCHASED
                override suspend fun restore() = decision.await()
            }, grantedTestStore))
            val owner = fixture.core.identity.distinctId()
            val pending = async {
                fixture.service.restorePurchases(
                    outcomeCorrelation = CommerceOutcomeCorrelation("retained-restore-${choice.name}", owner))
            }
            runCurrent()
            fixture.core.identity.setDistinctId("replacement-customer")
            decision.complete(choice)
            pending.await()
            assertEquals(listOf(owner), fixture.purchaseEventDistinctIds)
            assertEquals(listOf("retained-restore-${choice.name}"), fixture.purchaseCompletionEventIds)
        }
    }

    @Test
    fun testStoreRestoreChoicesBypassDelegateAndNativeQueries() = runTest {
        for (choice in TestStoreRestoreChoice.entries) {
            val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
            val fixture = fixture(this, emissions = emissions, testStore = NuxieTestStore(object : TestStoreChoices {
                override suspend fun purchase(product: StoreProduct) = TestStorePurchaseChoice.PURCHASED
                override suspend fun restore() = choice
            }, grantedTestStore))
            fixture.settings.delegate = object : NuxiePurchaseDelegate {
                override suspend fun purchase(product: StoreProduct): PurchaseResult = error("Delegate called")
                override suspend fun restorePurchases(): RestoreResult = error("Delegate called")
            }
            val owner = fixture.core.identity.distinctId()
            val result = fixture.service.restorePurchases(
                outcomeCorrelation = CommerceOutcomeCorrelation("restore-${choice.name}", owner),
            )
            when (choice) {
                TestStoreRestoreChoice.RESTORED -> assertEquals(RestoreResult.Restored, result)
                TestStoreRestoreChoice.NO_PURCHASES -> assertEquals(RestoreResult.NoPurchases, result)
                TestStoreRestoreChoice.FAILED -> assertTrue(result is RestoreResult.Failed)
            }
            assertEquals(true, emissions.single().second["test_store"])
            assertEquals(listOf("restore-${choice.name}"), fixture.purchaseCompletionEventIds)
            assertTrue(fixture.billing.queries.isEmpty())
            assertTrue(fixture.store.load().isEmpty())
        }
    }

    @Test
    fun purchasedDelegateOutcomeCapturesCheckoutCompletionWithoutNativeEvidence() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val journeyEvents = mutableListOf<StoredEvent>()
        val fixture = fixture(
            this,
            actions = actions,
            emissions = emissions,
            journeyEvents = journeyEvents,
        )
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = PurchaseResult.Purchased
            override suspend fun restorePurchases(): RestoreResult = RestoreResult.NoPurchases
        }

        assertEquals(
            PurchaseResult.Purchased,
            fixture.service.purchase(
                activity(),
                product(
                    subscription = true,
                    allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
                    rawProduct = subscriptionProductDetails(),
                ),
                null,
            ),
        )
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        val properties = emissions.single {
            it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED
        }.second
        assertEquals("external_delegate", properties["source"])
        assertEquals("nuxie-pro", properties["product_id"])
        assertEquals("play-pro", properties["store_product_id"])
        assertFalse("transaction_id" in properties)
        assertEquals(9.99, properties["price"])
        assertEquals("€9.99", properties["display_price"])
        assertForwardedPrice(
            ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED,
            properties,
            "9.99",
            "€9.99",
        )
        assertEquals(null, fixture.billing.launched)
        assertTrue(fixture.billing.queries.isEmpty())
        assertFalse("sync" in actions)
        assertTrue(fixture.store.load().isEmpty())
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.core.eventLog.awaitBarrier()
        assertEquals(
            listOf(SystemEventNames.PURCHASE_COMPLETED),
            journeyEvents.map(StoredEvent::name),
        )
        fixture.close()
    }

    private suspend fun awaitRealWork(timeoutMillis: Long = 5_000, predicate: () -> Boolean) {
        withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (!predicate()) {
                check(System.currentTimeMillis() < deadline) {
                    "Timed out waiting for real-dispatcher work to settle."
                }
                delay(5)
            }
        }
    }

    @Test
    fun delegateSuccessRetriesTheSameOperationAfterACaptureMiss() = runTest {
        var mintedOperations = 0
        val captureResults = mutableListOf(false, true)
        val journeyEvents = mutableListOf<StoredEvent>()
        val fixture = fixture(
            this,
            initialRetryDelayMillis = 10,
            maxRetryDelayMillis = 1_000,
            purchaseEventCaptureResults = captureResults,
            externalOperationId = { "external-retry-${++mintedOperations}" },
            journeyEvents = journeyEvents,
        )
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = PurchaseResult.Purchased
            override suspend fun restorePurchases(): RestoreResult = RestoreResult.NoPurchases
        }

        assertEquals(PurchaseResult.Purchased, fixture.service.purchase(activity(), product(), null))
        assertEquals(1, mintedOperations)
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertTrue(fixture.purchaseCompletionEventIds.isEmpty())
        assertTrue(journeyEvents.isEmpty())

        advanceTimeBy(19)
        runCurrent()
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        advanceTimeBy(1)
        runCurrent()
        // The retry's durable capture runs on the real EventLog dispatcher;
        // virtual time cannot drain it.
        awaitRealWork { fixture.purchaseCompletionEventIds.size == 1 }

        assertEquals(2, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, fixture.purchaseEventCaptureAttempts.distinct().size)
        assertEquals(1, fixture.purchaseCompletionEventIds.size)
        assertEquals(
            listOf(SystemEventNames.PURCHASE_COMPLETED),
            journeyEvents.map(StoredEvent::name),
        )
        fixture.close()
    }

    @Test
    fun delegateSuccessDropsItsOperationAfterBoundedCaptureRetries() = runTest {
        var mintedOperations = 0
        val warnings = mutableListOf<String>()
        val fixture = fixture(
            this,
            initialRetryDelayMillis = 1,
            maxRetryDelayMillis = 1_000,
            purchaseEventCaptureResults = mutableListOf(false, false, false, false, true),
            externalOperationId = { "external-drop-${++mintedOperations}" },
            logDroppedExternalPurchase = { operation, _, _ -> warnings += operation },
        )
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = PurchaseResult.Purchased
            override suspend fun restorePurchases(): RestoreResult = RestoreResult.NoPurchases
        }

        assertEquals(PurchaseResult.Purchased, fixture.service.purchase(activity(), product(), null))
        advanceTimeBy(2)
        runCurrent()
        advanceTimeBy(4)
        runCurrent()
        advanceTimeBy(8)
        runCurrent()

        assertEquals(1, mintedOperations)
        assertEquals(4, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, fixture.purchaseEventCaptureAttempts.distinct().size)
        assertTrue(fixture.purchaseCompletionEventIds.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("external-drop-1"))

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(4, fixture.purchaseEventCaptureAttempts.size)
        fixture.close()
    }

    @Test
    fun eachPurchasedDelegateCallbackGetsItsOwnExternalCommit() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = PurchaseResult.Purchased
            override suspend fun restorePurchases(): RestoreResult = RestoreResult.NoPurchases
        }

        assertEquals(PurchaseResult.Purchased, fixture.service.purchase(activity(), product(), null))
        assertEquals(PurchaseResult.Purchased, fixture.service.purchase(activity(), product(), null))

        assertEquals(
            2,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        assertEquals(2, fixture.purchaseCompletionEventIds.distinct().size)
        assertTrue(fixture.store.load().isEmpty())
        fixture.close()
    }

    @Test
    fun replayedExternalOperationCommitsOnlyOnce() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(
            this,
            actions = actions,
            externalOperationId = { "replayed-operation" },
        )
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = PurchaseResult.Purchased
            override suspend fun restorePurchases(): RestoreResult = RestoreResult.NoPurchases
        }

        assertEquals(PurchaseResult.Purchased, fixture.service.purchase(activity(), product(), null))
        assertEquals(PurchaseResult.Purchased, fixture.service.purchase(activity(), product(), null))

        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        fixture.close()
    }

    @Test
    fun externalPurchaseCallbackCapturesCarrierForInitiatingIdentityAcrossIdentityChange() = runTest {
        var mintedOperations = 0
        val journeyEvents = mutableListOf<StoredEvent>()
        val fixture = fixture(
            this,
            externalOperationId = { "external-race-${++mintedOperations}" },
            journeyEvents = journeyEvents,
        )
        val initiatingOwner = fixture.core.identity.distinctId()
        val callback = CompletableDeferred<PurchaseResult>()
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = callback.await()
            override suspend fun restorePurchases(): RestoreResult = RestoreResult.NoPurchases
        }
        val purchase = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()

        assertEquals(0, mintedOperations)
        fixture.core.identity.setDistinctId("replacement-customer")
        callback.complete(PurchaseResult.Purchased)

        assertEquals(PurchaseResult.Purchased, purchase.await())
        assertEquals(1, mintedOperations)
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertEquals(1, fixture.purchaseCompletionEventIds.size)
        assertEquals(listOf(initiatingOwner), fixture.purchaseEventDistinctIds)
        assertTrue(fixture.store.load().isEmpty())
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertTrue(journeyEvents.isEmpty())
        fixture.close()
    }

    @Test
    fun restoredDelegateDeclarationDoesNotScanPlayOrCreateNativeEvidence() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, actions = actions, emissions = emissions)
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = PurchaseResult.Cancelled
            override suspend fun restorePurchases(): RestoreResult = RestoreResult.Restored
        }

        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())

        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.RESTORE_COMPLETED },
        )
        val properties = emissions.single {
            it.first == ai.nuxie.sdk.events.SystemEventNames.RESTORE_COMPLETED
        }.second
        assertEquals("external_delegate", properties["source"])
        assertEquals(false, properties["test_store"])
        assertEquals(1, fixture.purchaseEventCaptureAttempts.size)
        assertTrue(fixture.billing.queries.isEmpty())
        assertEquals(null, fixture.billing.launched)
        assertFalse("sync" in actions)
        assertFalse("ack" in actions)
        assertFalse("consume" in actions)
        assertTrue(fixture.store.load().isEmpty())
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun catalogConsumableConsumesOnlyAfterServerAcceptance() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.requester, it)
        }
        val purchase = async { fixture.service.purchase(activity(), product(consumable = true), null) }
        runCurrent()

        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("consumable-token").forCheckout(fixture)))

        assertEquals(PurchaseResult.Purchased, purchase.await())
        assertTrue(actions.indexOf("sync") < actions.indexOf("consume"))
        assertFalse("ack" in actions)
        assertTrue(fixture.store.load().getValue("consumable-token").consumed)
        fixture.close()
    }

    @Test
    fun appManagedOwnershipRemainsDurableIfTheGlobalModeChangesLater() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, mode = PurchaseHandlingMode.APP_MANAGED, actions = actions)
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()

        fixture.settings.handlingMode = PurchaseHandlingMode.NUXIE_MANAGED
        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("app-managed-token").forCheckout(fixture)),
        )

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertFalse("ack" in actions)
        assertFalse("consume" in actions)
        fixture.close()
    }

    @Test
    fun missingAppManagedConsumableRemainsEligibleForSyncAfterHostConsumption() = runTest {
        val fixture = fixture(this, mode = PurchaseHandlingMode.APP_MANAGED)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(
                    consumable = true,
                    allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)),
                ),
                null,
            )
        }
        runCurrent()
        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("app-managed-consumable").forCheckout(fixture)),
        )
        assertEquals(PurchaseResult.Purchased, checkout.await())
        val beforeRestore = fixture.store.load().getValue("app-managed-consumable")
        assertTrue(beforeRestore.consumable)
        assertFalse(beforeRestore.nuxieManaged)
        assertFalse(beforeRestore.synced)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // Host consumption removes an app-managed consumable from Play's
        // active snapshot before Nuxie's server has necessarily accepted it.
        assertEquals(RestoreResult.NoPurchases, fixture.service.restorePurchases())

        val afterRestore = fixture.store.load().getValue("app-managed-consumable")
        assertNotNull("Its send is still owed", afterRestore.pendingSend)
        assertFalse(afterRestore.synced)
        assertTrue("Only the server's answer ends the early unlock", fixture.core.featureInfo.isAllowed("pro"))

        fixture.synchronizer = { accepted(it.requester, it) }
        fixture.service.recover()

        val afterRecovery = fixture.store.load().getValue("app-managed-consumable")
        assertFalse(afterRecovery.refused)
        assertTrue(afterRecovery.synced)
        fixture.close()
    }

    @Test
    fun storedEvidenceRehydratesProjectionAfterRestartAndCompletesInterruptedSync() = runTest {
        val shared = RecordingEvidenceStore()
        val first = fixture(this, store = shared)
        first.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val purchase = async {
            first.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        first.service.onPurchasesUpdated(okUpdate(playPurchase("restart-token").forCheckout(first)))
        assertEquals(PurchaseResult.Purchased, purchase.await())
        assertFalse(shared.load().getValue("restart-token").synced)
        assertTrue(first.core.featureInfo.isAllowed("pro"))

        val actions = mutableListOf<String>()
        val second = fixture(this, store = shared, actions = actions)
        second.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase(
                "restart-token",
                obfuscatedAccountId = accountHash(second.core.identity.distinctId()),
            ),
        )
        second.synchronizer = {
            assertTrue(second.core.featureInfo.isAllowed("pro"))
            accepted(it.requester, it)
        }
        second.service.recover()

        assertTrue(shared.load().getValue("restart-token").synced)
        assertTrue(shared.load().getValue("restart-token").acknowledged)
        assertTrue("ack" in actions)
        assertTrue(second.core.featureInfo.isAllowed("pro"))
        first.close()
        second.close()
    }

    @Test
    fun replacementIsPassedExactlyAndMissingReplacementReturnsTypedFailure() = runTest {
        val fixture = fixture(this)
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(playPurchase("old-token"))
        val missing = fixture.service.purchase(activity(), product(subscription = true), null)
        assertTrue((missing as PurchaseResult.Failed).cause is SubscriptionReplacementRequiredException)
        assertEquals(null, fixture.billing.launched)

        val replacement = SubscriptionReplacement("old-token", ReplacementMode.DEFERRED)
        val replacing = async { fixture.service.purchase(activity(), product(subscription = true), replacement) }
        runCurrent()
        assertEquals(replacement, fixture.billing.launched?.replacement)
        fixture.service.onPurchasesUpdated(
            okUpdate(
                playPurchase(
                    "new-token",
                    products = listOf("play-pro"),
                    state = StoredPurchaseState.PENDING,
                ).forCheckout(fixture),
            ),
        )
        assertEquals(PurchaseResult.Pending, replacing.await())
        fixture.close()
    }

    @Test
    fun anAlreadyActiveTokenCannotCompleteANewCheckoutForTheSameProduct() = runTest {
        val fixture = fixture(this)
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(playPurchase("old-token"))
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()

        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("old-token").forCheckout(fixture)))
        assertFalse(checkout.isCompleted)

        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("new-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        fixture.close()
    }

    @Test
    fun restoreConnectionFailureEmitsTheCorrelatedFailureWithoutThrowing() = runTest {
        val captured = mutableListOf<List<String>>()
        val fixture = fixture(this, capturePurchaseEventOverride = { name, _, id, owner ->
            captured += listOf(name, id, owner)
            true
        })
        val owner = fixture.core.identity.distinctId()
        val failure = BillingUnavailableException(3, "Billing service unavailable on device.")
        fixture.billing.queryFailure = failure

        val result = fixture.service.restorePurchases(
            expectedOwnerDistinctId = owner,
            outcomeCorrelation = CommerceOutcomeCorrelation("restore-unavailable", owner),
        )

        assertEquals(RestoreResult.Failed(failure), result)
        assertEquals(listOf(listOf(SystemEventNames.RESTORE_FAILED, "restore-unavailable", owner)), captured)
        assertEquals(listOf(BillingClient.ProductType.SUBS), fixture.billing.queries)
        assertTrue(fixture.store.load().isEmpty())
    }

    @Test
    fun restoreQueryCancellationRemainsCancellationWithoutFailureOutcome() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val cancellation = kotlinx.coroutines.CancellationException("restore cancelled")
        fixture.billing.queryFailure = cancellation

        val result = runCatching { fixture.service.restorePurchases() }

        assertEquals(cancellation, result.exceptionOrNull())
        assertFalse(SystemEventNames.RESTORE_FAILED in actions)
    }

    @Test
    fun restoreQueriesBothProductTypesAndDistinguishesEmptyFromRestored() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        assertEquals(RestoreResult.NoPurchases, fixture.service.restorePurchases())
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.RESTORE_NO_PURCHASES },
        )

        fixture.store.upsertBinding(product().bindingFor(fixture.core.identity.distinctId()))
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase(
                "restore-token",
                obfuscatedAccountId = accountHash(fixture.core.identity.distinctId()),
            ),
        )
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.RESTORE_COMPLETED },
        )
        assertTrue(fixture.store.load().containsKey("restore-token"))
        assertEquals(
            listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).let { it + it },
            fixture.billing.queries,
        )
        fixture.close()
    }

    @Test
    fun authenticatedRestoreFailsWhenTheServerRefusesAnyOfItsPurchases() = runTest {
        for (authenticated in listOf(true, false)) {
            val fixture = fixture(this)
            val owner = fixture.core.identity.distinctId()
            // Two purchases the server accepted for this customer before.
            val first = retainedRestoreEvidence(owner)
            val second = first.copy(purchaseToken = "second-token")
            fixture.store.upsert(first)
            fixture.store.upsert(second)
            fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
                playPurchase(first.purchaseToken, obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
                playPurchase(second.purchaseToken, obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
            )
            // The server accepts the first again and refuses the second.
            fixture.synchronizer = {
                if (it.purchaseToken == first.purchaseToken) {
                    accepted(owner, it)
                } else {
                    PurchaseSyncOutcome.Rejected(refused = true)
                }
            }

            val result = fixture.service.restorePurchases(
                expectedOwnerDistinctId = owner.takeIf { authenticated },
            )

            if (authenticated) {
                assertTrue("A signed Restore route advances only when every purchase is verified: $result", result is RestoreResult.Failed)
            } else {
                assertEquals("The host API reports a partial restore as restored", RestoreResult.Restored, result)
            }
            fixture.close()
        }
    }

    @Test
    fun authenticatedRestoreFailsWhenAnyOfItsPurchasesGetsNoAnswer() = runTest {
        for (authenticated in listOf(true, false)) {
            val fixture = fixture(this)
            val owner = fixture.core.identity.distinctId()
            // One purchase the server accepted for this customer before, and one
            // it has never answered, so it has no owner.
            val accepted = retainedRestoreEvidence(owner)
            val unanswered = owedRestoreEvidence(owner).copy(purchaseToken = "unanswered-token")
            fixture.store.upsert(accepted)
            fixture.store.upsert(unanswered)
            fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
                playPurchase(accepted.purchaseToken, obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
                playPurchase(unanswered.purchaseToken, obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
            )
            fixture.synchronizer = {
                if (it.purchaseToken == accepted.purchaseToken) accepted(owner, it) else PurchaseSyncOutcome.Rejected()
            }

            val result = fixture.service.restorePurchases(
                expectedOwnerDistinctId = owner.takeIf { authenticated },
            )

            if (authenticated) {
                assertTrue("A signed Restore route needs every purchase accepted: $result", result is RestoreResult.Failed)
            } else {
                assertEquals("The host API reports a partial restore as restored", RestoreResult.Restored, result)
            }
            fixture.close()
        }
    }

    @Test
    fun anAnswerThatCannotBeSavedTurnsTheOutboxOffForTheSession() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 1_000, maxRetryDelayMillis = 1_000)
        // The buyer's own checkout: no answer yet, so it unlocks early.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val bought = playPurchase("unsaved-refusal").forCheckout(fixture)
        fixture.service.onPurchasesUpdated(okUpdate(bought))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // The server refuses it, and saving that answer fails: the outbox is off.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(bought)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        fixture.store.rejectEvidenceUpsert = { it.purchaseToken == "unsaved-refusal" && it.pendingSend == null }
        val refreshes = fixture.authorityRefreshes
        fixture.service.recover()

        assertNotNull("Unsaved, the entry stays owed on disk", fixture.store.load().getValue("unsaved-refusal").pendingSend)
        assertFalse("Nothing unlocks early while the outbox is off", fixture.core.featureInfo.isAllowed("pro"))
        assertEquals("The requester's profile is still fetched", refreshes + 1, fixture.authorityRefreshes)

        // Off for the rest of the session: the checkout's retry timer is gone, and a
        // store that saves again changes nothing.
        fixture.store.rejectEvidenceUpsert = null
        val sent = fixture.requests.size
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("No timer sends it", sent, fixture.requests.size)
        // Each purchase Play delivers is still sent once.
        fixture.service.recover()
        assertEquals(sent + 1, fixture.requests.size)
        assertNotNull(fixture.store.load().getValue("unsaved-refusal").pendingSend)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(sent + 1, fixture.requests.size)
        fixture.close()
    }

    @Test
    fun anUnsavedAnswerIsSentAndAnsweredAgainAfterARelaunch() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 1_000, maxRetryDelayMillis = 1_000)
        val customer = fixture.core.identity.distinctId()
        val purchase = playPurchase("relaunch-token", obfuscatedAccountId = accountHash(customer))
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        // The server refuses it, and saving that answer fails.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        fixture.store.rejectEvidenceUpsert = { it.purchaseToken == "relaunch-token" && it.pendingSend == null }
        fixture.service.recover()
        assertNotNull(fixture.store.load().getValue("relaunch-token").pendingSend)
        fixture.close()

        // After a relaunch the hold is gone: the entry is sent and its answer saved.
        fixture.store.rejectEvidenceUpsert = null
        val relaunched = fixture(this, store = fixture.store, initialRetryDelayMillis = 1_000, maxRetryDelayMillis = 1_000)
        relaunched.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        relaunched.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        relaunched.service.recover()
        assertEquals(listOf("relaunch-token"), relaunched.requests.map { it.first }.distinct())
        assertNull(relaunched.store.load().getValue("relaunch-token").pendingSend)
        relaunched.close()
    }

    @Test
    fun authenticatedRestoreFailsWhenAPurchaseItReturnedNeverVerified() = runTest {
        for (authenticated in listOf(true, false)) {
            val fixture = fixture(this, verifyPurchaseSignature = { _, _, _ -> false })
            val owner = fixture.core.identity.distinctId()
            // The catalog's licensing key requires Play's signature for the product.
            fixture.store.upsertProductMapping(
                StoredProductMapping(
                    storeProductId = "play-pro",
                    nuxieProductId = "nuxie-pro",
                    productType = BillingClient.ProductType.SUBS,
                    consumable = false,
                    licensingPublicKey = "catalog-key",
                ),
            )
            val accepted = retainedRestoreEvidence(owner)
            // Play returns it, but its required signature never verified, so it is never sent.
            val unverified = owedRestoreEvidence(owner).copy(
                purchaseToken = "unverified-token",
                signatureVerified = false,
            )
            fixture.store.upsert(accepted)
            fixture.store.upsert(unverified)
            fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
                playPurchase(accepted.purchaseToken, obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
                playPurchase(unverified.purchaseToken, obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
            )
            fixture.synchronizer = { accepted(owner, it) }

            val result = fixture.service.restorePurchases(
                expectedOwnerDistinctId = owner.takeIf { authenticated },
            )

            if (authenticated) {
                assertTrue("Every purchase Play returned takes part: $result", result is RestoreResult.Failed)
            } else {
                assertEquals("The host API reports a partial restore as restored", RestoreResult.Restored, result)
            }
            assertEquals(listOf(accepted.purchaseToken), fixture.requests.map { it.first })
            fixture.close()
        }
    }

    @Test
    fun aStaleAnswerLeavesTheEntryANewerClaimAdmitted() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(retainedRestoreEvidence(owner))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash(owner)).copy(acknowledged = true),
        )
        val firstSendStarted = CompletableDeferred<Unit>()
        val answerTheFirstSend = CompletableDeferred<Unit>()
        var sends = 0
        fixture.synchronizer = {
            sends += 1
            if (sends == 1) {
                firstSendStarted.complete(Unit)
                answerTheFirstSend.await()
                PurchaseSyncOutcome.Rejected(refused = true)
            } else {
                accepted(owner, it)
            }
        }

        val first = async { fixture.service.restorePurchases() }
        runCurrent()
        firstSendStarted.await()
        // A second Restore admits a newer claim, identical but for its admission,
        // while the first claim's send is still out.
        val second = async { fixture.service.restorePurchases() }
        runCurrent()
        answerTheFirstSend.complete(Unit)

        assertTrue(first.await() is RestoreResult.Failed)
        assertEquals(
            "The first refusal was for a replaced entry: the newer claim is still sent",
            RestoreResult.Restored,
            second.await(),
        )
        assertEquals(
            listOf(PurchaseSyncReason.RESTORE, PurchaseSyncReason.RESTORE),
            fixture.requests.map { it.second.reason },
        )
        val restored = fixture.store.load().getValue("refresh-token")
        assertEquals(owner, restored.ownerDistinctId)
        assertNull(restored.pendingSend)
        fixture.close()
    }

    @Test
    fun eachKeptEntryIsSentAgainAtItsOwnDueTime() = runTest {
        val fixture = fixture(this, initialRetryDelayMillis = 30_000, maxRetryDelayMillis = 3_600_000)
        val customer = fixture.core.identity.distinctId()
        // One entry has already failed six times; the other has never been sent.
        fixture.store.upsert(owedRestoreEvidence(customer).copy(purchaseToken = "waiting", syncAttempts = 6))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("waiting", obfuscatedAccountId = accountHash(customer)).copy(acknowledged = true),
            playPurchase("fresh", obfuscatedAccountId = accountHash(customer)).copy(acknowledged = true),
        )
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        fun sends(token: String) = fixture.requests.count { it.first == token }

        fixture.service.recover()
        assertEquals(1, sends("fresh"))
        assertEquals(1, sends("waiting"))

        // Ten seconds later a launch sends both again; "fresh" fails a second time,
        // so it is next due 60 s after that failure, not 30 s after the first.
        advanceTimeBy(10_000)
        fixture.service.recover()
        assertEquals(2, sends("fresh"))
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals("Not before its own due time", 2, sends("fresh"))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, sends("fresh"))
        assertEquals("The other entry waits out its own longer delay", 2, sends("waiting"))
        fixture.close()
    }

    @Test
    fun aQueryOfOneTypeDoesNotDropAPurchaseOfTheOtherReportedByAnUpdate() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        // The catalog knows the product is a one-time purchase.
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        runCurrent()
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        // Play delivers the customer's purchase outside any checkout.
        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("update-only", obfuscatedAccountId = accountHash(customer))),
        )
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // The subscription query answers (with nothing), the one-time query fails.
        fixture.billing.failQueryTypes += BillingClient.ProductType.INAPP
        fixture.service.recover()

        assertTrue(
            "A subscription query cannot speak for a one-time purchase",
            fixture.core.featureInfo.isAllowed("pro"),
        )

        // Nor can both types answering without it: only the server's answer ends it.
        fixture.billing.failQueryTypes -= BillingClient.ProductType.INAPP
        fixture.service.recover()
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun aRestoreWhoseQueriesNoLongerReturnAPurchaseLeavesItsEarlyUnlock() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val subscription = product(subscription = true, allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)))
        val checkout = async { fixture.service.purchase(activity(), subscription, null) }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("subscription-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // The subscription query no longer returns it; the one-time query fails.
        fixture.billing.active[BillingClient.ProductType.SUBS] = emptyList()
        fixture.billing.failQueryTypes += BillingClient.ProductType.INAPP
        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)
        assertTrue("A Restore's queries never end the early unlock", fixture.core.featureInfo.isAllowed("pro"))

        // Both queries answer without it: still only the server's answer ends it.
        fixture.billing.failQueryTypes -= BillingClient.ProductType.INAPP
        assertEquals(RestoreResult.NoPurchases, fixture.service.restorePurchases())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun aCancelledCheckoutsPreflightQueryLeavesAnEarlierEarlyUnlock() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val pro = product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN)))
        val first = async { fixture.service.purchase(activity(), pro, null) }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("first-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, first.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // Play no longer returns the first purchase when the next checkout asks,
        // and the customer cancels that checkout.
        fixture.billing.active[BillingClient.ProductType.INAPP] = emptyList()
        fixture.billing.launchCode = BillingClient.BillingResponseCode.USER_CANCELED
        assertEquals(PurchaseResult.Cancelled, fixture.service.purchase(activity(), pro, null))

        assertTrue("A checkout's query never ends the early unlock", fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun aKeptEntrySavesItsDueTimeAndIsSentThenAfterARestart() = runTest {
        val clock = { 1_800_000_000_000L + testScheduler.currentTime }
        val fixture = fixture(this, initialRetryDelayMillis = 30_000, maxRetryDelayMillis = 3_600_000, nowMillis = clock)
        val customer = fixture.core.identity.distinctId()
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("kept-token", obfuscatedAccountId = accountHash(customer)),
        )
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        fixture.service.recover()
        val kept = fixture.store.load().getValue("kept-token")
        assertEquals("The due time is saved with the failed attempt", clock() + 30_000, kept.retryDueAtMillis)
        fixture.close()

        // Ten seconds later the app starts again. Play is not asked.
        advanceTimeBy(10_000)
        val restarted = fixture(
            this,
            store = fixture.store,
            initialRetryDelayMillis = 30_000,
            maxRetryDelayMillis = 3_600_000,
            nowMillis = clock,
        )
        restarted.synchronizer = { PurchaseSyncOutcome.Rejected() }
        runCurrent()
        assertTrue(restarted.requests.isEmpty())
        advanceTimeBy(19_999)
        runCurrent()
        assertTrue("Not before its saved due time", restarted.requests.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("kept-token"), restarted.requests.map { it.first })
        assertTrue(restarted.billing.queries.isEmpty())
        restarted.close()
    }

    @Test
    fun aFailedEntryWithNoSavedDueTimeIsDueAtLaunch() = runTest {
        // It failed before, but its due time was never saved.
        val store = RecordingEvidenceStore()
        store.upsert(owedRestoreEvidence("stored-customer").copy(syncAttempts = 2, retryDueAtMillis = null))

        val launched = fixture(this, store = store)
        launched.synchronizer = { PurchaseSyncOutcome.Rejected() }
        runCurrent()
        assertEquals(listOf("refresh-token"), launched.requests.map { it.first })
        assertTrue("Play is not asked", launched.billing.queries.isEmpty())
        launched.close()
    }

    @Test
    fun aStaleRetryTimerNeverSendsBeforeTheStoredDueTime() = runTest {
        val clock = { 1_800_000_000_000L + testScheduler.currentTime }
        val store = RecordingEvidenceStore()
        // An owed entry stored as due in 1 s.
        store.upsert(owedRestoreEvidence("stored-customer").copy(syncAttempts = 1, retryDueAtMillis = clock() + 1_000))
        val fixture = fixture(
            this,
            store = store,
            initialRetryDelayMillis = 10_000,
            maxRetryDelayMillis = 10_000,
            nowMillis = clock,
            startService = false,
        )
        val sentAt = mutableListOf<Long>()
        fixture.synchronizer = {
            sentAt += testScheduler.currentTime
            PurchaseSyncOutcome.Rejected()
        }
        runCurrent()

        // Launch scheduling reads the entry (due in 1 s) and is held there.
        val releaseLaunch = CompletableDeferred<Unit>()
        store.holdNextLoad = { releaseLaunch.await() }
        fixture.service.start()
        runCurrent()
        // Meanwhile recovery sends the entry, gets no answer and saves a later due time.
        fixture.service.recover()
        assertEquals(listOf(0L), sentAt)
        assertEquals(clock() + 10_000, store.load().getValue("refresh-token").retryDueAtMillis)
        // Then launch scheduling goes on with its older read of the entry.
        releaseLaunch.complete(Unit)
        runCurrent()

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals("Nothing is sent before the stored due time", listOf(0L), sentAt)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("It is sent at the stored due time", listOf(0L, 10_000L), sentAt)
        fixture.close()
    }

    @Test
    fun noTimerSendsWhileTheOutboxIsOff() = runTest {
        val clock = { 1_800_000_000_000L + testScheduler.currentTime }
        val store = RecordingEvidenceStore()
        store.upsert(owedRestoreEvidence("stored-customer"))
        store.upsert(
            owedRestoreEvidence("stored-customer").copy(purchaseToken = "later-token", retryDueAtMillis = clock() + 10_000),
        )
        // The store cannot save the first entry's retry bookkeeping (a full disk, say).
        store.rejectEvidenceUpsert = { it.purchaseToken == "refresh-token" && it.syncAttempts > 0 }
        val launched = fixture(
            this,
            store = store,
            initialRetryDelayMillis = 1_000,
            maxRetryDelayMillis = 1_000_000,
            nowMillis = clock,
        )
        val sent = mutableListOf<String>()
        launched.synchronizer = {
            sent += it.purchaseToken
            PurchaseSyncOutcome.Rejected()
        }

        // Sent at launch, with no answer; its attempt cannot be saved, so the outbox is
        // off: neither that entry's timer nor the other entry's sends again.
        runCurrent()
        assertEquals(listOf("refresh-token"), sent)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(listOf("refresh-token"), sent)
        assertEquals("Nothing was saved", 0, store.load().getValue("refresh-token").syncAttempts)
        launched.close()
    }

    @Test
    fun aPurchaseTheOutboxCannotSaveIsSentOnceAndCompletedByTheNextLaunch() = runTest {
        val actions = mutableListOf<String>()
        val proProfile = Json.parseToJsonElement(
            """{"features":[{"id":"pro","type":"boolean","unlimited":false}]}""",
        ).jsonObject
        // A full disk: the store refuses every write.
        val store = RecordingEvidenceStore().apply { failAllWrites = true }
        val fixture = fixture(this, store = store, actions = actions, refreshedProfile = proProfile)
        val unlockedAtSend = mutableListOf<Boolean>()
        fixture.synchronizer = { evidence ->
            unlockedAtSend += fixture.core.featureInfo.isAllowed("pro")
            accepted(fixture.core.identity.distinctId(), evidence)
        }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        val bought = playPurchase("full-disk-token").forCheckout(fixture)
        fixture.service.onPurchasesUpdated(okUpdate(bought))
        assertEquals("The checkout still finishes for the host and the Journey", PurchaseResult.Purchased, checkout.await())
        runCurrent()

        assertEquals("Exactly one send", listOf("full-disk-token"), fixture.requests.map { it.first })
        assertEquals("No early unlock", listOf(false), unlockedAtSend)
        assertEquals(1, fixture.authorityRefreshes)
        assertTrue("The profile the answer asked for shows access", fixture.core.featureInfo.isAllowed("pro"))
        assertEquals(1, actions.count { it == SystemEventNames.PURCHASE_COMPLETED })
        assertFalse("Not acknowledged", "ack" in actions)
        assertFalse("Not consumed", "consume" in actions)
        assertTrue("Nothing is saved", store.load().isEmpty())
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals("No timer sends it again", 1, fixture.requests.size)
        fixture.close()

        // The relaunch, with a store that saves again: recovery finds the purchase,
        // saves its entry, sends it and completes it with Play.
        store.failAllWrites = false
        val relaunchActions = mutableListOf<String>()
        val relaunched = fixture(this, store = store, actions = relaunchActions)
        relaunched.billing.active[BillingClient.ProductType.INAPP] = listOf(bought)
        relaunched.service.recover()
        runCurrent()
        assertEquals(listOf("full-disk-token"), relaunched.requests.map { it.first })
        val saved = store.load().getValue("full-disk-token")
        assertNull("Saved and answered", saved.pendingSend)
        assertTrue(saved.acknowledged)
        assertEquals(1, relaunchActions.count { it == "ack" })
        assertEquals("No second completion", 0, relaunchActions.count { it == SystemEventNames.PURCHASE_COMPLETED })
        relaunched.close()
    }

    @Test
    fun anUnreadableStoreTurnsTheOutboxOffAtLaunch() = runTest {
        val actions = mutableListOf<String>()
        val store = RecordingEvidenceStore().apply { unreadable = true }
        val fixture = fixture(this, store = store, actions = actions)
        runCurrent()
        val customer = fixture.core.identity.distinctId()
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("unreadable-token", obfuscatedAccountId = accountHash(customer)),
        )

        fixture.service.recover()

        assertEquals("What Play delivers is sent once", listOf("unreadable-token"), fixture.requests.map { it.first })
        assertEquals("Nothing is written to a store that cannot be read", 0, actions.count { it == "persist" })
        assertFalse("ack" in actions)
        fixture.close()
    }

    @Test
    fun aDueEntryTheCatalogNowRequiresSignedForCountsAnAttemptAndKeepsItsTimer() = runTest {
        val clock = { 1_800_000_000_000L + testScheduler.currentTime }
        val fixture = fixture(
            this,
            initialRetryDelayMillis = 1_000,
            maxRetryDelayMillis = 1_000_000,
            nowMillis = clock,
            startService = false,
        )
        val customer = fixture.core.identity.distinctId()
        // An owed, unverified entry that failed once and is due again in a second.
        fixture.store.upsert(
            owedRestoreEvidence(customer).copy(
                signatureVerified = false,
                syncAttempts = 1,
                retryDueAtMillis = clock() + 1_000,
            ),
        )
        // Before its retry fires, its product gains a licensing key.
        fixture.store.upsertProductMapping(
            StoredProductMapping(
                storeProductId = "play-pro",
                nuxieProductId = "nuxie-pro",
                productType = BillingClient.ProductType.SUBS,
                consumable = false,
                licensingPublicKey = "catalog-key",
            ),
        )
        var sends = 0
        fixture.synchronizer = {
            sends += 1
            accepted(it.requester, it)
        }
        fixture.service.start()
        runCurrent()

        // The retry fires; the signature gate keeps it unsent, and it counts an attempt.
        advanceTimeBy(1_000)
        runCurrent()
        val gated = fixture.store.load().getValue("refresh-token")
        assertEquals(0, sends)
        assertEquals(2, gated.syncAttempts)
        assertEquals(clock() + 2_000, gated.retryDueAtMillis)

        // A new timer was set: it fires at the next backoff step.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, sends)
        assertEquals(3, fixture.store.load().getValue("refresh-token").syncAttempts)
        fixture.close()
    }

    @Test
    fun anEntryNeverSentIsSentAtLaunchWithoutPlay() = runTest {
        // The entry was admitted, then the process died before its first send.
        val store = RecordingEvidenceStore()
        store.upsert(owedRestoreEvidence("stored-customer").copy(syncAttempts = 0, retryDueAtMillis = null))

        // The app starts again and Billing cannot connect.
        val launched = fixture(this, store = store)
        launched.billing.failQueries = true
        launched.synchronizer = { accepted(it.requester, it) }
        runCurrent()

        assertEquals("The outbox sends it without Play", listOf("refresh-token"), launched.requests.map { it.first })
        assertTrue(launched.billing.queries.isEmpty())
        assertNull(store.load().getValue("refresh-token").pendingSend)
        launched.close()
    }

    @Test
    fun anAlreadyOwnedCheckoutReportsOnlyItsFailure() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        fixture.core.identity.setDistinctId("buyer")
        fixture.synchronizer = { accepted(it.requester, it) }
        // Play owns the product for this account, but the device kept no record of it.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("owned-token", obfuscatedAccountId = accountHash("buyer")),
        )
        fixture.billing.launchCode = BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        val pro = product(licensingPublicKey = null)

        val result = fixture.service.purchase(
            activity(),
            pro,
            null,
            outcomeCorrelation = CommerceOutcomeCorrelation("owned-checkout", "buyer", "checkout-journey"),
        )

        assertTrue("$result", (result as PurchaseResult.Failed).cause is PurchaseAlreadyOwnedException)
        val outcomes = emissions.filter {
            it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED ||
                it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_FAILED
        }
        assertEquals(
            "The checkout's only outcome is the already-owned failure, as on iOS",
            listOf(
                ai.nuxie.sdk.events.SystemEventNames.PURCHASE_FAILED to mapOf<String, Any?>(
                    "product_id" to pro.productId,
                    "placement_id" to pro.placementId,
                    "store_product_id" to pro.storeProductId,
                    "reason" to "already_owned",
                    "test_store" to false,
                ),
            ),
            outcomes,
        )
        assertEquals("Under the checkout's outcome correlation", listOf("owned-checkout"), fixture.purchaseCompletionEventIds)
        assertEquals(
            "The owned purchase is still sent as the customer's purchase claim",
            listOf("owned-token" to PurchaseSyncRequest(PurchaseSyncReason.PURCHASE, "buyer", CustomerKind.IDENTIFIED)),
            fixture.requests.toList(),
        )
        assertNull(fixture.store.load().getValue("owned-token").checkoutCompletionEventId)
        fixture.close()
    }

    @Test
    fun aRecoveryAfterAnAlreadyOwnedCheckoutEmitsNoCompletion() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        fixture.core.identity.setDistinctId("buyer")
        fixture.synchronizer = { accepted(it.requester, it) }
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("owned-token", obfuscatedAccountId = accountHash("buyer")),
        )
        fixture.billing.launchCode = BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        fixture.service.purchase(
            activity(),
            product(licensingPublicKey = null),
            null,
            outcomeCorrelation = CommerceOutcomeCorrelation("owned-checkout", "buyer", "checkout-journey"),
        )

        // A launch recovery finds the purchase again: it carries no checkout's context.
        fixture.service.recover()

        assertFalse(
            "No completion counts as a conversion for a checkout Play refused as already owned",
            emissions.any { it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        fixture.close()
    }

    @Test
    fun aCheckoutsBindingGivesNoContextOnceTheCheckoutHasEnded() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        val customer = fixture.core.identity.distinctId()
        fixture.synchronizer = { accepted(it.requester, it) }
        // The customer cancels a checkout; its binding stays only as catalog facts.
        fixture.billing.launchCode = BillingClient.BillingResponseCode.USER_CANCELED
        assertEquals(PurchaseResult.Cancelled, fixture.service.purchase(activity(), product(licensingPublicKey = null), null))
        assertFalse(fixture.store.loadBindings().single().awaitingCheckout)

        // A purchase of that product for the customer's account turns up in a scan.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("elsewhere-token", obfuscatedAccountId = accountHash(customer)),
        )
        fixture.service.recover()

        assertEquals(listOf("elsewhere-token"), fixture.requests.map { it.first }.distinct())
        assertNull("No checkout made it", fixture.store.load().getValue("elsewhere-token").buyerDistinctId)
        assertFalse(emissions.any { it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED })
        fixture.close()
    }

    @Test
    fun aPurchaseALaunchScanFindsEmitsNoCompletion() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        val customer = fixture.core.identity.distinctId()
        fixture.synchronizer = { accepted(it.requester, it) }
        // Play owns a purchase of this customer's account that no checkout on this device made.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("scanned-token", obfuscatedAccountId = accountHash(customer)),
        )

        fixture.service.recover()
        fixture.service.recover()

        assertEquals("It is still sent", listOf("scanned-token"), fixture.requests.map { it.first }.distinct())
        assertFalse(emissions.any { it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED })
        fixture.close()
    }

    @Test
    fun aRecoveryDuringACheckoutNeverTakesAnAlreadyOwnedPurchaseForTheCheckouts() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        // The store invokes the catalog listener, as the real stores do.
        val store = RecordingEvidenceStore().apply { notifiesCatalogChanges = true }
        val fixture = fixture(this, store = store, emissions = emissions)
        fixture.core.identity.setDistinctId("buyer")
        fixture.synchronizer = { accepted(it.requester, it) }
        // Play already owns the product for this account; the device has no record of it.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("owned-token", obfuscatedAccountId = accountHash("buyer")),
        )
        fixture.billing.launchCode = BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
        val launching = CompletableDeferred<Unit>()
        val finishLaunching = CompletableDeferred<Unit>()
        fixture.billing.launchStarted = launching
        fixture.billing.holdLaunch = finishLaunching

        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(licensingPublicKey = null),
                null,
                outcomeCorrelation = CommerceOutcomeCorrelation("owned-checkout", "buyer", "checkout-journey"),
            )
        }
        runCurrent()
        launching.await()
        // A recovery ends while the checkout's binding is written and awaiting.
        fixture.service.recover()
        finishLaunching.complete(Unit)
        checkout.await()

        val outcomes = emissions.filter {
            it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED ||
                it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_FAILED
        }
        assertEquals(listOf(ai.nuxie.sdk.events.SystemEventNames.PURCHASE_FAILED), outcomes.map { it.first })
        assertEquals("already_owned", outcomes.single().second["reason"])
        fixture.close()
    }

    @Test
    fun aCheckoutsOwnProductMappingStartsNoRecovery() = runTest {
        val store = RecordingEvidenceStore().apply { notifiesCatalogChanges = true }
        val fixture = fixture(this, store = store)
        fixture.billing.launchCode = BillingClient.BillingResponseCode.USER_CANCELED

        assertEquals(PurchaseResult.Cancelled, fixture.service.purchase(activity(), product(licensingPublicKey = null), null))
        runCurrent()

        assertEquals("Only the checkout's own query asks Play", listOf(BillingClient.ProductType.INAPP), fixture.billing.queries)
        fixture.close()
    }

    @Test
    fun noOutboxWorkStartsBeforeTheCoreStarts() = runTest {
        val actions = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        var coreBuilt = false
        val fixture = fixture(
            this,
            actions = actions,
            emissions = emissions,
            startService = false,
            profileReady = { coreBuilt },
        )
        val customer = fixture.core.identity.distinctId()
        // An overdue entry the server accepts at once.
        fixture.store.upsert(
            owedRestoreEvidence(customer).copy(acknowledged = false, syncAttempts = 1, retryDueAtMillis = 1L),
        )
        fixture.synchronizer = { accepted(it.requester, it) }
        runCurrent()
        assertTrue("Nothing is sent before the core starts", fixture.requests.isEmpty())

        coreBuilt = true
        fixture.service.start()
        runCurrent()

        assertEquals(listOf("refresh-token"), fixture.requests.map { it.first })
        assertTrue(
            "The answer is reported as purchase synced",
            emissions.any { it.first == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_SYNCED },
        )
        assertTrue("Its managed completion runs", "ack" in actions)
        assertTrue(fixture.store.load().getValue("refresh-token").acknowledged)
        fixture.close()
    }

    @Test
    fun anAlreadyOwnedAnswerInAPurchaseUpdateIsReconciledLikeALaunchResult() = runTest {
        val fixture = fixture(this)
        fixture.core.identity.setDistinctId("buyer")
        fixture.synchronizer = { accepted("buyer", it) }
        val checkout = async { fixture.service.purchase(activity(), product(), null) }
        runCurrent()
        // Play accepted the launch, then reports through its callback that this
        // account already owns the product.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("owned-token", obfuscatedAccountId = accountHash("buyer")),
        )
        fixture.service.onPurchasesUpdated(
            PurchaseUpdate(result(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED), purchases = null),
        )

        val result = checkout.await()
        assertTrue("$result", (result as PurchaseResult.Failed).cause is PurchaseAlreadyOwnedException)
        assertEquals(
            listOf("owned-token" to PurchaseSyncRequest(PurchaseSyncReason.PURCHASE, "buyer", CustomerKind.IDENTIFIED)),
            fixture.requests.toList(),
        )
        fixture.close()
    }

    @Test
    fun aRestoreAdmitsWhatItFindsAsItsClaimWithoutUnlockingEarly() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        runCurrent()
        // A purchase of this customer's Play account that this device never stored.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("found-token", obfuscatedAccountId = accountHash(customer)),
        )
        val written = mutableListOf<PurchaseSyncReason?>()
        fixture.store.rejectEvidenceUpsert = { evidence ->
            if (evidence.purchaseToken == "found-token") written += evidence.pendingSend?.reason
            false
        }
        var unlockedEarly = false
        fixture.core.featureInfo.onFeatureChange = { featureId, _, newAccess, _ ->
            if (featureId == "pro" && newAccess.allowed) unlockedEarly = true
        }
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }

        assertTrue(fixture.service.restorePurchases() is RestoreResult.Failed)

        assertFalse("No sync entry is written first", PurchaseSyncReason.SYNC in written)
        assertEquals(PurchaseSyncReason.RESTORE, fixture.store.load().getValue("found-token").pendingSend?.reason)
        assertFalse("Nothing a Restore finds unlocks early", unlockedEarly)
        fixture.close()
    }

    @Test
    fun aStaleAcceptanceOnlyAsksForAProfileAndGrantsNothing() = runTest {
        val fixture = fixture(this)
        fixture.core.identity.setDistinctId("customer-a")
        fixture.store.upsert(owedRestoreEvidence("customer-a", kind = CustomerKind.IDENTIFIED))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash("customer-a")).copy(acknowledged = true),
        )
        val firstSendStarted = CompletableDeferred<Unit>()
        val answerTheFirstSend = CompletableDeferred<Unit>()
        var sends = 0
        fixture.synchronizer = {
            sends += 1
            if (sends == 1) {
                firstSendStarted.complete(Unit)
                answerTheFirstSend.await()
                // Its 200 grants "pro".
                accepted("customer-a", it)
            } else {
                PurchaseSyncOutcome.Rejected()
            }
        }
        // A's entry is out.
        val launch = async { fixture.service.recover() }
        runCurrent()
        firstSendStarted.await()

        // B's Restore takes the entry over mid-send.
        fixture.core.identity.setDistinctId("customer-b")
        fixture.core.featureInfo.publish(fixture.core.features.handleUserChange("customer-a", "customer-b"))
        val restore = async { fixture.service.restorePurchases() }
        runCurrent()
        assertEquals("customer-b", fixture.store.load().getValue("refresh-token").pendingSend?.distinctId)

        // Back to A when A's old 200 arrives.
        fixture.core.identity.setDistinctId("customer-a")
        fixture.core.featureInfo.publish(fixture.core.features.handleUserChange("customer-b", "customer-a"))
        val refreshesBefore = fixture.authorityRefreshes
        answerTheFirstSend.complete(Unit)
        launch.await()

        assertFalse("A stale 200 publishes no Features and holds none", fixture.core.featureInfo.isAllowed("pro"))
        assertEquals("It only asks for A's profile", refreshesBefore + 1, fixture.authorityRefreshes)
        assertEquals("B's claim is still owed", "customer-b", fixture.store.load().getValue("refresh-token").pendingSend?.distinctId)
        restore.await()
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun eachAcceptedAdmissionIsReportedOnceForItsCustomer() = runTest {
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fixture = fixture(this, emissions = emissions)
        // A's purchase, already accepted for A and reported for A.
        fixture.store.upsert(retainedRestoreEvidence("customer-a"))
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase("refresh-token", obfuscatedAccountId = accountHash("customer-a")).copy(acknowledged = true),
        )
        fun syncedFor(customer: String) = emissions.count { (name, properties) ->
            name == SystemEventNames.PURCHASE_SYNCED && properties["customer_id"] == customer
        }

        // B restores it and the server accepts B's claim.
        fixture.core.identity.setDistinctId("customer-b")
        fixture.synchronizer = { accepted("customer-b", it) }
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())
        assertEquals("B's accepted Restore is reported for B", 1, syncedFor("customer-b"))

        // Processing the same acceptance again reports nothing more.
        fixture.service.recover()
        assertEquals(1, syncedFor("customer-b"))
        assertEquals(0, syncedFor("customer-a"))
        fixture.close()
    }

    @Test
    fun aQueryInFlightCannotDropAPurchaseReportedAfterItStarted() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        runCurrent()
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        // Recovery's one-time query is out, with Play's answer from before the purchase.
        val queryOut = CompletableDeferred<Unit>()
        val answerTheQuery = CompletableDeferred<Unit>()
        fixture.billing.queryStartedFor[BillingClient.ProductType.INAPP] = queryOut
        fixture.billing.holdQueryOf[BillingClient.ProductType.INAPP] = answerTheQuery
        val recovery = async { fixture.service.recover() }
        runCurrent()
        queryOut.await()

        // Play delivers a new purchase of the same type meanwhile.
        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("new-token", obfuscatedAccountId = accountHash(customer))),
        )
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        answerTheQuery.complete(Unit)
        recovery.await()
        assertTrue(
            "An older query's answer cannot drop a purchase delivered after it started",
            fixture.core.featureInfo.isAllowed("pro"),
        )
        fixture.close()
    }

    @Test
    fun overlappingQueriesLeaveTheEarlyUnlockUntilTheServerAnswers() = runTest {
        val fixture = fixture(this)
        val customer = fixture.core.identity.distinctId()
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        runCurrent()
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }
        val purchase = playPurchase("dropped-token", obfuscatedAccountId = accountHash(customer))
        fixture.service.onPurchasesUpdated(okUpdate(purchase))
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // An older query is out, holding Play's answer that still had the purchase.
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(purchase)
        val olderOut = CompletableDeferred<Unit>()
        val answerTheOlder = CompletableDeferred<Unit>()
        fixture.billing.queryStartedFor[BillingClient.ProductType.INAPP] = olderOut
        fixture.billing.holdQueryOf[BillingClient.ProductType.INAPP] = answerTheOlder
        val older = async { fixture.service.recover() }
        runCurrent()
        olderOut.await()

        // A newer query finds it gone (a refund, say): the server has not answered.
        fixture.billing.active[BillingClient.ProductType.INAPP] = emptyList()
        fixture.service.recover()
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // The older answer arrives last: still nothing but the server decides.
        answerTheOlder.complete(Unit)
        older.await()
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        // The server refuses it: the early unlock ends at once, whatever Play said.
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(refused = true) }
        fixture.service.recover()
        assertFalse(
            "Only the server's answer ends the early unlock",
            fixture.core.featureInfo.isAllowed("pro"),
        )
        fixture.close()
    }

    @Test
    fun authenticatedRestoreWaitsForBackendVerification() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.service.rememberProduct(product())
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("deferred-authenticated-restore", obfuscatedAccountId = accountHash(owner)),
        )
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected() }

        val result = fixture.service.restorePurchases(expectedOwnerDistinctId = owner)

        assertTrue(result is RestoreResult.Failed)
        assertEquals(
            RestoreClaimError.UNCONFIRMED,
            ((result as RestoreResult.Failed).cause as RestoreClaimException).error,
        )
        assertFalse(fixture.store.load().getValue("deferred-authenticated-restore").synced)
        fixture.close()
    }

    @Test
    fun authenticatedFreshInstallRestoreRetainsServerVerifiedCatalogIdentity() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.billing.active[BillingClient.ProductType.SUBS] = listOf(
            playPurchase(
                "retired-product-token",
                products = listOf("play-retired"),
                obfuscatedAccountId = accountHash(owner),
            ),
        )
        fixture.synchronizer = { evidence ->
            assertFalse(evidence.catalogResolved)
            PurchaseSyncOutcome.Accepted(
                NuxieApi.PurchaseResponse(
                    body = Json.parseToJsonElement(
                        """{"success":true,"customer_id":"server-customer","features":[],"catalog_product":{"id":"nuxie-retired","store_product_id":"play-retired","base_plan_id":"annual","purchase_option_id":null,"offer_id":"launch","store_product_type":"subscription"}}""",
                    ).jsonObject,
                    success = true,
                    customerId = "server-customer",
                    catalogProduct = NuxieApi.VerifiedCatalogProduct(
                        productId = "nuxie-retired",
                        storeProductId = "play-retired",
                        basePlanId = "annual",
                        purchaseOptionId = null,
                        offerId = "launch",
                        storeProductType = "subscription",
                    ),
                ),
            )
        }

        assertEquals(
            RestoreResult.Restored,
            fixture.service.restorePurchases(expectedOwnerDistinctId = owner),
        )
        val retained = fixture.store.load().getValue("retired-product-token")
        assertEquals("nuxie-retired", retained.nuxieProductId)
        assertEquals("annual", retained.basePlanId)
        assertEquals("launch", retained.offerId)
        assertEquals(BillingClient.ProductType.SUBS, retained.productType)
        assertTrue(retained.catalogResolved)
        assertTrue(retained.acknowledged)
        fixture.close()
    }

    @Test
    fun identityChangeDuringDelegateRestoreSuppressesTheForwardedOutcome() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val restoreStarted = CompletableDeferred<Unit>()
        val finishRestore = CompletableDeferred<Unit>()
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = PurchaseResult.Purchased
            override suspend fun restorePurchases(): RestoreResult {
                restoreStarted.complete(Unit)
                finishRestore.await()
                return RestoreResult.NoPurchases
            }
        }
        val restoring = async { fixture.service.restorePurchases() }
        restoreStarted.await()

        fixture.core.identity.setDistinctId("replacement-customer")
        finishRestore.complete(Unit)

        assertEquals(RestoreResult.NoPurchases, restoring.await())
        assertFalse(ai.nuxie.sdk.events.SystemEventNames.RESTORE_NO_PURCHASES in actions)
        fixture.close()
    }

    @Test
    fun unknownAccountPurchaseSyncsForCurrentCustomerWithoutOptimisticOverlay() = runTest {
        val fixture = fixture(this)

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("other-customer", obfuscatedAccountId = accountHash("someone-else"))),
        )

        assertEquals(
            fixture.core.identity.distinctId(),
            fixture.requests.single().second.distinctId,
        )
        assertTrue(fixture.store.load().getValue("other-customer").synced)
        fixture.close()
    }

    private fun fixture(
        scope: TestScope,
        mode: PurchaseHandlingMode = PurchaseHandlingMode.NUXIE_MANAGED,
        store: RecordingEvidenceStore = RecordingEvidenceStore(),
        actions: MutableList<String> = mutableListOf(),
        emissions: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf(),
        initialRetryDelayMillis: Long = 60_000,
        maxRetryDelayMillis: Long = 60_000,
        purchaseEventCaptureResults: MutableList<Boolean> = mutableListOf(),
        capturePurchaseEventOverride: (suspend (
            String,
            Map<String, Any?>,
            String,
            String,
        ) -> Boolean)? = null,
        externalOperationId: (() -> String)? = null,
        verifyPurchaseSignature: (String, String, String) -> Boolean = { _, _, _ -> true },
        logDroppedExternalPurchase: (String, Int, Throwable) -> Unit = { _, _, _ -> },
        journeyEvents: MutableList<StoredEvent>? = null,
        testStore: NuxieTestStore? = null,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        // The service's clock follows the test's virtual clock, so saved due
        // times and retry timers agree.
        nowMillis: () -> Long = { 1_800_000_000_000L + scope.testScheduler.currentTime },
        /** Start the service as the core's start() does; a test can start it itself. */
        startService: Boolean = true,
        /** Whether the core is built far enough to refresh the profile. */
        profileReady: () -> Boolean = { true },
        /** The profile each refresh the service asks for lands, if any. */
        refreshedProfile: JsonObject? = null,
    ): Fixture {
        val storageDirectory = temporaryFolder.newFolder("fixture-${fixtures.size}")
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_purchase_${System.identityHashCode(store)}",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = FakeTransport(),
                registerLifecycle = false,
                eventDatabaseFile = File(storageDirectory, "events.db"),
                profileCacheDirectory = File(storageDirectory, "profiles"),
                requestInitialProfileRefresh = false,
                billingClientFactory = InertBillingClientAdapter.factory,
            ),
        )
        journeyEvents?.let { events ->
            core.eventLog.subscribeCommittedWithAdmission(
                sampleGeneration = { 0L },
            ) { event, _ ->
                if (event.distinctId == core.identity.distinctId()) {
                    events += event
                    true
                } else {
                    false
                }
            }
        }
        kotlinx.coroutines.runBlocking { core.purchases.awaitInitialProjection() }
        store.actions = actions
        val billing = FakeBilling(actions)
        val settings = PurchaseSettings(null, mode)
        val purchaseCompletionEventIds = mutableListOf<String>()
        val purchaseEventCaptureAttempts = mutableListOf<String>()
        val purchaseEventDistinctIds = mutableListOf<String>()
        var externalOperationSequence = 0
        lateinit var fixture: Fixture
        val service = PurchaseService(
            purchaseStorageScope = "test-fixture",
            billing = billing,
            evidenceStore = store,
            synchronizer = PurchaseSynchronizer { evidence, request ->
                fixture.requests += evidence.purchaseToken to request
                fixture.synchronizer(evidence)
            },
            features = core.features,
            distinctId = { core.identity.distinctId() },
            customerKind = core.identity::customerKind,
            emit = { name, properties ->
                actions += name
                emissions += name to properties
            },
            settings = settings,
            scope = scope.backgroundScope,
            initialRetryDelayMillis = initialRetryDelayMillis,
            maxRetryDelayMillis = maxRetryDelayMillis,
            outboxRetryInitialDelayMillis = initialRetryDelayMillis,
            outboxRetryMaxDelayMillis = maxRetryDelayMillis,
            nowMillis = nowMillis,
            api = core.api,
            capturePurchaseEvent = { name, properties, eventId, capturedDistinctId ->
                purchaseEventCaptureAttempts += eventId
                val captured = capturePurchaseEventOverride?.invoke(
                    name,
                    properties,
                    eventId,
                    capturedDistinctId,
                ) ?: if (purchaseEventCaptureResults.isEmpty()) {
                    true
                } else {
                    purchaseEventCaptureResults.removeAt(0)
                }
                val durablyCaptured = if (captured && journeyEvents != null) {
                    core.capturePurchaseEvent(name, properties, eventId, capturedDistinctId)
                } else {
                    captured
                }
                if (durablyCaptured && eventId !in purchaseCompletionEventIds) {
                    purchaseCompletionEventIds += eventId
                    purchaseEventDistinctIds += capturedDistinctId
                    actions += name
                    emissions += name to properties
                }
                durablyCaptured
            },
            newExternalOperationId = externalOperationId
                ?: { "external-operation-${++externalOperationSequence}" },
            verifyPurchaseSignature = verifyPurchaseSignature,
            logDroppedExternalPurchase = logDroppedExternalPurchase,
            testStore = testStore,
            ioDispatcher = ioDispatcher,
            refreshFeatureAuthority = {
                check(profileReady()) { "The profile is not built yet." }
                fixture.authorityRefreshes += 1
                refreshedProfile?.let { profile ->
                    scope.backgroundScope.launch {
                        core.features.hydrateProfile(core.identity.distinctId(), profile)
                    }
                }
            },
        )
        if (startService) service.start()
        fixture = Fixture(
            core,
            billing,
            store,
            service,
            settings,
            purchaseCompletionEventIds,
            purchaseEventCaptureAttempts,
            purchaseEventDistinctIds,
        ) { evidence -> accepted(core.identity.distinctId(), evidence) }
        fixtures += fixture
        return fixture
    }

    private class Fixture(
        val core: NuxieCore,
        val billing: FakeBilling,
        val store: RecordingEvidenceStore,
        val service: PurchaseService,
        val settings: PurchaseSettings,
        val purchaseCompletionEventIds: List<String>,
        val purchaseEventCaptureAttempts: List<String>,
        val purchaseEventDistinctIds: List<String>,
        var synchronizer: suspend (PurchaseEvidence) -> PurchaseSyncOutcome,
    ) {
        /** How often the service asked for a fresh server profile. */
        var authorityRefreshes = 0

        /** Every `/purchase` submission, by purchase token, in order. */
        val requests: MutableList<Pair<String, PurchaseSyncRequest>> =
            java.util.Collections.synchronizedList(mutableListOf())

        fun close() = core.stop()
    }

    private class RecordingEvidenceStore(private val checkThread: () -> Unit = {}) : PurchaseEvidenceStore {
        private val entries = linkedMapOf<String, PurchaseEvidence>()
        private val bindings =
            linkedMapOf<Pair<String, StoredProductIdentity>, StoredPurchaseBinding>()
        private val mappings = linkedMapOf<StoredProductIdentity, StoredProductMapping>()
        var actions: MutableList<String> = mutableListOf()
        var failEvidenceUpserts = false
        /** Refuses every write (entries, bindings, catalog), as a full disk does. */
        var failAllWrites = false
        /** Reads as empty and refuses every write, as a store that fails closed does. */
        var unreadable = false
        override suspend fun isReadable(): Boolean = !unreadable
        /** Rejects only the evidence writes it matches. */
        var rejectEvidenceUpsert: ((PurchaseEvidence) -> Boolean)? = null
        var afterNextLoad: ((Map<String, PurchaseEvidence>) -> Unit)? = null
        /** Runs once, after the next load took its snapshot, and may hold that load. */
        var holdNextLoad: (suspend () -> Unit)? = null
        var afterNextMappingsLoad: (suspend (List<StoredProductMapping>) -> Unit)? = null
        /** Runs before each evidence write, and may hold it. */
        var beforeUpsert: (suspend (PurchaseEvidence) -> Unit)? = null
        override suspend fun load(): Map<String, PurchaseEvidence> {
            checkThread()
            if (unreadable) return emptyMap()
            val snapshot = entries.toMap()
            val callback = afterNextLoad
            afterNextLoad = null
            callback?.invoke(snapshot)
            val hold = holdNextLoad
            holdNextLoad = null
            hold?.invoke()
            return snapshot
        }
        override suspend fun upsert(evidence: PurchaseEvidence): Boolean {
            beforeUpsert?.invoke(evidence)
            actions += "persist"
            if (unreadable || failAllWrites || failEvidenceUpserts || rejectEvidenceUpsert?.invoke(evidence) == true) return false
            entries[evidence.purchaseToken] = evidence
            return true
        }
        fun replaceWithoutRecording(evidence: PurchaseEvidence) {
            entries[evidence.purchaseToken] = evidence
        }
        override suspend fun loadBindings(): List<StoredPurchaseBinding> = bindings.values.toList()
        override suspend fun upsertBinding(binding: StoredPurchaseBinding): Boolean {
            checkThread()
            if (unreadable || failAllWrites) return false
            bindings[binding.obfuscatedAccountId to binding.productIdentity] = binding
            return true
        }
        override suspend fun loadProductMappings(): List<StoredProductMapping> {
            val snapshot = mappings.values.toList()
            val callback = afterNextMappingsLoad
            afterNextMappingsLoad = null
            callback?.invoke(snapshot)
            return snapshot
        }
        /** Invoke the catalog listener as the real stores do; off unless a test asks. */
        var notifiesCatalogChanges = false
        private var catalogListener: (suspend () -> Unit)? = null
        override fun setProductMappingsChangedListener(listener: (suspend () -> Unit)?) {
            catalogListener = listener
        }
        override suspend fun upsertProductMapping(mapping: StoredProductMapping, catalogChange: Boolean): Boolean {
            checkThread()
            if (unreadable || failAllWrites) return false
            val changed = mappings.put(mapping.productIdentity, mapping) != mapping
            if (notifiesCatalogChanges && changed && catalogChange) catalogListener?.invoke()
            return true
        }
    }

    private class FakeBilling(private val actions: MutableList<String>) : PlayBillingGateway {
        val active = mutableMapOf<String, List<PlayPurchase>>()
        val queries = mutableListOf<String>()
        var launched: CheckoutRequest? = null
        var launchCode = BillingClient.BillingResponseCode.OK
        val acknowledgeCodes = mutableListOf<Int>()
        var queryFailure: Exception? = null
        var failQueries = false
        /** Product types whose queries fail while the others answer. */
        val failQueryTypes = mutableSetOf<String>()
        var queryStarted: CompletableDeferred<Unit>? = null
        var releaseQueries: CompletableDeferred<Unit>? = null
        /**
         * The next query of one product type: signalled when it starts, held until
         * released, answering what Play had then. Each applies to one query only.
         */
        val queryStartedFor = mutableMapOf<String, CompletableDeferred<Unit>>()
        val holdQueryOf = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** Signalled when a checkout launches; the next one is held until [holdLaunch] completes. */
        var launchStarted: CompletableDeferred<Unit>? = null
        var holdLaunch: CompletableDeferred<Unit>? = null

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult {
            launched = request
            launchStarted?.complete(Unit)
            holdLaunch?.let { hold ->
                holdLaunch = null
                hold.await()
            }
            return billingResult(launchCode)
        }

        override suspend fun queryActive(productType: String): ActivePurchasesResult {
            queries += productType
            queryFailure?.let { throw it }
            queryStarted?.complete(Unit)
            releaseQueries?.await()
            if (failQueries || productType in failQueryTypes) {
                return ActivePurchasesResult.Failed(
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                    "offline",
                )
            }
            val answer = active[productType].orEmpty()
            queryStartedFor.remove(productType)?.complete(Unit)
            holdQueryOf.remove(productType)?.await()
            return ActivePurchasesResult.Success(answer)
        }

        /** Signalled when an acknowledgement starts; the next one is held until [holdAcknowledge] completes. */
        var acknowledgeStarted: CompletableDeferred<Unit>? = null
        var holdAcknowledge: CompletableDeferred<Unit>? = null

        override suspend fun acknowledge(purchaseToken: String): BillingResult {
            actions += "ack"
            acknowledgeStarted?.complete(Unit)
            holdAcknowledge?.let { hold ->
                holdAcknowledge = null
                hold.await()
            }
            return billingResult(
                if (acknowledgeCodes.isEmpty()) BillingClient.BillingResponseCode.OK
                else acknowledgeCodes.removeAt(0),
            )
        }

        override suspend fun consume(purchaseToken: String): BillingResult {
            actions += "consume"
            return billingResult(BillingClient.BillingResponseCode.OK)
        }

        private fun billingResult(code: Int): BillingResult = BillingResult.newBuilder()
            .setResponseCode(code)
            .setDebugMessage("result")
            .build()
    }

    private fun product(
        subscription: Boolean = false,
        productId: String = "nuxie-pro",
        storeProductId: String = "play-pro",
        basePlanId: String? = if (subscription) "annual" else null,
        offerId: String? = if (subscription) "launch" else null,
        consumable: Boolean = false,
        allowances: List<FeatureAllowance> = emptyList(),
        licensingPublicKey: String? = "test-public-key",
        rawProduct: ProductDetails? = null,
    ) = StoreProduct(
        productId = productId,
        storeProductId = storeProductId,
        basePlanId = basePlanId,
        offerId = offerId,
        placementId = "primary",
        rawProduct = rawProduct,
        offerToken = "offer-token",
        isOfferPersonalized = true,
        productType = if (subscription) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP,
        consumable = consumable,
        featureAllowances = allowances,
        licensingPublicKey = licensingPublicKey,
        purchaseContext = PurchaseContext("experience-1", "v1"),
    )

    private fun oneTimeProductDetails(): ProductDetails {
        val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(
            """{"productId":"play-pro","type":"inapp","title":"Pro","name":"Pro","description":"Pro","oneTimePurchaseOfferDetails":{"formattedPrice":"¥1,200","priceAmountMicros":1200000000,"priceCurrencyCode":"JPY"}}""",
        )
    }

    private fun subscriptionProductDetails(): ProductDetails {
        val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(
            """{"productId":"play-pro","type":"subs","title":"Pro","name":"Pro","description":"Pro","subscriptionOfferDetails":[{"basePlanId":"annual","offerId":"launch","offerIdToken":"offer-token","pricingPhases":[{"billingPeriod":"P1M","priceCurrencyCode":"EUR","formattedPrice":"€0.00","priceAmountMicros":0,"recurrenceMode":2,"billingCycleCount":1},{"billingPeriod":"P1Y","priceCurrencyCode":"EUR","formattedPrice":"€9.99","priceAmountMicros":9990000,"recurrenceMode":1,"billingCycleCount":0}]}]}""",
        )
    }

    private fun assertForwardedPrice(
        name: String,
        properties: Map<String, Any?>,
        expectedPrice: String,
        expectedDisplayPrice: String,
    ) {
        val activity = checkNotNull(
            ActivityCuration.activity(name, JsonValueConverter.fromMap(properties)),
        )
        val info = when (activity) {
            is NuxieActivity.PurchaseCompleted -> activity.info
            is NuxieActivity.PurchaseFailed -> activity.info
            is NuxieActivity.PurchaseCancelled -> activity.info
            is NuxieActivity.PurchasePending -> activity.info
            else -> error("Expected a purchase lifecycle activity, got ${activity.name}.")
        }
        assertEquals(0, checkNotNull(info.price).compareTo(BigDecimal(expectedPrice)))
        assertEquals(expectedDisplayPrice, info.displayPrice)
    }

    private fun playPurchase(
        token: String,
        products: List<String> = listOf("play-pro"),
        state: StoredPurchaseState = StoredPurchaseState.PURCHASED,
        obfuscatedAccountId: String? = null,
    ) = PlayPurchase(
        purchaseToken = token,
        packageName = "com.example.app",
        products = products,
        state = state,
        acknowledged = false,
        obfuscatedAccountId = obfuscatedAccountId,
        originalJson = "{}",
        signature = "",
    )

    private fun okUpdate(vararg purchases: PlayPurchase) = PurchaseUpdate(
        result(BillingClient.BillingResponseCode.OK),
        purchases.toList(),
    )

    private fun accepted(
        customerId: String,
        evidence: PurchaseEvidence,
    ) = PurchaseSyncOutcome.Accepted(
        NuxieApi.PurchaseResponse(
            Json.parseToJsonElement(
                """{"success":true,"customer_id":"$customerId","features":[{"id":"pro","type":"boolean","allowed":true,"unlimited":false},{"id":"unlimited","type":"metered","allowed":true,"unlimited":true}]}""",
            ).jsonObject,
            true,
            customerId,
            NuxieApi.VerifiedCatalogProduct(
                productId = evidence.nuxieProductId ?: "server-product",
                storeProductId = evidence.storeProductIds.single(),
                basePlanId = evidence.basePlanId,
                purchaseOptionId = evidence.purchaseOptionId,
                offerId = evidence.offerId,
                storeProductType = when {
                    evidence.productType == BillingClient.ProductType.SUBS -> "subscription"
                    evidence.consumable -> "consumable"
                    else -> "nonConsumable"
                },
            ),
        ),
    )

    private fun result(code: Int): BillingResult = BillingResult.newBuilder()
        .setResponseCode(code)
        .setDebugMessage("result")
        .build()

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).get()

    private fun PlayPurchase.forCheckout(fixture: Fixture): PlayPurchase = copy(
        obfuscatedAccountId = fixture.billing.launched?.obfuscatedAccountId,
    )

    private fun StoreProduct.bindingFor(owner: String, nuxieManaged: Boolean = true) = StoredPurchaseBinding(
        obfuscatedAccountId = accountHash(owner),
        distinctId = owner,
        storeProductId = storeProductId,
        nuxieProductId = productId,
        basePlanId = basePlanId,
        offerId = offerId,
        productType = productType,
        consumable = consumable,
        context = StoredPurchaseContext(
            placementId,
            purchaseContext?.experienceId,
            purchaseContext?.experienceVersion,
        ),
        featureAllowances = featureAllowances.map {
            StoredFeatureAllowance(it.featureId, it.type.name, it.unlimited, it.allowance)
        },
        licensingPublicKey = licensingPublicKey,
        nuxieManaged = nuxieManaged,
    )

    private fun accountHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
