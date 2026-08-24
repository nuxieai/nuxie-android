package ai.nuxie.sdk.events

import ai.nuxie.sdk.NuxieEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BatchItemWireEncoderTest {
    @Test
    fun timestampsFormatAsUtcIsoMilliseconds() {
        val stored = StoredEvent.from(
            NuxieEvent(
                id = "event-id",
                name = "formatted_timestamp",
                distinctId = "user-1",
                timestampMillis = 1_784_462_400_999L, // 2026-07-19T12:00:00.999Z
            ),
        )

        val wire = Json.parseToJsonElement(BatchItemWireEncoder.encode(stored)).jsonObject

        assertEquals("2026-07-19T12:00:00.999Z", wire.getValue("timestamp").toString().trim('"'))
    }

    @Test
    fun liftCandidatesWithTheWrongTypeRemainOnlyInProperties() {
        val stored = StoredEvent.from(
            NuxieEvent(
                id = "event-id",
                name = "button_clicked",
                distinctId = "user-1",
                properties = mapOf(
                    "\$anon_distinct_id" to mapOf("nested" to true),
                    "value" to "five",
                    "entityId" to 3,
                ),
                timestampMillis = 1_784_462_400_000L,
            ),
        )

        val wire = Json.parseToJsonElement(BatchItemWireEncoder.encode(stored)).jsonObject

        assertFalse(wire.containsKey("\$anon_distinct_id"))
        assertFalse(wire.containsKey("value"))
        assertFalse(wire.containsKey("entityId"))
        assertEquals(stored.properties, wire["properties"])
    }

    @Test
    fun unsignedIntegersAboveTheIeee754SafeRangeKeepTheirJsonPrecision() {
        val stored = StoredEvent.from(
            NuxieEvent(
                id = "event-id",
                name = "large_number",
                distinctId = "user-1",
                properties = mapOf("uint64" to ULong.MAX_VALUE),
                timestampMillis = 1_784_462_400_000L,
            ),
        )

        assertEquals(
            "{\"distinct_id\":\"user-1\",\"event\":\"large_number\",\"idempotency_key\":\"event-id\"," +
                "\"properties\":{\"uint64\":18446744073709551615}," +
                "\"timestamp\":\"2026-07-19T12:00:00.000Z\"}",
            BatchItemWireEncoder.encode(stored),
        )
    }

    @Test
    fun callerSuppliedJsonElementsWithNonJsonNumbersAreRejected() {
        try {
            StoredEvent.from(
                NuxieEvent(
                    id = "event-id",
                    name = "bad_number",
                    distinctId = "user-1",
                    properties = mapOf(
                        "bad" to kotlinx.serialization.json.JsonPrimitive(Double.NaN),
                    ),
                    timestampMillis = 1_784_462_400_000L,
                ),
            )
            org.junit.Assert.fail("Expected NaN JSON primitive to be rejected")
        } catch (expected: IllegalArgumentException) {
            // NaN must never reach canonical storage or the wire.
        }
    }
}
