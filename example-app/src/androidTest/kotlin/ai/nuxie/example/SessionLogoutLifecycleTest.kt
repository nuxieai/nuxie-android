package ai.nuxie.example

import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.billing.PurchaseHandlingMode
import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run alone with -e sessionLifecycle true; the completed application owner stays closed. */
class SessionLogoutLifecycleTest {
  @Test fun recreationRetainsDrainFailureAndRetry() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("sessionLifecycle") == "true")
    assumeTrue(ExamplePurchaseProvider.supportsAnonymousReset)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as ExampleApplication
    check(app.sessionLogout == null && app.providerOperations == null) { "Requires a fresh instrumentation process." }
    val prefs = app.getSharedPreferences("example-session", Context.MODE_PRIVATE)
    val savedLogout = prefs.getString("logout", null)
    val savedOperations = prefs.getString("provider-operations", null)
    check(savedLogout == null && savedOperations == null) { "Requires a session without pending recovery." }
    val created = mutableListOf<Activity>()
    val callbacks = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: Activity, state: Bundle?) {
        if (activity is MainActivity) created += activity
      }
      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityResumed(activity: Activity) = Unit
      override fun onActivityPaused(activity: Activity) = Unit
      override fun onActivityStopped(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
      override fun onActivityDestroyed(activity: Activity) = Unit
    }
    val entered = CompletableDeferred<Unit>()
    val releaseRestore = CompletableDeferred<Unit>()
    val providerEntered = CompletableDeferred<Unit>()
    val releaseLogout = CompletableDeferred<Unit>()
    val restoreCalls = AtomicInteger()
    val logoutCalls = AtomicInteger()
    val customer = "logout-lifecycle-${SystemClock.elapsedRealtime()}"
    val sessionKey = "b".repeat(64)
    var sessionForCleanup: SessionLogout? = null
    Nuxie.shutdownAndAwait()
    app.registerActivityLifecycleCallbacks(callbacks)
    try {
      ExampleFeatureServer().use { server ->
        val configuration = NuxieConfiguration("pk_test_session_owner_${SystemClock.elapsedRealtime()}").apply {
          environment = NuxieEnvironment.DEVELOPMENT
          testingOverrides.apiEndpoint = URL(server.url)
          purchaseHandlingMode = PurchaseHandlingMode.APP_MANAGED
          purchaseDelegate = object : NuxiePurchaseDelegate {
            override suspend fun purchase(product: StoreProduct) = PurchaseResult.Cancelled
            override suspend fun restorePurchases(): RestoreResult {
              restoreCalls.incrementAndGet()
              entered.complete(Unit)
              releaseRestore.await()
              return RestoreResult.Restored
            }
          }
        }
        instrumentation.runOnMainSync {
          app.ownProviderOperations(configuration)
          Nuxie.setup(app, configuration)
          Nuxie.identify(customer)
        }
        val owner = requireNotNull(app.providerOperations)
        val original = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          .putExtra("nuxie_api_key", "pk_test_session_lifecycle_${SystemClock.elapsedRealtime()}")
          .putExtra("nuxie_api_endpoint", server.url)
          .putExtra("nuxie_distinct_id", customer)
          .putExtra("nuxie_test_store", true))
        assertTrue(Nuxie.isSetup)
        assertEquals(customer, Nuxie.distinctId)
        instrumentation.runOnMainSync {
          app.prepareLogout(sessionKey) {
            assertFalse("SDK teardown must precede provider logout", Nuxie.isSetup)
            assertEquals(LogoutJournal.Stage.SDK_RETIRED, app.logoutJournal.read()?.stage)
            if (logoutCalls.incrementAndGet() == 1) {
              providerEntered.complete(Unit)
              releaseLogout.await()
              error("Controlled provider completion failure")
            }
          }
        }
        val session = requireNotNull(app.sessionLogout)
        sessionForCleanup = session
        val caller = async(Dispatchers.Default) { Nuxie.restorePurchases() }
        withTimeout(15_000) { entered.await() }
        caller.cancelAndJoin()
        val first = async(Dispatchers.Default) { session.logout() }
        await("held drain persisted") { app.logoutJournal.read()?.stage == LogoutJournal.Stage.REQUESTED }
        first.cancelAndJoin()
        val drainingHost = recreate(original)
        assertSame(owner, app.providerOperations)
        assertSame(session, app.sessionLogout)
        assertEquals(SessionLogout.State.CLOSING, session.state.value)
        assertTrue(Nuxie.isSetup)
        assertEquals("Held provider work must keep the original identity", customer, Nuxie.distinctId)
        assertEquals(0, logoutCalls.get())
        assertTrue(app.providerOperationJournal.hasUnfinished())
        assertBlocked(drainingHost, "Signing out")
        assertTrue(Nuxie.restorePurchases() is RestoreResult.Failed)
        assertEquals(1, restoreCalls.get())

        releaseRestore.complete(Unit)
        withTimeout(15_000) { providerEntered.await() }
        val providerHost = recreate(drainingHost)
        assertFalse(Nuxie.isSetup)
        assertSame(session, app.sessionLogout)
        assertBlocked(providerHost, "Signing out")
        assertFalse(app.providerOperationJournal.hasUnfinished())
        releaseLogout.complete(Unit)
        await("failed provider completion") { session.state.value == SessionLogout.State.FAILED }
        val retryHost = recreate(providerHost)
        assertSame(session, app.sessionLogout)
        assertFalse("Recreation cannot replay old launch setup", Nuxie.isSetup)
        assertBlocked(retryHost, "Retry before continuing")
        assertEquals(LogoutJournal.Stage.SDK_RETIRED, app.logoutJournal.read()?.stage)
        assertEquals(SessionLogout.State.COMPLETE, withTimeout(15_000) { session.logout() })
        assertEquals(2, logoutCalls.get())
        assertEquals(LogoutJournal.Record(sessionKey, LogoutJournal.Stage.COMPLETE), app.logoutJournal.read())
        val completedHost = recreate(retryHost)
        assertSame(session, app.sessionLogout)
        assertFalse(Nuxie.isSetup)
        assertBlocked(completedHost, "Signed out")
        assertTrue(owner.restorePurchases() is RestoreResult.Failed)
        assertEquals(1, restoreCalls.get())
      }
    } finally {
      releaseRestore.complete(Unit)
      releaseLogout.complete(Unit)
      try {
        sessionForCleanup?.let { withTimeout(15_000) { it.logout() } }
      } finally {
        try {
          instrumentation.runOnMainSync { created.filterNot { it.isDestroyed }.forEach { it.finish() } }
          instrumentation.waitForIdleSync()
        } finally {
          try {
            Nuxie.shutdownAndAwait()
          } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
            assertTrue(prefs.edit().putString("logout", savedLogout).putString("provider-operations", savedOperations).commit())
            assertEquals(savedLogout, prefs.getString("logout", null))
            assertEquals(savedOperations, prefs.getString("provider-operations", null))
          }
        }
      }
    }
  }

  private fun recreate(before: Activity): Activity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as ExampleApplication
    instrumentation.runOnMainSync { before.recreate() }
    await("replacement Activity resumed") { app.currentActivity is MainActivity && app.currentActivity !== before }
    instrumentation.waitForIdleSync()
    return requireNotNull(app.currentActivity)
  }

  private fun assertBlocked(activity: Activity, expectedStatus: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      val buttons = mutableListOf<Button>()
      val text = mutableListOf<String>()
      fun visit(view: View) {
        if (view is Button) buttons += view
        if (view is TextView) text += view.text.toString()
        if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
      }
      visit(activity.window.decorView)
      assertTrue(buttons.isNotEmpty())
      assertTrue("Every operation control must remain disabled", buttons.all { !it.isEnabled })
      assertTrue("Expected $expectedStatus in $text", text.any { it.contains(expectedStatus) })
    }
  }

  private fun await(description: String, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (!condition()) {
      check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $description" }
      SystemClock.sleep(25)
    }
  }
}
