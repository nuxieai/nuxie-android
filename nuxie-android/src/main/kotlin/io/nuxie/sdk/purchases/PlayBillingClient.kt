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
)

internal data class PlayBillingSubscriptionOfferSnapshot(
  val basePlanId: String?,
  val offerId: String?,
  val pricingPhases: List<PlayBillingPricingPhaseSnapshot>,
)

internal data class PlayBillingPricingPhaseSnapshot(
  val formattedPrice: String,
  val priceAmountMicros: Long,
  val billingPeriod: String,
  val recurrenceMode: Int,
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

internal class AndroidPlayBillingClient(
  context: Context,
) : PlayBillingClient {
  @Volatile
  private var purchasesUpdatedListener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)? = null

  private val billingClient: BillingClient = BillingClient.newBuilder(context)
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
    if (billingClient.isReady) {
      onSetupFinished(PlayBillingResult(BillingClient.BillingResponseCode.OK, "Billing service ready"))
      return
    }

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
    )
  }
}

private fun ProductDetails.subscriptionOffersSnapshot(): List<PlayBillingSubscriptionOfferSnapshot> {
  return subscriptionOfferDetails.orEmpty().map { offer ->
    PlayBillingSubscriptionOfferSnapshot(
      basePlanId = offer.basePlanId,
      offerId = offer.offerId,
      pricingPhases = offer.pricingPhases.pricingPhaseList.map { phase ->
        PlayBillingPricingPhaseSnapshot(
          formattedPrice = phase.formattedPrice,
          priceAmountMicros = phase.priceAmountMicros,
          billingPeriod = phase.billingPeriod,
          recurrenceMode = phase.recurrenceMode,
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
