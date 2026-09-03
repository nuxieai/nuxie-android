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
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
        scope.coroutineContext[Job]?.cancelAndJoin()
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
            if (state == "completed") {
                journal.complete(
                    run.id,
                    "done",
                    200_000,
                    responseOutputs = vector.getValue("responses").jsonObject,
                )
            }
            val reopened = DeviceLegRunJournal(directory, "customer")
            val resumable = reopened.recover(suite.number("reopenedAtMillis"))
            val recovered = reopened.runs().single()
            assertEquals(vector.number("expectedGeneration"), recovered.generation)
            assertEquals(vector["expectedOutcome"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content, recovered.completion?.outcome)
            assertEquals(vector["expectedCompletedAtMillis"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long, recovered.completion?.atMillis)
            val retainedResponses = if (recovered.completion == null) {
                recovered.context.getValue("responses")
            } else {
                recovered.outputs.getValue("responses")
            }
            assertEquals(vector.getValue("responses"), retainedResponses)
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

    @Test fun `abandonment moves response outputs and clears execution context`() {
        val responses = JsonObject(mapOf("answer" to JsonPrimitive("retained")))
        val armed = arm().copy(
            context = JsonObject(
                mapOf(
                    "event" to JsonObject(mapOf("transient" to JsonPrimitive(true))),
                    "responses" to responses,
                ),
            ),
        )
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(
            journal.admit(armed, JourneyReentry.EveryTime, "step", 100_000),
        )
        journal.markStartedQueued(run)

        journal.recover(200_000)

        val abandoned = DeviceLegRunJournal(directory, "customer").runs().single()
        assertEquals("abandoned", abandoned.completion?.outcome)
        assertEquals(responses, abandoned.outputs.getValue("responses"))
        assertEquals(JsonObject(emptyMap()), abandoned.context.getValue("event"))
        assertEquals(JsonObject(emptyMap()), abandoned.context.getValue("responses"))
    }

    @Test fun `journal admits canonical contexts larger than the legacy cap`() {
        val payload = "x".repeat(17 * 1024 * 1024)
        val armed = arm().copy(
            context = JsonObject(
                mapOf(
                    "event" to JsonObject(mapOf("payload" to JsonPrimitive(payload))),
                    "responses" to JsonObject(emptyMap()),
                ),
            ),
        )
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(
            journal.admit(armed, JourneyReentry.EveryTime, "step", 100_000),
        )
        assertEquals(
            payload.length,
            DeviceLegRunJournal(directory, "customer").runs().single().context
                .getValue("event").jsonObject.getValue("payload").jsonPrimitive.content.length,
        )

        journal.complete(run.id, "done", 200_000)

        val completed = DeviceLegRunJournal(directory, "customer").runs().single()
        assertEquals(JsonObject(emptyMap()), completed.context.getValue("event"))
        assertEquals("done", completed.completion?.outcome)
    }

    @Test fun `large collected responses are not duplicated before completion`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(
            journal.admit(arm(), JourneyReentry.EveryTime, "survey", 100_000),
        )
        val answer = "y".repeat(21 * 1024 * 1024)
        val responses = JsonObject(mapOf("answer" to JsonPrimitive(answer)))

        journal.recordResponses(run.id, responses)

        val pending = DeviceLegRunJournal(directory, "customer").runs().single()
        assertEquals(answer, pending.context.getValue("responses").jsonObject
            .getValue("answer").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), pending.outputs.getValue("responses"))

        journal.complete(
            run.id,
            "done",
            200_000,
            responseOutputs = responses,
        )

        val completed = DeviceLegRunJournal(directory, "customer").runs().single()
        assertEquals(JsonObject(emptyMap()), completed.context.getValue("responses"))
        assertEquals(answer, completed.outputs.getValue("responses").jsonObject
            .getValue("answer").jsonPrimitive.content)
    }

    @Test fun `renderer publication survives reopen before advancing sequences`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(
            journal.admit(arm(), JourneyReentry.EveryTime, "survey", 100_000),
        )
        journal.markStartedQueued(run)
        val context = JsonObject(
            run.context + (
                "responses" to JsonObject(mapOf("answer" to JsonPrimitive("premium")))
            ),
        )
        val publication = DeviceLegRun.PendingPresentationPublication(
            invocationId = "invocation-1",
            batchSequence = 0,
            nextEmissionSequence = 2,
            sourceScreenId = "survey",
            sourceActionId = "submit",
            sourceComponentId = "submit-button",
            sourceInstanceId = "survey-1",
            responsesChanged = true,
            items = listOf(
                DeviceLegRun.PendingPresentationPublication.Item(
                    name = "survey_submitted",
                    properties = JsonObject(mapOf("answer" to JsonPrimitive("premium"))),
                    eventId = "emission-2",
                    occurredAtMillis = 101_000,
                ),
            ),
        )

        assertNotNull(
            journal.stagePresentationPublication(run.id, "survey", context, publication),
        )
        assertNotNull(
            journal.stagePresentationPublication(run.id, "survey", context, publication),
        )

        val staged = DeviceLegRunJournal(directory, "customer").runs().single()
        assertEquals(publication, staged.pendingPresentationPublication)
        assertEquals("premium", staged.context.getValue("responses").jsonObject
            .getValue("answer").jsonPrimitive.content)
        assertEquals(0L, staged.nextPresentationBatchSequence)
        assertEquals(0L, staged.nextPresentationEmissionSequence)

        val cleared = requireNotNull(
            DeviceLegRunJournal(directory, "customer")
                .clearPresentationPublication(run.id, "invocation-1"),
        )
        assertNull(cleared.pendingPresentationPublication)
        assertEquals(1L, cleared.nextPresentationBatchSequence)
        assertEquals(2L, cleared.nextPresentationEmissionSequence)
        assertEquals("premium", cleared.context.getValue("responses").jsonObject
            .getValue("answer").jsonPrimitive.content)
    }

    @Test fun `partially published renderer batch abandons with responses before report retirement`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(
            journal.admit(arm(), JourneyReentry.EveryTime, "survey", 100_000),
        )
        journal.markStartedQueued(run)
        val context = JsonObject(
            run.context + (
                "responses" to JsonObject(mapOf("answer" to JsonPrimitive("premium")))
                ),
        )
        val publication = DeviceLegRun.PendingPresentationPublication(
            invocationId = "invocation-1",
            batchSequence = 0,
            nextEmissionSequence = 1,
            sourceScreenId = "survey",
            sourceActionId = "submit",
            responsesChanged = true,
            items = listOf(
                DeviceLegRun.PendingPresentationPublication.Item(
                    name = "survey_submitted",
                    properties = JsonObject(emptyMap()),
                    eventId = "event-1",
                    occurredAtMillis = 100,
                ),
            ),
        )
        requireNotNull(
            journal.stagePresentationPublication(run.id, run.stepId, context, publication),
        )

        val abandoned = requireNotNull(
            journal.abandonPendingPresentationPublication(run.id, "invocation-1", 200),
        )

        assertEquals("abandoned", abandoned.completion?.outcome)
        assertEquals(
            JsonPrimitive("premium"),
            abandoned.outputs.getValue("responses").jsonObject["answer"],
        )
        assertEquals(publication, abandoned.pendingPresentationPublication)
        assertEquals(0L, abandoned.nextPresentationBatchSequence)
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
        val buffered = DeviceLegRunJournal(directory, "customer").runs().single()
        assertEquals(
            JsonPrimitive("yes"),
            buffered.context.getValue("responses").jsonObject["answer"],
        )
        assertEquals(JsonObject(emptyMap()), buffered.outputs.getValue("responses"))
    }

    @Test fun `authenticated app scope survives key rotation and isolates another app`() {
        val firstScope = DeviceLegStorageScope(ProfileDeliveryAuthority("app", "test"))
        val rotatedKeyScope = DeviceLegStorageScope(ProfileDeliveryAuthority("app", "test"))
        val otherAppScope = DeviceLegStorageScope(ProfileDeliveryAuthority("other", "test"))
        val first = DeviceLegRunJournal(directory, "customer", firstScope)
        val run = requireNotNull(first.admit(arm(), JourneyReentry.EveryTime, "step", 100_000))
        first.markStartedQueued(run)
        first.park(run.id, "step", 200_000)

        assertEquals(run.id, DeviceLegRunJournal(directory, "customer", rotatedKeyScope).runs().single().id)
        assertTrue(DeviceLegRunJournal(directory, "customer", otherAppScope).runs().isEmpty())
    }

    @Test fun `live run retains its exact release until completion is queued`() {
        val (release, pinnedArm, entry) = retainedReleaseFixture()
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(
            journal.admit(
                pinnedArm,
                JourneyReentry.EveryTime,
                "step",
                100_000,
                release = release,
            ),
        )
        journal.markStartedQueued(run)
        journal.park(run.id, "step", 200_000)

        assertEquals(entry, DeviceLegRunJournal(directory, "customer").releasePin(release.envelope
            .getValue("descriptorSha256").jsonPrimitive.content))

        journal.complete(run.id, "done", 110_000)
        journal.markCompletionQueued(journal.runs().single())
        assertNull(journal.releasePin(release.envelope.getValue("descriptorSha256").jsonPrimitive.content))
    }

    @Test fun `recovery abandons a parked run whose retained locator no longer matches`() {
        val (release, pinnedArm, _) = retainedReleaseFixture()
        val scope = DeviceLegStorageScope.testFixture
        val journal = DeviceLegRunJournal(directory, "customer", scope)
        val run = requireNotNull(
            journal.admit(
                pinnedArm,
                JourneyReentry.EveryTime,
                "step",
                100_000,
                release = release,
            ),
        )
        journal.markStartedQueued(run)
        journal.park(run.id, "step", 200_000)

        val digest = release.envelope.getValue("descriptorSha256").jsonPrimitive.content
        val pinFile = File(
            directory,
            "device-leg-state-v1/release-pins/${scope.customerDigest("customer")}/$digest.json",
        )
        val entry = Json.parseToJsonElement(pinFile.readText()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        pinFile.writeText(
            JsonObject(
                entry + (
                    "locator" to JsonObject(
                        locator + ("experienceId" to JsonPrimitive("swapped-experience")),
                    )
                ),
            ).toString(),
        )

        val reopened = DeviceLegRunJournal(directory, "customer", scope)
        assertTrue(reopened.recover(150_000).isEmpty())
        val abandoned = reopened.runs().single()
        assertEquals("abandoned", abandoned.completion?.outcome)
        assertEquals(150_000L, abandoned.completion?.atMillis)
    }

    @Test fun `recovery abandons a parked run whose artifact closure is missing`() {
        val digest = "a".repeat(64)
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(
            journal.admit(
                arm(),
                JourneyReentry.EveryTime,
                "step",
                100_000,
                artifactDigests = setOf(digest),
            ),
        )
        journal.markStartedQueued(run)
        journal.park(run.id, "step", 200_000)

        val reopened = DeviceLegRunJournal(directory, "customer")
        assertEquals(setOf(digest), reopened.runs().single().artifactDigests)
        assertTrue(reopened.recover(150_000) { false }.isEmpty())
        assertEquals("abandoned", reopened.runs().single().completion?.outcome)
    }

    @Test fun `failed journal publication removes its newly written release pin`() {
        val (release, pinnedArm, _) = retainedReleaseFixture()
        val invalidArm = pinnedArm.copy(
            context = JsonObject(
                mapOf(
                    "event" to JsonObject(mapOf("invalid" to JsonPrimitive(Double.NaN))),
                    "responses" to JsonObject(emptyMap()),
                ),
            ),
        )
        val journal = DeviceLegRunJournal(directory, "customer")

        try {
            journal.admit(
                invalidArm,
                JourneyReentry.EveryTime,
                "step",
                100_000,
                release = release,
            )
            fail("Invalid journal JSON must reject admission")
        } catch (_: ai.nuxie.sdk.experiences.ReleaseAuthenticationException) { }

        val releaseRoot = File(directory, "device-leg-state-v1/release-pins")
        assertTrue(
            releaseRoot.walkTopDown().none { it.isFile && it.extension == "json" },
        )
    }

    @Test fun `run limit rejection does not write a release pin`() {
        val (release, pinnedArm, _) = retainedReleaseFixture()
        val journal = DeviceLegRunJournal(
            directory,
            "customer",
            maximumRunCount = 0,
        )

        try {
            journal.admit(
                pinnedArm,
                JourneyReentry.EveryTime,
                "step",
                100_000,
                release = release,
            )
            fail("Run limit must reject admission")
        } catch (_: java.io.IOException) { }

        val releaseRoot = File(directory, "device-leg-state-v1/release-pins")
        assertTrue(
            releaseRoot.walkTopDown().none { it.isFile && it.extension == "json" },
        )
    }

    @Test fun `another admission removes release pins whose journal is gone`() {
        val (release, pinnedArm, _) = retainedReleaseFixture()
        val scope = DeviceLegStorageScope.testFixture
        val orphanCustomer = "orphan-customer"
        assertNotNull(
            DeviceLegRunJournal(directory, orphanCustomer, scope).admit(
                pinnedArm,
                JourneyReentry.EveryTime,
                "step",
                100_000,
                release = release,
            ),
        )
        val root = File(directory, "device-leg-state-v1")
        val orphanDigest = scope.customerDigest(orphanCustomer)
        val orphanPins = File(root, "release-pins/$orphanDigest")
        assertTrue(orphanPins.isDirectory)
        assertTrue(File(root, "journals/$orphanDigest.json").delete())

        assertNotNull(
            DeviceLegRunJournal(directory, "active-customer", scope).admit(
                pinnedArm,
                JourneyReentry.EveryTime,
                "step",
                110_000,
                release = release,
            ),
        )

        assertFalse(orphanPins.exists())
    }

    @Test fun `effect identity is stable for one cursor visit and rotates after advance`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(journal.admit(arm(), JourneyReentry.EveryTime, "effect", 100_000))
        journal.markStartedQueued(run)

        val first = journal.claimEffect(run.id, "effect")
        assertEquals(first, journal.claimEffect(run.id, "effect"))
        journal.transition(run.id, "effect", run.context)
        assertNotEquals(first, journal.claimEffect(run.id, "effect"))
    }

    @Test fun `revocation blocks reopening until every abandonment is queued`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val run = requireNotNull(journal.admit(arm(), JourneyReentry.EveryTime, "wait", 100_000))
        journal.markStartedQueued(run)
        journal.park(run.id, "wait", 200_000)

        journal.abandonAll(150_000)
        assertNull(journal.admit(arm(), JourneyReentry.EveryTime, "step", 160_000))
        assertEquals("abandoned", journal.runs().single().completion?.outcome)
        assertFalse(journal.finalizeRevocation())

        journal.markCompletionQueued(journal.runs().single())
        assertTrue(journal.finalizeRevocation())
        assertNotNull(journal.admit(arm(), JourneyReentry.EveryTime, "step", 170_000))
    }

    @Test fun `state arm receipt is durable and cleared by foreground kind`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val armed = arm()
        val receipt = deviceLegStateArmReceipt(armed)
        assertNotNull(journal.admit(armed, JourneyReentry.EveryTime, "step", 100_000,
            stateArmReceipt = receipt))
        assertNull(journal.admit(armed, JourneyReentry.EveryTime, "step", 100_001,
            stateArmReceipt = receipt))

        journal.clearStateArmReceipts("app_foregrounded")
        assertNotNull(journal.admit(armed, JourneyReentry.EveryTime, "step", 100_002,
            stateArmReceipt = receipt))
    }

    @Test fun `state arm receipt is independent of JSON object key order`() {
        val armed = arm()
        val reordered = armed.copy(
            reference = JsonObject(
                linkedMapOf(
                    "descriptorSha256" to armed.reference.getValue("descriptorSha256"),
                    "legId" to armed.reference.getValue("legId"),
                    "versionId" to armed.reference.getValue("versionId"),
                    "experienceId" to armed.reference.getValue("experienceId"),
                ),
            ),
            entryCondition = JsonObject(
                linkedMapOf(
                    "condition" to JsonObject(
                        linkedMapOf(
                            "expr" to JsonObject(mapOf("value" to JsonPrimitive(true), "type" to JsonPrimitive("Bool"))),
                            "ir_version" to JsonPrimitive(1),
                        ),
                    ),
                    "type" to JsonPrimitive("app_foregrounded"),
                ),
            ),
        )

        assertEquals(deviceLegStateArmReceipt(armed), deviceLegStateArmReceipt(reordered))
    }

    @Test fun `checkmarks retire after delivery and the authored reentry window`() {
        val journal = DeviceLegRunJournal(directory, "customer")
        val window = JourneyReentry.OncePerWindow(100)
        val run = requireNotNull(
            journal.admit(arm(), window, "step", 100),
        )
        finish(journal, run, 110)
        assertEquals(window, journal.checkmark("experience")?.reentry)
        assertEquals(110L, journal.checkmark("experience")?.lastSeenLiveAtMillis)

        journal.retainCheckmarks(emptyMap(), atMillis = 209)
        assertNotNull(DeviceLegRunJournal(directory, "customer").checkmark("experience"))
        journal.retainCheckmarks(emptyMap(), atMillis = 210)
        assertNull(DeviceLegRunJournal(directory, "customer").checkmark("experience"))

        val everyTime = requireNotNull(
            journal.admit(arm(), JourneyReentry.EveryTime, "step", 300),
        )
        finish(journal, everyTime, 310)
        assertEquals(JourneyReentry.EveryTime, journal.checkmark("experience")?.reentry)
        assertEquals(310L, journal.checkmark("experience")?.lastSeenLiveAtMillis)
        journal.retainCheckmarks(emptyMap(), atMillis = 311)
        assertNull(journal.checkmark("experience"))
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
            journal.complete(
                run.id,
                vector.text("outcome"),
                vector.number("completedAtMillis"),
                outputs.getValue("event").jsonObject,
                outputs.getValue("responses").jsonObject,
            )
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

    private fun retainedReleaseFixture(): Triple<JourneyPlaneProfile.Release, JourneyPlaneProfile.Arm, JsonObject> {
        val fixture = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText(),
        ).jsonObject
        val entry = fixture.getValue("entry").jsonObject
        val locator = entry.getValue("locator").jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val identity = requireNotNull(ai.nuxie.sdk.experiences.ExperienceReleaseIdentity.fromJson(locator))
        val legId = locator.getValue("legId").jsonPrimitive.content
        val release = JourneyPlaneProfile.Release(identity, legId, envelope)
        val arm = JourneyPlaneProfile.Arm(
            reference = JsonObject(
                mapOf(
                    "experienceId" to locator.getValue("experienceId"),
                    "versionId" to locator.getValue("experienceVersionId"),
                    "legId" to locator.getValue("legId"),
                    "descriptorSha256" to envelope.getValue("descriptorSha256"),
                ),
            ),
            binding = JsonObject(mapOf("type" to JsonPrimitive("new"))),
            entryCondition = JsonObject(mapOf("type" to JsonPrimitive("app_foregrounded"))),
            context = JsonObject(mapOf("event" to JsonObject(emptyMap()), "responses" to JsonObject(emptyMap()))),
        )
        return Triple(release, arm, entry)
    }

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
