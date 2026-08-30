package ai.nuxie.sdk.commerce

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import ai.nuxie.sdk.features.FeatureAllowance

internal sealed interface OfferSelection {
    data object None : OfferSelection
    data object Automatic : OfferSelection
    data class Exact(val offerId: String) : OfferSelection
}

internal data class CatalogProductRequest(
    val productId: String,
    val storeProductId: String,
    @BillingClient.ProductType val productType: String,
    val basePlanId: String? = null,
    val offerSelection: OfferSelection = OfferSelection.None,
    val placementId: String? = null,
    val isOfferPersonalized: Boolean = false,
    val consumable: Boolean = false,
    val featureAllowances: List<FeatureAllowance> = emptyList(),
    val licensingPublicKey: String? = null,
    val experienceId: String? = null,
    val experienceVersion: String? = null,
)

internal data class ProductQuery(
    val productId: String,
    @BillingClient.ProductType val productType: String,
)

internal fun interface ProductDetailsQuery {
    suspend fun query(products: List<ProductQuery>): ProductDetailsQueryResult
}

internal sealed interface ProductDetailsQueryResult {
    data class Success(
        val products: List<PlayProductDetails>,
        val unfetchedProducts: List<PlayUnfetchedProduct> = emptyList(),
    ) : ProductDetailsQueryResult

    data class Failed(val responseCode: Int, val debugMessage: String) : ProductDetailsQueryResult
}

internal data class PlayUnfetchedProduct(
    val productId: String,
    @BillingClient.ProductType val productType: String,
    val statusCode: Int,
)

internal data class PlayProductDetails(
    val productId: String,
    @BillingClient.ProductType val productType: String,
    val rawProduct: ProductDetails?,
    val subscriptionOffers: List<PlaySubscriptionOffer>,
    val oneTimePurchaseOfferToken: String? = null,
)

internal data class PlaySubscriptionOffer(
    val basePlanId: String,
    val offerId: String?,
    val offerToken: String,
    val offerTags: List<String>,
    val pricingPhases: List<PlayPricingPhase>,
)

internal data class PlayPricingPhase(
    val priceAmountMicros: Long,
    val billingPeriod: String,
    val billingCycleCount: Int,
    val recurrenceMode: PlayRecurrenceMode,
)

internal enum class PlayRecurrenceMode {
    FINITE,
    INFINITE,
    NON_RECURRING,
}

internal class ProductResolutionException(message: String) : IllegalStateException(message)

