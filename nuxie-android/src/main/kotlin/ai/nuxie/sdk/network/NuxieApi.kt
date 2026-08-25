package ai.nuxie.sdk.network

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.SdkVersion
import ai.nuxie.sdk.events.CanonicalJson
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.features.FeatureType
import java.io.IOException
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The API client, ported from the iOS `NuxieApi`: all POST, gzip request
 * bodies, `Nuxie-Android-SDK/<version>` user agent. Only `/batch` exists in
 * this slice; other endpoints arrive with their subsystems.
 */
internal class NuxieApi(
    private val apiKey: String,
    environment: NuxieEnvironment,
    private val transport: HttpTransport = HttpUrlConnectionTransport(),
) {
    private val baseUrl: String = when (environment) {
        NuxieEnvironment.PRODUCTION -> "https://i.nuxie.ai"
        NuxieEnvironment.DEVELOPMENT -> "https://dev-i.nuxie.ai"
    }

    class BatchRejectedException(val statusCode: Int) :
        IOException("Batch rejected with status $statusCode")

    class RequestRejectedException(val statusCode: Int, endpoint: String) :
        IOException("$endpoint rejected with status $statusCode")

    class PurchaseRejectedException(
        val statusCode: Int,
        val permanent: Boolean,
    ) : IOException("/purchase rejected with status $statusCode")

    data class PlayPurchaseReport(
        val packageName: String,
        val productId: String,
        val purchaseToken: String,
        val basePlanId: String?,
        val offerId: String?,
        val obfuscatedAccountId: String?,
        val distinctId: String,
    )

    data class FeatureUseEventData(
        val value: Double,
        val properties: Map<String, Any?>?,
    )

    data class PlayPurchaseUseReport(
        val packageName: String,
        val productId: String,
        val purchaseToken: String,
        val basePlanId: String?,
        val offerId: String?,
        val obfuscatedAccountId: String?,
        val distinctId: String,
        val eventId: String,
    )

    data class PurchaseBackedFeatureUseReport(
        val customerId: String,
        val featureId: String,
        val requiredBalance: Double,
        val eventData: FeatureUseEventData,
        val entityId: String?,
        val purchase: PlayPurchaseUseReport,
    )

    data class PurchaseResponse(
        val body: kotlinx.serialization.json.JsonObject,
        val success: Boolean,
        val customerId: String?,
    )

    /** Opaque conditional-fetch validator (the response ETag, scoped to /profile). */
    class ProfileCacheValidator(val rawValue: String)

    sealed interface ProfileFetchResult {
        /** Fresh profile JSON text plus the next conditional validator, if any. */
        class Modified(val bodyText: String, val validator: ProfileCacheValidator?) : ProfileFetchResult
        object NotModified : ProfileFetchResult
    }

    /** The server-authoritative response returned by POST /entitled. */
    class FeatureCheckResult(
        val customerId: String,
        val featureId: String,
        val requiredBalance: Double,
        val code: String,
        val allowed: Boolean,
        val unlimited: Boolean,
        val balance: Double?,
        val type: FeatureType,
    )

    /**
     * POST /profile with an optional If-None-Match conditional (iOS parity:
     * 304 without a validator is an invalid response). The body text is
     * duplicate-key validated and bounded by the transport read cap; callers
     * parse it.
     */
    fun fetchProfile(
        distinctId: String,
        locale: String?,
        revalidating: ProfileCacheValidator? = null,
    ): ProfileFetchResult {
        val body = buildString {
            append("{\"apiKey\":")
            append(jsonString(apiKey))
            append(",\"distinct_id\":")
            append(jsonString(distinctId))
            if (locale != null) {
                append(",\"locale\":")
                append(jsonString(locale))
            }
            append(",\"version\":1}")
        }.encodeToByteArray()

        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept-Encoding", "gzip")
            put("User-Agent", "Nuxie-Android-SDK/${SdkVersion.VALUE}")
            revalidating?.let { put("If-None-Match", it.rawValue) }
        }
        val response = transport.execute(
            HttpTransport.Request(url = URL("$baseUrl/profile"), headers = headers, body = body),
        )
        if (response.statusCode == 304) {
            if (revalidating == null) throw IOException("304 without a validator")
            return ProfileFetchResult.NotModified
        }
        if (response.statusCode !in 200..299) {
            throw RequestRejectedException(response.statusCode, "/profile")
        }
        val text = response.body.decodeToString()
        StrictJsonValidator.requireNoDuplicateKeys(text)
        return ProfileFetchResult.Modified(
            bodyText = text,
            validator = response.header("ETag")?.let(::ProfileCacheValidator),
        )
    }

    /**
     * Post pre-encoded batch items (canonical JSON text from the
     * conformance-tested encoder; assembled by concatenation so item bytes
     * reach the wire exactly as encoded). Returns normally on 2xx ack.
     *
     * @throws IOException on transport failure (retryable)
     * @throws BatchRejectedException on a non-2xx response
     */
    fun postBatch(encodedItems: List<String>) {
        require(encodedItems.isNotEmpty()) { "postBatch requires at least one item." }
        val body = buildString {
            // iOS parity: every POST body carries camel-cased "apiKey".
            append("{\"apiKey\":")
            append(jsonString(apiKey))
            append(",\"batch\":[")
            encodedItems.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append(item)
            }
            append("]}")
        }.encodeToByteArray()

        val response = transport.execute(
            HttpTransport.Request(
                url = URL("$baseUrl/batch"),
                // iOS parity: request bodies are NOT gzip-compressed in
                // production (NuxieCore passes useGzipCompression: false);
                // responses accept gzip.
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept-Encoding" to "gzip",
                    "User-Agent" to "Nuxie-Android-SDK/${SdkVersion.VALUE}",
                ),
                body = body,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw BatchRejectedException(response.statusCode)
        }
    }

    /**
     * POST /event: the synchronous decision lane. The body is the batch-item
     * projection of the captured event (same encoder, same lift rules) plus
     * apiKey. Returns the duplicate-key-validated response body text.
     */
    fun postEvent(encodedBatchItem: String): String {
        require(encodedBatchItem.startsWith("{")) { "postEvent expects an encoded batch item." }
        val body = buildString {
            append("{\"apiKey\":")
            append(jsonString(apiKey))
            append(',')
            append(encodedBatchItem, 1, encodedBatchItem.length)
        }.encodeToByteArray()
        val response = transport.execute(
            HttpTransport.Request(
                url = URL("$baseUrl/event"),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept-Encoding" to "gzip",
                    "User-Agent" to "Nuxie-Android-SDK/${SdkVersion.VALUE}",
                ),
                body = body,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw RequestRejectedException(response.statusCode, "/event")
        }
        val text = response.body.decodeToString()
        StrictJsonValidator.requireNoDuplicateKeys(text)
        return text
    }

    /** POST /entitled using the iOS FeatureCheckRequest body shape. */
    fun checkFeature(
        customerId: String,
        featureId: String,
        requiredBalance: Double?,
        entityId: String?,
    ): FeatureCheckResult {
        val body = buildString {
            append("{\"apiKey\":")
            append(jsonString(apiKey))
            append(",\"customerId\":")
            append(jsonString(customerId))
            append(",\"featureId\":")
            append(jsonString(featureId))
            requiredBalance?.let { append(",\"requiredBalance\":").append(it) }
            entityId?.let { append(",\"entityId\":").append(jsonString(it)) }
            append('}')
        }.encodeToByteArray()
        val response = transport.execute(
            HttpTransport.Request(
                url = URL("$baseUrl/entitled"),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept-Encoding" to "gzip",
                    "User-Agent" to "Nuxie-Android-SDK/${SdkVersion.VALUE}",
                ),
                body = body,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw RequestRejectedException(response.statusCode, "/entitled")
        }
        val text = response.body.decodeToString()
        StrictJsonValidator.requireNoDuplicateKeys(text)
        val parsed = Json.parseToJsonElement(text).jsonObject
        val customerId = parsed.requiredString("customerId", "/entitled response")
        val responseFeatureId = parsed.requiredString("featureId", "/entitled response")
        val requiredResponseBalance = parsed.requiredDouble("requiredBalance", "/entitled response")
        val code = parsed.requiredString("code", "/entitled response")
        val type = (parsed["type"] as? JsonPrimitive)?.content?.toFeatureType()
            ?: throw IOException("/entitled response has an invalid feature type")
        val allowed = (parsed["allowed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: throw IOException("/entitled response is missing allowed")
        val unlimited = (parsed["unlimited"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: throw IOException("/entitled response is missing unlimited")
        val balance = (parsed["balance"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        return FeatureCheckResult(
            customerId,
            responseFeatureId,
            requiredResponseBalance,
            code,
            allowed,
            unlimited,
            balance,
            type,
        )
    }

    /** POST /entitled with an unreconciled Play purchase and its first spend. */
    fun useFeatureWithPurchase(report: PurchaseBackedFeatureUseReport): FeatureCheckResult {
        val body = buildString {
            append("{\"apiKey\":").append(jsonString(apiKey))
            append(",\"customerId\":").append(jsonString(report.customerId))
            append(",\"featureId\":").append(jsonString(report.featureId))
            append(",\"requiredBalance\":").append(report.requiredBalance)
            append(",\"eventData\":{\"value\":").append(report.eventData.value)
            report.eventData.properties?.let { properties ->
                append(",\"properties\":")
                append(CanonicalJson.encode(JsonValueConverter.fromMap(properties)))
            }
            append('}')
            report.entityId?.let { append(",\"entityId\":").append(jsonString(it)) }
            append(",\"purchase\":{\"type\":\"playstore\"")
            append(",\"packageName\":").append(jsonString(report.purchase.packageName))
            append(",\"productId\":").append(jsonString(report.purchase.productId))
            append(",\"purchaseToken\":").append(jsonString(report.purchase.purchaseToken))
            report.purchase.basePlanId?.let { append(",\"basePlanId\":").append(jsonString(it)) }
            report.purchase.offerId?.let { append(",\"offerId\":").append(jsonString(it)) }
            report.purchase.obfuscatedAccountId?.let {
                append(",\"obfuscatedAccountId\":").append(jsonString(it))
            }
            append(",\"distinctId\":").append(jsonString(report.purchase.distinctId))
            append(",\"eventId\":").append(jsonString(report.purchase.eventId))
            append("}}")
        }.encodeToByteArray()
        val response = transport.execute(
            HttpTransport.Request(
                url = URL("$baseUrl/entitled"),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept-Encoding" to "gzip",
                    "User-Agent" to "Nuxie-Android-SDK/${SdkVersion.VALUE}",
                ),
                body = body,
            ),
        )
        if (response.statusCode !in 200..299) {
            throw RequestRejectedException(response.statusCode, "/entitled")
        }
        val text = response.body.decodeToString()
        StrictJsonValidator.requireNoDuplicateKeys(text)
        val parsed = Json.parseToJsonElement(text).jsonObject
        val customerId = parsed.requiredString("customerId", "/entitled response")
        val featureId = parsed.requiredString("featureId", "/entitled response")
        val code = parsed.requiredString("code", "/entitled response")
        val type = (parsed["type"] as? JsonPrimitive)?.content?.toFeatureType()
            ?: throw IOException("/entitled response has an invalid feature type")
        val allowed = (parsed["allowed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: throw IOException("/entitled response is missing allowed")
        val unlimited = (parsed["unlimited"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: throw IOException("/entitled response is missing unlimited")
        val balance = (parsed["balance"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        return FeatureCheckResult(
            customerId = customerId,
            featureId = featureId,
            requiredBalance = report.requiredBalance,
            code = code,
            allowed = allowed,
            unlimited = unlimited,
            balance = balance,
            type = type,
        )
    }

    /**
     * Submit Play evidence for server-authoritative verification. UNIV-2632
     * supplies the server-side Play Developer API arm served by this contract.
     */
    fun postPurchase(report: PlayPurchaseReport): PurchaseResponse {
        val body = buildString {
            append("{\"apiKey\":").append(jsonString(apiKey))
            append(",\"type\":\"playstore\"")
            append(",\"packageName\":").append(jsonString(report.packageName))
            append(",\"productId\":").append(jsonString(report.productId))
            append(",\"purchaseToken\":").append(jsonString(report.purchaseToken))
            report.basePlanId?.let { append(",\"basePlanId\":").append(jsonString(it)) }
            report.offerId?.let { append(",\"offerId\":").append(jsonString(it)) }
            report.obfuscatedAccountId?.let {
                append(",\"obfuscatedAccountId\":").append(jsonString(it))
            }
            append(",\"distinctId\":").append(jsonString(report.distinctId))
            append('}')
        }.encodeToByteArray()
        val response = transport.execute(
            HttpTransport.Request(
                url = URL("$baseUrl/purchase"),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept-Encoding" to "gzip",
                    "User-Agent" to "Nuxie-Android-SDK/${SdkVersion.VALUE}",
                ),
                body = body,
            ),
        )
        if (response.statusCode !in 200..299) {
            val permanent = response.statusCode in 400..499 && response.statusCode !in setOf(408, 429)
            throw PurchaseRejectedException(response.statusCode, permanent)
        }
        val text = response.body.decodeToString().ifBlank { "{}" }
        StrictJsonValidator.requireNoDuplicateKeys(text)
        val parsed = Json.parseToJsonElement(text).jsonObject
        val success = (parsed["success"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: throw IOException("/purchase response is missing success")
        val customerId = ((parsed["customer_id"] ?: parsed["customerId"]) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        return PurchaseResponse(parsed, success, customerId)
    }

    private fun kotlinx.serialization.json.JsonObject.requiredString(key: String, context: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IOException("$context is missing $key")

    private fun kotlinx.serialization.json.JsonObject.requiredDouble(key: String, context: String): Double =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: throw IOException("$context is missing $key")

    private fun String.toFeatureType(): FeatureType? = when (this) {
        "boolean" -> FeatureType.BOOLEAN
        "metered" -> FeatureType.METERED
        "creditSystem" -> FeatureType.CREDIT_SYSTEM
        else -> null
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
