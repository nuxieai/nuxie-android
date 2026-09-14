package ai.nuxie.sdk.billing

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class TestStorePurchaseChoice { PURCHASED, PENDING, CANCELLED, FAILED }
internal enum class TestStoreRestoreChoice { RESTORED, NO_PURCHASES, FAILED }

/** Android checkout UI supplies choices; the store never receives a billing gateway or delegate. */
internal interface TestStoreChoices {
    suspend fun purchase(product: StoreProduct): TestStorePurchaseChoice
    suspend fun purchase(product: StoreProduct, activity: android.app.Activity): TestStorePurchaseChoice = purchase(product)
    suspend fun restore(): TestStoreRestoreChoice
}

internal data class TestStorePurchaseResponse(
    val result: PurchaseResult,
    val transactionId: String? = null,
)

internal data class TestStoreRestoreResponse(
    val result: RestoreResult,
    val products: List<StoreProduct> = emptyList(),
)

/** In-memory, customer-scoped purchases with no Play transaction or verification evidence. */
internal class NuxieTestStore(private val choices: TestStoreChoices) {
    private val mutex = Mutex()
    private val purchases = mutableMapOf<String, MutableMap<String, StoreProduct>>()

    suspend fun purchase(product: StoreProduct, distinctId: String, activity: android.app.Activity? = null): TestStorePurchaseResponse {
        // Do not hold storage ownership while waiting for a human choice. The
        // initiating customer is retained even if the SDK identity changes.
        return when (if (activity == null) choices.purchase(product) else choices.purchase(product, activity)) {
            TestStorePurchaseChoice.PURCHASED -> {
                mutex.withLock {
                    purchases.getOrPut(distinctId) { linkedMapOf() }[product.productId] = product
                }
                TestStorePurchaseResponse(PurchaseResult.Purchased, "nuxie-test-${UUID.randomUUID()}")
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
