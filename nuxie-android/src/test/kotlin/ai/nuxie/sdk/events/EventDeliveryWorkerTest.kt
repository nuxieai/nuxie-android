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
                is Int -> HttpTransport.Response(next, ByteArray(0))
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
