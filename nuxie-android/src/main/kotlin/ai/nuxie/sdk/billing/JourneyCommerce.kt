package ai.nuxie.sdk.billing

import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.features.FeatureAllowance
import android.app.Activity
import com.android.billingclient.api.BillingClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JourneyCommerceException(message: String) : IllegalStateException(message)

/** Stable ownership for the one outcome event that resumes a commerce action. */
internal data class CommerceOutcomeCorrelation(
    val eventId: String,
    val distinctId: String,
)

/** Authenticated catalog projected into exact Play product requests. */
internal data class JourneyProductCatalog(
    val requests: List<CatalogProductRequest>,
) {
    companion object {
        fun parse(release: AuthenticatedJourneyRelease): JourneyProductCatalog {
            val products = release.descriptor.array("products")
                .mapIndexed { index, element ->
                    val product = element as? JsonObject
                        ?: invalid("Product[$index] is not an object")
                    val id = product.requiredString("id", "Product[$index]")
                    id to parseProduct(id, product)
                }
                .also { parsed ->
                    if (parsed.map { it.first }.distinct().size != parsed.size) {
                        invalid("Journey has duplicate Products")
                    }
                }
                .toMap()

            val requests = release.descriptor.array("placements")
                .mapIndexedNotNull { index, element ->
                    val placement = element as? JsonObject
                        ?: invalid("Placement[$index] is not an object")
                    val placementId = placement.requiredString("id", "Placement[$index]")
                    val productId = placement.requiredString("productId", "Placement '$placementId'")
                    val product = products[productId]
                        ?: invalid("Placement '$placementId' references unknown Product '$productId'")
                    if (product.platform != GOOGLE_PLAY) return@mapIndexedNotNull null
                    val play = placement["googlePlay"]
                    val offerSelection = when (play) {
                        null, JsonNull -> OfferSelection.None
                        is JsonObject -> OfferSelection.Exact(
                            play.requiredString("offerId", "Placement '$placementId' Google Play selection"),
                        )
                        else -> invalid("Placement '$placementId' has invalid Google Play selection")
                    }
                    CatalogProductRequest(
                        productId = productId,
                        storeProductId = product.storeProductId,
                        productType = product.billingType,
                        basePlanId = product.basePlanId,
                        purchaseOptionId = product.purchaseOptionId,
                        offerSelection = offerSelection,
                        placementId = placementId,
                        consumable = product.type == CONSUMABLE,
                        featureAllowances = product.featureAllowances,
                        experienceId = release.identity.experienceId,
                        experienceVersion = release.identity.experienceVersionId,
                    )
                }
            if (requests.mapNotNull(CatalogProductRequest::placementId).distinct().size != requests.size) {
                invalid("Journey has duplicate Google Play Placements")
            }
            return JourneyProductCatalog(requests)
        }

        private fun parseProduct(id: String, product: JsonObject): Product {
            val type = product.requiredString("type", "Product '$id'")
            if (type !in PRODUCT_TYPES) invalid("Product '$id' has unsupported type '$type'")
            val store = product["store"] as? JsonObject
                ?: invalid("Product '$id' has no store identity")
            val platform = store.requiredString("platform", "Product '$id' store")
            val storeProductId = store.requiredString("productId", "Product '$id' store")
            if (platform != GOOGLE_PLAY) {
                return Product(
                    type = type,
                    platform = platform,
                    storeProductId = storeProductId,
                    billingType = "",
                    basePlanId = null,
                    purchaseOptionId = null,
                    featureAllowances = emptyList(),
                )
            }
            val storeType = store.requiredString("productType", "Product '$id' store")
            if (storeType != type) invalid("Product '$id' type does not match its Play identity")
            val basePlanId = store.optionalString("basePlanId", "Product '$id' store")
            val purchaseOptionId = store.optionalString("purchaseOptionId", "Product '$id' store")
            when (type) {
                SUBSCRIPTION -> if (basePlanId.isNullOrBlank() || purchaseOptionId != null) {
                    invalid("Play subscription Product '$id' requires one exact base plan")
                }
                else -> if (basePlanId != null) {
                    invalid("Play one-time Product '$id' cannot use a base plan")
                }
            }
            return Product(
                type = type,
                platform = platform,
                storeProductId = storeProductId,
                billingType = if (type == SUBSCRIPTION) {
                    BillingClient.ProductType.SUBS
                } else {
                    BillingClient.ProductType.INAPP
                },
                basePlanId = basePlanId,
                purchaseOptionId = purchaseOptionId,
                featureAllowances = parseAllowances(id, product),
            )
        }

        private fun parseAllowances(productId: String, product: JsonObject): List<FeatureAllowance> =
            product.array("entitlements").flatMapIndexed { index, element ->
                val entitlement = element as? JsonObject
                    ?: invalid("Product '$productId' Entitlement[$index] is not an object")
                val entitlementId = entitlement.requiredString(
                    "id",
                    "Product '$productId' Entitlement[$index]",
                )
                val featureId = entitlement.optionalString("featureId", "Entitlement '$entitlementId'")
                    ?: entitlementId
                val externalId = entitlement.optionalString(
                    "featureExternalId",
                    "Entitlement '$entitlementId'",
                )
                val allowanceType = entitlement.optionalString(
                    "allowanceType",
                    "Entitlement '$entitlementId'",
                )
                val allowance = (entitlement["allowance"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }
                    ?.doubleOrNull
                val primary = FeatureAllowance.fromDescriptor(
                    featureId,
                    externalId,
                    allowanceType,
                    allowance,
                )
                val usage = entitlement.array("purchaseUsageFeatureIds").map { value ->
                    val id = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        ?: invalid("Entitlement '$entitlementId' has an invalid purchase usage feature")
                    FeatureAllowance.fromDescriptor(id, null, allowanceType, allowance)
                }
                listOf(primary) + usage
            }.distinctBy(FeatureAllowance::featureId)

        private data class Product(
            val type: String,
            val platform: String,
            val storeProductId: String,
            val billingType: String,
            val basePlanId: String?,
            val purchaseOptionId: String?,
            val featureAllowances: List<FeatureAllowance>,
        )
    }
}

/** One presented Journey's exact, live Play products and checkout operations. */
internal class JourneyCommerceSession(
    products: List<StoreProduct>,
    private val purchases: PurchaseService,
) {
    val products: List<StoreProduct> = products.toList()
    private val productsByPlacement = this.products.associateBy(StoreProduct::placementId)

    suspend fun purchase(
        activity: Activity,
        placementId: String,
        correlation: CommerceOutcomeCorrelation,
    ): PurchaseResult {
        val product = productsByPlacement[placementId]
            ?: return PurchaseResult.Failed(
                JourneyCommerceException("No live Play product for Placement '$placementId'"),
            )
        return withContext(Dispatchers.Main.immediate) {
            purchases.purchase(
                activity = activity,
                product = product,
                replacement = null,
                expectedOwnerDistinctId = correlation.distinctId,
                outcomeCorrelation = correlation,
            )
        }
    }

    suspend fun restore(correlation: CommerceOutcomeCorrelation): RestoreResult =
        purchases.restorePurchases(
            expectedOwnerDistinctId = correlation.distinctId,
            outcomeCorrelation = correlation,
        )
}

internal fun interface JourneyCommercePreparing {
    suspend fun prepare(release: AuthenticatedJourneyRelease): JourneyCommerceSession?

    companion object {
        val NONE = JourneyCommercePreparing { null }
    }
}

internal class JourneyCommercePreparer(
    private val resolver: ProductResolver,
    private val purchases: PurchaseService,
) : JourneyCommercePreparing {
    override suspend fun prepare(release: AuthenticatedJourneyRelease): JourneyCommerceSession? {
        val requests = JourneyProductCatalog.parse(release).requests
        return JourneyCommerceSession(resolver.resolve(requests), purchases)
    }
}

/** Register signed mappings before out-of-band Play evidence can arrive. */
internal fun PurchaseEvidenceStore.registerJourneyProductMappings(
    release: AuthenticatedJourneyRelease,
): Boolean = JourneyProductCatalog.parse(release).requests.all { request ->
    upsertProductMapping(
        StoredProductMapping(
            storeProductId = request.storeProductId,
            nuxieProductId = request.productId,
            basePlanId = request.basePlanId,
            purchaseOptionId = request.purchaseOptionId,
            offerId = (request.offerSelection as? OfferSelection.Exact)?.offerId,
            productType = request.productType,
            consumable = request.consumable,
            context = StoredPurchaseContext(
                placementId = request.placementId,
                experienceId = request.experienceId,
                experienceVersion = request.experienceVersion,
            ),
            featureAllowances = request.featureAllowances.map { allowance ->
                StoredFeatureAllowance(
                    featureId = allowance.featureId,
                    type = allowance.type.name,
                    unlimited = allowance.unlimited,
                    allowance = allowance.allowance,
                )
            },
            licensingPublicKey = request.licensingPublicKey,
        ),
    )
}

private fun JsonObject.array(key: String): JsonArray =
    this[key] as? JsonArray ?: invalid("Journey has no '$key' array")

private fun JsonObject.requiredString(key: String, owner: String): String =
    (this[key] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.takeIf(String::isNotBlank)
        ?: invalid("$owner has no '$key'")

private fun JsonObject.optionalString(key: String, owner: String): String? {
    val value = this[key] ?: return null
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.takeIf(String::isNotBlank)
        ?: invalid("$owner has invalid '$key'")
}

private fun invalid(message: String): Nothing = throw JourneyCommerceException(message)

private const val GOOGLE_PLAY = "google_play"
private const val SUBSCRIPTION = "subscription"
private const val CONSUMABLE = "consumable"
private val PRODUCT_TYPES = setOf(SUBSCRIPTION, CONSUMABLE, "nonConsumable")
