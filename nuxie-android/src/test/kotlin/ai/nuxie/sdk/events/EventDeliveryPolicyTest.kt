package ai.nuxie.sdk.events

import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.network.NuxieApi
import java.io.IOException
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventDeliveryPolicyTest {
    @Test
    fun nonSuccessDispositionsFollowSharedContract() {
        var executed = 0
        FixtureRunner.run("events/delivery-disposition.json", "events/delivery-disposition") { vector ->
            val response = vector.body.getValue("response").jsonObject
            val code = response.getValue("status_code").jsonPrimitive.int
            if (code != 200) {
                executed++
                val disposition = EventDeliveryPolicy.disposition(
                    NuxieApi.BatchRejectedException(code, response["retry_after"]?.jsonPrimitive?.content), 0,
                )
                val expected = vector.body.getValue("expect").jsonObject.getValue("disposition").jsonPrimitive.content
                val actual = when (disposition) {
                    is EventDeliveryDisposition.Retry -> "retry"
                    EventDeliveryDisposition.Split -> "split"
                    EventDeliveryDisposition.UnhealthyAuthentication -> "unhealthy_authentication"
                }
                assertEquals(expected, actual)
                if (code == 429) assertEquals(EventDeliveryDisposition.Retry(60_000), disposition)
            }
        }
        assertEquals(4, executed)
    }

    @Test
    fun transientUnknownAndTransportFailuresNeverDiscardEvents() {
        for (code in listOf(404, 408, 409, 425, 429, 500, 503)) {
            assertEquals(EventDeliveryDisposition.Retry(),
                EventDeliveryPolicy.disposition(NuxieApi.BatchRejectedException(code), 0))
        }
        assertEquals(EventDeliveryDisposition.Retry(), EventDeliveryPolicy.disposition(IOException("offline"), 0))
    }

    @Test
    fun retryAfterSupportsSecondsAndHttpDatesWithoutDesugaring() {
        assertEquals(1500L, EventDeliveryPolicy.parseRetryAfter("1.5", 0))
        assertEquals(1000L, EventDeliveryPolicy.parseRetryAfter("Thu, 01 Jan 1970 00:00:01 GMT", 0))
        assertEquals(0L, EventDeliveryPolicy.parseRetryAfter("Thu, 01 Jan 1970 00:00:01 GMT", 2000))
        listOf(null, "invalid", "-1", "NaN", "Infinity", "Thu, 01 Jan 1970 00:00:01 GMT junk").forEach {
            assertNull(EventDeliveryPolicy.parseRetryAfter(it, 0))
        }
    }
}
