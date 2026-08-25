package ai.nuxie.sdk.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transport seam under [NuxieApi]. Production uses [HttpUrlConnectionTransport];
 * tests inject an in-process fake (deps policy: no OkHttp, no MockWebServer).
 */
internal fun interface HttpTransport {
    class Request(
        val url: URL,
        val headers: Map<String, String>,
        /** Body bytes exactly as they should leave the socket (already gzipped when so declared). */
        val body: ByteArray,
        val method: String = "POST",
        val followRedirects: Boolean = true,
    )

    class Response(
        val statusCode: Int,
        val body: ByteArray,
        headers: Map<String, String> = emptyMap(),
        val finalUrl: URL? = null,
    ) {
        /** Case-insensitive header map (HTTP header names are case-insensitive). */
        val headers: Map<String, String> =
            headers.entries.associate { (name, value) -> name.lowercase() to value }

        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** A closeable response body used by artifact acquisition without buffering it first. */
    class StreamingResponse(
        val statusCode: Int,
        val body: InputStream,
        headers: Map<String, String> = emptyMap(),
        val finalUrl: URL,
        val declaredContentLength: Long? = null,
        private val closeAction: () -> Unit = {},
    ) : Closeable {
        val headers: Map<String, String> =
            headers.entries.associate { (name, value) -> name.lowercase() to value }

        fun header(name: String): String? = headers[name.lowercase()]

        override fun close() {
            runCatching { body.close() }
            closeAction()
        }
    }

    @Throws(IOException::class)
    fun execute(request: Request): Response

    /**
     * Opens a streaming response. Fakes that only implement [execute] retain
     * their existing behavior; production overrides this to avoid buffering.
     */
    @Throws(IOException::class)
    fun open(request: Request): StreamingResponse {
        val response = execute(request)
        return StreamingResponse(
            statusCode = response.statusCode,
            body = ByteArrayInputStream(response.body),
            headers = response.headers,
            finalUrl = response.finalUrl ?: request.url,
            declaredContentLength = response.header("content-length")?.toLongOrNull()
                ?: response.body.size.toLong(),
        )
    }
}

/** HttpURLConnection transport with bounded buffered reads and opt-in streaming. */
internal class HttpUrlConnectionTransport(
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : HttpTransport {
    override fun execute(request: HttpTransport.Request): HttpTransport.Response {
        return open(request).use { response ->
            HttpTransport.Response(
                statusCode = response.statusCode,
                body = response.body.readBounded(maxResponseBytes),
                headers = response.headers,
                finalUrl = response.finalUrl,
            )
        }
    }

    override fun open(request: HttpTransport.Request): HttpTransport.StreamingResponse {
        val connection = request.url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = request.followRedirects
            connection.doOutput = request.body.isNotEmpty() || request.method == "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (connection.doOutput) {
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { (name, _) -> name!! }
                .mapValues { (_, values) -> values.lastOrNull().orEmpty() }
            return HttpTransport.StreamingResponse(
                statusCode = statusCode,
                body = stream ?: ByteArrayInputStream(ByteArray(0)),
                headers = headers,
                finalUrl = connection.url,
                declaredContentLength = headers.entries
                    .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
                    ?.value?.toLongOrNull(),
                closeAction = connection::disconnect,
            )
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private fun InputStream.readBounded(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("Response exceeded $maxBytes bytes")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 25L * 1024L * 1024L
    }
}
