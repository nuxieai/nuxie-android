package ai.nuxie.sdk.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import ai.nuxie.sdk.logging.NuxieLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Synchronous admission fence; cannot wait behind a profile request. */
    private val onVisibilityChanged: (Boolean) -> Unit = {},
    /** Best-effort work on entering background (e.g. a delivery flush). */
    private val onBackground: (suspend () -> Unit)? = null,
    /** Best-effort work after the background event is durably captured. */
    private val afterBackground: (suspend () -> Unit)? = null,
    /** Recovery work when the app returns to the foreground. */
    private val onForeground: (suspend () -> Unit)? = null,
) : Application.ActivityLifecycleCallbacks {
    private enum class Transition { INITIAL_FOREGROUND, FOREGROUND, BACKGROUND }

    private val transitions = Channel<Transition>(capacity = Channel.UNLIMITED)
    private val startedActivities = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>(),
    )
    private var sawInitialForeground = false
    private var lastResumed = java.lang.ref.WeakReference<Activity>(null)
    private val resumedActivities = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())

    private val closed = AtomicBoolean(false)

    private val worker = scope.launch {
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
                    // Canonical profile revalidation and journey
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

    /** Stop intake, cancel unfinished transition work, and join its cleanup. */
    suspend fun close() {
        closed.set(true)
        transitions.close()
        worker.cancelAndJoin()
    }

    /** Admit a host that was already visible when a late SDK setup registered callbacks. */
    @Suppress("DEPRECATION")
    fun admitVisibleActivity(activity: Activity) {
        activity.runOnUiThread {
            if (closed.get() || activity.isFinishing || activity.isDestroyed || !activity.window.decorView.isShown) return@runOnUiThread
            val probe = NuxieResumedActivityProbe().also {
                it.coordinator = java.lang.ref.WeakReference(this)
            }
            activity.fragmentManager.beginTransaction().add(probe, null).commitAllowingStateLoss()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (closed.get()) return
        if (!startedActivities.add(activity)) return
        if (startedActivities.size == 1) {
            onVisibilityChanged(true)
            if (!sawInitialForeground) {
                sawInitialForeground = true
                transitions.trySend(Transition.INITIAL_FOREGROUND)
            } else {
                transitions.trySend(Transition.FOREGROUND)
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (closed.get()) return
        if (!startedActivities.remove(activity)) return
        if (startedActivities.isEmpty()) {
            onVisibilityChanged(false)
            transitions.trySend(Transition.BACKGROUND)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) {
        if (!closed.get()) {
            lastResumed = java.lang.ref.WeakReference(activity)
            resumedActivities.add(activity)
        }
    }

    /** Main-thread lookup for native checkout; never retain or select a stopped Activity. */
    internal fun purchaseActivity(): Activity? {
        if (closed.get()) return null
        fun usable(activity: Activity) = activity in startedActivities &&
            !activity.isFinishing && !activity.isDestroyed
        return lastResumed.get()?.takeIf(::usable)
            ?: startedActivities.firstOrNull { usable(it) && it.hasWindowFocus() }
            ?: startedActivities.firstOrNull(::usable)
    }
    internal fun isAppForeground(): Boolean = !closed.get() && startedActivities.isNotEmpty()

    internal fun resumedActivity(): Activity? {
        if (closed.get()) return null
        fun usable(activity: Activity) = activity in resumedActivities && activity in startedActivities && !activity.isDestroyed
        return lastResumed.get()?.takeIf(::usable) ?: resumedActivities.firstOrNull(::usable)
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities.remove(activity)
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { resumedActivities.remove(activity) }

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
            Log.w(LOG_TAG, "Lifecycle operation failed; processing will continue", failure, Log.sensitive("operation", label))
        }
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
    }
}

/** One-shot platform lifecycle probe for SDK setup after Activity.onResume. */
// Kotlin internal emits a JVM-public class and public no-argument constructor for restoration.
@android.annotation.SuppressLint("ValidFragment")
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class NuxieResumedActivityProbe : android.app.Fragment() {
    internal var coordinator = java.lang.ref.WeakReference<NuxieLifecycleCoordinator>(null)
    private var started = false

    override fun onStart() {
        super.onStart()
        started = true
        // A resumed host receives onResume before this admission reaches the queue.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val host = activity
            if (started && host != null && !host.isDestroyed && !host.isFinishing) {
                coordinator.get()?.onActivityStarted(host)
            }
            coordinator.clear()
            fragmentManager?.takeUnless { it.isDestroyed }?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
        }
    }

    override fun onResume() {
        super.onResume()
        activity?.let { coordinator.get()?.onActivityResumed(it) }
    }

    override fun onStop() {
        started = false
        super.onStop()
    }
}
