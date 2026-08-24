package ai.nuxie.sdk.journey

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.identity.IdentityProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * E1 fixture conformance: each test drives the SDK from a fixture's input
 * vectors and compares the SDK's actual emissions and state against the
 * fixture's expectations (shape-only reads of the fixture prove nothing).
 *
 * Deferred post-E1 suites:
 * - `journeys/parking/emission.json`: parking needs execution checkpoints and
 *   pending-action deadlines.
 * - `journeys/handoff/claim.json`: device-to-server handoff needs a serialized
 *   execution envelope and server claim acknowledgement.
 * - `journeys/takeover/claimable.json`: takeover needs mailbox claiming,
 *   relaunch restoration, and pending-action scheduling.
 * - `journeys/seizure-race/device-handoff-wins.json`: the race is resolved by
 *   handoff/seizure compare-and-swap behavior.
 * - `journeys/conformance/either-vocabulary.json`: its action decoding and
 *   execution (including presentation-dependent actions) land with the next
 *   Journey execution slice.
 */
@RunWith(RobolectricTestRunner::class)
class JourneyConformanceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = 1_784_462_400_000L
    private val distinctId = "customer-1"

    private class Identity : IdentityProvider {
        override fun distinctId() = "customer-1"
        override fun anonymousId() = "customer-1"
        override fun rawDistinctId(): String? = null
        override val isIdentified = false
    }

    private class CapturingStore : EventStore {
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

    private data class Harness(
        val root: File,
        val captured: CapturingStore,
        val log: EventLog,
        val runStore: JourneyStore,
        val service: JourneyService,
    )

    private fun harness(): Harness {
        val root = createTempDir(prefix = "nuxie-journey-conformance-")
        val captured = CapturingStore()
        val identity = Identity()
        val log = EventLog(
            store = captured,
            contextBuilder = NuxieContextBuilder(
                RuntimeEnvironment.getApplication(),
                NuxieEnvironment.DEVELOPMENT,
                LogLevel.NONE,
                identity,
            ),
            identity = identity,
            beforeSend = null,
            scope = scope,
            nowMillis = { now },
        )
        val runStore = JourneyStore(root)
        return Harness(
            root,
            captured,
            log,
            runStore,
            JourneyService(runStore, JourneyLedger(log), JourneyReleaseProvider { emptyList() }, { now }),
        )
    }

    @After fun tearDown() = scope.cancel()

    private fun activeRun(id: String) = JourneyRun(
        id = id,
        distinctId = distinctId,
        experienceId = "experience-1",
        experienceVersion = "version-1",
        epoch = 0,
        plane = JourneyPlane.DEVICE,
        settingsSnapshot = JsonObject(emptyMap()),
        state = JourneyRunState.ACTIVE,
    )

    @Test
    fun transitionsBasicFixtureDrivesTheLedgerToTheExpectedVectors() = runBlocking {
        val root = fixture("journeys/transitions/basic.json")
        val journeyId = root.getValue("journeyId").jsonPrimitive.content
        val h = harness()
        try {
            h.runStore.save(activeRun(journeyId))

            root.getValue("timeline").jsonArray.forEach { step ->
                val value = step.jsonObject
                h.service.transition(
                    distinctId,
                    journeyId,
                    value["fromNode"]?.jsonPrimitive?.contentOrNull,
                    value.getValue("toNode").jsonPrimitive.content,
                )
            }
            h.log.awaitBarrier()

            val emitted = h.captured.events.values.filter { it.name == JourneyEventNames.TRANSITION }
            val expected = root.getValue("expected").jsonArray
            assertEquals(expected.size, emitted.size)
            expected.forEachIndexed { index, vector ->
                val vectorObject = vector.jsonObject
                assertEquals(
                    vectorObject.getValue("event").jsonPrimitive.content,
                    emitted[index].name,
                )
                val expectedProperties = vectorObject.getValue("properties").jsonObject
                val actual = emitted[index].properties.filterKeys { !it.startsWith("$") }
                assertEquals(expectedProperties.keys, actual.keys)
                expectedProperties.forEach { (key, value) ->
                    assertEquals(
                        "property $key of vector $index",
                        value.jsonPrimitive.content,
                        actual.getValue(key).toString().trim('"'),
                    )
                }
            }
        } finally {
            h.root.deleteRecursively()
        }
    }

    @Test
    fun supersededFixtureDrivesGhostSuppressionEndToEnd() = runBlocking {
        val root = fixture("journeys/ghost/superseded.json")
        val downFact = root.getValue("downFact").jsonObject
        val expected = root.getValue("expected").jsonObject
        val journeyId = downFact.getValue("properties").jsonObject
            .getValue("journey_id").jsonPrimitive.content
        val h = harness()
        try {
            h.runStore.save(activeRun(journeyId))
            val body = buildJsonObject { put("facts", buildJsonArray { add(downFact) }) }

            h.service.applyDownFacts(body, distinctId)
            h.log.awaitBarrier()

            // The server fact commits once, is delivered immediately (never
            // uploaded), and carries the server-authored ISO timestamp.
            val factId = downFact.getValue("id").jsonPrimitive.content
            val committed = h.captured.events.getValue(factId)
            assertEquals(JourneyEventNames.SUPERSEDED, committed.name)
            assertTrue("server facts are born delivered", factId in h.captured.delivered)
            assertEquals(1_785_002_520_000L, committed.timestampMillis)

            val run = h.runStore.load(distinctId, journeyId)!!
            assertEquals(expected.getValue("isGhost").jsonPrimitive.boolean, run.isGhost)

            // A ghost plays out silently: no exit fact, no completion credit,
            // no effect requests.
            if (!expected.getValue("emitsExit").jsonPrimitive.boolean) {
                h.service.exit(distinctId, journeyId, "completed")
                h.log.awaitBarrier()
                assertTrue(h.captured.events.values.none { it.name == JourneyEventNames.EXITED })
            }
            if (!expected.getValue("recordsCompletion").jsonPrimitive.boolean) {
                assertFalse(h.runStore.hasCompleted(distinctId, "experience-1"))
            }
            if (!expected.getValue("requestsEffects").jsonPrimitive.boolean) {
                assertNull(
                    h.service.requestEffect(
                        distinctId, journeyId, "node-1", 0, "send_push", JsonObject(emptyMap()),
                    ),
                )
                h.log.awaitBarrier()
                assertTrue(
                    h.captured.events.values.none { it.name == JourneyEventNames.EFFECT_REQUESTED },
                )
            }

            // Route-once: replaying the same body commits nothing new.
            val countBefore = h.captured.events.size
            h.service.applyDownFacts(body, distinctId)
            h.log.awaitBarrier()
            assertEquals(countBefore, h.captured.events.size)
        } finally {
            h.root.deleteRecursively()
        }
    }

    private fun fixture(relativePath: String): JsonObject = Json.parseToJsonElement(
        File(FixtureRunner.fixturesRoot(), relativePath).readText(),
    ).jsonObject
}
