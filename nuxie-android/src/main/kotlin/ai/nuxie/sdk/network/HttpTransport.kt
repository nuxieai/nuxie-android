package ai.nuxie.sdk.network

import java.io.ByteArrayOutputStream
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
    )

    class Response(
        val statusCode: Int,
        val body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ) {
        /** Case-insensitive header map (HTTP header names are case-insensitive). */
        val headers: Map<String, String> =
            headers.entries.associate { (name, value) -> name.lowercase() to value }

        fun header(name: String): String? = headers[name.lowercase()]
    }

    @Throws(IOException::class)
    fun execute(request: Request): Response
}

/** HttpURLConnection POST transport with bounded response reads. */
internal class HttpUrlConnectionTransport(
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : HttpTransport {
    override fun execute(request: HttpTransport.Request): HttpTransport.Response {
        val connection = request.url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { it.write(request.body) }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBounded(maxResponseBytes) } ?: ByteArray(0)
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { (name, _) -> name!! }
                .mapValues { (_, values) -> values.lastOrNull().orEmpty() }
            return HttpTransport.Response(statusCode, body, headers)
        } finally {
            connection.disconnect()
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

