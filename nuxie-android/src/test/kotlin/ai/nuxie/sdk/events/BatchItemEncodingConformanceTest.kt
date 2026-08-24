package ai.nuxie.sdk.events

import ai.nuxie.sdk.fixtures.FixtureRunner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BatchItemEncodingConformanceTest {
    @Test
    fun everyBatchItemEncodingVectorMatchesCanonicalWireText() {
        FixtureRunner.run(
            relativePath = "events/batch-item-encoding.json",
            expectedSuite = "events/batch-item-encoding",
        ) { vector ->
            val input = vector.body.getValue("event").jsonObject
            val properties = input["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val encoded = BatchItemWireEncoder.encode(
                StoredEvent(
                    id = input.string("id"),
                    name = input.string("name"),
                    properties = properties,
                    timestampMillis = parseIsoMillis(input.string("timestamp")),
                    distinctId = input.string("distinct_id"),
                ),
            )

            // The canonical properties text pins the real encoder output.
            val expectedProperties = vector.body["expect_properties_json"]?.jsonPrimitive?.content
            assertNotNull("[${vector.name}] missing property JSON expectation", expectedProperties)

            // Build the COMPLETE expected wire object: every field the contract
            // requires, from the vector's expectations, with contract-mandated
            // fields derived from the input where the fixture's partial expect
            // block omits them. Comparing full canonical text means a dropped
            // field or an unexpected extra top-level field fails every vector.
            val expect = vector.body.getValue("expect").jsonObject
            val expectedWire = linkedMapOf<String, JsonElement>(
                "event" to expect.getValue("event"),
                "distinct_id" to expect.getValue("distinct_id"),
                "timestamp" to (
                    expect["timestamp"]
                        ?: JsonPrimitive(formatIsoMillis(parseIsoMillis(input.string("timestamp"))))
                    ),
                "properties" to (
                    expect["properties"] ?: Json.parseToJsonElement(expectedProperties!!)
                    ),
                "idempotency_key" to expect.getValue("idempotency_key"),
            )
            expect["anon_distinct_id"]?.let { expectedWire["\$anon_distinct_id"] = it }
            expect["value"]?.let { expectedWire["value"] = it }
            // Fixture expectation labels are normalized; the wire key is camel-cased
            // like the iOS reference encoder.
            expect["entity_id"]?.let { expectedWire["entityId"] = it }

            assertEquals(
                "[${vector.name}] complete canonical batch item",
                CanonicalJson.encode(JsonObject(expectedWire)),
                encoded,
            )
            assertEquals(
                "[${vector.name}] canonical properties",
                expectedProperties,
                CanonicalJson.encode(
                    Json.parseToJsonElement(encoded).jsonObject.getValue("properties"),
                ),
            )
        }
    }

    /** Second-precision ISO-8601 UTC parse; the JDK time API stays out repo-wide. */
    private fun parseIsoMillis(iso: String): Long =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).run {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
            parse(iso)?.time ?: error("Unparseable fixture timestamp: $iso")
        }

    private fun formatIsoMillis(timestampMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
            timeZone = TimeZone.getTimeZone("UTC")
            format(java.util.Date(timestampMillis))
        }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
}
