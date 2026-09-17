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
    sessionSignIn = null
    val owner = requireNotNull(providerOperations)
    sessionLogout = SessionLogout(
      owner,
      persist = { stage -> withContext(Dispatchers.IO) { logoutJournal.write(LogoutJournal.Record(session, stage)) } },
      resetSdk = { Nuxie.reset() },
      retireSdk = { Nuxie.shutdownAndAwait() },
      logoutProvider = logoutProvider,
    )
  }

  internal var sessionSignIn: SessionSignIn? = null
    private set

  internal fun prepareSignIn(session: String, authenticate: suspend () -> Unit): SessionSignIn {
    sessionSignIn?.let {
      check(it.session == session) { "Another sign-in is already owned by the application." }
      return it
    }
    return SessionSignIn(
      session,
      begin = {
        check(!Nuxie.isSetup) { "Previous SDK session is still running." }
        check(!providerOperationJournal.hasUnfinished()) { "Previous purchases need recovery." }
        withContext(Dispatchers.IO) { logoutJournal.beginSession(session) }
      },
      authenticate = authenticate,
      complete = {
        withContext(Dispatchers.IO) { logoutJournal.completeSession(session) }
        providerOperations = null
        sessionLogout = null
      },
    ).also { sessionSignIn = it }
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
