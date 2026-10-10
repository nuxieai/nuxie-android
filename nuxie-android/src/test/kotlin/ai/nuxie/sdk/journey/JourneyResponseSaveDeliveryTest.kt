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
import kotlinx.coroutines.async
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
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
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

    @Test fun `reservation precedes transport and observers see durable completion`() = runBlocking {
        for (queued in listOf(false, true)) {
            val journal = JourneyRunJournal(File(directory, "$queued"), "anon")
            val run = responseSaveRun(journal)
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<JourneyResponseSaveDisplay>()
            val delivery = worker(JourneyResponseSaveTransport { sheet ->
                sent.complete(Unit)
                release.await()
                JourneyResponseSaveReply(JourneyResponseSaveReply.Code.SAVED, sheet.sequence)
            })
            delivery.setDisplayObserver { source, journey ->
                val reopened = JourneyRunJournal(File(directory, "$queued"), source.distinctId)
                assertTrue(reopened.pendingResponseSaves().isEmpty())
                observed.complete(checkNotNull(reopened.responseSaveDisplays(journey)["feedback"]))
            }
            delivery.activate(JourneyStorageScope.testFixture)
            val sheet = if (queued) delivery.enqueue(journal, run, "feedback", json("{}"))
                else delivery.reserveWaiting(journal, run, "feedback", json("{}"))
            assertEquals(JourneyResponseSaveDisplay.saving(1), journal.responseSaveDisplays(run.journeyId)["feedback"])
            if (!queued) assertFalse(sent.isCompleted)
            val waiting = if (queued) null else async { delivery.sendWaiting(sheet, journal) }
            withTimeout(5_000) { sent.await() }
            release.complete(Unit)
            assertEquals(JourneyResponseSaveDisplay(1, false, true, ""), withTimeout(5_000) { observed.await() })
            waiting?.await()?.let { assertTrue(it.confirmed) }
            delivery.shutdown()
        }
    }

    @Test fun `whole number receipt spellings retain numeric sequences`(): Unit = kotlinx.coroutines.runBlocking {
        val rows = json(FixtureRunner.fixturesRoot().resolve("responses/save-cases.json").readText()).getValue("replies").jsonArray.takeLast(4)
        val sequences = rows.map { JourneyResponseSaveReply.decode(
            it.jsonObject.getValue("bodyText").jsonPrimitive.content.encodeToByteArray(), 1).sequence }
        assertEquals(listOf(1L, 7L, 1L, null), sequences)
    }

    @Test fun `shared wire and reply cases ignore HTTP status`() = runBlocking {
        val vectors = json(FixtureRunner.fixturesRoot().resolve("responses/save-cases.json").readText())
        val expected = vectors.getValue("request").jsonObject
        val rows = vectors.getValue("replies").jsonArray
        assertEquals(52, rows.size)
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
                    HttpTransport.Response(status, row.getValue("bodyText").jsonPrimitive.content.encodeToByteArray())
                }
                val received = api(transport).sendResponseSave(sheet)
                val stopped = journal.recordResponseSaveReply(sheet, received, 1_000_000)
                val outcome = row.getValue("expected").jsonPrimitive.content
                assertTrue(outcome in setOf("confirmed", "stopped", "retry", "retry_until_deadline"))
                assertEquals(outcome == "confirmed", received.confirmed)
                assertEquals(outcome == "stopped", stopped)
                assertEquals(if (outcome.startsWith("retry")) 1 else 0, journal.pendingResponseSaves().size)
                if (outcome.startsWith("retry")) assertEquals(5_000L, journal.responseSaveAttempts(1_000_000).single().delay(1_000_000))
                if (outcome == "retry_until_deadline") {
                    assertEquals(JourneyResponseSaveReply.Code.UNKNOWN_FORM, received.code)
                    assertTrue(journal.responseSaveAttempts(1_600_000).single().unknownFormExpired(1_600_000))
                }
            }
        }
    }

    @Test fun `waiting failure preserves older sheet and a new tap confirms once`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val older = journal.reserveResponseSave(run, "feedback", json("""{"stars":1}"""), true)
        journal.recordResponseSaveReply(older, JourneyResponseSaveReply.noAnswer, 1_000_000)
        val requests = CopyOnWriteArrayList<Long>()
        val delivery = worker(api(HttpTransport { request ->
            val sheet = JourneyResponseSave.fromJson(json(request.body.decodeToString()))
            requests += sheet.sequence
            assertEquals("anon", sheet.distinctId)
            val body = if (sheet.sequence == 2L) """{"status":"error","code":"save_unavailable"}""" else """{"status":"replayed","sequence":3}"""
            HttpTransport.Response(503, body.encodeToByteArray())
        }))
        delivery.activate(JourneyStorageScope.testFixture)
        assertFalse(delivery.sendWaiting(journal, run, "feedback", json("{}")).confirmed)
        assertEquals(listOf(older), journal.pendingResponseSaves())
        assertTrue(delivery.sendWaiting(journal, run, "feedback", json("{}")).confirmed)
        assertTrue(journal.pendingResponseSaves().isEmpty())
        assertEquals(listOf(2L, 3L), requests)
        assertEquals(4L, JourneyRunJournal(directory, "anon").reserveResponseSave(run, "feedback", json("{}"), false).sequence)
    }

    @Test fun `waiting transport sends empty and large exact sheets without filtering`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val large = JsonObject(buildMap {
            for (index in 0..<300) put("field-$index", JsonPrimitive("invalid as typed"))
            put("é", JsonPrimitive("composed")); put("e\u0301", JsonPrimitive("decomposed")); put("__proto__", JsonPrimitive(false))
        })
        for (answers in listOf(json("{}"), large)) {
            val delivery = worker(api(HttpTransport { request ->
                assertEquals(answers, json(request.body.decodeToString()).getValue("answers"))
                HttpTransport.Response(200, """{"status":"error","code":"invalid_request"}""".encodeToByteArray())
            }))
            delivery.activate(JourneyStorageScope.testFixture)
            assertEquals(JourneyResponseSaveReply.Code.INVALID_REQUEST, delivery.sendWaiting(journal, run, "feedback", answers).code)
            delivery.shutdown()
        }
    }

    @Test fun `waiting reply after run ends never erases a newer sheet`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<Long>()
        val delivery = worker(JourneyResponseSaveTransport { sheet ->
            requests += sheet.sequence
            started.complete(Unit)
            release.await()
            reply("""{"status":"saved","sequence":1}""")
        })
        delivery.activate(JourneyStorageScope.testFixture)
        val waiting = async { delivery.sendWaiting(journal, run, "feedback", json("{}")) }
        withTimeout(5_000) { started.await() }
        delivery.shutdown()
        val replacement = journal.reserveResponseSave(run, "feedback", json("""{"stars":2}"""), true)
        journal.markStartedQueued(run)
        journal.complete(run.id, "done", 2000)
        journal.markCompletionQueued(run)
        release.complete(Unit)
        assertTrue(waiting.await().confirmed)
        assertEquals(listOf(replacement), journal.pendingResponseSaves())
        assertEquals(listOf(1L), requests)
    }

    @Test fun `cancelled enqueue still wakes delivery after durable reservation`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val sent = CompletableDeferred<Unit>()
        val delivery = worker(JourneyResponseSaveTransport {
            sent.complete(Unit)
            reply("""{"status":"saved","sequence":1}""")
        })
        delivery.activate(JourneyStorageScope.testFixture)
        withTimeout(5_000) { while (scope.coroutineContext[kotlinx.coroutines.Job]!!.children.any()) delay(10) }
        val dispatcher = QueuedResponseSaveDispatcher()
        val callerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val enqueue = callerScope.async { delivery.enqueue(journal, run, "feedback", json("{}")) }
            dispatcher.next().run()
            val returnToCaller = dispatcher.next()
            enqueue.cancel()
            returnToCaller.run()
            assertTrue(enqueue.isCancelled)
            withTimeout(2_000) { sent.await() }
        } finally { callerScope.cancel() }
    }

    @Test fun `unknown form deadline and backoff survive restart and backward clock`(): Unit = kotlinx.coroutines.runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val sheet = journal.reserveResponseSave(run, "feedback", json("{}"), true)
        val unknown = reply("""{"status":"error","code":"unknown_form"}""")
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
        assertEquals(listOf(json("""{"apiKey":"test-key","distinct_id":"anon","journey_id":"01900000-0000-7000-8000-000000000001","experience_id":"experience-1","experience_version_id":"version-1","form_name":"feedback","sequence":2,"answers":{"stars":2}}""")), requests)
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

    @Test fun `background callback keeps an accepted save in flight`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<Long>()
        val delivery = worker(JourneyResponseSaveTransport { sheet ->
            requests += sheet.sequence
            started.complete(Unit)
            release.await()
            reply("""{"status":"saved","sequence":1}""")
        })
        val store = ai.nuxie.sdk.events.SQLiteEventStore(context)
        try {
            val catalog = ai.nuxie.sdk.experiences.JourneyProfileCatalog(emptyMap(),
                ai.nuxie.sdk.experiences.JourneyReleaseHighWaterStore(context)) { null }
            val service = JourneyService(ai.nuxie.sdk.identity.IdentityService(context), store, catalog, directory, scope,
                capture = { _, _, _, _ -> true }, responseSaveDelivery = delivery)
            delivery.activate(JourneyStorageScope.testFixture)
            delivery.enqueue(journal, run, "feedback", json("{}"))
            withTimeout(5_000) { started.await() }
            service.settleAppBackground()
            release.complete(Unit)
            withTimeout(5_000) { while (journal.pendingResponseSaves().isNotEmpty()) delay(10) }
            assertEquals(listOf(1L), requests)
        } finally { release.complete(Unit); store.close() }
    }

    @Test fun `enqueue during send does not reinstate receipt backoff`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        journal.reserveResponseSave(run, "first", json("{}"), true)
        val root = File(directory, "journey-state-v2/journals")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<JourneyResponseSave>()
        val sleeps = CopyOnWriteArrayList<Long>()
        val delivery = JourneyResponseSaveDelivery(directory, JourneyResponseSaveTransport { sheet ->
            requests += sheet
            if (requests.size == 1) {
                started.complete(Unit)
                release.await()
                assertTrue(root.setWritable(false))
            } else assertTrue(root.setWritable(true))
            JourneyResponseSaveReply(JourneyResponseSaveReply.Code.SAVED, sheet.sequence)
        }, scope, nowMillis = { 1_000_000 }, sleep = { sleeps += it; kotlinx.coroutines.awaitCancellation() })
            .also { workers += it }
        try {
            delivery.activate(JourneyStorageScope.testFixture)
            withTimeout(3_000) { started.await() }
            delivery.enqueue(journal, run, "new", json("{}"))
            release.complete(Unit)
            withTimeout(3_000) { while (journal.pendingResponseSaves().isNotEmpty()) delay(10) }
            assertTrue(sleeps.isEmpty())
            assertEquals(3, requests.size)
        } finally { release.complete(Unit); delivery.shutdown(); root.setWritable(true) }
    }

    @Test fun `receipt writes back off and recover`() = runBlocking { assertReceiptBackoff(false) }

    @Test fun `enqueue wakes receipt write backoff`() = runBlocking { assertReceiptBackoff(true) }

    private suspend fun assertReceiptBackoff(enqueueDuringBackoff: Boolean) {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        journal.reserveResponseSave(run, "first", json("{}"), true)
        val root = File(directory, "journey-state-v2/journals")
        val time = java.util.concurrent.atomic.AtomicLong(1_000_000)
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val requests = CopyOnWriteArrayList<JourneyResponseSave>()
        val sleeps = kotlinx.coroutines.channels.Channel<Pair<Long, CompletableDeferred<Unit>>>(10)
        val delivery = JourneyResponseSaveDelivery(directory, JourneyResponseSaveTransport { sheet ->
            requests += sheet
            if (failing.get()) assertTrue(root.setWritable(false))
            JourneyResponseSaveReply(JourneyResponseSaveReply.Code.SAVED, sheet.sequence)
        }, scope, nowMillis = time::get, sleep = { duration ->
            val release = CompletableDeferred<Unit>()
            sleeps.send(duration to release)
            release.await()
        }).also { workers += it }
        try {
            delivery.activate(JourneyStorageScope.testFixture)
            var pendingRelease: CompletableDeferred<Unit>? = null
            for ((index, expected) in listOf(5_000L, 10_000L, 20_000L).withIndex()) {
                val (duration, release) = withTimeout(3_000) { sleeps.receive() }
                assertEquals(expected, duration)
                assertEquals(index + 1, requests.size)
                pendingRelease = release
                if (index == 0) {
                    time.addAndGet(-86_400_000)
                    release.complete(Unit)
                    val (remaining, rollbackRelease) = withTimeout(3_000) { sleeps.receive() }
                    assertEquals(5_000L, remaining)
                    assertEquals(1, requests.size)
                    time.addAndGet(expected)
                    rollbackRelease.complete(Unit)
                } else if (index < 2) { time.addAndGet(expected); release.complete(Unit) }
            }
            failing.set(false)
            assertTrue(root.setWritable(true))
            if (enqueueDuringBackoff) delivery.enqueue(journal, run, "new", json("{}"))
            else { time.addAndGet(20_000); checkNotNull(pendingRelease).complete(Unit) }
            withTimeout(3_000) { while (journal.pendingResponseSaves().isNotEmpty()) delay(10) }
            assertEquals(if (enqueueDuringBackoff) 5 else 4, requests.size)
        } finally {
            delivery.shutdown()
            root.setWritable(true)
        }
    }

    @Test fun `receipt write failure never sends a replaced snapshot`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        journal.reserveResponseSave(run, "first", json("{}"), true)
        journal.reserveResponseSave(run, "second", json("{}"), true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<JourneyResponseSave>()
        val delivery = worker(JourneyResponseSaveTransport { sheet ->
            requests += sheet
            if (requests.size == 1) { started.complete(Unit); release.await() }
            reply("""{"status":"saved","sequence":2}""")
        })
        delivery.activate(JourneyStorageScope.testFixture)
        withTimeout(5_000) { started.await() }
        val other = if (requests.first().formName == "first") "second" else "first"
        delivery.enqueue(journal, run, other, json("""{"new":true}"""))
        val journals = File(directory, "journey-state-v2/journals")
        val retained = File(directory, "retained-journals")
        assertTrue(journals.renameTo(retained))
        journals.writeText("blocks receipt persistence")
        try {
            release.complete(Unit)
            delay(200)
            assertFalse(requests.any { it.formName == other && it.sequence == 1L })
            delivery.shutdown()
        } finally {
            delivery.shutdown()
            journals.delete()
            assertTrue(retained.renameTo(journals))
        }
        assertEquals(2, journal.pendingResponseSaves().size)
    }

    @Test fun `long backoff sleeps once and enqueue wakes it`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        val old = journal.reserveResponseSave(run, "old", json("{}"), true)
        repeat(7) { journal.recordResponseSaveReply(old, JourneyResponseSaveReply.noAnswer, 1_000_000) }
        val sleeps = CopyOnWriteArrayList<Long>()
        val sent = CompletableDeferred<Unit>()
        val delivery = JourneyResponseSaveDelivery(directory, JourneyResponseSaveTransport { sheet ->
            assertEquals("new", sheet.formName)
            sent.complete(Unit)
            reply("""{"status":"saved","sequence":1}""")
        }, scope, nowMillis = { 1_000_000 }, sleep = { sleeps += it; kotlinx.coroutines.awaitCancellation() })
            .also { workers += it }
        delivery.activate(JourneyStorageScope.testFixture)
        withTimeout(3_000) { while (sleeps.isEmpty()) delay(10) }
        assertEquals(listOf(300_000L), sleeps)
        delivery.enqueue(journal, run, "new", json("{}"))
        withTimeout(3_000) { sent.await() }
    }

    @Test fun `corrupt discovery backs off while valid owners deliver`() = runBlocking {
        val journal = JourneyRunJournal(directory, "anon")
        val run = responseSaveRun(journal)
        journal.reserveResponseSave(run, "feedback", json("{}"), true)
        File(directory, "journey-state-v2/journals/" + "c".repeat(64) + ".json").writeText("broken")
        val time = java.util.concurrent.atomic.AtomicLong(1_000_000)
        val sleeps = kotlinx.coroutines.channels.Channel<Pair<Long, CompletableDeferred<Unit>>>(10)
        val sent = CompletableDeferred<Unit>()
        val delivery = JourneyResponseSaveDelivery(directory, JourneyResponseSaveTransport {
            sent.complete(Unit)
            reply("""{"status":"saved","sequence":1}""")
        }, scope, nowMillis = time::get, sleep = { duration ->
            val release = CompletableDeferred<Unit>()
            sleeps.send(duration to release)
            release.await()
        }).also { workers += it }
        delivery.activate(JourneyStorageScope.testFixture)
        withTimeout(3_000) { sent.await() }
        for (expected in listOf(5_000L, 10_000L, 20_000L)) {
            val (duration, release) = withTimeout(3_000) { sleeps.receive() }
            assertEquals(expected, duration)
            if (expected == 5_000L) {
                time.addAndGet(-86_400_000)
                release.complete(Unit)
                val (rollbackDuration, rollbackRelease) = withTimeout(3_000) { sleeps.receive() }
                assertEquals(5_000L, rollbackDuration)
                time.addAndGet(expected)
                rollbackRelease.complete(Unit)
                continue
            }
            time.addAndGet(expected)
            release.complete(Unit)
        }
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

private class QueuedResponseSaveDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    private val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
    fun next(): Runnable = checkNotNull(queue.poll(5, java.util.concurrent.TimeUnit.SECONDS))
}
