package ai.nuxie.sdk.commerce

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.features.LocalPurchaseGrant
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.testsupport.FakeTransport
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseServiceTest {
    @Test
    fun purchasedPersistsAndGrantsBeforeSyncThenAcknowledgesAfterAcceptance() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val product = product(
            grants = listOf(
                LocalPurchaseGrant("pro", FeatureType.BOOLEAN),
                LocalPurchaseGrant("exports", FeatureType.METERED),
                LocalPurchaseGrant("unlimited", FeatureType.METERED, unlimited = true),
                LocalPurchaseGrant("credits", FeatureType.CREDIT_SYSTEM),
            ),
        )
        fixture.synchronizer = { evidence ->
            actions += "sync"
            assertTrue(fixture.core.featureInfo.isAllowed("pro"))
            assertTrue(fixture.core.featureInfo.isAllowed("unlimited"))
            assertFalse(fixture.core.featureInfo.isAllowed("exports"))
            assertFalse(fixture.core.featureInfo.isAllowed("credits"))
            accepted(evidence.syncAttributionDistinctId)
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
    fun pendingPersistsButDoesNotGrantSyncOrAcknowledge() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        val purchase = async {
            fixture.service.purchase(
                activity(),
                product(grants = listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN))),
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
                product(grants = listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN))),
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
        assertFalse(restarted.core.featureInfo.isAllowed("pro"))
        restarted.close()
    }

    @Test
    fun recoveredAbsenceFromPlayRevokesAnUnsyncedManagedOptimisticGrant() = runTest {
        val fixture = fixture(this)
        fixture.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
        val checkout = async {
            fixture.service.purchase(
                activity(),
                product(grants = listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN))),
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
    fun unsolicitedUpdateRunsPipelineAndAppManagedNeverCompletesPlayPurchase() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, mode = PurchaseHandlingMode.APP_MANAGED, actions = actions)
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
        assertFalse(ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED in actions)
        fixture.close()
    }

    @Test
    fun unsolicitedTokenWithoutBindingSyncsButGrantsOnlyForAMatchingCustomer() = runTest {
        val unmatched = fixture(this)
        unmatched.service.rememberProduct(
            product(grants = listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN))),
        )
        unmatched.synchronizer = {
            assertFalse(unmatched.core.featureInfo.isAllowed("pro"))
            accepted(it.syncAttributionDistinctId)
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
            product(grants = listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN))),
        )
        matching.synchronizer = {
            assertTrue(matching.core.featureInfo.isAllowed("pro"))
            accepted(it.syncAttributionDistinctId)
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
    fun acceptedProjectionIsRevokedFromProvisionalOwnerWhenExactBindingAdoptsPurchase() = runTest {
        val fixture = fixture(this)
        val provisionalOwner = fixture.core.identity.distinctId()
        val provenOwner = "customer-b"
        val purchase = playPurchase(
            "adopted-token",
            obfuscatedAccountId = accountHash(provenOwner),
        )
        fixture.service.rememberProduct(
            product(grants = listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN))),
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
        fixture.core.features.handleUserChange(provisionalOwner, provenOwner)
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
            product(grants = listOf(LocalPurchaseGrant("pro", FeatureType.BOOLEAN))),
        )
        val syncStarted = CountDownLatch(1)
        val releaseAcceptance = CountDownLatch(1)
        fixture.synchronizer = {
            syncStarted.countDown()
            assertTrue(releaseAcceptance.await(5, TimeUnit.SECONDS))
            accepted(provenOwner)
        }

        val update = async(Dispatchers.Default) {
            fixture.service.onPurchasesUpdated(okUpdate(purchase))
        }
        assertTrue(syncStarted.await(5, TimeUnit.SECONDS))
        fixture.store.upsertBinding(product().bindingFor(provenOwner))
        fixture.core.identity.setDistinctId(provenOwner)
        fixture.core.features.handleUserChange(provisionalOwner, provenOwner)
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
    fun recoverySyncsProvisionallyAttributedEvidenceWithoutOptimisticGrant() = runTest {
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
                localFeatureGrants = listOf(
                    StoredLocalPurchaseGrant("pro", FeatureType.BOOLEAN.name, unlimited = false),
                ),
            ),
        )
        fixture.synchronizer = {
            assertEquals(fixture.core.identity.distinctId(), it.syncAttributionDistinctId)
            assertFalse(fixture.core.featureInfo.isAllowed("pro"))
            accepted(it.syncAttributionDistinctId)
        }

        fixture.service.recover()

        val recovered = fixture.store.load().getValue("legacy-blank-owner")
        assertTrue(recovered.synced)
        assertEquals(fixture.core.identity.distinctId(), recovered.syncAttributionDistinctId)
        assertEquals(null, recovered.ownerDistinctId)
        fixture.close()
    }

    @Test
    fun recoveryDoesNotGrantEvidenceWhoseRequiredSignatureWasNeverVerified() = runTest {
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
                localFeatureGrants = listOf(
                    StoredLocalPurchaseGrant("pro", FeatureType.BOOLEAN.name, unlimited = false),
                ),
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
                localFeatureGrants = listOf(
                    StoredLocalPurchaseGrant("pro", FeatureType.BOOLEAN.name, unlimited = false),
                ),
                catalogResolved = true,
            ),
        )
        fixture.billing.failQueries = true
        fixture.synchronizer = {
            assertFalse(fixture.core.featureInfo.isAllowed("pro"))
            accepted(it.syncAttributionDistinctId)
        }

        fixture.service.recover()

        assertTrue(fixture.store.load().getValue("legacy-unverified").synced)
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
        val fixture = fixture(this, actions = actions)
        fixture.settings.delegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct): PurchaseResult = PurchaseResult.Purchased
            override suspend fun restorePurchases(): RestoreResult = RestoreResult.NoPurchases
        }

        assertEquals(PurchaseResult.Purchased, fixture.service.purchase(activity(), product(), null))
        assertEquals(
            1,
            actions.count { it == ai.nuxie.sdk.events.SystemEventNames.PURCHASE_COMPLETED },
        )
        assertEquals(null, fixture.billing.launched)
        fixture.close()
    }

    @Test
    fun catalogConsumableConsumesOnlyAfterServerAcceptance() = runTest {
        val actions = mutableListOf<String>()
        val fixture = fixture(this, actions = actions)
        fixture.synchronizer = {
            actions += "sync"
            accepted(it.syncAttributionDistinctId)
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
    fun storedEvidenceSurvivesRestartAndCompletesInterruptedSync() = runTest {
        val shared = RecordingEvidenceStore()
        val first = fixture(this, store = shared)
        first.synchronizer = { PurchaseSyncOutcome.Rejected(permanent = false) }
        val purchase = async { first.service.purchase(activity(), product(), null) }
        runCurrent()
        first.service.onPurchasesUpdated(okUpdate(playPurchase("restart-token").forCheckout(first)))
        assertEquals(PurchaseResult.Purchased, purchase.await())
        assertFalse(shared.load().getValue("restart-token").synced)

        val actions = mutableListOf<String>()
        val second = fixture(this, store = shared, actions = actions)
        second.service.recover()

        assertTrue(shared.load().getValue("restart-token").synced)
        assertTrue(shared.load().getValue("restart-token").acknowledged)
        assertTrue("ack" in actions)
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
        val fixture = fixture(this)
        assertEquals(RestoreResult.NoPurchases, fixture.service.restorePurchases())

        fixture.store.upsertBinding(product().bindingFor(fixture.core.identity.distinctId()))
        fixture.billing.active[BillingClient.ProductType.INAPP] = listOf(
            playPurchase(
                "restore-token",
                obfuscatedAccountId = accountHash(fixture.core.identity.distinctId()),
            ),
        )
        assertEquals(RestoreResult.Restored, fixture.service.restorePurchases())
        assertTrue(fixture.store.load().containsKey("restore-token"))
        assertEquals(
            listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).let { it + it },
            fixture.billing.queries,
        )
        fixture.close()
    }

    @Test
    fun unknownAccountPurchaseSyncsForCurrentCustomerWithoutOptimisticGrant() = runTest {
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
        initialRetryDelayMillis: Long = 60_000,
        maxRetryDelayMillis: Long = 60_000,
    ): Fixture {
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_purchase_${System.identityHashCode(store)}",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(transport = FakeTransport(), registerLifecycle = false),
        )
        store.actions = actions
        val billing = FakeBilling(actions)
        val settings = PurchaseSettings(null, mode)
        lateinit var fixture: Fixture
        val service = PurchaseService(
            billing = billing,
            evidenceStore = store,
            synchronizer = PurchaseSynchronizer { fixture.synchronizer(it) },
            features = core.features,
            distinctId = { core.identity.distinctId() },
            emit = { name, _ -> actions += name },
            settings = settings,
            scope = scope.backgroundScope,
            initialRetryDelayMillis = initialRetryDelayMillis,
            maxRetryDelayMillis = maxRetryDelayMillis,
        )
        fixture = Fixture(core, billing, store, service, settings) { accepted(core.identity.distinctId()) }
        return fixture
    }

    private class Fixture(
        val core: NuxieCore,
        val billing: FakeBilling,
        val store: RecordingEvidenceStore,
        val service: PurchaseService,
        val settings: PurchaseSettings,
        var synchronizer: (PurchaseEvidence) -> PurchaseSyncOutcome,
    ) {
        fun close() = core.stop()
    }

    private class RecordingEvidenceStore : PurchaseEvidenceStore {
        private val entries = linkedMapOf<String, PurchaseEvidence>()
        private val bindings = linkedMapOf<String, StoredPurchaseBinding>()
        private val mappings = linkedMapOf<String, StoredProductMapping>()
        var actions: MutableList<String> = mutableListOf()
        override fun load(): Map<String, PurchaseEvidence> = entries.toMap()
        override fun upsert(evidence: PurchaseEvidence): Boolean {
            actions += "persist"
            entries[evidence.purchaseToken] = evidence
            return true
        }
        override fun loadBindings(): List<StoredPurchaseBinding> = bindings.values.toList()
        override fun upsertBinding(binding: StoredPurchaseBinding): Boolean {
            bindings["${binding.obfuscatedAccountId}:${binding.storeProductId}"] = binding
            return true
        }
        override fun loadProductMappings(): List<StoredProductMapping> = mappings.values.toList()
        override fun upsertProductMapping(mapping: StoredProductMapping): Boolean {
            mappings[mapping.storeProductId] = mapping
            return true
        }
    }

    private class FakeBilling(private val actions: MutableList<String>) : PlayBillingGateway {
        val active = mutableMapOf<String, List<PlayPurchase>>()
        val queries = mutableListOf<String>()
        var launched: CheckoutRequest? = null
        val acknowledgeCodes = mutableListOf<Int>()
        var failQueries = false

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult {
            launched = request
            return billingResult(BillingClient.BillingResponseCode.OK)
        }

        override suspend fun queryActive(productType: String): ActivePurchasesResult {
            queries += productType
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
        consumable: Boolean = false,
        grants: List<LocalPurchaseGrant> = emptyList(),
        licensingPublicKey: String? = null,
    ) = StoreProduct(
        productId = "nuxie-pro",
        storeProductId = "play-pro",
        basePlanId = if (subscription) "annual" else null,
        offerId = if (subscription) "launch" else null,
        placementId = "primary",
        rawProduct = null,
        offerToken = "offer-token",
        isOfferPersonalized = true,
        productType = if (subscription) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP,
        consumable = consumable,
        localFeatureGrants = grants,
        licensingPublicKey = licensingPublicKey,
        purchaseContext = PurchaseContext("experience-1", "v1"),
    )

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

    private fun accepted(customerId: String) = PurchaseSyncOutcome.Accepted(
        NuxieApi.PurchaseResponse(
            Json.parseToJsonElement(
                """{"success":true,"customer_id":"$customerId","features":[{"id":"pro","type":"boolean","allowed":true,"unlimited":false},{"id":"unlimited","type":"metered","allowed":true,"unlimited":true}]}""",
            ).jsonObject,
            true,
            customerId,
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
        localFeatureGrants = localFeatureGrants.map {
            StoredLocalPurchaseGrant(it.featureId, it.type.name, it.unlimited)
        },
        licensingPublicKey = licensingPublicKey,
        nuxieManaged = nuxieManaged,
    )

    private fun accountHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
