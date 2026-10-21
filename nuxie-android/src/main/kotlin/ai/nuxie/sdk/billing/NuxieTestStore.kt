package ai.nuxie.sdk.billing

import ai.nuxie.sdk.network.NuxieApi
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

internal enum class TestStorePurchaseChoice { PURCHASED, PENDING, CANCELLED, FAILED }
internal enum class TestStoreRestoreChoice { RESTORED, NO_PURCHASES, FAILED }

/** Android checkout UI supplies choices; the store never receives a billing gateway or delegate. */
internal interface TestStoreChoices {
    suspend fun purchase(product: StoreProduct): TestStorePurchaseChoice
    suspend fun purchase(product: StoreProduct, activity: android.app.Activity): TestStorePurchaseChoice = purchase(product)
    suspend fun restore(): TestStoreRestoreChoice
}

/** Asks the server to grant a Test Store purchase; answers with the `/purchase` body carrying access. */
internal fun interface TestStoreGranting {
    suspend fun grant(catalogProductId: String, transactionId: String, distinctId: String): JsonObject
}

internal data class TestStorePurchaseResponse(
    val result: PurchaseResult,
    val transactionId: String? = null,
    /** The server's `/purchase` answer for a granted purchase. */
    val access: JsonObject? = null,
)

internal data class TestStoreRestoreResponse(
    val result: RestoreResult,
    val products: List<StoreProduct> = emptyList(),
)

/**
 * Customer-scoped purchases with no Play transaction or verification evidence.
 * A purchase asks the server to grant the Product's Features, as RevenueCat's
 * Test Store does; without that grant it fails.
 */
internal class NuxieTestStore(
    private val choices: TestStoreChoices,
    private val granting: TestStoreGranting,
) {
    private val mutex = Mutex()
    private val purchases = mutableMapOf<String, MutableMap<String, StoreProduct>>()

    /**
     * A grant whose outcome is unknown (the server may have committed it) keeps
     * its transaction ID, so the customer's next Purchased of the same Product
     * replays that grant instead of granting again.
     */
    private val unsettledTransactionIds = mutableMapOf<Pair<String, String>, String>()

    suspend fun purchase(product: StoreProduct, distinctId: String, activity: android.app.Activity? = null): TestStorePurchaseResponse {
        // Do not hold storage ownership while waiting for a human choice. The
        // initiating customer is retained even if the SDK identity changes.
        return when (if (activity == null) choices.purchase(product) else choices.purchase(product, activity)) {
            TestStorePurchaseChoice.PURCHASED -> {
                val attempt = distinctId to product.productId
                val transactionId = mutex.withLock {
                    unsettledTransactionIds.getOrPut(attempt) { "nuxie-test-${UUID.randomUUID()}" }
                }
                val access = try {
                    granting.grant(product.productId, transactionId, distinctId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (failure is NuxieApi.TestStoreGrantRefusedException) {
                        mutex.withLock { unsettledTransactionIds.remove(attempt) }
                    }
                    return TestStorePurchaseResponse(PurchaseResult.Failed(failure))
                }
                mutex.withLock {
                    unsettledTransactionIds.remove(attempt)
                    purchases.getOrPut(distinctId) { linkedMapOf() }[product.productId] = product
                }
                TestStorePurchaseResponse(PurchaseResult.Purchased, transactionId, access)
            }
            TestStorePurchaseChoice.PENDING -> TestStorePurchaseResponse(PurchaseResult.Pending)
            TestStorePurchaseChoice.CANCELLED -> TestStorePurchaseResponse(PurchaseResult.Cancelled)
            TestStorePurchaseChoice.FAILED -> TestStorePurchaseResponse(
                PurchaseResult.Failed(IllegalStateException("Simulated Test Store purchase failure.")),
            )
        }
    }

    suspend fun restorePurchases(distinctId: String): TestStoreRestoreResponse = when (choices.restore()) {
        // Like iOS, choosing Restored is explicit success even without history.
        TestStoreRestoreChoice.RESTORED -> TestStoreRestoreResponse(
            RestoreResult.Restored,
            mutex.withLock { purchases[distinctId]?.values?.toList().orEmpty() },
        )
        TestStoreRestoreChoice.NO_PURCHASES -> TestStoreRestoreResponse(RestoreResult.NoPurchases)
        TestStoreRestoreChoice.FAILED -> TestStoreRestoreResponse(
            RestoreResult.Failed(IllegalStateException("Simulated Test Store restore failure.")),
        )
    }
}
