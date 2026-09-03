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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieLifecycleCoordinatorTest {
    @Test
    fun foregroundAuthorityRefreshCompletesBeforeTheForegroundEvent() = runBlocking {
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
                order += "device-legs-activated"
            },
        )
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

        try {
            coordinator.onActivityStarted(activity)
            coordinator.onActivityStopped(activity)
            coordinator.onActivityStarted(activity)
            withTimeout(5_000L) { foregroundEvent.await() }

            assertEquals(
                listOf(
                    "profile-revalidated",
                    "device-legs-activated",
                    "profile-revalidated",
                    "device-legs-activated",
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
