package ai.nuxie.sdk.commerce

import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal interface BillingClientAdapter {
    val isReady: Boolean

    fun startConnection(listener: BillingClientStateListener)

    fun endConnection()

    fun queryProductDetailsAsync(
        params: QueryProductDetailsParams,
        listener: ProductDetailsResponseListener,
    )
}

internal fun interface BillingClientAdapterFactory {
    fun create(listener: PurchasesUpdatedListener): BillingClientAdapter
}

internal data class PurchaseUpdate(
    val billingResult: BillingResult,
    val purchases: List<Purchase>?,
)

internal class BillingUnavailableException(
    @BillingClient.BillingResponseCode val responseCode: Int,
    val debugMessage: String,
) : IllegalStateException("Play Billing unavailable ($responseCode): $debugMessage")

internal class PlayBillingConnection(
    private val factory: BillingClientAdapterFactory,
    private val scope: CoroutineScope,
    private val onPurchasesUpdated: (PurchaseUpdate) -> Unit = {},
    private val initialRetryDelayMillis: Long = 1_000,
    private val maxRetryDelayMillis: Long = 60_000,
) : ProductDetailsQuery {
    private val lock = Any()
    private var client: BillingClientAdapter? = null
    private var connecting = false
    private var closed = false
    private var retryAttempt = 0
    private var reconnectJob: Job? = null
    private var ready = CompletableDeferred<Unit>()
    private var terminalFailure = false

    @Volatile
    var lastUpdate: PurchaseUpdate? = null
        private set

    private val purchaseListener = PurchasesUpdatedListener { result, purchases ->
        val update = PurchaseUpdate(result, purchases)
        lastUpdate = update
        Log.d(LOG_TAG, "Received Play purchase update (${result.responseCode}).")
        onPurchasesUpdated(update)
    }

    private val connectionListener = object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
            synchronized(lock) {
                connecting = false
                if (closed) return
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    retryAttempt = 0
                    terminalFailure = false
                    ready.complete(Unit)
                } else if (result.responseCode.isTransientSetupFailure()) {
                    scheduleReconnectLocked()
                } else {
                    terminalFailure = true
                    retryAttempt = 0
                    reconnectJob?.cancel()
                    reconnectJob = null
                    client?.endConnection()
                    client = null
                    ready.completeExceptionally(
                        BillingUnavailableException(result.responseCode, result.debugMessage),
                    )
                }
            }
        }

        override fun onBillingServiceDisconnected() {
            synchronized(lock) {
                connecting = false
                if (closed) return
                if (terminalFailure) return
                if (ready.isCompleted) ready = CompletableDeferred()
                scheduleReconnectLocked()
            }
        }
    }

    fun connect() {
        synchronized(lock) {
            if (closed || connecting || reconnectJob?.isActive == true) return
            terminalFailure = false
            val billingClient = client ?: factory.create(purchaseListener).also { client = it }
            if (ready.isCompleted) ready = CompletableDeferred()
            if (billingClient.isReady) {
                retryAttempt = 0
                ready.complete(Unit)
                return
            }
            connecting = true
            billingClient.startConnection(connectionListener)
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            reconnectJob?.cancel()
            reconnectJob = null
            client?.endConnection()
            if (!ready.isCompleted) {
                ready.completeExceptionally(IllegalStateException("Play Billing connection closed."))
            }
        }
    }

    override suspend fun query(products: List<ProductQuery>): ProductDetailsQueryResult {
        if (products.isEmpty()) return ProductDetailsQueryResult.Success(emptyList())
        val billingClient = awaitClient()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                products.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it.productId)
                        .setProductType(it.productType)
                        .build()
                },
            )
            .build()
        return suspendCancellableCoroutine { continuation ->
            billingClient.queryProductDetailsAsync(params) { result, queryResult ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    continuation.resume(
                        ProductDetailsQueryResult.Failed(result.responseCode, result.debugMessage),
                    )
                } else {
                    continuation.resume(
                        ProductDetailsQueryResult.Success(
                            queryResult.productDetailsList.map(::projectProductDetails),
                            queryResult.unfetchedProductList.map { unfetched ->
                                PlayUnfetchedProduct(
                                    productId = unfetched.productId,
                                    productType = unfetched.productType,
                                    statusCode = unfetched.statusCode,
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    private suspend fun awaitClient(): BillingClientAdapter {
        connect()
        val signal = synchronized(lock) { ready }
        signal.await()
        return synchronized(lock) {
            check(!closed) { "Play Billing connection closed." }
            checkNotNull(client)
        }
    }

    private fun scheduleReconnectLocked() {
        if (reconnectJob?.isActive == true) return
        val delayMillis = retryDelay(retryAttempt)
        retryAttempt += 1
        reconnectJob = scope.launch {
            delay(delayMillis)
            synchronized(lock) { reconnectJob = null }
            connect()
        }
    }

    private fun retryDelay(attempt: Int): Long {
        var result = initialRetryDelayMillis.coerceAtLeast(0)
        repeat(attempt.coerceAtMost(62)) {
            if (result >= maxRetryDelayMillis) return maxRetryDelayMillis
            result = (result * 2).coerceAtMost(maxRetryDelayMillis)
        }
        return result.coerceAtMost(maxRetryDelayMillis)
    }

    // ProductDetails and its nested Billing value types are final and have no
    // public JVM constructors, so a complete valid graph cannot be built in a
    // local test. Keep this direct native projection at the device boundary;
    // resolver tests instead cover the projected PlayProductDetails contract.
    private fun projectProductDetails(details: ProductDetails) = PlayProductDetails(
        productId = details.productId,
        productType = details.productType,
        rawProduct = details,
        oneTimePurchaseOfferToken = details.oneTimePurchaseOfferDetails?.offerToken,
        subscriptionOffers = details.subscriptionOfferDetails.orEmpty().map { offer ->
            PlaySubscriptionOffer(
                basePlanId = offer.basePlanId,
                offerId = offer.offerId,
                offerToken = offer.offerToken,
                offerTags = offer.offerTags,
                pricingPhases = offer.pricingPhases.pricingPhaseList.map { phase ->
                    PlayPricingPhase(
                        priceAmountMicros = phase.priceAmountMicros,
                        billingPeriod = phase.billingPeriod,
                        billingCycleCount = phase.billingCycleCount,
                        recurrenceMode = when (phase.recurrenceMode) {
                            ProductDetails.RecurrenceMode.FINITE_RECURRING -> PlayRecurrenceMode.FINITE
                            ProductDetails.RecurrenceMode.INFINITE_RECURRING -> PlayRecurrenceMode.INFINITE
                            else -> PlayRecurrenceMode.NON_RECURRING
                        },
                    )
                },
            )
        },
    )

    private companion object {
        const val LOG_TAG = "NuxieCommerce"

        fun Int.isTransientSetupFailure(): Boolean = when (this) {
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.ERROR,
            -> true
            else -> false
        }
    }
}

internal class GooglePlayBillingClientAdapter private constructor(
    private val client: BillingClient,
) : BillingClientAdapter {
    override val isReady: Boolean
        get() = client.isReady

    override fun startConnection(listener: BillingClientStateListener) {
        client.startConnection(listener)
    }

    override fun endConnection() {
        client.endConnection()
    }

    override fun queryProductDetailsAsync(
        params: QueryProductDetailsParams,
        listener: ProductDetailsResponseListener,
    ) {
        client.queryProductDetailsAsync(params, listener)
    }

    companion object {
        fun factory(context: Context): BillingClientAdapterFactory {
            val appContext = context.applicationContext ?: context
            return BillingClientAdapterFactory { listener ->
                val pendingPurchases = PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
                GooglePlayBillingClientAdapter(
                    BillingClient.newBuilder(appContext)
                        .setListener(listener)
                        .enablePendingPurchases(pendingPurchases)
                        .build(),
                )
            }
        }
    }
}
