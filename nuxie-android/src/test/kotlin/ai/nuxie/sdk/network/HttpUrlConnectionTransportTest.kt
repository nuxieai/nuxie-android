package ai.nuxie.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.zip.GZIPOutputStream

class HttpUrlConnectionTransportTest {
    @Test
    fun executeDecodesGzipAndPreservesSuccessMetadata() {
        val json = """{"customerId":"customer-1"}"""
        val compressed = gzip(json)
        val handler = StubUrlStreamHandler(
            statusCode = 200,
            responseBody = compressed,
            responseHeaders = mapOf(
                "Content-Encoding" to listOf("GZip"),
                "Content-Length" to listOf(compressed.size.toString()),
                "X-Request-Id" to listOf("request-1"),
            ),
        )
        val url = URL(null, "https://unit.test/profile", handler)

        val response = HttpUrlConnectionTransport().execute(get(url))

        assertEquals(200, response.statusCode)
        assertEquals(json, response.body.decodeToString())
        assertEquals("GZip", response.header("content-encoding"))
        assertEquals(compressed.size.toString(), response.header("content-length"))
        assertEquals("request-1", response.header("x-request-id"))
        assertEquals(url, response.finalUrl)
        assertTrue(handler.connection.disconnected)
    }

    @Test
    fun openDecodesContentEncodingsInReverseAndDropsEncodedLength() {
        val json = """{"features":[]}"""
        val compressed = gzip(json)
        val handler = StubUrlStreamHandler(
            statusCode = 200,
            responseBody = compressed,
            responseHeaders = mapOf(
                "Content-Encoding" to listOf("gzip, identity"),
                "Content-Length" to listOf(compressed.size.toString()),
            ),
        )
        val url = URL(null, "https://unit.test/profile", handler)

        HttpUrlConnectionTransport().open(get(url)).use { response ->
            assertEquals(json, response.body.readBytes().decodeToString())
            assertNull(response.declaredContentLength)
            assertEquals("gzip, identity", response.header("content-encoding"))
        }

        assertTrue(handler.connection.disconnected)
    }

    @Test
    fun executeDecodesGzipErrorBodyWithoutChangingStatusOrHeaders() {
        val json = """{"error":"try again"}"""
        val handler = StubUrlStreamHandler(
            statusCode = 503,
            responseBody = gzip(json),
            responseHeaders = mapOf(
                "content-encoding" to listOf("gzip"),
                "Retry-After" to listOf("30"),
            ),
        )
        val url = URL(null, "https://unit.test/profile", handler)

        val response = HttpUrlConnectionTransport().execute(get(url))

        assertEquals(503, response.statusCode)
        assertEquals(json, response.body.decodeToString())
        assertEquals("30", response.header("retry-after"))
        assertTrue(handler.connection.disconnected)
    }

    @Test
    fun openRejectsUnsupportedContentEncodingAndDisconnects() {
        val handler = StubUrlStreamHandler(
            statusCode = 200,
            responseBody = "encoded".encodeToByteArray(),
            responseHeaders = mapOf("Content-Encoding" to listOf("br")),
        )
        val url = URL(null, "https://unit.test/profile", handler)

        val error = assertThrows(IOException::class.java) {
            HttpUrlConnectionTransport().open(get(url))
        }

        assertEquals("Unsupported Content-Encoding: br", error.message)
        assertTrue(handler.connection.disconnected)
    }

    private fun get(url: URL) = HttpTransport.Request(
        url = url,
        headers = mapOf("Accept-Encoding" to "gzip"),
        body = ByteArray(0),
        method = "GET",
    )

    private fun gzip(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(value.encodeToByteArray())
        }
        return output.toByteArray()
    }

    private class StubUrlStreamHandler(
        private val statusCode: Int,
        private val responseBody: ByteArray,
        private val responseHeaders: Map<String, List<String>>,
    ) : URLStreamHandler() {
        lateinit var connection: StubHttpURLConnection
            private set

        override fun openConnection(url: URL): URLConnection =
            StubHttpURLConnection(url, statusCode, responseBody, responseHeaders).also {
                connection = it
            }
    }

    private class StubHttpURLConnection(
        url: URL,
        private val stubStatusCode: Int,
        private val responseBody: ByteArray,
        private val responseHeaders: Map<String, List<String>>,
    ) : HttpURLConnection(url) {
        var disconnected = false
            private set

        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = stubStatusCode

        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBody)

        override fun getErrorStream(): InputStream? =
            if (stubStatusCode in 200..299) null else ByteArrayInputStream(responseBody)

        override fun getHeaderFields(): Map<String, List<String>> = responseHeaders
    }
}
