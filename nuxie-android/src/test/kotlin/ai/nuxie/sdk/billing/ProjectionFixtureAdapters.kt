package ai.nuxie.sdk.billing

import ai.nuxie.sdk.features.FeatureAllowance
import ai.nuxie.sdk.features.FeatureType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapters from the cross-SDK optimistic-entitlement-projection fixture's
 * portable shapes to this SDK's projection inputs. The fixture carries RAW
 * signed-descriptor fields and routes them through the production classifier
 * so both SDKs must agree on the derivation (nil allowanceType is boolean;
 * "fixed" and "unlimited" are balance-bearing).
 */
internal object ProjectionFixtureAdapters {
    const val AUTHORITY_SCOPE = "scope-a"

    fun evidence(element: JsonElement?): List<PurchaseEvidence> =
        element.unlessNull()?.jsonArray.orEmpty().map { evidenceElement ->
            val evidence = evidenceElement.jsonObject
            val backendSynced = evidence.getValue("backendSynced").jsonPrimitive.content.toBoolean()
            val transactionId = evidence.getValue("transactionId").jsonPrimitive.content
            val owner = evidence.getValue("distinctId").jsonPrimitive.content
            PurchaseEvidence(
                purchaseToken = transactionId,
                packageName = "com.example.fixture",
                storeProductIds = listOf(transactionId),
                nuxieProductId = transactionId,
                purchaseState = StoredPurchaseState.PURCHASED,
                syncAttributionDistinctId = owner,
                ownerDistinctId = owner,
                acknowledged = false,
                firstSeenMillis = 1L,
                catalogResolved = true,
                signatureVerificationRequired = true,
                signatureVerified = true,
                authorityScope = AUTHORITY_SCOPE,
                revoked = evidence.getValue("revoked").jsonPrimitive.content.toBoolean(),
                backendSyncedAtMillis = 1L.takeIf { backendSynced },
            )
        }

    fun descriptors(element: JsonElement?): List<StoredProductMapping> =
        element.unlessNull()?.jsonObject.orEmpty().map { (transactionId, allowancesElement) ->
            StoredProductMapping(
                storeProductId = transactionId,
                nuxieProductId = transactionId,
                productType = "inapp",
                consumable = false,
                featureAllowances = allowancesElement.jsonArray.map { allowanceElement ->
                    val allowance = allowanceElement.jsonObject
                    val classified = FeatureAllowance.fromDescriptor(
                        featureId = allowance.getValue("featureId").jsonPrimitive.content,
                        featureExternalId = allowance["featureExternalId"].unlessNull()
                            ?.jsonPrimitive?.content,
                        allowanceType = allowance["allowanceType"].unlessNull()
                            ?.jsonPrimitive?.content,
                        allowance = allowance["allowance"].unlessNull()
                            ?.jsonPrimitive?.content?.toDouble(),
                    )
                    StoredFeatureAllowance(
                        featureId = classified.featureId,
                        type = classified.type.name,
                        unlimited = classified.unlimited,
                        allowance = classified.allowance,
                    )
                },
            )
        }

    fun expectedProjection(
        element: JsonElement?,
    ): Map<String, OptimisticFeatureOverlay>? = element.unlessNull()?.jsonObject?.mapValues { (_, value) ->
        val expected = value.jsonObject
        val type = featureType(expected.getValue("kind").jsonPrimitive.content)
        OptimisticFeatureOverlay(
            type = type,
            unlimited = expected["unlimited"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            balanceIncrease = expected["allowance"]?.jsonPrimitive?.content?.toDouble()
                ?.takeUnless { type == FeatureType.BOOLEAN },
        )
    }

    fun featureType(kind: String): FeatureType = when (kind) {
        "boolean" -> FeatureType.BOOLEAN
        "metered" -> FeatureType.METERED
        "credit_system", "creditSystem" -> FeatureType.CREDIT_SYSTEM
        else -> error("Unsupported fixture Feature kind: $kind")
    }

    fun JsonElement?.unlessNull(): JsonElement? = this?.takeUnless { it is JsonNull }
}
