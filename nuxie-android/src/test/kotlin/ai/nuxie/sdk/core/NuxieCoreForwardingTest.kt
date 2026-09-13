package ai.nuxie.sdk.core

import ai.nuxie.sdk.identity.UserTransitionCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import ai.nuxie.sdk.testsupport.InertBillingClientAdapter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
    @get:Rule val temporary = TemporaryFolder()

    private class RecordingApplication : Application() {
        val registrations = AtomicInteger()
        val unregistrations = AtomicInteger()
        var unregisterFailure: Throwable? = null

        fun attach(base: Context) {
            attachBaseContext(base)
        }

        override fun getApplicationContext(): Context = this

        override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
            registrations.incrementAndGet()
        }

        override fun unregisterActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
            unregistrations.incrementAndGet()
            unregisterFailure?.let { throw it }
        }
    }

    private class RecordingStore : EventStore {
        val pending = CopyOnWriteArrayList<StoredEvent>()
        val accessedAfterClose = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        val closeCount = AtomicInteger()
        var closeFailure: Throwable? = null
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
            closeCount.incrementAndGet()
            closed.set(true)
            closeFailure?.let { throw it }
        }
    }

    @Test
    fun concurrentShutdownCallersJoinOneTeardownAndWaiterCancellationDoesNotStopCleanup() = runBlocking {
        val store = RecordingStore()
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_shutdown_completion",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                store = store,
                transport = FakeTransport(),
                registerLifecycle = false,
                requestInitialProfileRefresh = false,
                billingClientFactory = InertBillingClientAdapter.factory,
            ),
        )
        val producerStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        try {
            core.purchases.awaitInitialProjection()
            core.producerScope.launch {
                try { producerStarted.complete(Unit); awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            withTimeout(5_000) {
                producerStarted.await()
                val first = async(Dispatchers.Default) { core.stopAndAwait() }
                cleanupStarted.await()
                val second = async(Dispatchers.Default) { core.stopAndAwait() }
                try {
                    delay(50)
                    assertFalse("every shutdown caller must await the same cleanup", second.isCompleted)
                    first.cancel()
                    first.join()
                    assertFalse(second.isCompleted)
                    assertEquals(0, store.closeCount.get())
                    releaseCleanup.complete(Unit)
                    second.await()
                    core.stopAndAwait()
                    assertEquals(1, store.closeCount.get())
                } finally { releaseCleanup.complete(Unit) }
            }
        } finally {
            releaseCleanup.complete(Unit)
            core.stop()
        }
    }

    @Test
    fun shutdownAttemptsEveryResourceAndSharesItsFailureWithLaterWaiters() = runBlocking {
        val application = RecordingApplication().apply { attach(RuntimeEnvironment.getApplication()) }
        val firstFailure = IllegalStateException("unregister failed")
        val finalFailure = IllegalStateException("store close failed")
        application.unregisterFailure = firstFailure
        val store = RecordingStore().apply { closeFailure = finalFailure }
        val core = NuxieCore(
            context = application,
            apiKey = "pk_test_shutdown_failure",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                store = store,
                transport = FakeTransport(),
                requestInitialProfileRefresh = false,
                billingClientFactory = InertBillingClientAdapter.factory,
            ),
        )
        core.start()
        repeat(2) {
            val observed = runCatching { core.stopAndAwait() }.exceptionOrNull()
            // Coroutine stack-trace recovery may copy the exception, retaining its cause.
            assertTrue(generateSequence(observed) { it.cause }.lastOrNull() === firstFailure)
        }
        assertEquals(listOf(finalFailure), firstFailure.suppressed.toList())
        assertEquals(1, application.unregistrations.get())
        assertEquals(1, store.closeCount.get())
        assertTrue(core.scope.coroutineContext[kotlinx.coroutines.Job]!!.isCompleted)
        assertTrue(core.producerScope.coroutineContext[kotlinx.coroutines.Job]!!.isCompleted)
    }

    @Test
    fun producerCancellationCleanupCanCommitBeforeEventWorkersAndSqliteClose() = runBlocking {
        val core = NuxieCore(
            context = RuntimeEnvironment.getApplication(),
            apiKey = "pk_test_producer_drain",
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = FakeTransport(),
                registerLifecycle = false,
                requestInitialProfileRefresh = false,
                billingClientFactory = InertBillingClientAdapter.factory,
                eventDatabaseFile = temporary.newFile("producer-events.db"),
            ),
        )
        val entered = CompletableDeferred<Unit>()
        val captured = CompletableDeferred<Result<Boolean>>()
        try {
            core.purchases.awaitInitialProjection()
            val producer = core.producerScope.launch {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        captured.complete(runCatching {
                            core.eventLog.captureDeliveredIdempotently(
                                SystemEventNames.FEATURE_USED,
                                mapOf("feature_id" to "credits", "amount" to 1),
                                "producer-cleanup-event", core.identity.distinctId(),
                            ) && core.store.hasStableOutcome("producer-cleanup-event")
                        })
                    }
                }
            }
            withTimeout(5_000) {
                entered.await()
                withContext(Dispatchers.Default) { core.stop() }
                assertTrue("producer cleanup must finish while event capture and SQLite are open", captured.await().getOrThrow())
                assertTrue(producer.isCompleted)
                var lateProducerRan = false
                val lateProducer = core.producerScope.launch { lateProducerRan = true }
                lateProducer.join()
                assertFalse(lateProducerRan)
                assertTrue(lateProducer.isCancelled)
            }
        } finally { core.stop() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun identityClosureDrainsAcceptedTransitionsAndRejectsLaterAdmission() = runTest {
        val coordinator = UserTransitionCoordinator(RecordingStore(), this)
        val releaseFirst = CompletableDeferred<Unit>()
        val observed = mutableListOf<String>()
        coordinator.addObserver { _, _, to ->
            if (to == "first") releaseFirst.await()
            observed += to
        }
        fun transition(to: String) = UserTransitionCoordinator.Transition(
            UserTransitionCoordinator.Kind.IDENTIFY,
            "anonymous", to, false,
        )
        coordinator.enqueue(transition("first"))
        coordinator.enqueue(transition("second"))
        runCurrent()
        val closing = launch { coordinator.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        coordinator.enqueue(transition("too-late"))
        releaseFirst.complete(Unit)
        closing.join()
        assertEquals(listOf("first", "second"), observed)
        coordinator.close()
        coordinator.enqueue(transition("still-too-late"))
        runCurrent()
        assertEquals(listOf("first", "second"), observed)
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
