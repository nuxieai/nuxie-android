package ai.nuxie.sdk.features

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical wrapper projections pinned by fixtures/encodings/feature-usage.json.
 *
 * Wrapper bridges bind the public model fields; this encoder keeps that
 * projection in one place so conformance tests detect drift in names, nulls,
 * numeric precision, or enum spellings.
 */
internal object FeatureWireEncoder {
    fun encode(result: FeatureUsageResult): JsonObject = JsonObject(
        linkedMapOf(
            "success" to JsonPrimitive(result.success),
            "featureId" to JsonPrimitive(result.featureId),
            "amountUsed" to JsonPrimitive(result.amountUsed),
            "message" to result.message.toJsonPrimitive(),
            "usage" to (result.usage?.let(::encode) ?: JsonNull),
            "authoritativeAccess" to (
                result.authoritativeAccess?.let(::encode) ?: JsonNull
            ),
        ),
    )

    fun encode(access: FeatureAccess): JsonObject = JsonObject(
        linkedMapOf(
            "allowed" to JsonPrimitive(access.allowed),
            "unlimited" to JsonPrimitive(access.unlimited),
            "balance" to access.balance.toJsonPrimitive(),
            "type" to JsonPrimitive(wireValue(access.type)),
        ),
    )

    fun wireValue(policy: FeatureCheckPolicy): String = when (policy) {
        FeatureCheckPolicy.CACHE_FIRST -> "cacheFirst"
        FeatureCheckPolicy.REMOTE -> "remote"
    }

    private fun encode(usage: FeatureUsageResult.UsageInfo): JsonObject = JsonObject(
        linkedMapOf(
            "current" to JsonPrimitive(usage.current),
            "limit" to usage.limit.toJsonPrimitive(),
            "remaining" to usage.remaining.toJsonPrimitive(),
        ),
    )

    private fun wireValue(type: FeatureType): String = when (type) {
        FeatureType.BOOLEAN -> "boolean"
        FeatureType.METERED -> "metered"
        FeatureType.CREDIT_SYSTEM -> "creditSystem"
    }

    private fun String?.toJsonPrimitive() = this?.let(::JsonPrimitive) ?: JsonNull

    private fun Double?.toJsonPrimitive() = this?.let(::JsonPrimitive) ?: JsonNull
}
