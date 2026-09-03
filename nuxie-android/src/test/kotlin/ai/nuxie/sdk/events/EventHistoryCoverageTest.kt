package ai.nuxie.sdk.events

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
class EventHistoryCoverageTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private var store: SQLiteEventStore? = null

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.filesDir, "nuxie")
        directory.deleteRecursively()
    }

    @After fun tearDown() = runBlocking {
        store?.close()
        directory.deleteRecursively()
        Unit
    }

    @Test fun `history coverage matches the pinned iOS vectors`() = runBlocking {
        val file = FixtureRunner.fixturesRoot().resolve("journeys/planes/history-coverage.json")
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("journey-history-coverage-v1", suite.getValue("suite").jsonPrimitive.content)
        for (element in suite.getValue("cases").jsonArray) {
            val vector = element.jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            store?.close()
            directory.deleteRecursively()
            var db = SQLiteEventStore(context, nowMillis = { vector.long("originMillis") }).also { store = it }
            assertEquals(name, vector.long("originMillis"), db.historyCoverageStartingAt())
            for (row in vector.getValue("events").jsonArray.map { it.jsonObject }) {
                db.insertPending(StoredEvent(id = row.getValue("id").jsonPrimitive.content,
                    name = "purchase", timestampMillis = row.long("timestampMillis"), distinctId = "person",
                    properties = JsonObject(mapOf("\$nuxie_event_origin" to row.getValue("origin")))))
                if (row.getValue("delivery").jsonPrimitive.content == "delivered") {
                    db.markDelivered(listOf(row.getValue("id").jsonPrimitive.content))
                }
            }
            for (step in vector.getValue("steps").jsonArray.map { it.jsonObject }) {
                when (step.getValue("op").jsonPrimitive.content) {
                    "prune" -> {
                        val result = db.pruneHistory(step.int("keeping"), step.long("olderThanMillis"))
                        assertEquals(name, step.int("countDeleted"), result.countDeleted)
                        assertEquals(name, step.int("ageDeleted"), result.ageDeleted)
                        assertEquals(name, step.long("coverageMillis"), result.coverageStartingAtMillis)
                        val retained = step.ids().filter { db.hasStableOutcome(it) }.toSet()
                        assertEquals(name, step.ids().toSet(), retained)
                        assertEquals(name, step.ids().size, db.countEvents("purchase", "person"))
                    }
                    "ack" -> db.markDelivered(step.ids())
                    "advance" -> assertEquals(name, step.long("coverageMillis"),
                        db.advanceHistoryCoverage(step.long("toMillis")))
                    "reopen" -> {
                        db.close()
                        db = SQLiteEventStore(context, nowMillis = { step.long("atMillis") }).also { store = it }
                        assertEquals(name, step.long("coverageMillis"), db.historyCoverageStartingAt())
                    }
                    "query" -> {
                        val rows = db.queryHistory("purchase", "person", step.long("sinceMillis"), null)
                        assertEquals(name, step.getValue("known").jsonPrimitive.boolean, rows != null)
                        if (rows != null) assertEquals(name, step.ids().toSet(), rows.map { it.id }.toSet())
                    }
                    else -> fail("Unknown fixture operation")
                }
            }
        }
    }

    @Test fun `failed coverage write rolls back pruning`() = runBlocking {
        val db = SQLiteEventStore(context, nowMillis = { 1000 }).also { store = it }
        db.insertPending(StoredEvent("survivor", "purchase", timestampMillis = 2000, distinctId = "person"))
        db.markDelivered(listOf("survivor"))
        AndroidSQLiteDriver().open(File(directory, "events.db").absolutePath).use { connection ->
            connection.prepare("""
                CREATE TRIGGER reject_coverage BEFORE UPDATE ON event_history_metadata
                BEGIN SELECT RAISE(ABORT, 'injected coverage failure'); END;
            """.trimIndent()).use { it.step() }
        }
        assertTrue(runCatching { db.pruneHistory(0, 3000) }.isFailure)
        assertTrue(db.hasStableOutcome("survivor"))
        assertEquals(1000, db.historyCoverageStartingAt())
        assertEquals(listOf("survivor"), db.queryHistory("purchase", "person", 1000, null)?.map { it.id })
    }

    @Test fun `legacy database migration never promises lifetime coverage`() = runBlocking {
        directory.mkdirs()
        AndroidSQLiteDriver().open(File(directory, "events.db").absolutePath).use { connection ->
            for (sql in listOf(
                "CREATE TABLE events (id TEXT PRIMARY KEY, name TEXT NOT NULL, properties BLOB NOT NULL, timestamp INTEGER NOT NULL, user_id TEXT NOT NULL, session_id TEXT, delivery_state INTEGER NOT NULL DEFAULT 2);",
                "CREATE TABLE stable_event_drops (event_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL);",
                "INSERT INTO events VALUES ('old', 'purchase', X'7B7D', 1000, 'person', NULL, 2);",
                "PRAGMA user_version = 2;",
            )) connection.prepare(sql).use { it.step() }
        }
        val db = SQLiteEventStore(context, nowMillis = { 5000 }).also { store = it }
        assertEquals(5000, db.historyCoverageStartingAt())
        assertNull(db.queryHistory("purchase", "person", null, null))
        assertNull(db.queryHistory("purchase", "person", 1000, null))
        assertTrue(db.hasStableOutcome("old"))
        assertEquals(emptyList<StoredEvent>(), db.queryHistory("purchase", "person", 5000, null))
    }

    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.ids() = getValue("ids").jsonArray.map { it.jsonPrimitive.content }
}
