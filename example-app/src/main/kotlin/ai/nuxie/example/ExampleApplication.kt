package ai.nuxie.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Tracks the current host, including Nuxie's Experience Activity, without retaining it. */
class ExampleApplication : Application(), Application.ActivityLifecycleCallbacks {
  private var resumed = WeakReference<Activity>(null)
  val currentActivity: Activity? get() = resumed.get()

  override fun onCreate() {
    super.onCreate()
    registerActivityLifecycleCallbacks(this)
  }

  override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
  override fun onActivityPaused(activity: Activity) {
    if (resumed.get() === activity) resumed.clear()
  }
  override fun onActivityDestroyed(activity: Activity) {
    if (resumed.get() === activity) resumed.clear()
  }
  override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
  override fun onActivityStarted(activity: Activity) = Unit
  override fun onActivityStopped(activity: Activity) = Unit
  override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
