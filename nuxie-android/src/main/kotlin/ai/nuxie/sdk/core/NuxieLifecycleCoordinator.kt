package ai.nuxie.sdk.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serializes foreground/background transitions through one FIFO worker so a
 * fast background -> foreground -> background burst can never interleave
 * fan-out (iOS `NuxieLifecycleCoordinator` parity, built on started-activity
 * counting instead of NotificationCenter).
 *
 * The launch-time $app_opened is emitted by [AppLifecycleTracker] during
 * setup. The first Activity still opens foreground-only runtime admission,
 * but does not emit a duplicate event.
 */
internal class NuxieLifecycleCoordinator(
    private val tracker: AppLifecycleTracker,
    private val sessions: ai.nuxie.sdk.session.SessionService,
    scope: CoroutineScope,
    /** Best-effort work on entering background (e.g. a delivery flush). */
    private val onBackground: (suspend () -> Unit)? = null,
    /** Best-effort work after the background event is durably captured. */
    private val afterBackground: (suspend () -> Unit)? = null,
    /** Recovery work when the app returns to the foreground. */
    private val onForeground: (suspend () -> Unit)? = null,
) : Application.ActivityLifecycleCallbacks {
    private enum class Transition { INITIAL_FOREGROUND, FOREGROUND, BACKGROUND }

    private val transitions = Channel<Transition>(capacity = Channel.UNLIMITED)
    private var startedActivities = 0
    private var sawInitialForeground = false

    init {
        scope.launch {
            for (transition in transitions) {
                when (transition) {
                    Transition.INITIAL_FOREGROUND -> {
                        // App-opened was already captured during setup, but
                        // screen-bearing work must remain closed until an
                        // Activity actually becomes visible.
                        runBestEffort("Initial foreground recovery", onForeground)
                    }
                    Transition.FOREGROUND -> {
                        sessions.onAppBecameActive()
                        // Canonical profile revalidation and device-leg
                        // activation finish before the foreground edge enters
                        // event routing, so stale authority cannot consume it.
                        runBestEffort("Foreground recovery", onForeground)
                        tracker.trackAppForegrounded()
                    }
                    Transition.BACKGROUND -> {
                        sessions.onAppDidEnterBackground()
                        // Close screen admission before the background event
                        // can enter journey routing.
                        runBestEffort("Background transition", onBackground)
                        tracker.trackAppBackgrounded()
                        // Android-specific best effort: push pending events out
                        // before the process is likely frozen or killed.
                        runBestEffort("Background recovery", afterBackground)
                    }
                }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities += 1
        if (startedActivities == 1) {
            if (!sawInitialForeground) {
                sawInitialForeground = true
                transitions.trySend(Transition.INITIAL_FOREGROUND)
            } else {
                transitions.trySend(Transition.FOREGROUND)
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            transitions.trySend(Transition.BACKGROUND)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private suspend fun runBestEffort(
        label: String,
        operation: (suspend () -> Unit)?,
    ) {
        if (operation == null) return
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(LOG_TAG, "$label failed; lifecycle processing will continue", failure)
        }
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
    }
}
