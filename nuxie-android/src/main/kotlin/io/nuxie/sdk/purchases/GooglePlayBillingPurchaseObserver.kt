package io.nuxie.sdk.purchases

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import io.nuxie.sdk.logging.NuxieLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class GooglePlayBillingPurchaseObserver(
  private val scope: CoroutineScope,
  private val syncService: PurchaseSyncService,
  private val client: PlayBillingClient,
  private val consumableProductIds: () -> Set<String>,
) {
  private val syncedTokens = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
  @Volatile private var started = false
  @Volatile private var connected = false

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
    client.endConnection()
  }

  private fun queryCurrentPurchases() {
    client.queryPurchases(
      productType = PlayStoreProductType.SUBSCRIPTION,
      includeSuspendedSubscriptions = true,
    ) { result, purchases ->
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
      if (result.responseCode == BillingClient.BillingResponseCode.OK) {
        processPurchases(purchases, productType = PlayStoreProductType.ONE_TIME)
      } else {
        NuxieLogger.debug("Play Billing one-time product query failed: ${result.debugMessage}")
      }
    }
  }

  internal fun onPurchasesUpdated(result: PlayBillingResult, purchases: List<PlayBillingPurchaseSnapshot>?) {
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
    for (purchase in purchases) {
      processPurchase(purchase, productType)
    }
  }

  private fun processPurchase(
    purchase: PlayBillingPurchaseSnapshot,
    productType: PlayStoreProductType?,
  ) {
    val token = purchase.purchaseToken.trim()
    if (token.isEmpty()) {
      return
    }

    if (purchase.purchaseState != PlayStorePurchaseState.PURCHASED) {
      NuxieLogger.debug("Play Billing purchase is ${purchase.purchaseState}; waiting for PURCHASED before sync")
      return
    }

    if (!syncedTokens.add(token)) {
      return
    }

    val consumables = consumableProductIds()
    val normalizedProductIds = purchase.productIds.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
    val resolvedProductType = productType ?: if (normalizedProductIds.any { it in consumables }) {
      PlayStoreProductType.ONE_TIME
    } else {
      null
    }
    val shouldConsume = resolvedProductType == PlayStoreProductType.ONE_TIME &&
      normalizedProductIds.any { it in consumables }

    scope.launch {
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
          )
        )
      }.getOrElse {
        syncedTokens.remove(token)
        NuxieLogger.warning("Play Store purchase sync failed: ${it.message}")
        return@launch
      }

      if (!response.success) {
        syncedTokens.remove(token)
      }
    }
  }

  companion object {
    fun create(
      context: Context,
      scope: CoroutineScope,
      syncService: PurchaseSyncService,
      consumableProductIds: () -> Set<String>,
    ): GooglePlayBillingPurchaseObserver {
      lateinit var observer: GooglePlayBillingPurchaseObserver
      val client = AndroidPlayBillingClient(context.applicationContext) { result, purchases ->
        observer.onPurchasesUpdated(result, purchases)
      }
      observer = GooglePlayBillingPurchaseObserver(
        scope = scope,
        syncService = syncService,
        client = client,
        consumableProductIds = consumableProductIds,
      )
      return observer
    }
  }
}

internal data class PlayBillingResult(
  val responseCode: Int,
  val debugMessage: String,
)

internal data class PlayBillingPurchaseSnapshot(
  val purchaseToken: String,
  val productIds: List<String>,
  val packageName: String?,
  val orderId: String?,
  val purchaseState: PlayStorePurchaseState,
)

internal interface PlayBillingClient {
  fun startConnection(
    onSetupFinished: (PlayBillingResult) -> Unit,
    onDisconnected: () -> Unit,
  )

  fun endConnection()

  fun queryPurchases(
    productType: PlayStoreProductType,
    includeSuspendedSubscriptions: Boolean,
    listener: (PlayBillingResult, List<PlayBillingPurchaseSnapshot>) -> Unit,
  )
}

private class AndroidPlayBillingClient(
  context: Context,
  private val purchasesUpdated: (PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit,
) : PlayBillingClient {
  private val billingClient: BillingClient = BillingClient.newBuilder(context)
    .setListener { result, purchases ->
      purchasesUpdated(result.toPlayBillingResult(), purchases?.map { it.toSnapshot() })
    }
    .enablePendingPurchases(
      PendingPurchasesParams.newBuilder()
        .enableOneTimeProducts()
        .enablePrepaidPlans()
        .build()
    )
    .enableAutoServiceReconnection()
    .build()

  override fun startConnection(
    onSetupFinished: (PlayBillingResult) -> Unit,
    onDisconnected: () -> Unit,
  ) {
    billingClient.startConnection(
      object : BillingClientStateListener {
        override fun onBillingSetupFinished(billingResult: BillingResult) {
          onSetupFinished(billingResult.toPlayBillingResult())
        }

        override fun onBillingServiceDisconnected() {
          onDisconnected()
        }
      }
    )
  }

  override fun endConnection() {
    billingClient.endConnection()
  }

  override fun queryPurchases(
    productType: PlayStoreProductType,
    includeSuspendedSubscriptions: Boolean,
    listener: (PlayBillingResult, List<PlayBillingPurchaseSnapshot>) -> Unit,
  ) {
    val builder = QueryPurchasesParams.newBuilder()
      .setProductType(productType.billingProductType)

    if (productType == PlayStoreProductType.SUBSCRIPTION) {
      builder.includeSuspendedSubscriptionsIfAvailable(includeSuspendedSubscriptions)
    }

    billingClient.queryPurchasesAsync(builder.build()) { result, purchases ->
      listener(result.toPlayBillingResult(), purchases.map { it.toSnapshot() })
    }
  }
}

private val PlayStoreProductType.billingProductType: String
  get() = when (this) {
    PlayStoreProductType.SUBSCRIPTION -> BillingClient.ProductType.SUBS
    PlayStoreProductType.ONE_TIME -> BillingClient.ProductType.INAPP
  }

private fun BillingResult.toPlayBillingResult(): PlayBillingResult {
  return PlayBillingResult(
    responseCode = responseCode,
    debugMessage = debugMessage,
  )
}

private fun Purchase.toSnapshot(): PlayBillingPurchaseSnapshot {
  return PlayBillingPurchaseSnapshot(
    purchaseToken = purchaseToken,
    productIds = products,
    packageName = packageName,
    orderId = orderId,
    purchaseState = when (purchaseState) {
      Purchase.PurchaseState.PURCHASED -> PlayStorePurchaseState.PURCHASED
      Purchase.PurchaseState.PENDING -> PlayStorePurchaseState.PENDING
      else -> PlayStorePurchaseState.UNSPECIFIED
    },
  )
}

private fun QueryPurchasesParams.Builder.includeSuspendedSubscriptionsIfAvailable(include: Boolean) {
  runCatching {
    val method = javaClass.methods.firstOrNull { method ->
      method.name == "includeSuspendedSubscriptions" &&
        method.parameterTypes.size == 1 &&
        method.parameterTypes[0] == java.lang.Boolean.TYPE
    } ?: return
    method.invoke(this, include)
  }
}
