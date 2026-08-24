package ai.nuxie.sdk.network

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.SdkVersion
import java.io.IOException
import java.net.URL

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

    /** Opaque conditional-fetch validator (the response ETag, scoped to /profile). */
    class ProfileCacheValidator(val rawValue: String)

    sealed interface ProfileFetchResult {
        /** Fresh profile JSON text plus the next conditional validator, if any. */
        class Modified(val bodyText: String, val validator: ProfileCacheValidator?) : ProfileFetchResult
        object NotModified : ProfileFetchResult
    }

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
