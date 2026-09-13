package ai.nuxie.sdk.events

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.NuxieApi
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EventDeliveryWorkerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stores = mutableListOf<SQLiteEventStore>()
    private val logs = mutableListOf<EventLog>()
    private var now = 1_784_462_400_000L

    private class FakeIdentity : IdentityProvider {
        override fun distinctId(): String = "user-1"
        override fun anonymousId(): String = "anonymous-user"
        override fun rawDistinctId(): String? = "user-1"
        override val isIdentified: Boolean = true
    }

    private class ScriptedTransport(vararg outcomes: Any) : HttpTransport {
        private val script = outcomes.toMutableList()
        val batches = mutableListOf<List<String>>()

        override fun execute(request: HttpTransport.Request): HttpTransport.Response {
            assertEquals("gzip", request.headers["Content-Encoding"])
            val decodedBody = GZIPInputStream(request.body.inputStream())
                .bufferedReader()
                .use { it.readText() }
            val body = Json.parseToJsonElement(decodedBody).jsonObject
            batches.add(
                body.getValue("batch").jsonArray.map { item ->
                    (item as JsonObject).getValue("idempotency_key").jsonPrimitive.content
                },
            )
            return when (val next = if (script.isEmpty()) 200 else script.removeAt(0)) {
                is Int -> HttpTransport.Response(next,
                    """{"status":"success","processed":${batches.last().size},"failed":0,"total":${batches.last().size}}""".encodeToByteArray())
                is String -> HttpTransport.Response(200, next.encodeToByteArray())
                is IOException -> throw next
                else -> error("Unsupported script entry: $next")
            }
        }
    }

    private fun store(): SQLiteEventStore =
        SQLiteEventStore(RuntimeEnvironment.getApplication()).also(stores::add)

    private fun eventLog(store: EventStore): EventLog = EventLog(
        store = store,
        contextBuilder = NuxieContextBuilder(
            RuntimeEnvironment.getApplication(),
            NuxieEnvironment.DEVELOPMENT,
            LogLevel.NONE,
            FakeIdentity(),
        ),
        identity = FakeIdentity(),
        beforeSend = null,
        scope = scope,
        nowMillis = { now },
    ).also(logs::add)

    private fun worker(store: EventStore, transport: HttpTransport): EventDeliveryWorker =
        EventDeliveryWorker(
            store = store,
            api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, transport),
            scope = scope,
            nowMillis = { now },
        )

    private suspend fun seed(store: EventStore, count: Int, prefix: String = "event") {
        repeat(count) { index ->
            store.insertPending(
                StoredEvent(
                    id = "$prefix-$index",
                    name = "seeded",
                    timestampMillis = now + index,
                    distinctId = "user-1",
                ),
            )
        }
    }

    @After
    fun tearDown() = runBlocking {
        logs.asReversed().forEach { runCatching { it.close() } }
        scope.coroutineContext[Job]?.cancelAndJoin()
        stores.asReversed().forEach { it.close() }
        stores.clear()
    }

    @Test
    fun flushDeliversInBatchesAndFlipsJourneyReleaseDeliveryState() = runBlocking {
        val store = store()
        val transport = ScriptedTransport()
        val delivery = worker(store, transport)

        seed(store, 120)
        assertTrue(delivery.flushAll())

        assertEquals(listOf(50, 50, 20), transport.batches.map { it.size })
        assertTrue(store.pendingBatch(limit = 10).isEmpty())
        delivery.close()
        store.close()
    }

    @Test
    fun transportFailureRetainsPendingAndRetriesWithSameIdempotencyKeys() = runBlocking {
        val store = store()
        val transport = ScriptedTransport(IOException("offline"))
        val delivery = worker(store, transport)

        seed(store, 3)
        assertFalse(delivery.flushAll())
        assertEquals(3, store.pendingBatch(limit = 10).size)

        // Backoff applies to automatic flushes; a manual flush bypasses it.
        assertTrue(delivery.flushAll())
        assertTrue(store.pendingBatch(limit = 10).isEmpty())

        // Exactly-once on the wire: the retry reused the same idempotency keys.
        assertEquals(transport.batches[0], transport.batches[1])
        delivery.close()
        store.close()
    }

    @Test
    fun rejectedBatchesAlsoStayPending() = runBlocking {
        val store = store()
        val delivery = worker(store, ScriptedTransport(500))

        seed(store, 2)
        assertFalse(delivery.flushAll())
        assertEquals(2, store.pendingBatch(limit = 10).size)
        delivery.close()
        store.close()
    }

    @Test
    fun oversizedBatchesSplitAndTerminalPoisonCannotBlockNeighbors() = runBlocking {
        val store = store()
        val transport = ScriptedTransport(413, 200, 422, 200)
        val delivery = worker(store, transport)
        seed(store, 3)
        assertTrue(delivery.flushAll())
        assertEquals(listOf(listOf("event-0", "event-1", "event-2"),
            listOf("event-0"), listOf("event-1"), listOf("event-2")), transport.batches)
        assertTrue(store.pendingBatch(10).isEmpty())
        assertEquals(3, countAllRows(store))
        delivery.close()
    }

    @Test
    fun failedPoisonRetirementKeepsWorkerAliveAndEventPending() = runBlocking {
        val store = store()
        var failRetirement = true
        val faultyStore = object : EventStore by store {
            override suspend fun markDelivered(ids: List<String>) {
                if (failRetirement) {
                    failRetirement = false
                    throw IOException("disk unavailable")
                }
                store.markDelivered(ids)
            }
        }
        val delivery = worker(faultyStore, ScriptedTransport(422, 422))
        seed(store, 1)
        assertFalse(delivery.flushAll())
        assertEquals(1, store.pendingBatch(10).size)
        assertTrue(delivery.flushAll())
        assertTrue(store.pendingBatch(10).isEmpty())
        delivery.close()
    }

    @Test
    fun authenticationFailureRetainsTheEntireBatch() = runBlocking {
        val store = store()
        val transport = ScriptedTransport(401)
        val delivery = worker(store, transport)
        seed(store, 3)
        assertFalse(delivery.flushAll())
        assertEquals(3, store.pendingBatch(10).size)
        assertEquals(1, transport.batches.size)
        delivery.close()
    }

    @Test
    fun partialAcceptanceRetiresOnlySuccessfulRowsAndRetriesFailures() = runBlocking {
        val store = store()
        val transport = ScriptedTransport(
            """{"status":"partial","processed":2,"failed":1,"total":3,"errors":[{"index":1,"event":"seeded","error":"retry"}]}""",
            IOException("offline"),
        )
        val delivery = worker(store, transport)
        seed(store, 3)

        assertFalse(delivery.flushAll())
        assertEquals(listOf("event-1"), store.pendingBatch(10).map { it.id })
        assertEquals(listOf("event-1"), transport.batches.last())
        assertTrue(delivery.flushAll())
        assertTrue(store.pendingBatch(10).isEmpty())
        delivery.close()
    }

    @Test
    fun invalidAcknowledgmentRetainsEveryRow() = runBlocking {
        val store = store()
        val transport = ScriptedTransport(
            """{"status":"partial","processed":2,"failed":1,"total":4,"errors":[{"index":7,"event":"seeded","error":"retry"}]}""",
        )
        val delivery = worker(store, transport)
        seed(store, 3)
        assertFalse(delivery.flushAll())
        assertEquals(3, store.pendingBatch(10).size)
        assertTrue(delivery.flushAll())
        assertEquals(transport.batches.first(), transport.batches.last())
        delivery.close()
    }

    @Test
    fun validNoProgressAcknowledgmentStopsInsteadOfSpinning() = runBlocking {
        val store = store()
        val transport = ScriptedTransport(
            """{"status":"partial","processed":0,"failed":1,"total":1,"errors":[{"index":0,"event":"seeded","error":"retry"}]}""",
        )
        val delivery = worker(store, transport)
        seed(store, 1)
        assertFalse(delivery.flushAll())
        assertEquals(1, transport.batches.size)
        assertEquals(1, store.pendingBatch(10).size)
        delivery.close()
    }

    @Test
    fun deliveredRowsAreRetainedUpToTheStorageCap() = runBlocking {
        val store = store()
        val delivery = EventDeliveryWorker(
            store = store,
            api = NuxieApi("pk_test", NuxieEnvironment.DEVELOPMENT, ScriptedTransport()),
            scope = scope,
            maxEventsStored = 10,
            nowMillis = { now },
        )

        seed(store, 25)
        assertTrue(delivery.flushAll())

        // Everything delivered, then cleaned down to the cap.
        val remaining = countAllRows(store)
        assertEquals(10, remaining)
        delivery.close()
        store.close()
    }

    private suspend fun countAllRows(store: SQLiteEventStore): Int {
        var count = 0
        // Delivered events are not in pendingBatch; count via session query
        // shortcut: reassign to a known user then count by name.
        count = store.countEvents(name = "seeded", distinctId = "user-1")
        return count
    }
}
