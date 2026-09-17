package ai.nuxie.example

import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.NuxieEnvironment
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Controlled provider completion; run alone in the RevenueCat selection. */
class SessionSignInLifecycleTest {
  @Test fun explicitSignInSurvivesRecreationAndDoesNotAdmitStaleLaunch() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    assumeTrue(InstrumentationRegistry.getArguments().getString("sessionSignIn") == "true")
    assumeTrue(ExamplePurchaseProvider.supportsLogout)
    val app = instrumentation.targetContext.applicationContext as ExampleApplication
    val prefs = app.getSharedPreferences("example-session", Context.MODE_PRIVATE)
    check(prefs.all.isEmpty() && app.sessionSignIn == null) { "Requires a clean example session." }
    val callback = CompletableDeferred<Unit>()
    var providerCalls = 0
    var host: Activity? = null
    Nuxie.shutdownAndAwait()
    try {
      ExampleFeatureServer().use { server ->
        val key = "pk_test_sign_in_${SystemClock.elapsedRealtime()}"
        val customer = "next-session-customer"
        val providerKey = "controlled-provider"
        val session = MessageDigest.getInstance("SHA-256").digest(
          JSONArray(listOf(ExamplePurchaseProvider.name, key, providerKey, customer, server.url))
            .toString().toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        app.logoutJournal.write(LogoutJournal.Record("a".repeat(64), LogoutJournal.Stage.COMPLETE))
        lateinit var owner: SessionSignIn
        instrumentation.runOnMainSync {
          owner = app.prepareSignIn(session) {
            providerCalls++
            callback.await()
          }
        }
        val launch = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          .putExtra("nuxie_api_key", key).putExtra("nuxie_provider_key", providerKey)
          .putExtra("nuxie_distinct_id", customer).putExtra("nuxie_api_endpoint", server.url)
        host = instrumentation.startActivitySync(launch)
        assertFalse(Nuxie.isSetup)
        val interrupted = app.providerOperationJournal.begin()
        click(requireNotNull(host), "Sign in with launch customer")
        await { owner.state.value == SessionSignIn.State.FAILED }
        assertEquals(0, providerCalls)
        assertTrue(app.providerOperationJournal.hasUnfinished())
        assertNull(app.logoutJournal.pendingSession())
        app.providerOperationJournal.finish(interrupted, false)
        click(requireNotNull(host), "Sign in with launch customer")
        await { owner.state.value == SessionSignIn.State.RUNNING && providerCalls == 1 }
        assertEquals(session, app.logoutJournal.pendingSession())
        assertFalse(app.logoutJournal.admits(session))
        val previous = host
        instrumentation.runOnMainSync { previous!!.recreate() }
        await { app.currentActivity is MainActivity && app.currentActivity !== previous }
        host = app.currentActivity
        assertSame(owner, app.sessionSignIn)
        assertFalse(Nuxie.isSetup)
        instrumentation.runOnMainSync {
          assertFalse(find(requireNotNull(host).window.decorView, "Sign in with launch customer")!!.isEnabled)
          assertFalse(find(requireNotNull(host).window.decorView, "Restore purchases")!!.isEnabled)
          requireNotNull(host).finish()
        }
        instrumentation.waitForIdleSync()
        callback.complete(Unit)
        await { owner.state.value == SessionSignIn.State.COMPLETE }
        assertEquals(1, providerCalls)
        assertNull(app.logoutJournal.read())
        assertTrue(app.logoutJournal.admits(session))
        assertNull(app.sessionLogout)
        assertNull(app.providerOperations)

        // Install a real SDK session with controlled HTTP, so startup admission
        // can be checked independently of provider credentials or live checkout.
        instrumentation.runOnMainSync {
          Nuxie.setup(app, NuxieConfiguration(key).apply {
            environment = NuxieEnvironment.DEVELOPMENT
            testingOverrides.apiEndpoint = URL(server.url)
          })
          Nuxie.identify(customer)
        }
        host = instrumentation.startActivitySync(Intent(launch).putExtra("nuxie_distinct_id", "old-customer"))
        instrumentation.runOnMainSync {
          assertFalse(find(requireNotNull(host).window.decorView, "Restore purchases")!!.isEnabled)
          requireNotNull(host).finish()
        }
        assertEquals(customer, Nuxie.distinctId)
        instrumentation.waitForIdleSync()
        host = instrumentation.startActivitySync(launch)
        instrumentation.runOnMainSync {
          assertTrue(find(requireNotNull(host).window.decorView, "Restore purchases")!!.isEnabled)
        }
        assertEquals(customer, Nuxie.distinctId)
      }
    } finally {
      callback.complete(Unit)
      instrumentation.runOnMainSync { host?.takeUnless { it.isDestroyed }?.finish() }
      Nuxie.shutdownAndAwait()
      check(prefs.edit().clear().commit())
    }
  }

  private fun find(view: View, label: String): Button? {
    if (view is Button && view.text.toString() == label) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), label)?.let { return it }
    return null
  }

  private fun click(activity: Activity, label: String) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val button = requireNotNull(find(activity.window.decorView, label))
      assertTrue(button.isShown && button.isEnabled)
      assertTrue(button.performClick())
    }
  }

  private fun await(condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (!condition()) {
      check(SystemClock.elapsedRealtime() < deadline) { "Session transition timed out." }
      SystemClock.sleep(25)
    }
  }
}
