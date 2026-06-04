package io.nuxie.sdk.purchases

/**
 * Android purchase delegation.
 *
 * iOS uses StoreKit and passes StoreKit product objects through a delegate. On Android,
 * we start with a product-id-first contract so apps (or wrappers) can integrate their
 * preferred purchase stack (Play Billing, RevenueCat, etc) without the SDK forcing a
 * billing implementation before the backend supports Play purchase sync.
 */
interface NuxiePurchaseDelegate {
  suspend fun purchase(productId: String): PurchaseResult

  suspend fun purchaseOutcome(productId: String): PurchaseOutcome {
    val result = purchase(productId)
    return PurchaseOutcome(result = result, productId = productId)
  }

  suspend fun restore(): RestoreResult
}

sealed class PurchaseResult {
  data object Success : PurchaseResult()
  data object Cancelled : PurchaseResult()
  data object Pending : PurchaseResult()
  data class Failed(val message: String) : PurchaseResult()
}

data class PurchaseOutcome(
  val result: PurchaseResult,
  val productId: String? = null,
  /**
   * Optional Play Store purchase metadata. When present on a successful outcome,
   * the SDK syncs the purchase token with Nuxie before confirming the flow purchase.
   */
  val playStorePurchase: PlayStorePurchase? = null,
  val purchaseToken: String? = null,
  val orderId: String? = null,
)

enum class PlayStorePurchaseState {
  UNSPECIFIED,
  PURCHASED,
  PENDING,
}

@kotlinx.serialization.Serializable
enum class PlayStoreProductType {
  @kotlinx.serialization.SerialName("subscription")
  SUBSCRIPTION,

  @kotlinx.serialization.SerialName("one_time")
  ONE_TIME,
}

data class PlayStorePurchase(
  val purchaseToken: String,
  val productIds: List<String> = emptyList(),
  val packageName: String? = null,
  val basePlanId: String? = null,
  val productType: PlayStoreProductType? = null,
  val consumePurchase: Boolean = false,
  val orderId: String? = null,
  val purchaseState: PlayStorePurchaseState = PlayStorePurchaseState.PURCHASED,
  val distinctId: String? = null,
) {
  val productId: String?
    get() = productIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct().singleOrNull()
}

sealed class RestoreResult {
  data class Success(val restoredCount: Int) : RestoreResult()
  data object NoPurchases : RestoreResult()
  data class Failed(val message: String) : RestoreResult()
}
