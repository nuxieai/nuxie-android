package ai.nuxie.sdk.billing

import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.ActivityCuration
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.testsupport.FakeTransport
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
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
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
    fun billingClientJourneyReleaseDeliveryIsTheTrustBoundaryWhenNoLicensingKeyIsConfigured() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
        assertFalse(retained.signatureVerificationRequired)
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
        assertTrue(evidence.signatureVerificationRequired)
        assertFalse(evidence.signatureVerified)
        assertTrue(evidence.permanentlyRejected)
        assertTrue(evidence.revoked)
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
            accepted(evidence.syncAttributionDistinctId, evidence)
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
            accepted(it.syncAttributionDistinctId, it)
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
            PurchaseSyncOutcome.Rejected(permanent = true)
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
        assertTrue(fixture.store.load().getValue("rejected-token").permanentlyRejected)

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
    fun recoveredAbsenceFromPlayRevokesAnUnsyncedManagedOptimisticOverlay() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("refunded-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        fixture.service.recover()

        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.close()
    }

    @Test
    fun mixedPendingAndVerifiedUpdatePublishesInReservationOrder() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
    fun failedDurableRevocationStillNarrowsTheProjectionAndReportsFailure() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
        fixture.store.failEvidenceUpserts = true

        val result = fixture.service.restorePurchases()

        assertTrue(result is RestoreResult.Failed)
        assertFalse(fixture.store.load().getValue("failed-revocation").revoked)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        fixture.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        runCurrent()
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertEquals(
            null,
            fixture.service.useFeatureWithPendingPurchase(
                distinctId = owner,
                featureId = "pro",
                amount = 1.0,
                entityId = null,
                metadata = null,
            ),
        )
        fixture.close()
    }

    @Test
    fun cancelledRevocationCannotLeaveAnOlderRefreshVisible() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
                null,
            )
        }
        runCurrent()
        fixture.service.onPurchasesUpdated(okUpdate(playPurchase("revoked-token").forCheckout(fixture)))
        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        val staleRefreshStartedPublishing = CompletableDeferred<Unit>()
        val releaseStaleRefresh = CompletableDeferred<Unit>()
        fixture.core.featureInfo.onFeatureChange = { featureId, _, _, _ ->
            if (featureId == "refresh-blocker") {
                staleRefreshStartedPublishing.complete(Unit)
                releaseStaleRefresh.await()
            }
        }
        // Purchase-time pinning means a late descriptor cannot introduce a new
        // Feature on retained evidence, so the stale refresh is modeled as an
        // older projection application parked mid-publication; the revocation
        // staged behind it must still win once the chain drains.
        val staleRefresh = launch {
            fixture.core.features.applyOptimisticPurchaseProjection(
                fixture.core.identity.distinctId(),
                mapOf(
                    "pro" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null),
                    "refresh-blocker" to OptimisticFeatureOverlay(FeatureType.BOOLEAN, false, null),
                ),
            )
        }
        runCurrent()
        staleRefreshStartedPublishing.await()

        val restore = async { fixture.service.restorePurchases() }
        runCurrent()
        restore.cancel()
        releaseStaleRefresh.complete(Unit)
        staleRefresh.join()
        runCurrent()

        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertTrue(fixture.store.load().getValue("revoked-token").revoked)
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
            accepted(evidence.syncAttributionDistinctId, evidence)
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
        assertFalse(fixture.store.load().getValue("revoked-during-sync").revoked)
        finishSync.complete(Unit)
        purchaseUpdate.await()

        assertFalse(fixture.store.load().getValue("revoked-during-sync").revoked)
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
        assertFalse(fixture.store.load().getValue("purchase-during-restore-query").revoked)
        fixture.close()
    }

    @Test
    fun destinationProjectionInstallationOwnsTheRefreshMutexUntilInstallReturns() = runTest {
        val fixture = fixture(this)
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

        assertEquals(queryCountBeforeRestore, fixture.billing.queries.size)
        releaseInstall.countDown()
        installing.await()
        assertEquals(RestoreResult.NoPurchases, restore.await())
        assertEquals(queryCountBeforeRestore + 2, fixture.billing.queries.size)
        fixture.close()
    }

    @Test
    fun inlineDestinationSnapshotDuringProjectionPublicationDoesNotDeadlock() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
            accepted(it.syncAttributionDistinctId, it)
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
            accepted(it.syncAttributionDistinctId, it)
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
            accepted(it.syncAttributionDistinctId, it)
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
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(),
                null,
                expectedOwnerDistinctId = owner,
                outcomeCorrelation = CommerceOutcomeCorrelation("journey-effect", owner),
            )
        }
        runCurrent()
        val purchase = playPurchase("journey-correlated").forCheckout(fixture)

        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        assertEquals(PurchaseResult.Purchased, checkout.await())
        assertEquals(listOf("journey-effect"), fixture.purchaseCompletionEventIds)
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
            accepted(it.syncAttributionDistinctId, it)
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
            accepted(it.syncAttributionDistinctId, it)
        }
        unmatched.service.onPurchasesUpdated(
            okUpdate(playPurchase("unmatched", obfuscatedAccountId = accountHash("someone-else"))),
        )

        val unmatchedEvidence = unmatched.store.load().getValue("unmatched")
        assertEquals(unmatched.core.identity.distinctId(), unmatchedEvidence.syncAttributionDistinctId)
        assertTrue(unmatchedEvidence.synced)
        assertEquals(null, unmatchedEvidence.ownerDistinctId)

        val matching = fixture(this)
        matching.service.rememberProduct(
            product(allowances = listOf(FeatureAllowance("pro", FeatureType.BOOLEAN))),
        )
        matching.synchronizer = {
            assertTrue(matching.core.featureInfo.isAllowed("pro"))
            accepted(it.syncAttributionDistinctId, it)
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
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }

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
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }

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
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
                signatureVerified = true,
            ),
        )
        fixture.store.afterNextLoad = { snapshot ->
            fixture.store.replaceWithoutRecording(
                snapshot.getValue("stale-purchase-snapshot").copy(
                    synced = true,
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
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
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
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = false,
                permanentlyRejected = true,
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
    fun acceptedProjectionIsRevokedFromProvisionalOwnerWhenExactBindingAdoptsPurchase() = runTest {
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
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        fixture.store.upsertBinding(product().bindingFor(provenOwner))
        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        val adoptedEvidence = fixture.store.load().getValue("adopted-token")
        assertEquals(provisionalOwner, adoptedEvidence.syncAttributionDistinctId)
        assertEquals(provenOwner, adoptedEvidence.ownerDistinctId)
        assertFalse(fixture.core.featureInfo.isAllowed("pro"))
        assertFalse(fixture.core.featureInfo.isAllowed("unlimited"))

        fixture.core.identity.setDistinctId(provenOwner)
        fixture.core.featureInfo.publish(
            fixture.core.features.handleUserChange(provisionalOwner, provenOwner),
        )
        fixture.service.onPurchasesUpdated(okUpdate(purchase))

        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        assertTrue(fixture.core.featureInfo.isAllowed("unlimited"))
        fixture.close()
    }

    @Test
    fun acceptanceLandingAfterExactBindingAdoptionPublishesOnlyToProvenOwner() = runTest {
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
        assertEquals(provisionalOwner, adoptedEvidence.syncAttributionDistinctId)
        assertEquals(provenOwner, adoptedEvidence.ownerDistinctId)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))
        assertTrue(fixture.core.featureInfo.isAllowed("unlimited"))
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
                syncAttributionDistinctId = fixture.core.identity.distinctId(),
                acknowledged = false,
                firstSeenMillis = 1L,
            ),
        )
        fixture.synchronizer = {
            assertEquals(fixture.core.identity.distinctId(), it.syncAttributionDistinctId)
            assertFalse(fixture.core.featureInfo.isAllowed("pro"))
            accepted(it.syncAttributionDistinctId, it)
        }

        fixture.service.recover()

        val recovered = fixture.store.load().getValue("legacy-blank-owner")
        assertTrue(recovered.synced)
        assertEquals(fixture.core.identity.distinctId(), recovered.syncAttributionDistinctId)
        assertEquals(null, recovered.ownerDistinctId)
        fixture.close()
    }

    @Test
    fun recoveryDoesNotProjectEvidenceWhoseRequiredSignatureWasNeverVerified() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "unverified-token",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
                signatureVerificationRequired = true,
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
    fun legacyEvidenceUsesStoredCatalogKeyToRequireVerificationDuringRecovery() = runTest {
        val fixture = fixture(this)
        fixture.service.rememberProduct(product(licensingPublicKey = "configured-key"))
        fixture.store.upsert(
            PurchaseEvidence(
                purchaseToken = "legacy-unverified",
                packageName = "com.example.app",
                storeProductIds = listOf("play-pro"),
                purchaseState = StoredPurchaseState.PURCHASED,
                syncAttributionDistinctId = fixture.core.identity.distinctId(),
                ownerDistinctId = fixture.core.identity.distinctId(),
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
            ),
        )
        fixture.billing.failQueries = true
        fixture.synchronizer = {
            assertFalse(fixture.core.featureInfo.isAllowed("pro"))
            accepted(it.syncAttributionDistinctId, it)
        }

        fixture.service.recover()

        assertTrue(fixture.store.load().getValue("legacy-unverified").synced)
        fixture.close()
    }

    @Test
    fun recoveryRepublishesTheProjectionAfterNormalizingLegacyEvidence() = runTest {
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
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
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
        runCurrent()
        assertTrue(
            "legacy evidence must project before normalization demands verification",
            fixture.core.featureInfo.isAllowed("pro"),
        )
        // Play is unreachable, so recovery can neither verify the signature
        // nor revoke the token as missing; only the normalization republish
        // can retract the projected legacy grant.
        fixture.billing.failQueries = true
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }

        fixture.service.recover()

        assertTrue(
            fixture.store.load().getValue("legacy-projected").signatureVerificationRequired,
        )
        assertFalse(
            "normalized-unverified evidence must not stay projected",
            fixture.core.featureInfo.isAllowed("pro"),
        )
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
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = false,
                synced = true,
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
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = true,
                synced = true,
                firstSeenMillis = 1L,
                catalogResolved = true,
                syncedEventEmitted = true,
                signatureVerificationRequired = true,
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
    fun explicitVerificationFailureIsPermanentButAmbiguousFailureIsTransient() {
        val permanent = Json.parseToJsonElement(
            """{"success":false,"error":"verification failed"}""",
        ).jsonObject
        val ambiguous = Json.parseToJsonElement(
            """{"success":false,"reason":"try_again"}""",
        ).jsonObject

        assertTrue(isPermanentPurchaseRejection(permanent))
        assertFalse(isPermanentPurchaseRejection(ambiguous))
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
            accepted(it.syncAttributionDistinctId, it)
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
            logWarning = { message, _ -> warnings += message },
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
            accepted(it.syncAttributionDistinctId, it)
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
            accepted(it.syncAttributionDistinctId, it)
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
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
        assertFalse(afterRestore.revoked)
        assertFalse(afterRestore.synced)
        assertTrue(fixture.core.featureInfo.isAllowed("pro"))

        fixture.synchronizer = { accepted(it.syncAttributionDistinctId, it) }
        fixture.service.recover()

        val afterRecovery = fixture.store.load().getValue("app-managed-consumable")
        assertFalse(afterRecovery.revoked)
        assertTrue(afterRecovery.synced)
        fixture.close()
    }

    @Test
    fun storedEvidenceRehydratesProjectionAfterRestartAndCompletesInterruptedSync() = runTest {
        val shared = RecordingEvidenceStore()
        val first = fixture(this, store = shared)
        first.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
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
            accepted(it.syncAttributionDistinctId, it)
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
    fun authenticatedRestoreWaitsForBackendVerification() = runTest {
        val fixture = fixture(this)
        val owner = fixture.core.identity.distinctId()
        fixture.service.rememberProduct(product())
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase("deferred-authenticated-restore", obfuscatedAccountId = accountHash(owner)),
        )
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }

        val result = fixture.service.restorePurchases(expectedOwnerDistinctId = owner)

        assertTrue(result is RestoreResult.Failed)
        assertTrue(
            (result as RestoreResult.Failed).cause.message.orEmpty()
                .contains("waiting for purchase verification"),
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
            fixture.store.load().getValue("other-customer").syncAttributionDistinctId,
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
        logWarning: (String, Throwable) -> Unit = { _, _ -> },
        journeyEvents: MutableList<StoredEvent>? = null,
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
            synchronizer = PurchaseSynchronizer { fixture.synchronizer(it) },
            features = core.features,
            distinctId = { core.identity.distinctId() },
            emit = { name, properties ->
                actions += name
                emissions += name to properties
            },
            settings = settings,
            scope = scope.backgroundScope,
            initialRetryDelayMillis = initialRetryDelayMillis,
            maxRetryDelayMillis = maxRetryDelayMillis,
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
            logWarning = logWarning,
        )
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
        fun close() = core.stop()
    }

    private class RecordingEvidenceStore : PurchaseEvidenceStore {
        private val entries = linkedMapOf<String, PurchaseEvidence>()
        private val bindings =
            linkedMapOf<Pair<String, StoredProductIdentity>, StoredPurchaseBinding>()
        private val mappings = linkedMapOf<StoredProductIdentity, StoredProductMapping>()
        var actions: MutableList<String> = mutableListOf()
        var failEvidenceUpserts = false
        var afterNextLoad: ((Map<String, PurchaseEvidence>) -> Unit)? = null
        var afterNextMappingsLoad: ((List<StoredProductMapping>) -> Unit)? = null
        override fun load(): Map<String, PurchaseEvidence> {
            val snapshot = entries.toMap()
            val callback = afterNextLoad
            afterNextLoad = null
            callback?.invoke(snapshot)
            return snapshot
        }
        override fun upsert(evidence: PurchaseEvidence): Boolean {
            actions += "persist"
            if (failEvidenceUpserts) return false
            entries[evidence.purchaseToken] = evidence
            return true
        }
        fun replaceWithoutRecording(evidence: PurchaseEvidence) {
            entries[evidence.purchaseToken] = evidence
        }
        override fun loadBindings(): List<StoredPurchaseBinding> = bindings.values.toList()
        override fun upsertBinding(binding: StoredPurchaseBinding): Boolean {
            bindings[binding.obfuscatedAccountId to binding.productIdentity] = binding
            return true
        }
        override fun loadProductMappings(): List<StoredProductMapping> {
            val snapshot = mappings.values.toList()
            val callback = afterNextMappingsLoad
            afterNextMappingsLoad = null
            callback?.invoke(snapshot)
            return snapshot
        }
        override fun upsertProductMapping(mapping: StoredProductMapping): Boolean {
            mappings[mapping.productIdentity] = mapping
            return true
        }
    }

    private class FakeBilling(private val actions: MutableList<String>) : PlayBillingGateway {
        val active = mutableMapOf<String, List<PlayPurchase>>()
        val queries = mutableListOf<String>()
        var launched: CheckoutRequest? = null
        var launchCode = BillingClient.BillingResponseCode.OK
        val acknowledgeCodes = mutableListOf<Int>()
        var failQueries = false
        var queryStarted: CompletableDeferred<Unit>? = null
        var releaseQueries: CompletableDeferred<Unit>? = null

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult {
            launched = request
            return billingResult(launchCode)
        }

        override suspend fun queryActive(productType: String): ActivePurchasesResult {
            queries += productType
            queryStarted?.complete(Unit)
            releaseQueries?.await()
            if (failQueries) {
                return ActivePurchasesResult.Failed(
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                    "offline",
                )
            }
            return ActivePurchasesResult.Success(active[productType].orEmpty())
        }

        override suspend fun acknowledge(purchaseToken: String): BillingResult {
            actions += "ack"
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
