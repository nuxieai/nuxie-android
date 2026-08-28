package ai.nuxie.sdk.events

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.identity.IdentityProvider
import ai.nuxie.sdk.journey.JourneyEventNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        var onPendingInserted: (StoredEvent) -> Unit = {}

        override suspend fun insertPending(event: StoredEvent) {
            pending.add(event)
            onPendingInserted(event)
        }
        override suspend fun insertPendingIfAbsent(event: StoredEvent): Boolean {
            if (pending.any { it.id == event.id } || delivered.any { it.id == event.id } ||
                event.id in stableDrops
            ) return false
            pending.add(event)
            return true
        }
        override suspend fun hasStableOutcome(eventId: String): Boolean =
            pending.any { it.id == eventId } || delivered.any { it.id == eventId } || eventId in stableDrops
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
            if (eventId in stableDrops) return false
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
        forwardingEnabled: () -> Boolean = { false },
        nowMillis: () -> Long = { 1_784_462_400_000L },
        sessionIdProvider: (() -> String?)? = null,
        beforeSend: ((NuxieEvent) -> NuxieEvent?)? = null,
    ): EventLog = EventLog(
        store = store,
        contextBuilder = contextBuilder(),
        identity = FakeIdentity(),
        beforeSend = beforeSend,
        scope = scope,
        nowMillis = nowMillis,
        sessionIdProvider = sessionIdProvider,
    ).also { eventLog ->
        eventLog.subscribeForwarding(isEnabled = forwardingEnabled) {}
    }

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
    fun slowForwardingDoesNotDelayLaterPersistenceAndCallbacksStayFifo() = runBlocking {
        val store = RecordingStore()
        val secondPersisted = CompletableDeferred<Unit>()
        store.onPendingInserted = { event ->
            if (event.name == "second") secondPersisted.complete(Unit)
        }
        val eventLog = log(store)
        val firstForwardingStarted = CompletableDeferred<Unit>()
        val releaseFirstForwarding = CompletableDeferred<Unit>()
        val forwarded = mutableListOf<String>()
        eventLog.subscribeForwarding { event ->
            if (event.name == "first") {
                firstForwardingStarted.complete(Unit)
                releaseFirstForwarding.await()
            }
            forwarded += event.name
        }

        try {
            eventLog.capture("first")
            withTimeout(1_000L) { firstForwardingStarted.await() }

            eventLog.capture("second")
            withTimeout(1_000L) { secondPersisted.await() }
        } finally {
            releaseFirstForwarding.complete(Unit)
        }
        eventLog.awaitBarrier()

        assertEquals(listOf("first", "second"), store.pending.map { it.name })
        assertEquals(listOf("first", "second"), forwarded)
    }

    @Test
    fun stableIdCaptureIsDurableAndIdempotent() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store)
        var announcements = 0
        eventLog.subscribeCommitted { announcements += 1 }

        assertTrue(eventLog.captureIdempotently("\$purchase_synced", emptyMap(), "stable-id", "owner-1"))
        assertTrue(eventLog.captureIdempotently("\$purchase_synced", emptyMap(), "stable-id", "owner-1"))

        assertEquals(listOf("stable-id"), store.pending.map { it.id })
        assertEquals("owner-1", store.pending.single().distinctId)
        assertEquals(1, announcements)
    }

    @Test
    fun stableIdBeforeSendDropIsTerminalAndIdempotent() = runBlocking {
        val store = RecordingStore()
        var hookCalls = 0
        val eventLog = log(store) {
            hookCalls += 1
            if (hookCalls == 1) null else error("Stable outcome must bypass beforeSend")
        }

        assertTrue(eventLog.captureIdempotently("\$purchase_synced", emptyMap(), "stable-id", "owner-1"))
        assertTrue(eventLog.captureIdempotently("\$purchase_synced", emptyMap(), "stable-id", "owner-1"))

        assertEquals(listOf("stable-id"), store.stableDrops)
        assertTrue(store.pending.isEmpty())
        assertEquals(1, hookCalls)
    }

    @Test
    fun acceptedDirectEventCommitsDeliveredAndAnnouncesExactlyOnce() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store)
        val observed = mutableListOf<StoredEvent>()
        eventLog.subscribeCommitted { observed += it }

        assertTrue(
            eventLog.captureDeliveredIdempotently(
                SystemEventNames.FEATURE_USED,
                mapOf("feature_id" to "credits", "amount" to 1.0),
                "accepted-event",
                "owner-1",
            ),
        )
        assertTrue(
            eventLog.captureDeliveredIdempotently(
                SystemEventNames.FEATURE_USED,
                mapOf("feature_id" to "credits", "amount" to 1.0),
                "accepted-event",
                "owner-1",
            ),
        )

        assertTrue(store.pending.isEmpty())
        assertEquals(listOf("accepted-event"), store.delivered.map(StoredEvent::id))
        assertEquals(listOf("accepted-event"), observed.map(StoredEvent::id))
    }

    @Test
    fun acceptedFeatureUseCarriesActiveSessionIdInDeliveredHistory() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store, sessionIdProvider = { "session-1" })

        assertTrue(
            eventLog.captureDeliveredIdempotently(
                SystemEventNames.FEATURE_USED,
                mapOf("feature_id" to "credits", "amount" to 1.0),
                "accepted-event",
                "owner-1",
            ),
        )

        val delivered = store.delivered.single()
        assertEquals("session-1", delivered.sessionId)
        assertEquals("session-1", delivered.properties.stringValue("\$session_id"))
    }

    @Test
    fun acceptedFeatureUsePreservesBeforeSendIdentityAndTimestamp() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store) {
            NuxieEvent(
                id = "transformed-id",
                name = "transformed-feature-use",
                distinctId = "transformed-user",
                properties = mapOf("transformed" to "yes"),
                timestampMillis = 1_234L,
            )
        }

        assertTrue(
            eventLog.captureDeliveredIdempotently(
                SystemEventNames.FEATURE_USED,
                mapOf("feature_id" to "credits", "amount" to 1.0),
                "accepted-event",
                "owner-1",
            ),
        )

        val stored = store.delivered.single()
        assertEquals("transformed-id", stored.id)
        assertEquals("transformed-feature-use", stored.name)
        assertEquals("transformed-user", stored.distinctId)
        assertEquals(1_234L, stored.timestampMillis)
        assertEquals("yes", stored.properties.stringValue("transformed"))
        assertEquals(SystemEventNames.FEATURE_USED, stored.forwardingName)
    }

    @Test
    fun deliveredEventUsesBeforeSendTimestampForForwardingEnvelope() = runBlocking {
        val store = RecordingStore()
        val forwardedTimestamps = mutableListOf<Pair<Long, Long>>()
        val eventLog = log(
            store = store,
            forwardingEnabled = { true },
            nowMillis = { 1_000L },
            beforeSend = { event ->
                NuxieEvent(
                    id = event.id,
                    name = event.name,
                    distinctId = event.distinctId,
                    properties = event.properties,
                    timestampMillis = 2_000L,
                )
            },
        )
        val forwarder = ActivityForwarder(
            resolveExperience = { _, _ -> null },
            deliver = { forwardedTimestamps += it.timestampMillis to it.receivedAtMillis },
        )
        eventLog.subscribeForwarding { event -> forwarder.onCommitted(event) }

        assertTrue(
            eventLog.captureDeliveredIdempotently(
                SystemEventNames.APP_OPENED,
                emptyMap(),
                "accepted-event",
                "owner-1",
            ),
        )
        eventLog.awaitBarrier()

        assertEquals(listOf(2_000L to 2_000L), forwardedTimestamps)
    }

    @Test
    fun ordinaryCapturePreservesBeforeSendIdAndTimestampThroughForwarding() = runBlocking {
        val store = RecordingStore()
        val forwarded = mutableListOf<ai.nuxie.sdk.NuxieActivityInfo>()
        val eventLog = log(
            store = store,
            forwardingEnabled = { true },
            nowMillis = { 1_000L },
            beforeSend = {
                NuxieEvent(
                    id = "transformed-id",
                    name = "transformed-name",
                    distinctId = "transformed-user",
                    properties = emptyMap(),
                    timestampMillis = 2_000L,
                )
            },
        )
        val forwarder = ActivityForwarder(
            resolveExperience = { _, _ -> null },
            deliver = { forwarded += it },
        )
        eventLog.subscribeForwarding { event -> forwarder.onCommitted(event) }

        eventLog.capture(SystemEventNames.APP_OPENED)
        eventLog.awaitBarrier()

        val stored = store.pending.single()
        assertEquals("transformed-id", stored.id)
        assertEquals("transformed-name", stored.name)
        assertEquals("anon-1", stored.distinctId)
        assertEquals(2_000L, stored.timestampMillis)
        assertEquals(SystemEventNames.APP_OPENED, stored.forwardingName)
        assertEquals(2_000L, stored.forwardingReceivedAtMillis)

        val info = forwarded.single()
        assertEquals("transformed-id", info.id)
        assertEquals(2_000L, info.timestampMillis)
        assertEquals(2_000L, info.receivedAtMillis)
    }

    @Test
    fun ordinaryBeforeSendRenameKeepsScopedIdentityAndTransformedEventFields() = runBlocking {
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
        assertEquals("original", stored.forwardingName)
        // Ordinary captures keep scoped attribution but preserve other hook fields.
        assertEquals("anon-1", stored.distinctId)
        assertEquals(1L, stored.timestampMillis)
        assertEquals("attacker-controlled-id", stored.id)
    }

    @Test
    fun ordinaryBeforeSendCannotDeleteScopedDistinctIdProperty() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store) { event ->
            NuxieEvent(
                id = event.id,
                name = event.name,
                distinctId = event.distinctId,
                properties = event.properties - "\$distinct_id",
                timestampMillis = event.timestampMillis,
            )
        }

        eventLog.capture("original")
        eventLog.awaitBarrier()

        val stored = store.pending.single()
        assertEquals("anon-1", stored.distinctId)
        assertEquals("anon-1", stored.properties.stringValue("\$distinct_id"))
    }

    @Test
    fun ordinaryBeforeSendCannotSpoofScopedDistinctIdProperty() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store) { event ->
            NuxieEvent(
                id = event.id,
                name = event.name,
                distinctId = "spoofed-field",
                properties = event.properties + ("\$distinct_id" to "spoofed-property"),
                timestampMillis = event.timestampMillis,
            )
        }

        eventLog.capture("original")
        eventLog.awaitBarrier()

        val stored = store.pending.single()
        assertEquals("anon-1", stored.distinctId)
        assertEquals("anon-1", stored.properties.stringValue("\$distinct_id"))
    }

    @Test
    fun forwardingTimestampPrecedesBeforeSendButAdmissionFollowsIt() = runBlocking {
        val store = RecordingStore()
        var enabled = false
        var clock = 1_000L
        val eventLog = log(
            store = store,
            beforeSend = { event ->
                clock = 2_000L
                enabled = true
                event
            },
            forwardingEnabled = { enabled },
            nowMillis = { clock },
        )

        eventLog.capture("first")
        eventLog.awaitBarrier()

        assertEquals(1_000L, store.pending.single().forwardingReceivedAtMillis)
    }

    @Test
    fun serverFactKeepsHistoricalEventTimeAndUsesLocalReceiptTime() = runBlocking {
        val store = RecordingStore()
        val eventLog = log(store, forwardingEnabled = { true }, nowMillis = { 2_000L })
        val fact = StoredEvent(
            id = "server-fact",
            name = JourneyEventNames.CONVERTED,
            timestampMillis = 500L,
            distinctId = "anon-1",
        )

        assertTrue(eventLog.commitServerFact(fact))

        val committed = store.delivered.single()
        assertEquals(500L, committed.timestampMillis)
        assertEquals(2_000L, committed.forwardingReceivedAtMillis)
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
