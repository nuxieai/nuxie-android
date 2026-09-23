package ai.nuxie.sdk.journey

import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JourneyConversionWatchTest {
    @Test fun sharedAttributionVectors() = runBlocking {
        val corpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/conversion-watch.json").readText()).jsonObject
        for (raw in corpus.getValue("vectors").jsonArray) {
            val vector = raw.jsonObject
            val label = vector.getValue("name").jsonPrimitive.content
            val watches = vector.getValue("watches").jsonObject.mapValues { JourneyConversionWatch.fromJson(it.value.jsonObject) }.toMutableMap()
            for (watch in watches.values) assertEquals(watch, JourneyConversionWatch.fromJson(watch.toJson()))
            val value = vector.getValue("event").jsonObject
            val event = StoredEvent(id = value.getValue("id").jsonPrimitive.content,
                name = value.getValue("name").jsonPrimitive.content,
                properties = value.getValue("properties").jsonObject, distinctId = "customer",
                timestampMillis = value.getValue("occurredAt").jsonPrimitive.long)
            val acceptedAt = vector.getValue("acceptedAt").jsonPrimitive.long
            val normalized = JourneyConversionWatch.normalized(event, acceptedAt)
            val matching = watches.values.filter { normalized != null && it.matches(normalized) }.map { it.journeyId }.toSet()
            JourneyConversionWatch.apply(event, acceptedAt, matching, watches)
            val expected = vector.getValue("expectedConversions").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val actual = watches.values.mapNotNull { watch -> watch.conversion?.let { watch.journeyId to it.eventId } }.toMap()
            assertEquals(label, expected, actual)
            for ((id, time) in vector["expectedBasis"]?.jsonObject.orEmpty()) {
                assertEquals(label, time.jsonPrimitive.long, watches[id]?.basis?.occurredAt)
            }
        }
    }

    @Test fun goalConditionUsesImmutableJourneyIdentityAndOccurrenceTime() = runBlocking {
        val goal = Json.parseToJsonElement("""{
          "criterion":{"type":"event","eventName":"outcome","condition":{"ir_version":1,"expr":{
            "type":"And","args":[
              {"type":"Compare","op":"==","left":{"type":"Journey.Id"},"right":{"type":"String","value":"journey"}},
              {"type":"Compare","op":"==","left":{"type":"Time.Now"},"right":{"type":"Timestamp","value":2}}
            ]}}},
          "attribution":{"basis":"entry","window":{"amount":1,"unit":"minute"}}
        }""").jsonObject
        val watch = JourneyConversionWatch("journey", "experience", "version", "policy", goal, 0)
        assertTrue(watch.matches(StoredEvent(id = "event", name = "outcome", distinctId = "customer", timestampMillis = 2000)))
        assertFalse(watch.matches(StoredEvent(id = "event", name = "outcome", distinctId = "customer", timestampMillis = 3000)))
        assertFalse(watch.copy(journeyId = "other").matches(StoredEvent(id = "event", name = "outcome", distinctId = "customer", timestampMillis = 2000)))
    }
}
