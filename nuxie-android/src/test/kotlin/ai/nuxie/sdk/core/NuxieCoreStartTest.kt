package ai.nuxie.sdk.core

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.billing.InMemoryPurchaseEvidenceStore
import ai.nuxie.sdk.billing.PurchaseEvidence
import ai.nuxie.sdk.billing.PurchaseSend
import ai.nuxie.sdk.billing.PurchaseSyncReason
import ai.nuxie.sdk.billing.StoredPurchaseState
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.identity.CustomerKind
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import ai.nuxie.sdk.testsupport.canonicalJourneyProfileResponse
import com.android.billingclient.api.BillingClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieCoreStartTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun anEntryDueAtLaunchReportsItsSyncToJourneys() = runBlocking {
        val journeyEvents = CopyOnWriteArrayList<StoredEvent>()
        val syncedRouted = CompletableDeferred<Unit>()
        val evidenceStore = InMemoryPurchaseEvidenceStore()
        val transport = FakeTransport()
        val storage = temporaryFolder.newFolder("core")
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_start_order",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = transport,
                registerLifecycle = false,
                purchaseEvidenceStore = evidenceStore,
                eventDatabaseFile = File(storage, "events.db"),
                profileCacheDirectory = File(storage, "profiles"),
                requestInitialProfileRefresh = false,
                billingClientFactory = InertBillingClientAdapter.factory,
                journeyEventObserver = {
                    journeyEvents += it
                    if (it.name == SystemEventNames.PURCHASE_SYNCED) syncedRouted.complete(Unit)
                },
            ),
        )
        try {
            val customer = core.identity.distinctId()
            // The server accepts the purchase at once.
            transport.respond = { request ->
                when (request.url.path) {
                    "/purchase" -> HttpTransport.Response(
                        200,
                        """{"success":true,"customer_id":"$customer","features":[],"catalog_product":{"id":"nuxie-pro","store_product_id":"play-pro","store_product_type":"nonConsumable"}}"""
                            .encodeToByteArray(),
                    )
                    "/profile" -> canonicalJourneyProfileResponse()
                    else -> HttpTransport.Response(200, ByteArray(0))
                }
            }
            // An entry stored by an earlier launch, overdue now.
            evidenceStore.upsert(
                PurchaseEvidence(
                    purchaseToken = "launch-token",
                    packageName = "com.example.app",
                    storeProductIds = listOf("play-pro"),
                    nuxieProductId = "nuxie-pro",
                    productType = BillingClient.ProductType.INAPP,
                    purchaseState = StoredPurchaseState.PURCHASED,
                    obfuscatedAccountId = accountHash(customer),
                    buyerDistinctId = customer,
                    nuxieManaged = true,
                    acknowledged = true,
                    pendingSend = PurchaseSend(PurchaseSyncReason.PURCHASE, customer, CustomerKind.ANONYMOUS),
                    syncAttempts = 1,
                    retryDueAtMillis = 1L,
                    firstSeenMillis = 1L,
                    catalogResolved = true,
                    completionEmitted = true,
                    signatureVerified = true,
                    authorityScope = "test-fixture",
                ),
            )

            core.start()

            withTimeout(10_000) {
                while (evidenceStore.load().getValue("launch-token").syncedEventEmitted.not()) delay(10)
            }
            withTimeout(10_000) { syncedRouted.await() }
            core.eventLog.awaitBarrier()
            // The event log may offer one event again with a fresher admission;
            // what matters is that this event reached Journey routing.
            assertEquals(
                "Journey routing receives the launch send's purchase_synced",
                listOf(JsonPrimitive("launch-token")),
                journeyEvents
                    .filter { it.name == SystemEventNames.PURCHASE_SYNCED }
                    .distinctBy { it.id }
                    .map { it.properties["transaction_id"] },
            )
        } finally {
            core.stopAndAwait()
        }
    }

    private fun accountHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
