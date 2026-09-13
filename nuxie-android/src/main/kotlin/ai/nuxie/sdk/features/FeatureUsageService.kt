package ai.nuxie.sdk.features

import ai.nuxie.sdk.billing.PurchaseService
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.TimeBasedEpochGenerator
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.identity.IdentityScope
import ai.nuxie.sdk.network.NuxieApi
import android.util.Log
import java.io.IOException
import java.io.FileNotFoundException
import java.io.File
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
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

/** Immediate-confirmation metered Feature use with the purchase first-spend gate hidden inside. */
internal class FeatureUsageService(
    private val api: NuxieApi,
    private val purchases: PurchaseService,
    private val identity: IdentityProvider,
    private val features: FeatureService,
    private val eventLog: EventLog,
    private val scope: CoroutineScope,
    journalFile: File,
) {
    private val journal = AtomicFile(journalFile)
    private val journalMutex = Mutex()

    init { journalFile.parentFile?.mkdirs() }

    private fun loadCommands(): List<JsonObject> = try {
        (Json.parseToJsonElement(journal.openRead().use { it.readBytes().decodeToString() }) as JsonArray).map { it.jsonObject }
    } catch (_: FileNotFoundException) {
        emptyList()
    }

    private fun saveCommands(commands: List<JsonObject>) {
        val stream = journal.startWrite()
        try {
            stream.write(JsonArray(commands).toString().encodeToByteArray())
            journal.finishWrite(stream)
        } catch (error: Throwable) {
            journal.failWrite(stream)
            throw error
        }
    }

    private class PublicationDrain : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<PublicationDrain>
    }

    private suspend fun <T> withJournalDecision(decide: suspend (MutableList<FeatureInfo.Mutation>) -> T): T {
        val reentrant = currentCoroutineContext()[PublicationDrain] != null
        return withContext(NonCancellable) {
            val publications = mutableListOf<FeatureInfo.Mutation>()
            val result = runCatching { journalMutex.withLock { decide(publications) } }
            val drain: suspend () -> Unit = {
                withContext(PublicationDrain()) {
                    publications.forEach { features.publishStaged(it) }
                }
            }
            // A listener may await another command. Its publication follows the
            // current FIFO slot, so that nested command must return before draining.
            if (reentrant) scope.launch(NonCancellable) { drain() } else drain()
            result.getOrThrow()
        }
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        val operationIds = journalMutex.withLock { loadCommands().map { it.getValue("command").jsonObject.getValue("operationId") } }
        for (operationId in operationIds) {
            try {
                withJournalDecision { publications ->
                    loadCommands().firstOrNull { it.getValue("command").jsonObject["operationId"] == operationId }
                        ?.let { deliver(it, publications) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(LOG_TAG, "Feature command remains pending", error)
            }
        }
    }

    private suspend fun deliver(record: JsonObject, publications: MutableList<FeatureInfo.Mutation>): FeatureUsageResult {
        val command = record.getValue("command").jsonObject
        val operationId = command.getValue("operationId")
        val response = (record["response"] as? JsonObject) ?: try { api.consumeFeature(command) } catch (error: NuxieApi.RequestRejectedException) {
            if (error.statusCode in listOf(400, 401, 403, 404, 410, 413)) {
                saveCommands(loadCommands().filterNot { it["command"]?.jsonObject?.get("operationId") == operationId })
            }
            throw error
        }.also { result ->
            saveCommands(loadCommands().map { if (it["command"]?.jsonObject?.get("operationId") == operationId)
                JsonObject(it + ("response" to result)) else it })
        }
        val accepted = response["accepted"] == JsonPrimitive(true)
        val featureId = command.string("featureId")!!
        val amount = command.double("quantity")!!
        val entityId = command.string("entityId")
        val distinctId = command.string("customerId")!!
        if (accepted) {
            val current = identity.captureScope()
            if (record["response"] == null && current.distinctId == distinctId && response["idempotentReplay"] != JsonPrimitive(true)) response.double("balance")?.let {
                features.stageAuthoritativeUsageBalance(featureId, it, entityId, current)?.let(publications::add)
            }
            captureAcceptedUse(featureId, amount, entityId, record["metadata"] as? JsonObject,
                command.string("operationId")!!, distinctId)
        }
        saveCommands(loadCommands().filterNot { it["command"]?.jsonObject?.get("operationId") == operationId })
        return FeatureUsageResult(success = accepted, featureId = featureId, amountUsed = amount,
            message = response.string("code"), usage = null,
            authoritativeAccess = when (response.string("type")) {
                "boolean" -> FeatureType.BOOLEAN
                "metered" -> FeatureType.METERED
                "creditSystem" -> FeatureType.CREDIT_SYSTEM
                else -> null
            }?.let { FeatureAccess(response["active"] == JsonPrimitive(true), response["unlimited"] == JsonPrimitive(true), response.double("balance"), it) }
        ).also {
                it.consumptionReceipt = FeatureConsumptionResult(command.string("operationId")!!, accepted,
                    response.string("code")!!, amount, response.double("balance"), response["unlimited"] == JsonPrimitive(true),
                    response["active"] == JsonPrimitive(true), response["idempotentReplay"] == JsonPrimitive(true),
                    response.string("customerId") ?: distinctId, response.string("featureId") ?: featureId, response.double("occurredAtMs"))
            }
    }

    suspend fun consumeFeature(featureId: String, quantity: Double, operationId: String, entityId: String?): FeatureConsumptionResult {
        require(operationId.isNotEmpty() && operationId == operationId.trim() && operationId.length <= 256) {
            "A stable operation ID is required"
        }
        return useFeatureAndWait(featureId, quantity, entityId, false, null, operationId).consumptionReceipt
            ?: throw IOException("Feature command receipt is missing")
    }

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
        operationId: String? = null,
    ): FeatureUsageResult = withContext(Dispatchers.IO) {
        require(amount.isFinite() && amount > 0 && amount % 1.0 == 0.0 && amount <= 9_007_199_254_740_991.0) {
            "Feature quantity must be a positive exact integer"
        }
        val identityScope = identity.captureScope()
        val distinctId = identityScope.distinctId
        if (!setUsage && operationId == null) {
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
        ensureIdentity(identityScope)

        val commandId = operationId ?: TimeBasedEpochGenerator.shared.next()
        withJournalDecision { publications ->
            val record = identity.withCurrentScope(identityScope) {
                val fields = linkedMapOf("customerId" to JsonPrimitive(distinctId),
                    "featureId" to JsonPrimitive(featureId), "quantity" to JsonPrimitive(amount.toLong()))
                entityId?.let { fields["entityId"] = JsonPrimitive(it) }
                if (setUsage) fields["mode"] = JsonPrimitive("set_usage")
                if (operationId != null) {
                    loadCommands().firstOrNull { it["command"]?.jsonObject?.string("operationId") == operationId }?.let {
                        val pending = it.getValue("command").jsonObject
                        require(fields.all { (key, value) -> pending[key] == value } && pending["entityId"] == fields["entityId"] && pending["mode"] == fields["mode"]) {
                            "Operation ID conflicts with a pending command"
                        }
                    }
                }
                val existing = loadCommands().firstOrNull { record ->
                    val command = record.getValue("command").jsonObject
                    (command.string("operationId") == commandId) && fields.all { (key, value) -> command[key] == value } &&
                        command["entityId"] == fields["entityId"] && command["mode"] == fields["mode"] && record["metadata"] == metadata
                }
                existing ?: JsonObject(buildMap {
                    put("command", JsonObject(fields + ("operationId" to JsonPrimitive(commandId))))
                    metadata?.let { put("metadata", it) }
                }).also { saveCommands(loadCommands() + it) }
            } ?: throw CancellationException()
            val result = deliver(record, publications)
            ensureIdentity(identityScope)
            result
        }
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
        metadata?.let { properties["metadata"] = JsonValueConverter.toNativeMap(it) }
        if (!eventLog.captureDeliveredIdempotently(
            SystemEventNames.FEATURE_USED,
            properties,
            eventId,
            distinctId,
        )) throw IOException("Accepted Feature use could not be persisted locally")
    }

    private fun ensureIdentity(expected: IdentityScope) {
        if (!identity.isCurrentScope(expected)) throw CancellationException()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        const val LOG_TAG = "Nuxie"
    }
}
