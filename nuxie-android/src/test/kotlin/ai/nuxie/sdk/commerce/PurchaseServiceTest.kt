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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
            accepted(evidence.distinctId)
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
        val fixture = fixture(this)
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
        fixture.close()
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
            accepted(it.distinctId)
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
    fun unknownAccountPurchaseIsNotAttributedToTheCurrentCustomer() = runTest {
        val fixture = fixture(this)

        fixture.service.onPurchasesUpdated(
            okUpdate(playPurchase("other-customer", obfuscatedAccountId = accountHash("someone-else"))),
        )

        assertEquals("", fixture.store.load().getValue("other-customer").distinctId)
        fixture.close()
    }

    private fun fixture(
        scope: TestScope,
        mode: PurchaseHandlingMode = PurchaseHandlingMode.NUXIE_MANAGED,
        store: RecordingEvidenceStore = RecordingEvidenceStore(),
        actions: MutableList<String> = mutableListOf(),
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
            initialRetryDelayMillis = 60_000,
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
    }

    private class FakeBilling(private val actions: MutableList<String>) : PlayBillingGateway {
        val active = mutableMapOf<String, List<PlayPurchase>>()
        val queries = mutableListOf<String>()
        var launched: CheckoutRequest? = null

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult {
            launched = request
            return billingResult(BillingClient.BillingResponseCode.OK)
        }

        override suspend fun queryActive(productType: String): ActivePurchasesResult {
            queries += productType
            return ActivePurchasesResult.Success(active[productType].orEmpty())
        }

        override suspend fun acknowledge(purchaseToken: String): BillingResult {
            actions += "ack"
            return billingResult(BillingClient.BillingResponseCode.OK)
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
