package io.nuxie.sdk.purchases

import android.content.Context
import com.android.billingclient.api.BillingClient
import io.nuxie.sdk.logging.NuxieLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class GooglePlayBillingPurchaseObserver(
  private val scope: CoroutineScope,
  private val syncService: PurchaseSyncService,
  private val client: PlayBillingClient,
  private val distinctIdProvider: () -> String,
  private val consumableProductIds: () -> Set<String>,
) {
  private data class SyncedPurchaseKey(
    val distinctId: String,
    val purchaseToken: String,
  )

  private val syncedPurchases = Collections.newSetFromMap(ConcurrentHashMap<SyncedPurchaseKey, Boolean>())
  @Volatile private var started = false
  @Volatile private var connected = false

  init {
    client.setPurchasesUpdatedListener(::onPurchasesUpdated)
  }

  fun start() {
    if (connected) {
      queryCurrentPurchases()
      return
    }
    if (started) {
      return
    }

    started = true
    client.startConnection(
      onSetupFinished = { result ->
        if (!started) return@startConnection
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
          connected = true
          queryCurrentPurchases()
        } else {
          started = false
          connected = false
          NuxieLogger.debug("Play Billing setup skipped: ${result.debugMessage}")
        }
      },
      onDisconnected = {
        started = false
        connected = false
        NuxieLogger.debug("Play Billing service disconnected")
      },
    )
  }

  fun refreshPurchases() {
    if (connected) {
      queryCurrentPurchases()
    } else {
      start()
    }
  }

  fun stop() {
    started = false
    connected = false
    client.setPurchasesUpdatedListener(null)
    client.endConnection()
  }

  private fun queryCurrentPurchases() {
    client.queryPurchases(
      productType = PlayStoreProductType.SUBSCRIPTION,
      includeSuspendedSubscriptions = true,
    ) { result, purchases ->
      if (!isActive()) return@queryPurchases
      if (result.responseCode == BillingClient.BillingResponseCode.OK) {
        processPurchases(purchases, productType = PlayStoreProductType.SUBSCRIPTION)
      } else {
        NuxieLogger.debug("Play Billing subscription query failed: ${result.debugMessage}")
      }
    }

    client.queryPurchases(
      productType = PlayStoreProductType.ONE_TIME,
      includeSuspendedSubscriptions = false,
    ) { result, purchases ->
      if (!isActive()) return@queryPurchases
      if (result.responseCode == BillingClient.BillingResponseCode.OK) {
        processPurchases(purchases, productType = PlayStoreProductType.ONE_TIME)
      } else {
        NuxieLogger.debug("Play Billing one-time product query failed: ${result.debugMessage}")
      }
    }
  }

  internal fun onPurchasesUpdated(result: PlayBillingResult, purchases: List<PlayBillingPurchaseSnapshot>?) {
    if (!isActive()) return
    if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
      if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
        NuxieLogger.debug("Play Billing purchase update ignored: ${result.debugMessage}")
      }
      return
    }

    processPurchases(purchases, productType = null)
  }

  private fun processPurchases(
    purchases: List<PlayBillingPurchaseSnapshot>,
    productType: PlayStoreProductType?,
  ) {
    if (!isActive()) return
    for (purchase in purchases) {
      processPurchase(purchase, productType)
    }
  }

  private fun processPurchase(
    purchase: PlayBillingPurchaseSnapshot,
    productType: PlayStoreProductType?,
  ) {
    if (!isActive()) return
    val token = purchase.purchaseToken.trim()
    if (token.isEmpty()) {
      return
    }

    if (purchase.purchaseState != PlayStorePurchaseState.PURCHASED) {
      NuxieLogger.debug("Play Billing purchase is ${purchase.purchaseState}; waiting for PURCHASED before sync")
      return
    }

    val distinctId = distinctIdProvider().trim()
    val syncedKey = SyncedPurchaseKey(distinctId = distinctId, purchaseToken = token)
    if (!syncedPurchases.add(syncedKey)) {
      return
    }

    val consumables = consumableProductIds()
    val normalizedProductIds = purchase.productIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
    val allProductsAreConsumable = normalizedProductIds.isNotEmpty() && normalizedProductIds.all { it in consumables }
    val resolvedProductType = productType ?: if (allProductsAreConsumable) {
      PlayStoreProductType.ONE_TIME
    } else {
      null
    }
    val shouldConsume = resolvedProductType == PlayStoreProductType.ONE_TIME && allProductsAreConsumable

    scope.launch {
      if (!isActive()) {
        syncedPurchases.remove(syncedKey)
        return@launch
      }

      val response = runCatching {
        syncService.syncPlayStorePurchase(
          PlayStorePurchase(
            purchaseToken = token,
            productIds = normalizedProductIds,
            packageName = purchase.packageName,
            productType = resolvedProductType,
            consumePurchase = shouldConsume,
            orderId = purchase.orderId,
            purchaseState = purchase.purchaseState,
            distinctId = distinctId,
          )
        )
      }.getOrElse {
        syncedPurchases.remove(syncedKey)
        NuxieLogger.warning("Play Store purchase sync failed: ${it.message}")
        return@launch
      }

      if (!response.success) {
        syncedPurchases.remove(syncedKey)
      }
    }
  }

  private fun isActive(): Boolean = started && connected

  companion object {
    fun create(
      context: Context,
      scope: CoroutineScope,
      syncService: PurchaseSyncService,
      distinctIdProvider: () -> String,
      consumableProductIds: () -> Set<String>,
    ): GooglePlayBillingPurchaseObserver {
      val client = AndroidPlayBillingClient(context.applicationContext)
      return GooglePlayBillingPurchaseObserver(
        scope = scope,
        syncService = syncService,
        client = client,
        distinctIdProvider = distinctIdProvider,
        consumableProductIds = consumableProductIds,
      )
    }
  }
}
