package ai.nuxie.sdk.events

import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieActivityInfo
import ai.nuxie.sdk.journey.JourneyEventNames
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityForwarderTest {
    @Test
    fun committedEventUsesCaptureNameAndRetainsBothTimestamps() = runBlocking {
        val delivered = mutableListOf<NuxieActivityInfo>()
        val forwarder = ActivityForwarder { delivered.add(it) }
        val event = StoredEvent(
            id = "event-1",
            name = "renamed-by-before-send",
            properties = JsonObject(emptyMap()),
            timestampMillis = 1_000L,
            distinctId = "customer-1",
            forwardingName = SystemEventNames.APP_BACKGROUNDED,
            forwardingReceivedAtMillis = 2_000L,
        )

        forwarder.onCommitted(event)

        val info = delivered.single()
        assertEquals("event-1", info.id)
        assertEquals(1_000L, info.timestampMillis)
        assertEquals(2_000L, info.receivedAtMillis)
        assertEquals(NuxieActivity.AppBackgrounded, info.activity)
    }

    @Test
    fun listenerAbsenceAtAdmissionDropsWithoutLaterReplay() = runBlocking {
        val delivered = mutableListOf<NuxieActivityInfo>()
        val forwarder = ActivityForwarder { delivered.add(it) }
        val event = StoredEvent("event-1", SystemEventNames.APP_OPENED, distinctId = "customer-1")

        forwarder.onCommitted(event)

        assertEquals(emptyList<NuxieActivityInfo>(), delivered)
    }

    @Test
    fun malformedJourneyActivityStaysSuppressed() = runBlocking {
        val delivered = mutableListOf<NuxieActivityInfo>()
        val forwarder = ActivityForwarder { delivered.add(it) }
        val event = StoredEvent(
            id = "event-1",
            name = JourneyEventNames.LEG_COMPLETED,
            properties = JsonObject(mapOf("journey_id" to JsonPrimitive("journey-1"))),
            timestampMillis = 1_000L,
            distinctId = "customer-1",
            forwardingName = JourneyEventNames.LEG_COMPLETED,
            forwardingReceivedAtMillis = 2_000L,
        )

        forwarder.onCommitted(event)

        assertEquals(emptyList<NuxieActivityInfo>(), delivered)
    }

    @Test
    fun journeyCompletionUsesItsCanonicalOccurrenceTimestamp() = runBlocking {
        val delivered = mutableListOf<NuxieActivityInfo>()
        val forwarder = ActivityForwarder { delivered.add(it) }
        val event = StoredEvent(
            id = "event-1",
            name = JourneyEventNames.LEG_COMPLETED,
            properties = JsonObject(
                mapOf(
                    "experience_id" to JsonPrimitive("experience-1"),
                    "experience_version_id" to JsonPrimitive("version-1"),
                    "journey_id" to JsonPrimitive("journey-1"),
                    "leg_id" to JsonPrimitive("leg-1"),
                    "leg_generation" to JsonPrimitive(3),
                    "outcome" to JsonPrimitive("continue"),
                    "completed_at" to JsonPrimitive("1970-01-01T00:00:01.500Z"),
                ),
            ),
            timestampMillis = 1_000L,
            distinctId = "customer-1",
            forwardingName = JourneyEventNames.LEG_COMPLETED,
            forwardingReceivedAtMillis = 2_000L,
        )

        forwarder.onCommitted(event)

        val info = delivered.single()
        val activity = info.activity as NuxieActivity.JourneyCompleted
        assertEquals("journey-1", activity.experience.journeyId)
        assertEquals("leg-1", activity.legId)
        assertEquals(1_500L, info.timestampMillis)
    }
}
