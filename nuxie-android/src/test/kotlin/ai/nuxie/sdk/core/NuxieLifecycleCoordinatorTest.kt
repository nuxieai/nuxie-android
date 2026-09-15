package ai.nuxie.sdk.core

import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.session.SessionService
import android.app.Activity
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieLifecycleCoordinatorTest {
    @Test
    fun visibilityFencesDoNotWaitForSuspendedForegroundRecovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val visibility = CopyOnWriteArrayList<Boolean>()
        val context = RuntimeEnvironment.getApplication()
        val coordinator = NuxieLifecycleCoordinator(
            tracker = AppLifecycleTracker(
                preferences = context.getSharedPreferences("visibility-fence", Context.MODE_PRIVATE),
                appVersionProvider = { "1" }, nowMillis = { 100_000L }, emit = { _, _ -> },
            ),
            sessions = SessionService { 100_000L }, scope = scope,
            onVisibilityChanged = { visibility += it },
            onForeground = { entered.complete(Unit); release.await() },
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        try {
            coordinator.onActivityStarted(activity)
            withTimeout(3_000) { entered.await() }
            coordinator.onActivityStopped(activity)
            coordinator.onActivityStarted(activity)
            assertEquals(listOf(true, false, true), visibility)
        } finally {
            release.complete(Unit)
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun checkoutActivityTracksVisibleResumedOwnerWithoutRetainingStoppedHosts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = RuntimeEnvironment.getApplication()
        val coordinator = NuxieLifecycleCoordinator(
            tracker = AppLifecycleTracker(
                preferences = context.getSharedPreferences("checkout-owner", Context.MODE_PRIVATE),
                appVersionProvider = { "1" }, nowMillis = { 100_000L }, emit = { _, _ -> },
            ),
            sessions = SessionService { 100_000L }, scope = scope,
        )
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            coordinator.onActivityStarted(first.get())
            coordinator.onActivityStarted(second.get())
            coordinator.onActivityResumed(first.get())
            assertEquals(first.get(), coordinator.purchaseActivity())
            coordinator.onActivityStopped(first.get())
            assertEquals(second.get(), coordinator.purchaseActivity())
            coordinator.onActivityStopped(second.get())
            assertEquals(null, coordinator.purchaseActivity())
            coordinator.close()
            coordinator.onActivityStarted(first.get())
            coordinator.onActivityResumed(first.get())
            assertEquals(null, coordinator.purchaseActivity())
        } finally {
            first.pause().stop().destroy()
            second.pause().stop().destroy()
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun closeWaitsForCancelledTransitionCleanupAndRejectsLaterCallbacks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val cleaningUp = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val emitted = CopyOnWriteArrayList<String>()
        val context = RuntimeEnvironment.getApplication()
        val coordinator = NuxieLifecycleCoordinator(
            tracker = AppLifecycleTracker(
                preferences = context.getSharedPreferences("lifecycle-close", Context.MODE_PRIVATE),
                appVersionProvider = { "1" },
                nowMillis = { 100_000L },
                emit = { name, _ -> emitted += name },
            ),
            sessions = SessionService { 100_000L },
            scope = scope,
            onForeground = {
                calls.incrementAndGet()
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleaningUp.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            },
        )
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        try {
            coordinator.onActivityStarted(activity)
            withTimeout(3_000) { entered.await() }
            coordinator.onActivityStopped(activity)
            coordinator.onActivityStarted(activity)
            val closing = async { coordinator.close() }
            withTimeout(3_000) { cleaningUp.await() }
            assertFalse(closing.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(3_000) { closing.await() }
            coordinator.onActivityStopped(activity)
            coordinator.onActivityStarted(activity)
            coordinator.close()
            assertEquals(1, calls.get())
            assertEquals(emptyList<String>(), emitted)
        } finally {
            releaseCleanup.complete(Unit)
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun lateSetupAdmitsVisibleHostOnceAndPreservesForegroundOrdering() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val order = CopyOnWriteArrayList<String>()
        val foregroundEvent = CompletableDeferred<Unit>()
        val context = RuntimeEnvironment.getApplication()
        val tracker = AppLifecycleTracker(
            preferences = context.getSharedPreferences(
                "lifecycle-coordinator-order",
                Context.MODE_PRIVATE,
            ),
            appVersionProvider = { "1.0 (1)" },
            nowMillis = { 100_000L },
            emit = { name, _ ->
                if (name == SystemEventNames.APP_OPENED) {
                    order += "foreground-event"
                    foregroundEvent.complete(Unit)
                }
            },
        )
        val coordinator = NuxieLifecycleCoordinator(
            tracker = tracker,
            sessions = SessionService { 100_000L },
            scope = scope,
            onForeground = {
                order += "profile-revalidated"
                order += "journeys-activated"
            },
        )
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().visible().get()

        try {
            coordinator.admitVisibleActivity(activity)
            coordinator.onActivityStarted(activity)
            coordinator.onActivityStopped(activity)
            coordinator.onActivityStarted(activity)
            withTimeout(5_000L) { foregroundEvent.await() }

            assertEquals(
                listOf(
                    "profile-revalidated",
                    "journeys-activated",
                    "profile-revalidated",
                    "journeys-activated",
                    "foreground-event",
                ),
                order,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedForegroundRecoveryStillCapturesEventsAndKeepsTheWorkerAlive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = AtomicInteger()
        val foregroundEvents = AtomicInteger()
        val context = RuntimeEnvironment.getApplication()
        val tracker = AppLifecycleTracker(
            preferences = context.getSharedPreferences(
                "lifecycle-coordinator-recovery-failure",
                Context.MODE_PRIVATE,
            ),
            appVersionProvider = { "1.0 (1)" },
            nowMillis = { 100_000L },
            emit = { name, _ ->
                if (name == SystemEventNames.APP_OPENED) foregroundEvents.incrementAndGet()
            },
        )
        val coordinator = NuxieLifecycleCoordinator(
            tracker = tracker,
            sessions = SessionService { 100_000L },
            scope = scope,
            onForeground = {
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("profile failed")
            },
        )
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

        try {
            coordinator.onActivityStarted(activity)
            repeat(2) {
                coordinator.onActivityStopped(activity)
                coordinator.onActivityStarted(activity)
                withTimeout(5_000L) {
                    while (foregroundEvents.get() <= it) delay(5L)
                }
            }

            assertEquals(3, attempts.get())
            assertEquals(2, foregroundEvents.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backgroundClosesRuntimeAdmissionBeforeEmittingAndFlushesAfterward() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val order = CopyOnWriteArrayList<String>()
        val flushed = CompletableDeferred<Unit>()
        val context = RuntimeEnvironment.getApplication()
        val tracker = AppLifecycleTracker(
            preferences = context.getSharedPreferences(
                "lifecycle-coordinator-background-order",
                Context.MODE_PRIVATE,
            ),
            appVersionProvider = { "1.0 (1)" },
            nowMillis = { 100_000L },
            emit = { name, _ ->
                if (name == SystemEventNames.APP_BACKGROUNDED) {
                    order += "background-event"
                }
            },
        )
        val coordinator = NuxieLifecycleCoordinator(
            tracker = tracker,
            sessions = SessionService { 100_000L },
            scope = scope,
            onBackground = { order += "runtime-closed" },
            afterBackground = {
                order += "delivery-flushed"
                flushed.complete(Unit)
            },
        )
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

        try {
            coordinator.onActivityStarted(activity)
            coordinator.onActivityStopped(activity)
            withTimeout(5_000L) { flushed.await() }

            assertEquals(
                listOf("runtime-closed", "background-event", "delivery-flushed"),
                order,
            )
        } finally {
            scope.cancel()
        }
    }
}
