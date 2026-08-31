package ai.nuxie.sdk.billing

import ai.nuxie.sdk.experiences.AuthenticatedRelease
import com.android.billingclient.api.BillingClient

/** Convert already-classified signed release allowances into durable Play lookup mappings. */
internal fun AuthenticatedRelease.storedGooglePlayProductMappings(): List<StoredProductMapping> =
    googlePlayProductAllowances.map { product ->
        StoredProductMapping(
            storeProductId = product.storeProductId,
            nuxieProductId = product.productId,
            productType = when (product.productType) {
                "subscription" -> BillingClient.ProductType.SUBS
                "consumable", "nonConsumable" -> BillingClient.ProductType.INAPP
                else -> error("Authenticated release contained an unclassified Product type.")
            },
            consumable = product.productType == "consumable",
            featureAllowances = product.featureAllowances.map { allowance ->
                StoredFeatureAllowance(
                    featureId = allowance.featureId,
                    type = allowance.type.name,
                    unlimited = allowance.unlimited,
                    allowance = allowance.allowance,
                )
            },
        )
    }

/** Register each Product separately so the store's first-resolution pin linearization is preserved. */
internal fun PurchaseEvidenceStore.registerAuthenticatedReleaseProductMappings(
    release: AuthenticatedRelease,
): Boolean {
    var registered = true
    release.storedGooglePlayProductMappings().forEach { mapping ->
        if (!upsertProductMapping(mapping)) registered = false
    }
    return registered
}
