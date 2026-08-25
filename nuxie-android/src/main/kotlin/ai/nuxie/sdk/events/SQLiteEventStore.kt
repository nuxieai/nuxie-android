package ai.nuxie.sdk.events

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLite-backed event persistence.
 *
 * Its suspend interface confines the connection and every operation to one
 * writer dispatcher, matching the serialized access of the iOS actor.
 */
internal class SQLiteEventStore(
    context: Context,
    private val writerDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : EventStore {
    private val databaseFile = File(context.filesDir, "nuxie/events.db")
    private var connection: SQLiteConnection? = null
    private var closed = false

    override suspend fun insertPending(event: StoredEvent): Unit = onWriter { database ->
        database.prepare(
            """
            INSERT INTO events (id, name, properties, timestamp, user_id, session_id, delivery_state)
            VALUES (?, ?, ?, ?, ?, ?, ?);
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, event.id)
            statement.bindText(2, event.name)
            statement.bindBlob(3, event.encodedProperties())
            statement.bindLong(4, event.timestampMillis)
            statement.bindText(5, event.distinctId)
            event.sessionId?.let { statement.bindText(6, it) } ?: statement.bindNull(6)
            statement.bindLong(7, DELIVERY_PENDING)
            statement.step()
        }
        Unit
    }

    override suspend fun insertPendingIfAbsent(event: StoredEvent): Boolean = onWriter { database ->
        database.immediateTransaction {
            database.prepare(
                """
                INSERT OR IGNORE INTO events (id, name, properties, timestamp, user_id, session_id, delivery_state)
                SELECT ?, ?, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM stable_event_drops WHERE event_id = ?
                );
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, event.id)
                statement.bindText(2, event.name)
                statement.bindBlob(3, event.encodedProperties())
                statement.bindLong(4, event.timestampMillis)
                statement.bindText(5, event.distinctId)
                event.sessionId?.let { statement.bindText(6, it) } ?: statement.bindNull(6)
                statement.bindLong(7, DELIVERY_PENDING)
                statement.bindText(8, event.id)
                statement.step()
            }
            database.queryLong("SELECT changes();") == 1L
        }
    }

    override suspend fun hasStableOutcome(eventId: String): Boolean = onWriter { database ->
        database.prepare(
            """
            SELECT EXISTS(
                SELECT 1 FROM events WHERE id = ?
                UNION ALL
                SELECT 1 FROM stable_event_drops WHERE event_id = ?
            );
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, eventId)
            statement.bindText(2, eventId)
            check(statement.step()) { "Expected a stable-outcome result." }
            statement.getLong(0) == 1L
        }
    }

    override suspend fun insertDeliveredIfAbsent(event: StoredEvent): Boolean = onWriter { database ->
        database.immediateTransaction {
            database.prepare(
                """
                INSERT OR IGNORE INTO events (id, name, properties, timestamp, user_id, session_id, delivery_state)
                VALUES (?, ?, ?, ?, ?, ?, ?);
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, event.id)
                statement.bindText(2, event.name)
                statement.bindBlob(3, event.encodedProperties())
                statement.bindLong(4, event.timestampMillis)
                statement.bindText(5, event.distinctId)
                event.sessionId?.let { statement.bindText(6, it) } ?: statement.bindNull(6)
                statement.bindLong(7, DELIVERY_DELIVERED)
                statement.step()
            }
            database.queryLong("SELECT changes();") == 1L
        }
    }

    override suspend fun markDelivered(ids: List<String>) {
        if (ids.isEmpty()) return
        onWriter { database ->
            val placeholders = List(ids.size) { "?" }.joinToString(",")
            database.prepare(
                "UPDATE events SET delivery_state = $DELIVERY_DELIVERED WHERE id IN ($placeholders);",
            ).use { statement ->
                ids.forEachIndexed { index, id -> statement.bindText(index + 1, id) }
                statement.step()
            }
        }
    }

    override suspend fun reassignEvents(from: String, to: String): Int = onWriter { database ->
        database.immediateTransaction {
            database.prepare("UPDATE events SET user_id = ? WHERE user_id = ?;").use { statement ->
                statement.bindText(1, to)
                statement.bindText(2, from)
                statement.step()
            }
            database.queryLong("SELECT changes();").toInt()
        }
    }

    override suspend fun deleteOldestDeliveredEvents(keeping: Int): Int = onWriter { database ->
        database.immediateTransaction {
            database.prepare(
                """
                DELETE FROM events
                WHERE delivery_state = $DELIVERY_DELIVERED
                  AND id IN (
                    SELECT id FROM events
                    ORDER BY timestamp ASC
                    LIMIT max(0, (SELECT COUNT(*) FROM events) - ?)
                  );
                """.trimIndent(),
            ).use { statement ->
                statement.bindLong(1, keeping.toLong())
                statement.step()
            }
            database.queryLong("SELECT changes();").toInt()
        }
    }

    override suspend fun hasEvent(
        name: String,
        distinctId: String,
        sinceMillis: Long?,
    ): Boolean = onWriter { database ->
        val timeClause = if (sinceMillis == null) "" else " AND timestamp >= ?"
        database.prepare(
            """
            SELECT EXISTS(
                SELECT 1 FROM events
                WHERE user_id = ? AND name = ?$timeClause
                LIMIT 1
            );
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, distinctId)
            statement.bindText(2, name)
            sinceMillis?.let { statement.bindLong(3, it) }
            check(statement.step()) { "Expected an EXISTS result." }
            statement.getLong(0) != 0L
        }
    }

    override suspend fun countEvents(
        name: String,
        distinctId: String,
        sinceMillis: Long?,
        untilMillis: Long?,
    ): Int = onWriter { database ->
        database.queryEventAggregate("COUNT(*)", name, distinctId, sinceMillis, untilMillis)?.toInt() ?: 0
    }

    override suspend fun getFirstEventTime(
        name: String,
        distinctId: String,
        sinceMillis: Long?,
        untilMillis: Long?,
    ): Long? = onWriter { database ->
        database.queryEventAggregate("MIN(timestamp)", name, distinctId, sinceMillis, untilMillis)
    }

    override suspend fun getLastEventTime(
        name: String,
        distinctId: String,
        sinceMillis: Long?,
        untilMillis: Long?,
    ): Long? = onWriter { database ->
        database.queryEventAggregate("MAX(timestamp)", name, distinctId, sinceMillis, untilMillis)
    }

    override suspend fun querySessionEvents(sessionId: String): List<StoredEvent> = onWriter { database ->
        database.prepare(
            """
            SELECT id, name, properties, timestamp, user_id, session_id
            FROM events
            WHERE session_id = ?
            ORDER BY timestamp DESC;
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, sessionId)
            buildList {
                while (statement.step()) {
                    add(statement.readStoredEvent())
                }
            }
        }
    }

    override suspend fun recordStableDrop(
        eventId: String,
        recordedAtMillis: Long,
    ): Boolean = onWriter { database ->
        database.immediateTransaction {
            database.prepare(
                "INSERT OR IGNORE INTO stable_event_drops (event_id, created_at) VALUES (?, ?);",
            ).use { statement ->
                statement.bindText(1, eventId)
                statement.bindLong(2, recordedAtMillis)
                statement.step()
            }
            database.queryLong("SELECT changes();") == 1L
        }
    }

    override suspend fun pendingBatch(limit: Int): List<StoredEvent> = onWriter { database ->
        database.prepare(
            """
            SELECT id, name, properties, timestamp, user_id, session_id
            FROM events
            WHERE delivery_state = ?
            ORDER BY timestamp ASC, id ASC
            LIMIT ?;
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, DELIVERY_PENDING)
            statement.bindLong(2, limit.toLong())
            buildList {
                while (statement.step()) {
                    add(statement.readStoredEvent())
                }
            }
        }
    }

    override suspend fun close() {
        withContext(writerDispatcher) {
            connection?.close()
            connection = null
            closed = true
        }
    }

    private suspend fun <T> onWriter(block: (SQLiteConnection) -> T): T =
        withContext(writerDispatcher) { block(openConnection()) }

    private fun openConnection(): SQLiteConnection {
        check(!closed) { "Event store is closed." }
        connection?.let { return it }

        databaseFile.parentFile?.mkdirs()
        val opened = AndroidSQLiteDriver().open(databaseFile.absolutePath)
        try {
            opened.execute("PRAGMA journal_mode=WAL;")
            opened.execute("PRAGMA busy_timeout=5000;")
            opened.execute("PRAGMA synchronous=NORMAL;")
            opened.execute("PRAGMA foreign_keys=ON;")
            opened.execute(CREATE_EVENTS_TABLE)
            migrate(opened)
            CREATE_INDEXES.forEach { sql -> runCatching { opened.execute(sql) } }
        } catch (failure: Throwable) {
            opened.close()
            throw failure
        }
        return opened.also { connection = it }
    }

    private fun migrate(database: SQLiteConnection) {
        val version = database.queryLong("PRAGMA user_version;")
        if (version < 1L) {
            runCatching {
                database.execute(
                    "ALTER TABLE events ADD COLUMN delivery_state INTEGER NOT NULL DEFAULT 2;",
                )
            }
            database.execute("PRAGMA user_version = 1;")
        }
        if (version < 2L) {
            database.execute(CREATE_STABLE_DROPS_TABLE)
            database.execute("PRAGMA user_version = 2;")
        }
    }

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { it.step() }
    }

    private fun SQLiteConnection.queryLong(sql: String): Long = prepare(sql).use { statement ->
        check(statement.step()) { "Expected a row from: $sql" }
        statement.getLong(0)
    }

    private fun <T> SQLiteConnection.immediateTransaction(block: () -> T): T {
        execute("BEGIN IMMEDIATE;")
        try {
            return block().also { execute("COMMIT;") }
        } catch (failure: Throwable) {
            runCatching { execute("ROLLBACK;") }
            throw failure
        }
    }

    private fun SQLiteConnection.queryEventAggregate(
        aggregate: String,
        name: String,
        distinctId: String,
        sinceMillis: Long?,
        untilMillis: Long?,
    ): Long? {
        val sql = buildString {
            append("SELECT $aggregate FROM events WHERE user_id = ? AND name = ?")
            if (sinceMillis != null) append(" AND timestamp >= ?")
            if (untilMillis != null) append(" AND timestamp <= ?")
            append(';')
        }
        return prepare(sql).use { statement ->
            statement.bindEventQuery(distinctId, name, sinceMillis, untilMillis)
            check(statement.step()) { "Expected an aggregate result." }
            if (statement.isNull(0)) null else statement.getLong(0)
        }
    }

    private fun SQLiteStatement.bindEventQuery(
        distinctId: String,
        name: String,
        sinceMillis: Long?,
        untilMillis: Long?,
    ) {
        var index = 1
        bindText(index++, distinctId)
        bindText(index++, name)
        sinceMillis?.let { bindLong(index++, it) }
        untilMillis?.let { bindLong(index, it) }
    }

    private fun SQLiteStatement.readStoredEvent(): StoredEvent = StoredEvent.fromStorage(
        id = getText(0),
        name = getText(1),
        encodedProperties = getBlob(2),
        timestampMillis = getLong(3),
        distinctId = getText(4),
        sessionId = if (isNull(5)) null else getText(5),
    )

    private companion object {
        const val DELIVERY_PENDING = 0L
        const val DELIVERY_DELIVERED = 2L

        val CREATE_EVENTS_TABLE =
            """
            CREATE TABLE IF NOT EXISTS events (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                properties BLOB NOT NULL,
                timestamp INTEGER NOT NULL,
                user_id TEXT NOT NULL,
                session_id TEXT,
                delivery_state INTEGER NOT NULL DEFAULT 2
            );
            """.trimIndent()

        val CREATE_STABLE_DROPS_TABLE =
            """
            CREATE TABLE IF NOT EXISTS stable_event_drops (
                event_id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL
            );
            """.trimIndent()

        val CREATE_INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_events_delivery ON events(delivery_state, timestamp);",
            "CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp);",
            "CREATE INDEX IF NOT EXISTS idx_events_user_id ON events(user_id);",
            "CREATE INDEX IF NOT EXISTS idx_events_name ON events(name);",
            "CREATE INDEX IF NOT EXISTS idx_events_session_id ON events(session_id);",
            "CREATE INDEX IF NOT EXISTS idx_events_user_name_time ON events(user_id, name, timestamp DESC);",
            "CREATE INDEX IF NOT EXISTS idx_events_user_time ON events(user_id, timestamp DESC);",
            "CREATE INDEX IF NOT EXISTS idx_events_session_time ON events(session_id, timestamp DESC);",
        )
    }
}
