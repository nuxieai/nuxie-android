package ai.nuxie.sdk.journey

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.identity.IdentityProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class JourneyServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_784_462_400_000L

    private class Identity : IdentityProvider {
        override fun distinctId() = "customer-1"
        override fun anonymousId() = "customer-1"
        override fun rawDistinctId(): String? = null
        override val isIdentified = false
    }

    private class Store : EventStore {
        val events = linkedMapOf<String, StoredEvent>()
        val delivered = mutableSetOf<String>()
        override suspend fun insertPending(event: StoredEvent) {
            check(events.putIfAbsent(event.id, event) == null) { "duplicate event" }
        }
        override suspend fun markDelivered(ids: List<String>) { delivered += ids }
        override suspend fun hasEvent(name: String, distinctId: String, sinceMillis: Long?) = false
        override suspend fun countEvents(name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?) = 0
        override suspend fun getFirstEventTime(name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?) = null
        override suspend fun getLastEventTime(name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?) = null
        override suspend fun querySessionEvents(sessionId: String) = emptyList<StoredEvent>()
        override suspend fun reassignEvents(from: String, to: String) = 0
        override suspend fun deleteOldestDeliveredEvents(keeping: Int) = 0
        override suspend fun recordStableDrop(eventId: String, recordedAtMillis: Long) = true
        override suspend fun pendingBatch(limit: Int) = events.values.filterNot { it.id in delivered }.take(limit)
        override suspend fun close() = Unit
    }

    private data class Harness(val root: File, val store: Store, val log: EventLog, val service: JourneyService)

    private fun harness(reentry: JourneyReentry = JourneyReentry.EveryTime): Harness {
        val root = createTempDir(prefix = "nuxie-journey-")
        val eventStore = Store()
        val identity = Identity()
        val eventLog = EventLog(
            store = eventStore,
            contextBuilder = NuxieContextBuilder(RuntimeEnvironment.getApplication(), NuxieEnvironment.DEVELOPMENT, LogLevel.NONE, identity),
            identity = identity,
            beforeSend = null,
            scope = scope,
            nowMillis = { now },
        )
        val release = AdmittedJourneyRelease(
            experienceId = "experience-1",
            experienceVersion = "version-1",
            triggerEventName = "opened",
            reentry = reentry,
            settingsTemplate = buildJsonObject {
                put("goal", JsonPrimitive("goal"))
                put("conversion_anchor", JsonPrimitive("journey_start"))
                put("goal_window_ms", JsonPrimitive(1_000L))
                put("end_on_goal", JsonPrimitive(true))
            },
        )
        return Harness(
            root,
            eventStore,
            eventLog,
            JourneyService(JourneyStore(root), JourneyLedger(eventLog), JourneyReleaseProvider { name -> if (name == "opened") listOf(release) else emptyList() }, { now }),
        )
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun enrollmentAndFiveFactsUseTheDocumentedWireProperties() = runBlocking {
        val h = harness()
        try {
            val result = h.service.handleEventForTrigger(StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"))
            val id = (result.single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started).ref.journeyId!!
            h.service.transition("customer-1", id, null, "screen-a")
            h.service.milestone("customer-1", id, "reached-a")
            val first = h.service.requestEffect("customer-1", id, "effect-a", 2, "send_push", JsonObject(emptyMap()))
            val retry = h.service.requestEffect("customer-1", id, "effect-a", 2, "send_push", JsonObject(emptyMap()))
            h.service.exit("customer-1", id, "completed")
            h.log.awaitBarrier()

            assertEquals(first, retry)
            assertEquals(
                setOf("journey_id", "epoch", "experience_id", "experience_version", "trigger_ref", "plane", "settings_snapshot"),
                h.store.events.values.first { it.name == JourneyEventNames.ENROLLED }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
            assertEquals(
                setOf("journey_id", "epoch", "to_node", "region", "plane"),
                h.store.events.values.first { it.name == JourneyEventNames.TRANSITION }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
            assertEquals(
                setOf("journey_id", "epoch", "milestone_id"),
                h.store.events.values.first { it.name == JourneyEventNames.MILESTONE }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
            assertEquals(
                setOf("journey_id", "epoch", "node_id", "invocation_id", "effect", "payload"),
                h.store.events.values.first { it.name == JourneyEventNames.EFFECT_REQUESTED }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
            assertEquals(
                setOf("journey_id", "epoch", "reason", "at"),
                h.store.events.values.first { it.name == JourneyEventNames.EXITED }.properties.keys.filterNot { it.startsWith("$") }.toSet(),
            )
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun reentryAndAlreadyActiveAdmissionsAreSuppressed() = runBlocking {
        val h = harness(JourneyReentry.OncePerWindow(1_000))
        try {
            val event = StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1")
            val first = h.service.handleEventForTrigger(event).single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            assertTrue(h.service.handleEventForTrigger(event).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed)
            h.service.exit("customer-1", first.ref.journeyId!!, "completed")
            assertTrue(h.service.handleEventForTrigger(event).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Suppressed)
            now += 1_000
            assertTrue(h.service.handleEventForTrigger(event).single() is ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started)
        } finally { h.root.deleteRecursively() }
    }

    @Test
    fun downFactsCommitOnceNeverUploadAndSupersedeGhostsTheRun() = runBlocking {
        val h = harness()
        try {
            val started = h.service.handleEventForTrigger(StoredEvent("trigger-1", "opened", timestampMillis = now, distinctId = "customer-1"))
                .single() as ai.nuxie.sdk.events.TriggerService.JourneyTriggerResult.Started
            val journeyId = started.ref.journeyId!!
            val fact = buildJsonObject {
                put("id", JsonPrimitive("fact-1"))
                put("event", JsonPrimitive(JourneyEventNames.SUPERSEDED))
                put("timestamp", JsonPrimitive(1L))
                put("properties", buildJsonObject { put("journey_id", JsonPrimitive(journeyId)) })
            }
            val body = buildJsonObject { put("facts", JsonArray(listOf(fact, fact))) }
            h.service.applyDownFacts(body, "customer-1")
            h.service.exit("customer-1", journeyId, "completed")
            h.log.awaitBarrier()
            assertTrue(JourneyStore(h.root).load("customer-1", journeyId)!!.isGhost)
            assertEquals(1, h.store.events.values.count { it.id == "fact-1" })
            assertTrue("server fact must never upload", "fact-1" in h.store.delivered)
            assertNull(h.store.events.values.firstOrNull { it.name == JourneyEventNames.EXITED && it.properties["journey_id"]?.toString()?.contains(journeyId) == true })
        } finally { h.root.deleteRecursively() }
    }
}
