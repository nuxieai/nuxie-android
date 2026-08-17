package io.nuxie.sdk.purchases

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.Purchase

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

internal data class PlayBillingProductDetailsSnapshot(
  val productId: String,
  val productType: PlayStoreProductType,
  val name: String,
  val title: String,
  val oneTimeOffers: List<PlayBillingOneTimeOfferSnapshot> = emptyList(),
  val subscriptionOffers: List<PlayBillingSubscriptionOfferSnapshot> = emptyList(),
)

internal data class PlayBillingOneTimeOfferSnapshot(
  val formattedPrice: String,
  val priceAmountMicros: Long,
  val offerId: String? = null,
  val purchaseOptionId: String? = null,
  val offerToken: String? = null,
  val fullPriceMicros: Long? = null,
  val priceCurrencyCode: String? = null,
)

internal data class PlayBillingSubscriptionOfferSnapshot(
  val basePlanId: String?,
  val offerId: String?,
  val offerToken: String = "",
  val pricingPhases: List<PlayBillingPricingPhaseSnapshot>,
)

internal data class PlayBillingPricingPhaseSnapshot(
  val formattedPrice: String,
  val priceAmountMicros: Long,
  val billingPeriod: String,
  val recurrenceMode: Int,
  val billingCycleCount: Int = 1,
)

internal interface PlayBillingClient {
  val isReady: Boolean

  fun setPurchasesUpdatedListener(
    listener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)?,
  )

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

  fun queryProductDetails(
    productType: PlayStoreProductType,
    productIds: List<String>,
    listener: (PlayBillingResult, List<PlayBillingProductDetailsSnapshot>) -> Unit,
  )
}

internal class AndroidPlayBillingClient : PlayBillingClient {
  @Volatile
  private var purchasesUpdatedListener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)? = null

  private val connectionLock = Any()
  private var connectionInFlight = false
  private val setupCallbacks = mutableListOf<(PlayBillingResult) -> Unit>()
  private val disconnectionCallbacks = mutableListOf<() -> Unit>()

  private val billingClient: BillingClient

  constructor(context: Context) {
    billingClient = BillingClient.newBuilder(context)
      .setListener { result, purchases ->
        purchasesUpdatedListener?.invoke(result.toPlayBillingResult(), purchases?.map { it.toSnapshot() })
      }
      .enablePendingPurchases(
        PendingPurchasesParams.newBuilder()
          .enableOneTimeProducts()
          .enablePrepaidPlans()
          .build()
      )
      .enableAutoServiceReconnection()
      .build()
  }

  internal constructor(billingClient: BillingClient) {
    this.billingClient = billingClient
  }

  override val isReady: Boolean
    get() = billingClient.isReady

  override fun setPurchasesUpdatedListener(
    listener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)?,
  ) {
    purchasesUpdatedListener = listener
  }

  override fun startConnection(
    onSetupFinished: (PlayBillingResult) -> Unit,
    onDisconnected: () -> Unit,
  ) {
    var readyResult: PlayBillingResult? = null
    var shouldStart = false
    synchronized(connectionLock) {
      disconnectionCallbacks += onDisconnected
      if (billingClient.isReady) {
        readyResult = PlayBillingResult(BillingClient.BillingResponseCode.OK, "Billing service ready")
      } else {
        setupCallbacks += onSetupFinished
        if (!connectionInFlight) {
          connectionInFlight = true
          shouldStart = true
        }
      }
    }

    readyResult?.let {
      onSetupFinished(it)
      return
    }

    if (!shouldStart) return

    try {
      billingClient.startConnection(
        object : BillingClientStateListener {
          override fun onBillingSetupFinished(billingResult: BillingResult) {
            finishSetup(billingResult.toPlayBillingResult())
          }

          override fun onBillingServiceDisconnected() {
            notifyDisconnected()
          }
        }
      )
    } catch (error: Throwable) {
      finishSetup(
        PlayBillingResult(
          responseCode = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
          debugMessage = "Play Billing connection failed: ${error.message}",
        )
      )
    }
  }

  override fun endConnection() {
    val pendingSetup = synchronized(connectionLock) {
      connectionInFlight = false
      val pending = setupCallbacks.toList()
      setupCallbacks.clear()
      disconnectionCallbacks.clear()
      pending
    }
    val closedResult = PlayBillingResult(
      responseCode = BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
      debugMessage = "Billing connection closed",
    )
    try {
      billingClient.endConnection()
    } finally {
      pendingSetup.forEach { it(closedResult) }
    }
  }

  private fun finishSetup(result: PlayBillingResult) {
    val callbacks = synchronized(connectionLock) {
      connectionInFlight = false
      val pending = setupCallbacks.toList()
      setupCallbacks.clear()
      if (result.responseCode != BillingClient.BillingResponseCode.OK) {
        disconnectionCallbacks.clear()
      }
      pending
    }
    callbacks.forEach { it(result) }
  }

  private fun notifyDisconnected() {
    val setupResult = PlayBillingResult(
      responseCode = BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
      debugMessage = "Billing service disconnected",
    )
    val (setup, disconnected) = synchronized(connectionLock) {
      connectionInFlight = false
      val setup = setupCallbacks.toList()
      val disconnected = disconnectionCallbacks.toList()
      setupCallbacks.clear()
      disconnectionCallbacks.clear()
      setup to disconnected
    }
    setup.forEach { it(setupResult) }
    disconnected.forEach { it() }
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

  override fun queryProductDetails(
    productType: PlayStoreProductType,
    productIds: List<String>,
    listener: (PlayBillingResult, List<PlayBillingProductDetailsSnapshot>) -> Unit,
  ) {
    val products = productIds.map { productId ->
      QueryProductDetailsParams.Product.newBuilder()
        .setProductId(productId)
        .setProductType(productType.billingProductType)
        .build()
    }
    val params = QueryProductDetailsParams.newBuilder()
      .setProductList(products)
      .build()

    billingClient.queryProductDetailsAsync(params) { result, response ->
      listener(
        result.toPlayBillingResult(),
        response.productDetailsList.map { it.toSnapshot() },
      )
    }
  }
}

