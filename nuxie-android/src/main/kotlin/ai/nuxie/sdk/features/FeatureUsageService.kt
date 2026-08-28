package ai.nuxie.sdk.features

import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.commerce.PurchaseService
import ai.nuxie.sdk.events.BatchItemWireEncoder
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.network.NuxieApi
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Immediate-confirmation metered Feature use with the commerce first-spend gate hidden inside. */
internal class FeatureUsageService(
    private val api: NuxieApi,
    private val purchases: PurchaseService,
    private val identity: IdentityProvider,
    private val featureInfo: FeatureInfo,
    private val eventLog: EventLog,
    private val scope: CoroutineScope,
) {
    fun useFeature(
        featureId: String,
        amount: Double,
        entityId: String?,
        metadata: Map<String, Any?>?,
    ) {
        val metadataSnapshot = metadata?.toMap()
        scope.launch {
            runCatching {
                useFeatureAndWait(
                    featureId,
                    amount,
                    entityId,
                    setUsage = false,
                    metadataSnapshot?.let(JsonValueConverter::fromMap),
                )
            }.onFailure { Log.w(LOG_TAG, "useFeature failed", it) }
        }
    }

    suspend fun useFeatureAndWait(
        featureId: String,
        amount: Double,
        entityId: String?,
        setUsage: Boolean,
        metadata: Map<String, Any?>?,
    ): FeatureUsageResult = useFeatureAndWait(
        featureId,
        amount,
        entityId,
        setUsage,
        metadata?.let(JsonValueConverter::fromMap),
    )

    private suspend fun useFeatureAndWait(
        featureId: String,
        amount: Double,
        entityId: String?,
        setUsage: Boolean,
        metadata: JsonObject?,
    ): FeatureUsageResult = withContext(Dispatchers.IO) {
        val distinctId = identity.distinctId()
        if (!setUsage) {
            purchases.useFeatureWithPendingPurchase(
                distinctId = distinctId,
                featureId = featureId,
                amount = amount,
                entityId = entityId,
                metadata = metadata?.toMap(),
            )?.let { result ->
                if (result.success) {
                    captureAcceptedUse(
                        featureId,
                        amount,
                        entityId,
                        metadata,
                        TimeBasedEpochGenerator.shared.next(),
                        distinctId,
                    )
                }
                return@withContext result
            }
        }
        ensureIdentity(distinctId)

        val properties = linkedMapOf<String, Any?>("feature_extId" to featureId)
        if (setUsage) properties["setUsage"] = true
        metadata?.let { properties["metadata"] = it }
        properties["value"] = amount
        entityId?.let { properties["entityId"] = it }
        val stored = StoredEvent.from(
            NuxieEvent(
                name = SystemEventNames.FEATURE_USED,
                distinctId = distinctId,
                properties = properties,
            ),
        )
        val response = Json.parseToJsonElement(
            api.postEvent(BatchItemWireEncoder.encode(stored)),
        ).jsonObject
        ensureIdentity(distinctId)

        val status = response.string("status")
            ?: throw IOException("/event response is missing status")
        val usage = (response["usage"] as? JsonObject)?.let { raw ->
            FeatureUsageResult.UsageInfo(
                current = raw.double("current")
                    ?: throw IOException("/event response usage is missing current"),
                limit = raw.double("limit"),
                remaining = raw.double("remaining"),
            )
        }
        usage?.remaining?.let { featureInfo.setBalance(featureId, it, entityId) }
        val accepted = status == "ok" || status == "success"
        if (accepted) {
            captureAcceptedUse(
                featureId,
                amount,
                entityId,
                metadata,
                response.string("eventId") ?: response.string("event_id") ?: stored.id,
                distinctId,
            )
        }
        FeatureUsageResult(
            success = accepted,
            featureId = featureId,
            amountUsed = amount,
            message = response.string("message"),
            usage = usage,
        )
    }

    private suspend fun captureAcceptedUse(
        featureId: String,
        amount: Double,
        entityId: String?,
        metadata: JsonObject?,
        eventId: String,
        distinctId: String,
    ) {
        val properties = linkedMapOf<String, Any?>(
            "feature_id" to featureId,
            "amount" to amount,
        )
        entityId?.let { properties["entity_id"] = it }
        metadata?.let { properties["metadata"] = it }
        eventLog.captureDeliveredIdempotently(
            SystemEventNames.FEATURE_USED,
            properties,
            eventId,
            distinctId,
        )
    }

    private fun ensureIdentity(expected: String) {
        if (identity.distinctId() != expected) throw CancellationException()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        const val LOG_TAG = "Nuxie"
    }
}
