package ai.nuxie.sdk.network

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.SdkVersion
import ai.nuxie.sdk.events.CanonicalJson
import ai.nuxie.sdk.events.JsonValueConverter
import ai.nuxie.sdk.features.FeatureType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private const val PROFILE_APP_ID_HEADER = "Nuxie-App-Id"
private const val PROFILE_APP_ENVIRONMENT_HEADER = "Nuxie-App-Environment"

/**
 * The API client, ported from the iOS `NuxieApi`. Ordinary captured events
 * use `/batch`; Feature consumption uses `/feature/consume`.
 * Requests carry the `Nuxie-Android-SDK/<version>` user agent.
 */
internal class NuxieApi(
    private val apiKey: String,
    environment: NuxieEnvironment,
    private val transport: HttpTransport = HttpUrlConnectionTransport(),
    baseUrlOverride: URL? = null,
) {
    private val baseUrl: String = baseUrlOverride?.let(::normalizedBaseUrl) ?: when (environment) {
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
        val productId: String?,
        val purchaseToken: String,
        val basePlanId: String?,
        val purchaseOptionId: String? = null,
        val offerId: String?,
        val productType: String? = null,
        val obfuscatedAccountId: String?,
        val distinctId: String,
    )

    private companion object {
        fun normalizedBaseUrl(value: URL): String {
            require(value.protocol == "http" || value.protocol == "https") {
                "apiEndpoint must use http or https."
            }
            return value.toExternalForm().trimEnd('/')
        }
    }

    data class FeatureUseEventData(
        val value: Double,
        val properties: Map<String, Any?>?,
    )

    data class PlayPurchaseUseReport(
        val packageName: String,
        val productId: String,
        val purchaseToken: String,
        val basePlanId: String?,
        val purchaseOptionId: String? = null,
        val offerId: String?,
        val productType: String? = null,
        val obfuscatedAccountId: String?,
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

    data class VerifiedCatalogProduct(
        val productId: String,
        val storeProductId: String,
        val basePlanId: String?,
        val purchaseOptionId: String?,
        val offerId: String?,
        val storeProductType: String,
    )

    data class PurchaseResponse(
        val body: JsonObject,
        val success: Boolean,
        val customerId: String?,
        val catalogProduct: VerifiedCatalogProduct?,
    )

    /** Opaque conditional validator bound to one profile resource and authority. */
    class ProfileCacheValidator(
        val rawValue: String,
        val resourceScope: String? = null,
        val authority: ProfileDeliveryAuthority? = null,
    )

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
        val idempotentReplay: Boolean = false,
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

        val resourceScope = "$baseUrl/profile"
        val scopedValidator = revalidating?.takeIf { it.resourceScope == resourceScope }
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept-Encoding", "gzip")
            put("User-Agent", "Nuxie-Android-SDK/${SdkVersion.VALUE}")
            scopedValidator?.let { put("If-None-Match", it.rawValue) }
        }
        val response = transport.execute(
            HttpTransport.Request(url = URL("$baseUrl/profile"), headers = headers, body = body),
        )
        if (response.statusCode == 304) {
            val expected = scopedValidator ?: throw IOException("304 without a scoped validator")
            if (expected.authority != null) {
                val returnedAuthority = profileAuthority(response)
                    ?: throw IOException("Canonical 304 omitted profile authority")
                val returnedValidator = profileValidator(
                    response = response,
                    resourceScope = resourceScope,
                    authority = returnedAuthority,
                )
                if (returnedAuthority != expected.authority ||
                    returnedValidator?.rawValue != expected.rawValue
                ) {
                    throw IOException("Canonical 304 changed profile authority")
                }
            }
            return ProfileFetchResult.NotModified
        }
        if (response.statusCode !in 200..299) {
            throw RequestRejectedException(response.statusCode, "/profile")
        }
        val text = response.body.decodeToString()
        StrictJsonValidator.requireNoDuplicateKeys(text)
        val authority = profileAuthority(response)
        val validator = profileValidator(response, resourceScope, authority)
        val canonical = runCatching {
            (Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)
                ?.get("schemaVersion") == JsonPrimitive("nuxie.journey-plane-profile.v1")
        }.getOrDefault(false)
        if (canonical && (authority == null || validator == null)) {
            throw IOException("Canonical profile omitted authenticated authority or validator")
        }
        return ProfileFetchResult.Modified(
            bodyText = text,
            validator = validator,
        )
    }

    private fun profileAuthority(response: HttpTransport.Response): ProfileDeliveryAuthority? {
        val appId = response.header(PROFILE_APP_ID_HEADER)
        val environment = response.header(PROFILE_APP_ENVIRONMENT_HEADER)
        if (appId == null && environment == null) return null
        if (appId == null || environment == null) {
            throw IOException("Incomplete profile authority")
        }
        return ProfileDeliveryAuthority(appId, environment).also {
            if (!it.isValid) throw IOException("Invalid profile authority")
        }
    }

    private fun profileValidator(
        response: HttpTransport.Response,
        resourceScope: String,
        authority: ProfileDeliveryAuthority?,
    ): ProfileCacheValidator? {
        val raw = response.header("ETag")?.trim()?.takeIf {
            it.isNotEmpty() && it.encodeToByteArray().size <= 256 && it.none(Char::isISOControl)
        } ?: return null
        val opaque = raw.removePrefix("W/")
        if (opaque.length < 2 || opaque.first() != '"' || opaque.last() != '"') return null
        return ProfileCacheValidator(raw, resourceScope, authority)
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
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Content-Encoding" to "gzip",
                    "Accept-Encoding" to "gzip",
                    "User-Agent" to "Nuxie-Android-SDK/${SdkVersion.VALUE}",
                ),
                body = gzip(body),
            ),
        )
        if (response.statusCode !in 200..299) {
            throw BatchRejectedException(response.statusCode)
        }
    }

    /**
     * POST /event: the synchronous Feature command lane. The body is the batch-item
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

    fun consumeFeature(command: JsonObject): JsonObject {
        val body = JsonObject(command + ("apiKey" to JsonPrimitive(apiKey))).toString().encodeToByteArray()
        val response = transport.execute(HttpTransport.Request(
            url = URL("$baseUrl/feature/consume"),
            headers = mapOf("Content-Type" to "application/json", "Accept-Encoding" to "gzip",
                "User-Agent" to "Nuxie-Android-SDK/${SdkVersion.VALUE}"), body = body,
        ))
        if (response.statusCode !in 200..299) throw RequestRejectedException(response.statusCode, "/feature/consume")
        val text = response.body.decodeToString()
        StrictJsonValidator.requireNoDuplicateKeys(text)
        val result = Json.parseToJsonElement(text).jsonObject
        for (key in listOf("operationId", "customerId", "featureId")) {
            if (result[key] != command[key]) throw IOException("Feature command receipt does not match $key")
        }
        if ((result["quantity"] as? JsonPrimitive)?.content?.toDoubleOrNull() !=
            (command["quantity"] as? JsonPrimitive)?.content?.toDoubleOrNull()) throw IOException("Feature command receipt quantity mismatch")
        for (key in listOf("accepted", "unlimited", "active", "idempotentReplay")) {
            if ((result[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == null) {
                throw IOException("Feature command receipt is missing $key")
            }
        }
        result.requiredString("code", "Feature command receipt")
        return result
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
            requiredBalance?.let { append(",\"requiredBalance\":").append(jsonNumber(it)) }
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

    /** Verify a pending Play purchase and commit its first spend atomically. */
    fun useFeatureWithPurchase(report: PurchaseBackedFeatureUseReport): FeatureCheckResult {
        require(report.eventData.value.isFinite() && report.eventData.value > 0 &&
            report.eventData.value % 1.0 == 0.0 && report.eventData.value <= 9_007_199_254_740_991.0) {
            "Feature quantity must be a positive exact integer"
        }
        val evidence = JsonObject(buildMap {
            put("type", JsonPrimitive("playstore"))
            put("purchaseToken", JsonPrimitive(report.purchase.purchaseToken))
            put("productId", JsonPrimitive(report.purchase.productId))
            report.purchase.basePlanId?.let { put("basePlanId", JsonPrimitive(it)) }
            report.purchase.purchaseOptionId?.let { put("purchaseOptionId", JsonPrimitive(it)) }
            report.purchase.offerId?.let { put("offerId", JsonPrimitive(it)) }
            report.purchase.productType?.let { put("productType", JsonPrimitive(it)) }
        })
        val command = JsonObject(buildMap {
            put("customerId", JsonPrimitive(report.customerId))
            put("featureId", JsonPrimitive(report.featureId))
            put("operationId", JsonPrimitive(report.purchase.eventId))
            put("quantity", JsonPrimitive(report.eventData.value.toLong()))
            report.entityId?.let { put("entityId", JsonPrimitive(it)) }
            put("purchase", evidence)
        })
        val result = consumeFeature(command)
        if (result["accepted"] != JsonPrimitive(true)) throw IOException("Feature purchase consumption denied")
        val type = (result["type"] as? JsonPrimitive)?.content?.toFeatureType()
            ?: throw IOException("Feature command receipt has an invalid type")
        return FeatureCheckResult(customerId = report.customerId, featureId = report.featureId,
            requiredBalance = report.requiredBalance, code = result.requiredString("code", "Feature command receipt"),
            allowed = result["active"] == JsonPrimitive(true), unlimited = result["unlimited"] == JsonPrimitive(true),
            balance = (result["balance"] as? JsonPrimitive)?.content?.toDoubleOrNull(), type = type,
            idempotentReplay = result["idempotentReplay"] == JsonPrimitive(true))
    }

    /**
     * Submit Play evidence for server-authoritative verification. UNIV-2632
     * supplies the server-side Play Developer API arm served by this contract.
     */
    fun postPurchase(report: PlayPurchaseReport): PurchaseResponse {
        val body = buildString {
            append("{\"apiKey\":").append(jsonString(apiKey))
            append(",\"type\":\"playstore\"")
            append(",\"purchase_token\":").append(jsonString(report.purchaseToken))
            report.productId?.let { append(",\"product_id\":").append(jsonString(it)) }
            report.basePlanId?.let { append(",\"base_plan_id\":").append(jsonString(it)) }
            report.purchaseOptionId?.let {
                append(",\"purchase_option_id\":").append(jsonString(it))
            }
            report.offerId?.let { append(",\"offer_id\":").append(jsonString(it)) }
            report.productType?.let { append(",\"product_type\":").append(jsonString(it)) }
            report.obfuscatedAccountId?.let {
                append(",\"obfuscated_account_id\":").append(jsonString(it))
            }
            append(",\"distinct_id\":").append(jsonString(report.distinctId))
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
        val catalog = parsed["catalog_product"] as? JsonObject
        if (success && catalog == null) {
            throw IOException("/purchase response is missing catalog_product")
        }
        val storeProductType = catalog?.requiredString(
            "store_product_type",
            "/purchase catalog_product",
        )?.takeIf { it in setOf("subscription", "consumable", "nonConsumable") }
        if (catalog != null && storeProductType == null) {
            throw IOException("/purchase catalog_product has an invalid store_product_type")
        }
        return PurchaseResponse(
            body = parsed,
            success = success,
            customerId = customerId,
            catalogProduct = catalog?.let {
                VerifiedCatalogProduct(
                    productId = it.requiredString("id", "/purchase catalog_product"),
                    storeProductId = it.requiredString(
                        "store_product_id",
                        "/purchase catalog_product",
                    ),
                    basePlanId = it.nullableString("base_plan_id", "/purchase catalog_product"),
                    purchaseOptionId = it.nullableString(
                        "purchase_option_id",
                        "/purchase catalog_product",
                    ),
                    offerId = it.nullableString("offer_id", "/purchase catalog_product"),
                    storeProductType = checkNotNull(storeProductType),
                )
            },
        )
    }

    private fun JsonObject.nullableString(key: String, context: String): String? =
        when (val value = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.content
                ?: throw IOException("$context has an invalid $key")
            else -> throw IOException("$context has an invalid $key")
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

    // Whole quantities must use integer JSON spelling for the ingest contract.
    // BigDecimal preserves the Double's decimal value without truncating fractions.
    private fun jsonNumber(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

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

private fun gzip(body: ByteArray): ByteArray =
    ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(body) }
        output.toByteArray()
    }
