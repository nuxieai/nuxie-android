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
