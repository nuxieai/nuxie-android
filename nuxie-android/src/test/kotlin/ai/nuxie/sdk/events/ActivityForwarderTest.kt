package ai.nuxie.sdk.events

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.JourneyExitReason
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
        val forwarder = ActivityForwarder(
            resolveExperience = { _, _ -> null },
            deliver = { delivered.add(it) },
        )
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
        val forwarder = ActivityForwarder(
            resolveExperience = { _, _ -> null },
            deliver = { delivered.add(it) },
        )
        val event = StoredEvent("event-1", SystemEventNames.APP_OPENED, distinctId = "customer-1")

        forwarder.onCommitted(event)

        assertEquals(emptyList<NuxieActivityInfo>(), delivered)
    }

    @Test
    fun malformedNonSupersededJourneyActivityStaysSuppressed() = runBlocking {
        val delivered = mutableListOf<NuxieActivityInfo>()
        val forwarder = ActivityForwarder(
            resolveExperience = { _, journeyId -> ExperienceRef("experience-1", "version-1", journeyId) },
            deliver = { delivered.add(it) },
        )
        val event = StoredEvent(
            id = "event-1",
            name = JourneyEventNames.CONVERTED,
            properties = JsonObject(mapOf("journey_id" to JsonPrimitive("journey-1"))),
            timestampMillis = 1_000L,
            distinctId = "customer-1",
            forwardingName = JourneyEventNames.CONVERTED,
            forwardingReceivedAtMillis = 2_000L,
        )

        forwarder.onCommitted(event)

        assertEquals(emptyList<NuxieActivityInfo>(), delivered)
    }

    @Test
    fun supersededJourneyActivityReceivesItsMissingExperienceReference() = runBlocking {
        val delivered = mutableListOf<NuxieActivityInfo>()
        val forwarder = ActivityForwarder(
            resolveExperience = { _, journeyId -> ExperienceRef("experience-1", "version-1", journeyId) },
            deliver = { delivered.add(it) },
        )
        val event = StoredEvent(
            id = "event-1",
            name = JourneyEventNames.SUPERSEDED,
            properties = JsonObject(mapOf("journey_id" to JsonPrimitive("journey-1"))),
            timestampMillis = 1_000L,
            distinctId = "customer-1",
            forwardingName = JourneyEventNames.SUPERSEDED,
            forwardingReceivedAtMillis = 2_000L,
        )

        forwarder.onCommitted(event)

        assertEquals(
            NuxieActivity.JourneyEnded(
                ExperienceRef("experience-1", "version-1", "journey-1"),
                JourneyExitReason.SUPERSEDED,
            ),
            delivered.single().activity,
        )
    }
}
