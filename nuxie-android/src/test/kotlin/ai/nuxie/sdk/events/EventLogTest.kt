package ai.nuxie.sdk.events

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.identity.IdentityProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventLogTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class FakeIdentity : IdentityProvider {
        override fun distinctId(): String = "anon-1"
        override fun anonymousId(): String = "anon-1"
        override fun rawDistinctId(): String? = null
        override val isIdentified: Boolean = false
    }

    private class RecordingStore : EventStore {
        val pending = mutableListOf<StoredEvent>()
        val stableDrops = mutableListOf<String>()
        val delivered = mutableListOf<StoredEvent>()

        override suspend fun insertPending(event: StoredEvent) { pending.add(event) }
        override suspend fun insertDeliveredIfAbsent(event: StoredEvent): Boolean {
            if (pending.any { it.id == event.id } || delivered.any { it.id == event.id }) return false
            delivered.add(event)
            return true
        }
        override suspend fun markDelivered(ids: List<String>) = Unit
        override suspend fun hasEvent(name: String, distinctId: String, sinceMillis: Long?) = false
        override suspend fun countEvents(
            name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?,
        ) = 0
        override suspend fun getFirstEventTime(
            name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?,
        ): Long? = null
        override suspend fun getLastEventTime(
            name: String, distinctId: String, sinceMillis: Long?, untilMillis: Long?,
        ): Long? = null
        override suspend fun querySessionEvents(sessionId: String): List<StoredEvent> = emptyList()
        override suspend fun reassignEvents(from: String, to: String) = 0
        override suspend fun deleteOldestDeliveredEvents(keeping: Int) = 0
        override suspend fun recordStableDrop(eventId: String, recordedAtMillis: Long): Boolean {
            stableDrops.add(eventId)
            return true
        }
        override suspend fun pendingBatch(limit: Int): List<StoredEvent> = pending.take(limit)
        override suspend fun close() = Unit
    }

    private fun contextBuilder(): NuxieContextBuilder = NuxieContextBuilder(
        org.robolectric.RuntimeEnvironment.getApplication(),
        NuxieEnvironment.DEVELOPMENT,
        LogLevel.DEBUG,
        FakeIdentity(),
    )

    private fun log(
        store: EventStore,
        beforeSend: ((NuxieEvent) -> NuxieEvent?)? = null,
    ): EventLog = EventLog(
        store = store,
        contextBuilder = contextBuilder(),
        identity = FakeIdentity(),
        beforeSend = beforeSend,
        scope = scope,
        nowMillis = { 1_784_462_400_000L },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun committedSubscribersRunInSubscriptionOrderAfterPersistence() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store)
        val observed = mutableListOf<String>()

        eventLog.subscribeCommitted { event ->
            // Persistence-before-announcement: the store already holds it.
            assertTrue(store.pending.any { it.id == event.id })
            observed.add("first:${event.name}")
        }
        eventLog.subscribeCommitted { event -> observed.add("second:${event.name}") }

        eventLog.capture("one")
        eventLog.capture("two")
        eventLog.capture("three")
        eventLog.awaitBarrier()

        assertEquals(
            listOf(
                "first:one", "second:one",
                "first:two", "second:two",
                "first:three", "second:three",
            ),
            observed,
        )
        assertEquals(listOf("one", "two", "three"), store.pending.map { it.name })
    }

    @Test
    fun beforeSendRenameKeepsIdentityAndTimestamp() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store) { event ->
            NuxieEvent(
                id = "attacker-controlled-id",
                name = "renamed",
                distinctId = "attacker-controlled-user",
                properties = mapOf("kept" to true),
                timestampMillis = 1L,
            )
        }

        eventLog.capture("original")
        eventLog.awaitBarrier()

        val stored = store.pending.single()
        assertEquals("renamed", stored.name)
        // Recovery owns identity: id, distinctId, and timestamp are pinned.
        assertEquals("anon-1", stored.distinctId)
        assertEquals(1_784_462_400_000L, stored.timestampMillis)
        assertTrue(stored.id != "attacker-controlled-id")
    }

    @Test
    fun beforeSendNullTerminallyDropsAndRecordsStableDrop() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store) { null }

        eventLog.capture("dropped")
        eventLog.awaitBarrier()

        assertTrue(store.pending.isEmpty())
        assertEquals(1, store.stableDrops.size)
    }

    @Test
    fun captureEnrichesWithContextAndSanitizesCustomProperties() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store)

        eventLog.capture("enriched", mapOf("custom" to "x".repeat(2000)))
        eventLog.awaitBarrier()

        val properties = store.pending.single().properties
        assertEquals("nuxie-android", properties.stringValue("\$lib"))
        assertEquals("anon-1", properties.stringValue("\$distinct_id"))
        assertEquals("Android", properties.stringValue("\$os_name"))
        assertEquals(1000, properties.stringValue("custom")?.length)
    }

    @Test
    fun emptyEventNamesAreIgnored() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store)

        eventLog.capture("")
        eventLog.capture("kept")
        eventLog.awaitBarrier()

        assertEquals(listOf("kept"), store.pending.map { it.name })
    }

    @Test
    fun customPropertiesWinOverEnrichment() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store)

        eventLog.capture("override", mapOf("\$lib" to "custom-lib"))
        eventLog.awaitBarrier()

        assertEquals("custom-lib", store.pending.single().properties.stringValue("\$lib"))
    }

    @Test
    fun subscriberFailureDoesNotStopLaterSubscribersOrCaptures() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store)
        val observed = mutableListOf<String>()

        eventLog.subscribeCommitted { error("subscriber exploded") }
        eventLog.subscribeCommitted { event -> observed.add(event.name) }

        eventLog.capture("one")
        eventLog.capture("two")
        eventLog.awaitBarrier()

        assertEquals(listOf("one", "two"), observed)
        assertNull(store.pending.firstOrNull { it.name != "one" && it.name != "two" })
    }

    private fun kotlinx.serialization.json.JsonObject.stringValue(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
