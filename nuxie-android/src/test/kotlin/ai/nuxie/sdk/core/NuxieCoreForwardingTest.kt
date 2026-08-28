package ai.nuxie.sdk.core

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.testsupport.FakeTransport
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieCoreForwardingTest {
    private class RecordingStore : EventStore {
        val pending = CopyOnWriteArrayList<StoredEvent>()
        var onPendingInserted: (StoredEvent) -> Unit = {}

        override suspend fun insertPending(event: StoredEvent) {
            pending += event
            onPendingInserted(event)
        }

        override suspend fun insertDeliveredIfAbsent(event: StoredEvent) = true
        override suspend fun markDelivered(ids: List<String>) = Unit
        override suspend fun hasEvent(name: String, distinctId: String, sinceMillis: Long?) = false
        override suspend fun countEvents(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ) = 0
        override suspend fun getFirstEventTime(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ): Long? = null
        override suspend fun getLastEventTime(
            name: String,
            distinctId: String,
            sinceMillis: Long?,
            untilMillis: Long?,
        ): Long? = null
        override suspend fun querySessionEvents(sessionId: String) = emptyList<StoredEvent>()
        override suspend fun reassignEvents(from: String, to: String) = 0
        override suspend fun deleteOldestDeliveredEvents(keeping: Int) = 0
        override suspend fun recordStableDrop(eventId: String, recordedAtMillis: Long) = true
        override suspend fun pendingBatch(limit: Int) = pending.take(limit)
        override suspend fun close() = Unit
    }

    @Test
    fun slowListenerDoesNotDelayLaterPersistenceAndForwardingStaysFifo() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val lifecyclePreferences = application.getSharedPreferences("nuxie_lifecycle", 0)
        lifecyclePreferences.edit().clear().commit()
        val store = RecordingStore()
        val appOpenPersisted = CompletableDeferred<Unit>()
        store.onPendingInserted = { event ->
            if (event.name == SystemEventNames.APP_OPENED) appOpenPersisted.complete(Unit)
        }
        val firstForwardingStarted = CompletableDeferred<Unit>()
        val releaseFirstForwarding = CompletableDeferred<Unit>()
        val forwarded = mutableListOf<NuxieActivity>()
        val core = NuxieCore(
            context = application,
            apiKey = "pk_test_forwarding_worker",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                store = store,
                transport = FakeTransport(),
                appVersion = { "1.0 (1)" },
                registerLifecycle = false,
            ),
            forwardingEnabled = { true },
            forwardActivity = { info ->
                if (info.activity == NuxieActivity.AppInstalled) {
                    firstForwardingStarted.complete(Unit)
                    releaseFirstForwarding.await()
                }
                forwarded += info.activity
            },
        )

        try {
            try {
                core.start()
                withTimeout(1_000L) { firstForwardingStarted.await() }

                // App opened was enqueued after app installed. Its insert must not
                // wait for the first forwarded callback to return.
                withTimeout(1_000L) { appOpenPersisted.await() }
            } finally {
                releaseFirstForwarding.complete(Unit)
            }
            core.eventLog.awaitBarrier()

            assertEquals(
                listOf(SystemEventNames.APP_INSTALLED, SystemEventNames.APP_OPENED),
                store.pending.take(2).map(StoredEvent::name),
            )
            assertEquals(
                listOf(NuxieActivity.AppInstalled, NuxieActivity.AppOpened),
                forwarded.take(2),
            )
        } finally {
            releaseFirstForwarding.complete(Unit)
            core.stop()
            lifecyclePreferences.edit().clear().commit()
        }
    }
}