internal class ProductResolver(
    private val productDetailsQuery: ProductDetailsQuery,
    private val purchaseStore: PurchaseEvidenceStore,
) {
    suspend fun resolve(requests: List<CatalogProductRequest>): List<StoreProduct> {
        if (requests.isEmpty()) return emptyList()

        val queryProducts = requests
            .map { ProductQuery(it.storeProductId, it.productType) }
            .distinct()
        val queryResult = when (val result = productDetailsQuery.query(queryProducts)) {
            is ProductDetailsQueryResult.Success -> result
            is ProductDetailsQueryResult.Failed -> throw ProductResolutionException(
                "Play product query failed (${result.responseCode}): ${result.debugMessage}",
            )
        }
        val details = queryResult.products.associateBy { it.productId to it.productType }
        val unfetchedProducts = queryResult.unfetchedProducts.associateBy {
            it.productId to it.productType
        }
        val resolvedProducts = mutableListOf<StoreProduct>()
        val failures = mutableListOf<String>()

        requests.forEach { request ->
            val key = request.storeProductId to request.productType
            val product = details[key]
            when {
                product == null -> {
                    val unfetched = unfetchedProducts[key]
                    val reason = if (unfetched == null) {
                        "missing from Play query result"
                    } else {
                        "unfetched by Play (status code ${unfetched.statusCode})"
                    }
                    failures += request.failureDescription(reason)
                }

                request.productType == BillingClient.ProductType.SUBS && request.basePlanId == null -> {
                    failures += request.failureDescription("subscription has no configured base plan")
                }

                request.productType == BillingClient.ProductType.SUBS &&
                    product.subscriptionOffers.none {
                        it.basePlanId == request.basePlanId && it.offerId == null
                    } -> {
                    failures += request.failureDescription(
                        "configured base plan '${request.basePlanId}' is absent from ProductDetails",
                    )
                }

                else -> resolvedProducts += resolve(request, product)
            }
        }

        if (failures.isNotEmpty()) {
            throw ProductResolutionException(
                "Unable to resolve Play products: ${failures.joinToString("; ")}",
            )
        }
        if (resolvedProducts.any { !purchaseStore.upsertProductMapping(it.toStoredMapping()) }) {
            throw ProductResolutionException("Unable to cache resolved Play product mappings.")
        }

        return resolvedProducts
    }

    private fun resolve(
        request: CatalogProductRequest,
        product: PlayProductDetails,
    ): StoreProduct {
        if (request.productType != BillingClient.ProductType.SUBS) {
            return StoreProduct(
                productId = request.productId,
                storeProductId = request.storeProductId,
                basePlanId = null,
                offerId = null,
                placementId = request.placementId,
                rawProduct = product.rawProduct,
                offerToken = product.oneTimePurchaseOfferToken,
                isOfferPersonalized = request.isOfferPersonalized,
                productType = request.productType,
                consumable = request.consumable,
                featureAllowances = request.featureAllowances,
                licensingPublicKey = request.licensingPublicKey,
                purchaseContext = PurchaseContext(request.experienceId, request.experienceVersion),
            )
        }

        val basePlanId = checkNotNull(request.basePlanId)
        val basePlan = product.subscriptionOffers.firstOrNull {
            it.basePlanId == basePlanId && it.offerId == null
        } ?: error("Base plan availability is validated before resolution.")
        val selected = when (request.offerSelection) {
            OfferSelection.None -> basePlan
            OfferSelection.Automatic -> selectAutomatic(
                product.subscriptionOffers.filter { it.basePlanId == basePlanId },
            ) ?: basePlan
            is OfferSelection.Exact -> product.subscriptionOffers.firstOrNull {
                it.basePlanId == basePlanId &&
                    it.offerId == request.offerSelection.offerId &&
                    it.introductoryPhases.size <= 1
            } ?: basePlan
        }
        return StoreProduct(
            productId = request.productId,
            storeProductId = request.storeProductId,
            basePlanId = selected.basePlanId,
            offerId = selected.offerId,
            placementId = request.placementId,
            rawProduct = product.rawProduct,
            offerToken = selected.offerToken,
            isOfferPersonalized = request.isOfferPersonalized,
            productType = request.productType,
            consumable = false,
            featureAllowances = request.featureAllowances,
            licensingPublicKey = request.licensingPublicKey,
            purchaseContext = PurchaseContext(request.experienceId, request.experienceVersion),
        )
    }

    private fun CatalogProductRequest.failureDescription(reason: String): String =
        "$productId ($storeProductId, $productType): $reason"

    private fun StoreProduct.toStoredMapping() = StoredProductMapping(
        storeProductId = storeProductId,
        nuxieProductId = productId,
        basePlanId = basePlanId,
        offerId = offerId,
        productType = productType,
        consumable = consumable,
        context = StoredPurchaseContext(
            placementId,
            purchaseContext?.experienceId,
            purchaseContext?.experienceVersion,
        ),
        featureAllowances = featureAllowances.map {
            StoredFeatureAllowance(it.featureId, it.type.name, it.unlimited, it.allowance)
        },
        licensingPublicKey = licensingPublicKey,
    )

    private val PlaySubscriptionOffer.introductoryPhases: List<PlayPricingPhase>
        get() = pricingPhases.filter { it.recurrenceMode != PlayRecurrenceMode.INFINITE }

    private fun selectAutomatic(offers: List<PlaySubscriptionOffer>): PlaySubscriptionOffer? {
        val eligible = offers.filter {
            it.offerId != null &&
                IGNORE_OFFER_TAG !in it.offerTags &&
                it.introductoryPhases.size == 1
        }
        val freeTrials = eligible.filter { it.introductoryPhases.single().priceAmountMicros == 0L }
        if (freeTrials.isNotEmpty()) {
            return freeTrials.maxByOrNull {
                val phase = it.introductoryPhases.single()
                billingPeriodDays(phase.billingPeriod) * phase.billingCycleCount.coerceAtLeast(1)
            }
        }
        return eligible
            .filter { it.introductoryPhases.single().priceAmountMicros > 0L }
            .minByOrNull { it.introductoryPhases.single().priceAmountMicros }
    }

    private fun billingPeriodDays(period: String): Long {
        val match = ISO_PERIOD.matchEntire(period) ?: return 0L
        fun value(group: Int): Long = match.groupValues[group].toLongOrNull() ?: 0L
        return value(1) * 365L + value(2) * 30L + value(3) * 7L + value(4)
    }

    private companion object {
        const val IGNORE_OFFER_TAG = "nuxie-ignore-offer"
        val ISO_PERIOD = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?")
    }
}
