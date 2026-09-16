package ai.nuxie.example

import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.Nuxie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Tracks the current host, including Nuxie's Experience Activity, without retaining it. */
class ExampleApplication : Application(), Application.ActivityLifecycleCallbacks {
  internal var providerOperations: ProviderOperations? = null
    private set

  internal fun ownProviderOperations(configuration: NuxieConfiguration) {
    val delegate = configuration.purchaseDelegate ?: return
    val owner = providerOperations ?: ProviderOperations(delegate, journal = providerOperationJournal)
    providerOperations = owner
    configuration.purchaseDelegate = owner
  }

  internal val providerOperationJournal by lazy { ProviderOperationJournal(getSharedPreferences("example-session", MODE_PRIVATE)) }
  internal val logoutJournal by lazy { LogoutJournal(getSharedPreferences("example-session", MODE_PRIVATE)) }
  internal var sessionLogout: SessionLogout? = null
    private set

  internal fun prepareLogout(session: String, logoutProvider: suspend () -> Unit) {
    if (sessionLogout != null) return
    val owner = requireNotNull(providerOperations)
    sessionLogout = SessionLogout(
      owner,
      persist = { stage -> withContext(Dispatchers.IO) { logoutJournal.write(LogoutJournal.Record(session, stage)) } },
      resetSdk = { Nuxie.reset() },
      retireSdk = { Nuxie.shutdownAndAwait() },
      logoutProvider = logoutProvider,
    )
  }

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