internal val PlayStoreProductType.billingProductType: String
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

private fun ProductDetails.toSnapshot(): PlayBillingProductDetailsSnapshot {
  return PlayBillingProductDetailsSnapshot(
    productId = productId,
    productType = when (productType) {
      BillingClient.ProductType.SUBS -> PlayStoreProductType.SUBSCRIPTION
      else -> PlayStoreProductType.ONE_TIME
    },
    name = name,
    title = title,
    oneTimeOffers = oneTimeOffersSnapshot(),
    subscriptionOffers = subscriptionOffersSnapshot(),
  )
}

private fun ProductDetails.oneTimeOffersSnapshot(): List<PlayBillingOneTimeOfferSnapshot> {
  val offers = oneTimePurchaseOfferDetailsList.orEmpty()
    .ifEmpty { listOfNotNull(oneTimePurchaseOfferDetails) }

  return offers.map { offer ->
    PlayBillingOneTimeOfferSnapshot(
      formattedPrice = offer.formattedPrice,
      priceAmountMicros = offer.priceAmountMicros,
      offerId = offer.offerId,
      purchaseOptionId = offer.purchaseOptionId,
      offerToken = offer.offerToken,
      fullPriceMicros = offer.fullPriceMicros,
      priceCurrencyCode = offer.priceCurrencyCode,
    )
  }
}

private fun ProductDetails.subscriptionOffersSnapshot(): List<PlayBillingSubscriptionOfferSnapshot> {
  return subscriptionOfferDetails.orEmpty().map { offer ->
    PlayBillingSubscriptionOfferSnapshot(
      basePlanId = offer.basePlanId,
      offerId = offer.offerId,
      offerToken = offer.offerToken,
      pricingPhases = offer.pricingPhases.pricingPhaseList.map { phase ->
        PlayBillingPricingPhaseSnapshot(
          formattedPrice = phase.formattedPrice,
          priceAmountMicros = phase.priceAmountMicros,
          billingPeriod = phase.billingPeriod,
          recurrenceMode = phase.recurrenceMode,
          billingCycleCount = phase.billingCycleCount,
        )
      },
    )
  }
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
