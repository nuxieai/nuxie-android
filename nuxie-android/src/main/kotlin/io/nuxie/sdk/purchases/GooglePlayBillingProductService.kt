package io.nuxie.sdk.purchases

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import io.nuxie.sdk.flows.FlowProduct
import io.nuxie.sdk.flows.FlowProductFetchException
import io.nuxie.sdk.flows.FlowProductService
import io.nuxie.sdk.flows.ProductPeriod
import io.nuxie.sdk.logging.NuxieLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_PRODUCT_DETAILS_QUERY_PRODUCT_COUNT = 20

internal class GooglePlayBillingProductService(
  private val client: PlayBillingClient,
) : FlowProductService {
  private val readyLock = Any()
  private var pendingReady: CompletableDeferred<Boolean>? = null

  override suspend fun fetchProducts(productIds: Set<String>): List<FlowProduct> {
    val normalizedIds = productIds
      .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
      .distinct()

    if (normalizedIds.isEmpty()) return emptyList()
    if (!ensureReady()) {
      throw FlowProductFetchException("Play Billing product metadata setup failed")
    }

    val subscriptionDetails = queryProductDetailsCollectingFailures(PlayStoreProductType.SUBSCRIPTION, normalizedIds)
    val oneTimeDetails = queryProductDetailsCollectingFailures(PlayStoreProductType.ONE_TIME, normalizedIds)
    val details = subscriptionDetails.products + oneTimeDetails.products
    if (details.isEmpty() && (subscriptionDetails.hadFailure || oneTimeDetails.hadFailure)) {
      throw FlowProductFetchException("Play Billing product metadata query failed")
    }

    val productsById = linkedMapOf<String, FlowProduct>()
    for (detail in details) {
      val flowProduct = detail.toFlowProduct() ?: continue
      productsById.putIfAbsent(flowProduct.id, flowProduct)
    }

    val missing = normalizedIds.filterNot(productsById::containsKey)
    if (missing.isNotEmpty()) {
      NuxieLogger.debug("Play Billing product metadata missing for ${missing.joinToString(",")}")
    }

    return normalizedIds.mapNotNull(productsById::get)
  }

  private suspend fun ensureReady(): Boolean {
    if (client.isReady) return true

    var shouldStart = false
    var alreadyReady = false
    val pending = synchronized(readyLock) {
      if (client.isReady) {
        alreadyReady = true
        null
      } else {
        pendingReady ?: CompletableDeferred<Boolean>().also {
          pendingReady = it
          shouldStart = true
        }
      }
    }

    if (alreadyReady) return true
    if (pending == null) return false
    if (shouldStart) {
      startReadyConnection(pending)
    }

    return pending.await()
  }

  private fun startReadyConnection(pending: CompletableDeferred<Boolean>) {
    val resumed = AtomicBoolean(false)

    fun completeOnce(value: Boolean) {
      if (resumed.compareAndSet(false, true)) {
        pending.complete(value)
        synchronized(readyLock) {
          if (pendingReady === pending) {
            pendingReady = null
          }
        }
      }
    }

    try {
      client.startConnection(
        onSetupFinished = { result ->
          val ready = result.responseCode == BillingClient.BillingResponseCode.OK
          if (!ready) {
            NuxieLogger.debug("Play Billing product metadata setup skipped: ${result.debugMessage}")
          }
          completeOnce(ready)
        },
        onDisconnected = {
          NuxieLogger.debug("Play Billing disconnected before product metadata query")
          completeOnce(false)
        },
      )
    } catch (error: Throwable) {
      NuxieLogger.debug("Play Billing product metadata setup failed: ${error.message}")
      completeOnce(false)
    }
  }

  private suspend fun queryProductDetails(
    productType: PlayStoreProductType,
    productIds: List<String>,
  ): List<PlayBillingProductDetailsSnapshot> {
    return suspendCancellableCoroutine { continuation ->
      client.queryProductDetails(productType, productIds) listener@{ result, products ->
        if (!continuation.isActive) return@listener
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
          continuation.resume(products)
        } else {
          continuation.resumeWithException(
            PlayBillingProductQueryException(
              "responseCode=${result.responseCode} ${result.debugMessage}"
            )
          )
        }
      }
    }
  }

  private data class ProductDetailsQueryResult(
    val products: List<PlayBillingProductDetailsSnapshot>,
    val hadFailure: Boolean,
  )

  private suspend fun queryProductDetailsCollectingFailures(
    productType: PlayStoreProductType,
    productIds: List<String>,
  ): ProductDetailsQueryResult {
    var hadFailure = false
    val products = productIds.chunked(MAX_PRODUCT_DETAILS_QUERY_PRODUCT_COUNT).flatMap { batch ->
      val details = runCatching {
        queryProductDetails(productType, batch)
      }.getOrElse { error ->
        hadFailure = true
        NuxieLogger.debug("Play Billing ${productType.name.lowercase()} product metadata query failed: ${error.message}")
        emptyList()
      }
      details
    }
    return ProductDetailsQueryResult(products = products, hadFailure = hadFailure)
  }

}

private class PlayBillingProductQueryException(message: String) : Exception(message)

internal fun PlayBillingProductDetailsSnapshot.toFlowProduct(): FlowProduct? {
  val productName = name.ifBlank { title.ifBlank { productId } }
  return when (productType) {
    PlayStoreProductType.SUBSCRIPTION -> {
      val phase = subscriptionOffers
        .asSequence()
        .flatMap { it.pricingPhases.asSequence() }
        .selectDisplayPhase()
        ?: return null

      FlowProduct(
        id = productId,
        name = productName,
        price = phase.formattedPrice,
        period = phase.billingPeriod.toProductPeriod(),
      )
    }
    PlayStoreProductType.ONE_TIME -> {
      val offer = oneTimeOffers.firstOrNull() ?: return null
      FlowProduct(
        id = productId,
        name = productName,
        price = offer.formattedPrice,
        period = null,
      )
    }
  }
}

private fun Sequence<PlayBillingPricingPhaseSnapshot>.selectDisplayPhase(): PlayBillingPricingPhaseSnapshot? {
  val phases = toList()
  return phases.firstOrNull {
    it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING &&
      it.priceAmountMicros > 0
  } ?: phases.firstOrNull { it.priceAmountMicros > 0 }
    ?: phases.firstOrNull()
}

private fun String.toProductPeriod(): ProductPeriod? {
  return when {
    matches(Regex("""P\d+W""")) -> ProductPeriod.WEEK
    matches(Regex("""P\d+M""")) -> ProductPeriod.MONTH
    matches(Regex("""P\d+Y""")) -> ProductPeriod.YEAR
    matches(Regex("""P\d+D""")) -> ProductPeriod.WEEK
    else -> null
  }
}
