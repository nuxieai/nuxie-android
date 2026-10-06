package ai.nuxie.sdk.journey

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.fixtures.FixtureRunner
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class JourneyResponseSaveDeliveryTest {
    private lateinit var directory: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<JourneyResponseSaveDelivery>()
    @Before fun setup() { directory = File(RuntimeEnvironment.getApplication().filesDir, "response-save-delivery").apply { mkdirs() } }
    @After fun cleanup() = runBlocking { workers.forEach { it.shutdown() }; scope.cancel(); directory.deleteRecursively(); Unit }
    private fun api(transport: HttpTransport) = NuxieApi("test-key", NuxieEnvironment.DEVELOPMENT, transport, URL("https://response-save.test"))
    private fun worker(transport: JourneyResponseSaveTransport, now: () -> Long = { 1_000_000 }) =
        JourneyResponseSaveDelivery(directory, transport, scope, now, sleep = { delay(it) }).also { workers += it }
    private fun reply(value: String, sequence: Long = 1) = JourneyResponseSaveReply.decode(value.encodeToByteArray(), sequence)

    @Test fun `shared wire and reply cases ignore HTTP status`() = runBlocking {
        val vectors = json(FixtureRunner.fixturesRoot().resolve("responses/save-cases.json").readText())
        val expected = vectors.getValue("request").jsonObject
        val rows = vectors.getValue("replies").jsonArray
        assertEquals(17, rows.size)
        for ((index, element) in rows.withIndex()) {
            val row = element.jsonObject
            for (status in listOf(row.getValue("httpStatus").jsonPrimitive.int, 201)) {
                val journal = JourneyRunJournal(File(directory, "$index-$status"), "anon")
                val run = responseSaveRun(journal)
                val sheet = journal.reserveResponseSave(run, "feedback", expected.getValue("answers").jsonObject, true)
                val transport = HttpTransport { request ->
                    assertEquals("/responses/save", request.url.path)
                    assertEquals("POST", request.method)
                    assertEquals(expected, json(request.body.decodeToString()))
                    HttpTransport.Response(status, row.getValue("body").toString().encodeToByteArray())
                }
                val received = api(transport).sendResponseSave(sheet)
                val stopped = journal.recordResponseSaveReply(sheet, received, 1_000_000)
                val outcome = row.getValue("expected").jsonPrimitive.content
                assertTrue(outcome in setOf("confirmed", "stopped", "retry", "retry_until_deadline"))
                assertEquals(outcome == "confirmed", received.confirmed)
                assertEquals(outcome == "stopped", stopped)
                assertEquals(if (outcome.startsWith("retry")) 1 else 0, journal.pendingResponseSaves().size)
                if (outcome.startsWith("retry")) assertEquals(5_000L, journal.responseSaveAttempts(1_000_000).single().delay(1_000_000))
            }
        }
    }

    @Test fun `unknown form deadline and backoff survive restart and backward clock`() {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val sheet = journal.reserveResponseSave(run, "feedback", json("{}"), true)
        val unknown = reply("""{"status":"unknown_form"}""")
        journal.recordResponseSaveReply(sheet, unknown, 1_000_000)
        val reopened = JourneyRunJournal(directory, "anon")
        val backward = reopened.responseSaveAttempts(900_000).single()
        assertEquals(5_000L, backward.delay(900_000))
        assertFalse(backward.unknownFormExpired(1_499_999))
        val restarted = JourneyRunJournal(directory, "anon")
        assertFalse(restarted.responseSaveAttempts(1_499_999).single().unknownFormExpired(1_499_999))
        assertTrue(restarted.responseSaveAttempts(1_500_000).single().unknownFormExpired(1_500_000))
        assertTrue(restarted.recordResponseSaveReply(sheet, unknown, 1_500_000))
        assertTrue(restarted.pendingResponseSaves().isEmpty())
        val next = restarted.reserveResponseSave(run, "feedback", json("{}"), true)
        for ((time, delay) in listOf(2_000_000L to 5_000L, 2_005_000L to 10_000L, 2_015_000L to 20_000L)) {
            val current = JourneyRunJournal(directory, "anon")
            current.recordResponseSaveReply(next, JourneyResponseSaveReply.noAnswer, time)
            assertEquals(delay, current.responseSaveAttempts(time).single().delay(time))
        }
    }

    @Test fun `worker recovers newest anonymous sheet after offline completion and sign in`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val attempted = CompletableDeferred<Unit>()
        val first = worker(api(HttpTransport { attempted.complete(Unit); throw IOException("offline") }))
        first.activate(JourneyStorageScope.testFixture)
        first.enqueue(journal, run, "feedback", json("""{"stars":1}"""))
        withTimeout(5_000) { attempted.await() }
        first.shutdown()
        journal.reserveResponseSave(run, "feedback", json("""{"stars":2}"""), true)
        journal.markStartedQueued(run)
        journal.complete(run.id, "done", 2000)
        journal.markCompletionQueued(run)
        assertTrue(JourneyRunJournal(directory, "signed-in").pendingResponseSaves().isEmpty())
        val requests = CopyOnWriteArrayList<JsonObject>()
        val second = worker(api(HttpTransport { request ->
            requests += json(request.body.decodeToString())
            HttpTransport.Response(503, """{"status":"saved","sequence":2}""".encodeToByteArray())
        }))
        second.activate(JourneyStorageScope.testFixture)
        withTimeout(5_000) { while (journal.pendingResponseSaves().isNotEmpty()) delay(10) }
        assertEquals(listOf(json("""{"apiKey":"test-key","distinct_id":"anon","journey_id":"journey-1","experience_id":"experience-1","experience_version_id":"version-1","form_name":"feedback","sequence":2,"answers":{"stars":2}}""")), requests)
    }

    @Test fun `unreadable owner does not block a valid journal`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        journal.reserveResponseSave(run, "feedback", json("{}"), true)
        val damaged = File(directory, "journey-state-v2/journals/${"c".repeat(64)}.json").apply { writeText("unreadable") }
        val recovery = JourneyRunJournal.recoverResponseSaveOwners(directory, JourneyStorageScope.testFixture)
        assertEquals(listOf("anon"), recovery.owners)
        assertTrue(recovery.needsRetry)
        val delivery = worker(api(HttpTransport { HttpTransport.Response(200, """{"status":"saved","sequence":1}""".encodeToByteArray()) }))
        delivery.activate(JourneyStorageScope.testFixture)
        withTimeout(5_000) { while (journal.pendingResponseSaves().isNotEmpty()) delay(10) }
        assertEquals("unreadable", damaged.readText())
    }

    @Test fun `worker reselects sheets after an awaited send`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        journal.reserveResponseSave(run, "first", json("{}"), true)
        journal.reserveResponseSave(run, "second", json("{}"), true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<JourneyResponseSave>()
        val delivery = worker(JourneyResponseSaveTransport { sheet ->
            requests += sheet
            if (requests.size == 1) { started.complete(Unit); release.await(); reply("""{"status":"saved","sequence":1}""") }
            else reply("""{"status":"saved","sequence":2}""")
        })
        delivery.activate(JourneyStorageScope.testFixture)
        withTimeout(5_000) { started.await() }
        val other = if (requests.first().formName == "first") "second" else "first"
        delivery.enqueue(journal, run, other, json("""{"new":true}"""))
        release.complete(Unit)
        withTimeout(5_000) { while (journal.pendingResponseSaves().isNotEmpty()) delay(10) }
        assertEquals(listOf(2L), requests.filter { it.formName == other }.map { it.sequence })
    }
}
