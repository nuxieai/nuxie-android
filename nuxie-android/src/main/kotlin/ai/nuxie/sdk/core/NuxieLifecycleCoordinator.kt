package ai.nuxie.sdk.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
 * setup; the first foreground transition after registration is therefore
 * swallowed to avoid a duplicate.
 */
internal class NuxieLifecycleCoordinator(
    private val tracker: AppLifecycleTracker,
    private val sessions: ai.nuxie.sdk.session.SessionService,
    scope: CoroutineScope,
) : Application.ActivityLifecycleCallbacks {
    private enum class Transition { FOREGROUND, BACKGROUND }

    private val transitions = Channel<Transition>(capacity = Channel.UNLIMITED)
    private var startedActivities = 0
    private var sawInitialForeground = false

    init {
        scope.launch {
            for (transition in transitions) {
                when (transition) {
                    Transition.FOREGROUND -> {
                        sessions.onAppBecameActive()
                        tracker.trackAppForegrounded()
                    }
                    Transition.BACKGROUND -> {
                        sessions.onAppDidEnterBackground()
                        tracker.trackAppBackgrounded()
                    }
                }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities += 1
        if (startedActivities == 1) {
            if (!sawInitialForeground) {
                // Launch $app_opened already emitted by trackAppLaunchEvents.
                sawInitialForeground = true
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
}
