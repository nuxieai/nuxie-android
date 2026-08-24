package ai.nuxie.sdk.network

import ai.nuxie.sdk.NuxieEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieApiTest {
    private class RecordingTransport(private val statusCode: Int = 200) : HttpTransport {
        val requests = mutableListOf<HttpTransport.Request>()
        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            requests.add(request)
            return HttpTransport.Response(statusCode, ByteArray(0))
        }
    }

    @Test
    fun batchBodyCarriesApiKeyAndItemBytesVerbatim() {
        val transport = RecordingTransport()
        val api = NuxieApi("pk_test_key", NuxieEnvironment.DEVELOPMENT, transport)

        api.postBatch(
            listOf(
                """{"event":"one","idempotency_key":"id-1"}""",
                """{"event":"two","idempotency_key":"id-2"}""",
            ),
        )

        val request = transport.requests.single()
        assertEquals("https://dev-i.nuxie.ai/batch", request.url.toString())
        assertEquals(
            """{"apiKey":"pk_test_key","batch":[""" +
                """{"event":"one","idempotency_key":"id-1"},""" +
                """{"event":"two","idempotency_key":"id-2"}]}""",
            request.body.decodeToString(),
        )
        assertEquals("application/json", request.headers["Content-Type"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("Nuxie-Android-SDK/"))
        // iOS parity: request bodies are not gzip-compressed.
        assertEquals(null, request.headers["Content-Encoding"])
    }

    @Test
    fun productionEnvironmentTargetsTheProductionHost() {
        val transport = RecordingTransport()
        NuxieApi("pk_live", NuxieEnvironment.PRODUCTION, transport).postBatch(listOf("{}"))
        assertEquals("https://i.nuxie.ai/batch", transport.requests.single().url.toString())
    }

    @Test
    fun nonSuccessResponsesThrowTyped() {
        val api = NuxieApi("pk", NuxieEnvironment.DEVELOPMENT, RecordingTransport(statusCode = 500))
        val failure = assertThrows(NuxieApi.BatchRejectedException::class.java) {
            api.postBatch(listOf("{}"))
        }
        assertEquals(500, failure.statusCode)
    }

    @Test
    fun apiKeysWithSpecialCharactersAreEscaped() {
        val transport = RecordingTransport()
        NuxieApi("pk\"quote\\slash", NuxieEnvironment.DEVELOPMENT, transport)
            .postBatch(listOf("{}"))
        assertTrue(
            transport.requests.single().body.decodeToString()
                .startsWith("""{"apiKey":"pk\"quote\\slash""""),
        )
    }

    @Test
    fun featureCheckUsesTheIosEntitledWireShape() {
        val transport = RecordingTransport()
        val api = NuxieApi("pk_test_key", NuxieEnvironment.DEVELOPMENT, transport)
        transport.apply {
            // The response is supplied by a second transport because this
            // tiny recording helper intentionally defaults to an empty body.
        }
        val responding = object : HttpTransport {
            var request: HttpTransport.Request? = null
            override fun execute(request: HttpTransport.Request): HttpTransport.Response {
                this.request = request
                return HttpTransport.Response(
                    200,
                    """{"allowed":true,"unlimited":false,"balance":3,"type":"metered"}"""
                        .encodeToByteArray(),
                )
            }
        }
        val checked = NuxieApi("pk_test_key", NuxieEnvironment.DEVELOPMENT, responding)
            .checkFeature("customer-1", "exports", 2.0, "project-1")

        assertTrue(checked.allowed)
        assertEquals("https://dev-i.nuxie.ai/entitled", responding.request!!.url.toString())
        assertEquals(
            """{"apiKey":"pk_test_key","customerId":"customer-1","featureId":"exports","requiredBalance":2.0,"entityId":"project-1"}""",
            responding.request!!.body.decodeToString(),
        )
    }
}
