package io.nuxie.sdk.purchases

import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.PurchaseResponse
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.PlayStorePurchaseRequest

class PurchaseSyncService(
  private val api: NuxieApiProtocol,
  private val identityService: IdentityService,
  private val featureService: FeatureService,
  private val eventService: EventService? = null,
) {
  suspend fun syncPlayStorePurchase(purchase: PlayStorePurchase): PurchaseResponse {
    val token = purchase.purchaseToken.trim()
    if (token.isEmpty()) {
      return PurchaseResponse(success = false, error = "purchase_token_required")
    }

    if (purchase.purchaseState != PlayStorePurchaseState.PURCHASED) {
      NuxieLogger.info("Skipping Play Store purchase sync because purchase is ${purchase.purchaseState}")
      return PurchaseResponse(
        success = false,
        error = when (purchase.purchaseState) {
          PlayStorePurchaseState.PENDING -> "purchase_pending"
          PlayStorePurchaseState.UNSPECIFIED -> "purchase_state_unspecified"
          PlayStorePurchaseState.PURCHASED -> "purchase_not_purchased"
        },
      )
    }

    val productId = purchase.productId
    val distinctId = purchase.distinctId.trimToNull() ?: identityService.getDistinctId().trimToNull()
    val request = PlayStorePurchaseRequest(
      type = "playstore",
      purchaseToken = token,
      productId = productId,
      packageName = purchase.packageName.trimToNull(),
      basePlanId = purchase.basePlanId.trimToNull(),
      distinctId = distinctId,
      productType = purchase.productType,
      consumePurchase = purchase.consumePurchase.takeIf { it },
    )

    val response = api.syncPlayStorePurchase(request)
    if (!response.success) {
      NuxieLogger.warning("Play Store purchase sync failed: ${response.error ?: "unknown_error"}")
      return response
    }

    response.features?.let { featureService.updateFromPurchase(it) }
    eventService?.track(
      "\$purchase_synced",
      properties = buildMap {
        put("provider", "playstore")
        put("product_id", productId ?: "")
        put("product_ids", purchase.productIds)
        put("product_type", purchase.productType?.wireValue ?: "")
        put("package_name", request.packageName ?: "")
        put("base_plan_id", request.basePlanId ?: "")
        put("order_id", purchase.orderId ?: "")
        put("customer_id", response.customerId ?: "")
      },
    )

    return response
  }
}

private fun String?.trimToNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private val PlayStoreProductType.wireValue: String
  get() = when (this) {
    PlayStoreProductType.SUBSCRIPTION -> "subscription"
    PlayStoreProductType.ONE_TIME -> "one_time"
  }
