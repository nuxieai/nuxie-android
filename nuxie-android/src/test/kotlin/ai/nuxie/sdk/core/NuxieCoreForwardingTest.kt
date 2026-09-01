package ai.nuxie.sdk.core

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieActivity
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.StoredEvent
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.testsupport.FakeTransport
import android.app.Application
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieCoreForwardingTest {
    private class RecordingApplication : Application() {
        val registrations = AtomicInteger()
        val unregistrations = AtomicInteger()

        fun attach(base: Context) {
            attachBaseContext(base)
        }

        override fun getApplicationContext(): Context = this

        override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
            registrations.incrementAndGet()
        }

        override fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
            unregistrations.incrementAndGet()
        }
    }

    private class RecordingStore : EventStore {
        val pending = CopyOnWriteArrayList<StoredEvent>()
        val accessedAfterClose = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
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
        override suspend fun pendingBatch(limit: Int): List<StoredEvent> {
            if (closed.get()) accessedAfterClose.set(true)
            return pending.take(limit)
        }
        override suspend fun close() {
            closed.set(true)
        }
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

    @Test
    fun stopJoinsResidualScopeWorkBeforeClosingTheOwnedStore() = runBlocking {
        val store = RecordingStore()
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_ordered_stop",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                store = store,
                transport = FakeTransport(),
                registerLifecycle = false,
            ),
        )
        val started = CompletableDeferred<Unit>()
        val residualWork = core.scope.launch {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                store.pendingBatch(limit = 1)
            }
        }

        try {
            started.await()
            core.stop()
            residualWork.join()

            assertFalse("core scope accessed its store after close", store.accessedAfterClose.get())
        } finally {
            core.stop()
        }
    }

    @Test
    fun stopUnregistersLifecycleCallbacksExactlyOnce() {
        val application = RecordingApplication().apply {
            attach(RuntimeEnvironment.getApplication())
        }
        val core = NuxieCore(
            context = application,
            apiKey = "pk_test_lifecycle_stop",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(transport = FakeTransport()),
        )

        try {
            core.start()
            assertEquals(1, application.registrations.get())

            core.stop()
            core.stop()

            assertEquals(1, application.unregistrations.get())
        } finally {
            core.stop()
        }
    }
}
