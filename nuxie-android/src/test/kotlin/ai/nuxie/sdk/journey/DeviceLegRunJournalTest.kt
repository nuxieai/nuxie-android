package ai.nuxie.sdk.journey

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.ActivityCuration
import ai.nuxie.sdk.events.ActivityForwarder
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.experiences.JourneyPlaneProfile
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.identity.IdentityProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeviceLegRunJournalTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var directory: File
    private var log: EventLog? = null

    @Before fun setUp() {
        directory = File(context.filesDir, "nuxie")
        directory.deleteRecursively()
    }

    @After fun tearDown() = runBlocking {
        log?.close()
        scope.cancel()
        directory.deleteRecursively()
        Unit
    }

    @Test fun `completion queues stable events and forgets the run without a network ack`() = runBlocking {
        val store = SQLiteEventStore(context, nowMillis = { 0 })
        val eventLog = eventLog(store).also { log = it }
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(journal.admit(arm(), JourneyReentry.OneTime, "screen", 100_000))
        val reporter = DeviceLegReporter(journal, eventLog::captureIdempotently)
        reporter.flushPending()
        journal.complete(run.id, "closed", 200_000)
        reporter.flushPending()
        val reopened = DeviceLegRunJournal(directory, "customer")
        assertTrue(reopened.runs().isEmpty())
        assertEquals("closed", reopened.checkmark("experience")?.outcome)
        assertEquals(100_000L, reopened.checkmark("experience")?.lastEnrollmentAtMillis)
        assertNull(reopened.admit(arm(), JourneyReentry.OneTime, "screen", 300_000))
        val rows = store.pendingBatch(10)
        assertEquals(setOf(run.startedEventId, run.completedEventId), rows.map { it.id }.toSet())
        assertEquals(setOf(JourneyEventNames.LEG_STARTED, JourneyEventNames.LEG_COMPLETED), rows.map { it.name }.toSet())
        val completion = rows.single { it.name == JourneyEventNames.LEG_COMPLETED }.properties
        assertEquals(run.journeyId, completion.getValue("journey_id").jsonPrimitive.content)
        assertEquals("0", completion.getValue("leg_generation").jsonPrimitive.content)
        assertEquals("1970-01-01T00:01:40.000Z", completion.getValue("started_at").jsonPrimitive.content)
        assertEquals("1970-01-01T00:03:20.000Z", completion.getValue("completed_at").jsonPrimitive.content)
    }

    @Test fun `shared recovery vectors only resume park points`() {
        val suite = fixture("run-recovery.json")
        for (item in suite.getValue("cases").jsonArray) {
            val vector = item.jsonObject
            directory.deleteRecursively()
            val journal = DeviceLegRunJournal(directory, "customer")
            val run = requireNotNull(journal.admit(arm(vector.getValue("binding").jsonObject), JourneyReentry.EveryTime,
                "step", suite.number("startedAtMillis")))
            journal.markStartedQueued(run)
            journal.recordResponses(run.id, vector.getValue("responses").jsonObject)
            val state = vector.text("beforeDeath")
            if (state == "parked") journal.park(run.id, "wait", vector.number("wakeAtMillis"))
            if (state == "completed") journal.complete(run.id, "done", 200_000)
            val reopened = DeviceLegRunJournal(directory, "customer")
            val resumable = reopened.recover(suite.number("reopenedAtMillis"))
            val recovered = reopened.runs().single()
            assertEquals(vector.number("expectedGeneration"), recovered.generation)
            assertEquals(vector["expectedOutcome"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content, recovered.completion?.outcome)
            assertEquals(vector["expectedCompletedAtMillis"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long, recovered.completion?.atMillis)
            assertEquals(vector.getValue("responses"), recovered.outputs["responses"])
            if (state == "parked") {
                assertEquals(listOf(run.id), resumable.map { it.id })
                assertEquals(300_000L, resumable.single().park?.wakeAtMillis)
                reopened.resumeParked(run.id)
                val again = DeviceLegRunJournal(directory, "customer")
                assertTrue(again.recover(500_000).isEmpty())
                assertEquals("abandoned", again.runs().single().completion?.outcome)
            } else assertTrue(resumable.isEmpty())
        }
    }

    @Test fun `continuations do not restart reentry windows or regress consumed chapters`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val policy = JourneyReentry.OncePerWindow(100_000)
        val first = requireNotNull(journal.admit(arm(), policy, "step", 100_000))
        finish(journal, first, 110_000)
        fun continuation(generation: Long) = arm(JsonObject(mapOf("type" to JsonPrimitive("continue"),
            "journeyId" to JsonPrimitive(first.journeyId), "generation" to JsonPrimitive(generation))))
        val earlier = requireNotNull(journal.admit(continuation(1), policy, "step", 150_000))
        val later = requireNotNull(journal.admit(continuation(2), policy, "step", 160_000))
        finish(journal, later, 170_000)
        finish(journal, earlier, 180_000)
        assertEquals(2L, journal.checkmark("experience")?.generation)
        assertEquals(100_000L, journal.checkmark("experience")?.lastEnrollmentAtMillis)
        assertNull(journal.admit(arm(), policy, "step", 199_000))
        assertNotNull(journal.admit(arm(), policy, "step", 200_000))
    }

    @Test fun `executor transitions atomically persist cursors context and fixed timer anchors`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(journal.admit(arm(), JourneyReentry.EveryTime, "condition", 1_000))
        journal.markStartedQueued(run)
        val changedContext = JsonObject(run.context + ("event" to JsonObject(mapOf("ready" to JsonPrimitive(true)))))
        journal.transition(run.id, "wait", changedContext, DeviceLegControlExecutor.Checkpoint(2_000, 12_000))

        val parked = DeviceLegRunJournal(directory, "customer").runs().single()
        assertEquals("wait", parked.stepId)
        assertEquals(changedContext, parked.context)
        assertEquals(2_000L, parked.park?.anchorAtMillis)
        assertEquals(12_000L, parked.park?.wakeAtMillis)

        journal.transition(run.id, "present", changedContext)
        val advanced = DeviceLegRunJournal(directory, "customer").runs().single()
        assertEquals("present", advanced.stepId)
        assertNull(advanced.park)
    }

    @Test fun `admission is atomic across instances and scoped by customer`() = runBlocking {
        val first = DeviceLegRunJournal(directory, "../customer")
        val second = DeviceLegRunJournal(directory, "../customer")
        val a = async(Dispatchers.Default) { first.admit(arm(), JourneyReentry.OneTime, "step", 100_000) }
        val b = async(Dispatchers.Default) { second.admit(arm(), JourneyReentry.OneTime, "step", 100_000) }
        assertEquals(1, listOfNotNull(a.await(), b.await()).size)
        assertNotNull(DeviceLegRunJournal(directory, "customer").admit(arm(), JourneyReentry.OneTime, "step", 100_000))
        assertEquals(1, DeviceLegRunJournal(directory, "../customer").runs().size)
    }

    @Test fun `equivalent directory paths share one admission lock`() = runBlocking {
        for (index in 0 until 20) {
            val first = DeviceLegRunJournal(directory, "customer-$index")
            val alias = DeviceLegRunJournal(File(directory, "."), "customer-$index")
            val begin = CompletableDeferred<Unit>()
            val a = async(Dispatchers.Default) { begin.await(); first.admit(arm(), JourneyReentry.OneTime, "step", 100_000) }
            val b = async(Dispatchers.Default) { begin.await(); alias.admit(arm(), JourneyReentry.OneTime, "step", 100_000) }
            begin.complete(Unit)
            assertEquals(1, listOfNotNull(a.await(), b.await()).size)
        }
    }

    @Test fun `forwarding handles offset and invalid occurrence timestamps on API 23`() = runBlocking {
        for ((timestamp, expected) in listOf(
            "1970-01-01T01:03:20.5001+01:00" to 200_500L,
            "not-a-date" to 900_000L,
            "1970-01-01T00:03:20.000Ztrailing" to 900_000L,
        )) {
            val delivered = mutableListOf<ai.nuxie.sdk.NuxieActivityInfo>()
            val forwarder = ActivityForwarder({ _, _ -> null }) { delivered.add(it) }
            forwarder.onCommitted(StoredEvent("event", JourneyEventNames.LEG_COMPLETED,
                JsonObject(mapOf("experience_id" to JsonPrimitive("experience"), "experience_version_id" to JsonPrimitive("version"),
                    "journey_id" to JsonPrimitive("journey"), "leg_id" to JsonPrimitive("a".repeat(64)),
                    "leg_generation" to JsonPrimitive(1), "outcome" to JsonPrimitive("done"), "completed_at" to JsonPrimitive(timestamp))),
                timestampMillis = 900_000, distinctId = "customer", forwardingReceivedAtMillis = 950_000))
            assertEquals(timestamp, expected, delivered.single().timestampMillis)
            assertEquals(950_000L, delivered.single().receivedAtMillis)
        }
    }

    @Test fun `invalid buffered JSON cannot replace the last readable snapshot`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(journal.admit(arm(), JourneyReentry.EveryTime, "step", 100_000))
        journal.recordResponses(run.id, JsonObject(mapOf("answer" to JsonPrimitive("yes"))))
        try {
            journal.recordResponses(run.id, JsonObject(mapOf("answer" to JsonPrimitive(Double.NaN))))
            fail("Non-finite JSON must fail before publishing a journal snapshot")
        } catch (_: ai.nuxie.sdk.experiences.ReleaseAuthenticationException) { }
        assertEquals(JsonPrimitive("yes"), DeviceLegRunJournal(directory, "customer").runs().single()
            .outputs.getValue("responses").jsonObject["answer"])
    }

    private fun finish(journal: DeviceLegRunJournal, run: DeviceLegRun, atMillis: Long) {
        journal.markStartedQueued(run)
        journal.complete(run.id, "done", atMillis)
        journal.markCompletionQueued(run)
    }

    @Test fun `shared reports preserve outputs and forwarding across stable capture retries`() = runBlocking {
        val vector = fixture("reports.json")
        for (modeValue in vector.getValue("captureModes").jsonArray) {
            val mode = modeValue.jsonPrimitive.content
            log?.close()
            directory.deleteRecursively()
            val store = SQLiteEventStore(context, nowMillis = { 0 })
            val events = eventLog(store, dropEvents = mode == "drop").also { log = it }
            val forwarded = CopyOnWriteArrayList<String>()
            val times = CopyOnWriteArrayList<Long>()
            val forwarder = ActivityForwarder({ _, _ -> null }) { activity ->
                forwarded.add(activity.name)
                times.add(activity.timestampMillis)
            }
            events.subscribeForwarding(isEnabled = { true }, handler = forwarder::onCommitted)
            val journal = DeviceLegRunJournal(directory, "customer")
            val run = requireNotNull(journal.admit(arm(vector.getValue("binding").jsonObject), JourneyReentry.EveryTime,
                "step", vector.number("startedAtMillis")))
            val outputs = vector.getValue("outputs").jsonObject
            journal.recordResponses(run.id, outputs.getValue("responses").jsonObject)
            journal.complete(run.id, vector.text("outcome"), vector.number("completedAtMillis"), outputs.getValue("event").jsonObject)
            if (mode == "accept_then_lost_receipt") {
                DeviceLegReporter(journal) { name, properties, id, customer ->
                    val captured = events.captureIdempotently(name, properties, id, customer)
                    captured && name != JourneyEventNames.LEG_COMPLETED
                }.flushPending()
                assertEquals(1, journal.runs().size)
                journal.complete(run.id, "abandoned", 900_000)
            }
            DeviceLegReporter(journal, events::captureIdempotently).flushPending()
            events.awaitBarrier()
            assertTrue(journal.runs().isEmpty())
            val rows = store.pendingBatch(10)
            if (mode == "drop") {
                assertTrue(rows.isEmpty())
                assertTrue(forwarded.isEmpty())
            } else {
                assertEquals(setOf(run.startedEventId, run.completedEventId), rows.map { it.id }.toSet())
                assertEquals(vector.getValue("eventNames").jsonArray.map { it.jsonPrimitive.content }.toSet(), rows.map { it.name }.toSet())
                assertEquals(vector.getValue("forwardedNames").jsonArray.map { it.jsonPrimitive.content }, forwarded.toList())
                assertEquals(listOf(vector.number("startedAtMillis"), vector.number("completedAtMillis")), times.toList())
                val report = rows.single { it.id == run.completedEventId }.properties
                assertEquals(outputs, report["outputs"])
                assertEquals(vector["startedAt"], report["started_at"])
                assertEquals(vector["completedAt"], report["completed_at"])
                assertEquals(vector["outcome"], report["outcome"])
                assertEquals(vector.getValue("binding").jsonObject["generation"], report["leg_generation"])
            }
        }
    }

    private fun fixture(name: String) = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
        .resolve("journeys/planes/$name").readText()).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long

    private fun arm(binding: JsonObject = JsonObject(mapOf("type" to JsonPrimitive("new")))) = JourneyPlaneProfile.Arm(
        reference = JsonObject(mapOf("experienceId" to JsonPrimitive("experience"), "versionId" to JsonPrimitive("version"),
            "legId" to JsonPrimitive("a".repeat(64)), "descriptorSha256" to JsonPrimitive("b".repeat(64)))),
        binding = binding, entryCondition = JsonObject(mapOf("type" to JsonPrimitive("app_foregrounded"))),
        context = JsonObject(mapOf("event" to JsonObject(emptyMap()), "responses" to JsonObject(emptyMap()))),
    )

    private fun eventLog(store: SQLiteEventStore, dropEvents: Boolean = false): EventLog {
        val identity = object : IdentityProvider {
            override fun distinctId() = "customer"
            override fun anonymousId() = "customer"
            override fun rawDistinctId(): String? = null
            override val isIdentified = false
        }
        return EventLog(store, NuxieContextBuilder(context, NuxieEnvironment.DEVELOPMENT, LogLevel.DEBUG, identity),
            identity, beforeSend = if (dropEvents) { { _ -> null } } else null, scope = scope, nowMillis = { 1_000_000 })
    }
}
