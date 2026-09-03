package ai.nuxie.sdk.events

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class SQLiteEventStoreTest {
    private lateinit var context: Context
    private lateinit var databaseDirectory: File
    private var store: SQLiteEventStore? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseDirectory = File(context.filesDir, "nuxie")
        databaseDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        runBlocking { store?.close() }
        store = null
        databaseDirectory.deleteRecursively()
    }

    @Test
    fun aggregateQueriesUseInclusiveTimeBounds() = runBlocking {
        val eventStore = SQLiteEventStore(context).also { store = it }
        eventStore.insertPending(storedEvent("first", 1_000, name = "purchase"))
        eventStore.insertPending(storedEvent("middle", 2_000, name = "purchase"))
        eventStore.insertPending(storedEvent("last", 3_000, name = "purchase"))
        eventStore.insertPending(storedEvent("different", 2_500, name = "other"))

        val since = 2_000L
        val until = 3_000L
        assertEquals(2, eventStore.countEvents("purchase", "user-1", since, until))
        assertEquals(since, eventStore.getFirstEventTime("purchase", "user-1", since, until))
        assertEquals(until, eventStore.getLastEventTime("purchase", "user-1", since, until))
    }

    @Test
    fun stableDropsAreIdempotentAndSurviveReopeningTheStore() = runBlocking {
        var eventStore = SQLiteEventStore(context).also { store = it }
        assertTrue(eventStore.recordStableDrop("stable-event", 1_000))
        assertTrue(eventStore.hasStableOutcome("stable-event"))
        eventStore.close()

        eventStore = SQLiteEventStore(context).also { store = it }
        assertFalse(eventStore.recordStableDrop("stable-event", 2_000))
    }

    @Test
    fun localRouteSurvivesReopeningUntilAcknowledged() = runBlocking {
        val event = storedEvent(
            id = "stable-route",
            timestampMillis = 1_000,
            distinctId = "owner-1",
            name = "journey-trigger",
        )
        var eventStore = SQLiteEventStore(context).also { store = it }
        val committed = eventStore.insertPendingAndStageRoute(event)
        assertTrue(committed.inserted)
        assertTrue(committed.localRoutePending)
        eventStore.close()

        eventStore = SQLiteEventStore(context).also { store = it }
        assertEquals(
            listOf("stable-route"),
            eventStore.queryPendingLocalRoutes("owner-1").map(StoredEvent::id),
        )
        assertTrue(eventStore.queryPendingLocalRoutes("other-owner").isEmpty())
        eventStore.markLocalRouteDelivered("stable-route")
        eventStore.close()

        eventStore = SQLiteEventStore(context).also { store = it }
        assertTrue(eventStore.queryPendingLocalRoutes("owner-1").isEmpty())
        assertTrue(eventStore.hasStableOutcome("stable-route"))
    }

    @Test
    fun sessionQueriesReturnNewestEventsFirst() = runBlocking {
        val eventStore = SQLiteEventStore(context).also { store = it }
        eventStore.insertPending(storedEvent("oldest", 1_000, sessionId = "session-1"))
        eventStore.insertPending(storedEvent("newest", 3_000, sessionId = "session-1"))
        eventStore.insertPending(storedEvent("middle", 2_000, sessionId = "session-1"))
        eventStore.insertPending(storedEvent("other", 4_000, sessionId = "session-2"))

        assertEquals(
            listOf("newest", "middle", "oldest"),
            eventStore.querySessionEvents("session-1").map(StoredEvent::id),
        )
    }

    @Test
    fun deletingOldestDeliveredEventsKeepsTheNewestRows() = runBlocking {
        val eventStore = SQLiteEventStore(context).also { store = it }
        val events = listOf(
            storedEvent(id = "oldest", name = "oldest", timestampMillis = 1_000),
            storedEvent(id = "old", name = "old", timestampMillis = 2_000),
            storedEvent(id = "new", name = "new", timestampMillis = 3_000),
            storedEvent(id = "newest", name = "newest", timestampMillis = 4_000),
        )
        events.forEach { eventStore.insertPending(it) }
        eventStore.markDelivered(events.map(StoredEvent::id))

        assertEquals(2, eventStore.deleteOldestDeliveredEvents(keeping = 2))

        assertFalse(eventStore.hasEvent(name = "oldest", distinctId = "user-1"))
        assertFalse(eventStore.hasEvent(name = "old", distinctId = "user-1"))
        assertTrue(eventStore.hasEvent(name = "new", distinctId = "user-1"))
        assertTrue(eventStore.hasEvent(name = "newest", distinctId = "user-1"))
    }

    @Test
    fun reassignEventsMovesEveryMatchingRowToTheNewDistinctId() = runBlocking {
        val eventStore = SQLiteEventStore(context).also { store = it }
        eventStore.insertPending(storedEvent(id = "anonymous", timestampMillis = 1_000, distinctId = "anon-1"))
        eventStore.insertPending(storedEvent(id = "other", timestampMillis = 2_000, distinctId = "user-2"))

        assertEquals(1, eventStore.reassignEvents(from = "anon-1", to = "user-1"))

        assertEquals(
            listOf("user-1", "user-2"),
            eventStore.pendingBatch(10).map(StoredEvent::distinctId),
        )
    }

    @Test
    fun markingAPendingEventDeliveredRemovesItFromTheBatch() = runBlocking {
        val eventStore = SQLiteEventStore(context).also { store = it }
        val event = storedEvent(id = "event-1", timestampMillis = 1_000)

        eventStore.insertPending(event)
        assertEquals(listOf("event-1"), eventStore.pendingBatch(10).map(StoredEvent::id))

        eventStore.markDelivered(listOf("event-1"))

        assertEquals(emptyList<StoredEvent>(), eventStore.pendingBatch(10))
    }

    @Test
    fun deliveredInsertIsAtomicIdempotentAndNeverAppearsInThePendingBatch() = runBlocking {
        val eventStore = SQLiteEventStore(context).also { store = it }
        val event = storedEvent(id = "server-fact", timestampMillis = 1_000)

        assertTrue(eventStore.insertDeliveredIfAbsent(event))
        assertFalse(eventStore.insertDeliveredIfAbsent(event))
        assertEquals(emptyList<StoredEvent>(), eventStore.pendingBatch(10))
        assertTrue(eventStore.hasEvent(event.name, event.distinctId))
    }

    @Test
    fun migratesAnEmptyDatabaseToVersionFourWithTheExactSchema() = runBlocking {
        val eventStore = SQLiteEventStore(context).also { store = it }

        assertEquals(emptyList<StoredEvent>(), eventStore.pendingBatch(limit = 1))
        eventStore.close()
        store = null

        val connection = AndroidSQLiteDriver().open(File(databaseDirectory, "events.db").absolutePath)
        connection.use {
            assertEquals(4L, it.queryLong("PRAGMA user_version;"))
            assertEquals(
                setOf(
                    "events",
                    "stable_event_drops",
                    "event_local_routes",
                    "event_history_metadata",
                ),
                it.queryStrings(
                    "SELECT name FROM sqlite_master " +
                        "WHERE type = 'table' AND name IN " +
                        "('events', 'stable_event_drops', 'event_local_routes', 'event_history_metadata');",
                ),
            )
            assertEquals(
                setOf(
                    "idx_events_delivery",
                    "idx_events_timestamp",
                    "idx_events_user_id",
                    "idx_events_name",
                    "idx_events_session_id",
                    "idx_events_user_name_time",
                    "idx_events_user_time",
                    "idx_events_session_time",
                ),
                it.queryStrings("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_events_%';"),
            )
        }
    }

    private fun SQLiteConnection.queryLong(sql: String): Long = prepare(sql).use { statement ->
        check(statement.step()) { "Expected one row from $sql" }
        statement.getLong(0)
    }

    private fun SQLiteConnection.queryStrings(sql: String): Set<String> = prepare(sql).use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(0))
        }
    }

    private fun storedEvent(
        id: String,
        timestampMillis: Long,
        distinctId: String = "user-1",
        sessionId: String? = null,
        name: String = "button_clicked",
    ): StoredEvent {
        val properties = buildMap {
            put("sequence", JsonPrimitive(timestampMillis))
            sessionId?.let { put("\$session_id", JsonPrimitive(it)) }
        }
        return StoredEvent(
            id = id,
            name = name,
            properties = JsonObject(properties),
            timestampMillis = timestampMillis,
            distinctId = distinctId,
        )
    }
}
