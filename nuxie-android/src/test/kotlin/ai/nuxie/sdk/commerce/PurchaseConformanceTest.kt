package ai.nuxie.sdk.commerce

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.testsupport.FakeTransport
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Drives the atomic purchase-sync fixture through the Android purchase
 * pipeline; reading and comparing the fixture shape alone proves nothing.
 * `post_use_access` and `ordinary_usage_fallback` describe the first-spend
 * gate deferred to UNIV-2649 and are intentionally not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseConformanceTest {
    @Test
    fun atomicPurchaseSyncFixtureDrivesRetryEvidenceAndEmissionSemantics() = runTest {
        val root = Json.parseToJsonElement(
            File(FixtureRunner.fixturesRoot(), "events/atomic-purchase-sync.json").readText(),
        ).jsonObject
        assertEquals("events/atomic-purchase-sync", root.getValue("suite").jsonPrimitive.content)
        assertEquals(2, root.getValue("version").jsonPrimitive.int)
        val expectedEvent = root.getValue("event").jsonObject
        val expectedEventName = expectedEvent.getValue("name").jsonPrimitive.content
        val expectedPropertyNames = expectedEvent.getValue("properties").jsonArray
            .map { it.jsonPrimitive.content }
        val retry = root.getValue("retry").jsonObject
        val acceptance = root.getValue("acceptance").jsonObject

        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_purchase_conformance",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = FakeTransport(),
                registerLifecycle = false,
            ),
        )
        val owner = core.identity.distinctId()
        val token = "fixture-purchase-token"
        val storeProductId = "fixture-play-product"
        val store = InMemoryPurchaseEvidenceStore()
        val purchase = PlayPurchase(
            purchaseToken = token,
            packageName = "com.example.fixture",
            products = listOf(storeProductId),
            state = StoredPurchaseState.PURCHASED,
            acknowledged = false,
            obfuscatedAccountId = accountHash(owner),
            originalJson = "{}",
            signature = "",
        )
        val billing = FixtureBilling(purchase)
        val syncAttempts = mutableListOf<String>()
        val emissions = mutableListOf<Pair<String, Map<String, Any?>>>()
        var evidenceAtCapture: PurchaseEvidence? = null
        var completionsAtCapture: Int? = null
        val service = PurchaseService(
            billing = billing,
            evidenceStore = store,
            synchronizer = PurchaseSynchronizer { evidence ->
                syncAttempts += evidence.purchaseToken
                if (syncAttempts.size == 1) {
                    PurchaseSyncOutcome.Rejected(permanent = false)
                } else {
                    PurchaseSyncOutcome.Accepted(
                        NuxieApi.PurchaseResponse(
                            body = Json.parseToJsonElement(
                                """{"success":true,"customer_id":"server-customer","features":[]}""",
                            ).jsonObject,
                            success = true,
                            customerId = "server-customer",
                        ),
                    )
                }
            },
            features = core.features,
            distinctId = core.identity::distinctId,
            emit = { name, properties ->
                if (name == expectedEventName) {
                    evidenceAtCapture = store.load()[token]
                    completionsAtCapture = billing.managedCompletions
                    emissions += name to properties
                }
            },
            settings = PurchaseSettings(null, PurchaseHandlingMode.NUXIE_MANAGED),
            scope = backgroundScope,
            initialRetryDelayMillis = 10,
            maxRetryDelayMillis = 10,
        )
        service.rememberProduct(
            StoreProduct(
                productId = "fixture-product",
                storeProductId = storeProductId,
                basePlanId = null,
                offerId = null,
                placementId = null,
                rawProduct = null,
                offerToken = null,
                isOfferPersonalized = false,
                productType = BillingClient.ProductType.INAPP,
                consumable = false,
            ),
        )

        service.onPurchasesUpdated(okUpdate(purchase))

        if (!retry.getValue("emit_on_failure").jsonPrimitive.boolean) {
            assertTrue(emissions.isEmpty())
        }
        if (retry.getValue("retain_evidence_on_failure").jsonPrimitive.boolean) {
            assertFalse(store.load().getValue(token).synced)
        }

        advanceTimeBy(10)
        runCurrent()

        val acceptedEvidence = store.load().getValue(token)
        assertEquals(
            retry.getValue("request_identity").jsonPrimitive.content,
            "stable_purchase_use_event_id",
        )
        assertEquals(listOf(token, token), syncAttempts)
        assertEquals("decoded_2xx", acceptance.getValue("boundary").jsonPrimitive.content)
        assertEquals(acceptance.getValue("command_success").jsonPrimitive.boolean, acceptedEvidence.synced)
        assertEquals(
            acceptance.getValue("emissions_per_accepted_receipt").jsonPrimitive.int,
            emissions.size,
        )
        val emission = emissions.single()
        assertEquals(expectedEventName, emission.first)
        assertEquals(expectedPropertyNames.toSet(), emission.second.keys)
        assertEquals(
            mapOf(
                "customer_id" to "server-customer",
                "original_transaction_id" to token,
                "product_id" to storeProductId,
                "transaction_id" to token,
            ),
            emission.second,
        )
        if (acceptance.getValue("capture_before_retiring_evidence").jsonPrimitive.boolean) {
            assertNotNull(evidenceAtCapture)
            assertTrue(evidenceAtCapture!!.synced)
            assertFalse(evidenceAtCapture!!.syncedEventEmitted)
            assertEquals(0, completionsAtCapture)
        }

        service.recover()

        assertEquals(acceptance.getValue("emissions_per_accepted_receipt").jsonPrimitive.int, emissions.size)
        core.stop()
    }

    private class FixtureBilling(private val purchase: PlayPurchase) : PlayBillingGateway {
        var managedCompletions = 0

        override suspend fun launch(activity: Activity, request: CheckoutRequest): BillingResult =
            billingResult(BillingClient.BillingResponseCode.OK)

        override suspend fun queryActive(productType: String): ActivePurchasesResult =
            ActivePurchasesResult.Success(
                if (productType == BillingClient.ProductType.INAPP) listOf(purchase) else emptyList(),
            )

        override suspend fun acknowledge(purchaseToken: String): BillingResult {
            managedCompletions += 1
            return billingResult(BillingClient.BillingResponseCode.OK)
        }

        override suspend fun consume(purchaseToken: String): BillingResult {
            managedCompletions += 1
            return billingResult(BillingClient.BillingResponseCode.OK)
        }

        private fun billingResult(code: Int): BillingResult = BillingResult.newBuilder()
            .setResponseCode(code)
            .setDebugMessage("fixture")
            .build()
    }

    private fun okUpdate(purchase: PlayPurchase) = PurchaseUpdate(
        billingResult(BillingClient.BillingResponseCode.OK),
        listOf(purchase),
    )

    private fun billingResult(code: Int): BillingResult = BillingResult.newBuilder()
        .setResponseCode(code)
        .setDebugMessage("fixture")
        .build()

    private fun accountHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
