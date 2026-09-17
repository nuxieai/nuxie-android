package ai.nuxie.example

import ai.nuxie.sdk.Nuxie
import android.content.Context
import android.content.Intent
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Seed an actual suspended sign-in, then externally stop and verify in a new process. */
class SignInStartupTest {
  private val key = "pk_test_explicit_sign_in_restart"
  private val customer = "explicit-next-customer"
  private val providerKey = "controlled-provider"
  private fun fingerprint() = MessageDigest.getInstance("SHA-256").digest(
    JSONArray(listOf(ExamplePurchaseProvider.name, key, providerKey, customer, null))
      .toString().toByteArray(Charsets.UTF_8),
  ).joinToString("") { "%02x".format(it) }

  @Test fun seed() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("signInPhase") == "seed")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as ExampleApplication
    val prefs = app.getSharedPreferences("example-session", Context.MODE_PRIVATE)
    check(prefs.all.isEmpty()) { "Requires a clean example session." }
    Nuxie.shutdownAndAwait()
    app.logoutJournal.write(LogoutJournal.Record("a".repeat(64), LogoutJournal.Stage.COMPLETE))
    val entered = CompletableDeferred<Unit>()
    val neverCompleted = CompletableDeferred<Unit>()
    instrumentation.runOnMainSync {
      val owner = app.prepareSignIn(fingerprint()) {
        entered.complete(Unit)
        neverCompleted.await()
      }
      CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch { owner.signIn() }
    }
    withTimeout(15_000) { entered.await() }
    assertEquals(fingerprint(), app.logoutJournal.pendingSession())
    assertFalse(app.logoutJournal.admits(fingerprint()))
    assertTrue(prefs.edit().putInt("sign-in-seed-pid", Process.myPid()).commit())
  }

  @Test fun verify() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("signInPhase") == "verify")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as ExampleApplication
    val prefs = app.getSharedPreferences("example-session", Context.MODE_PRIVATE)
    check(prefs.contains("sign-in-seed-pid")) { "Requires the matching seed." }
    assertNotEquals(prefs.getInt("sign-in-seed-pid", -1), Process.myPid())
    try {
      assertNull(app.sessionSignIn)
      assertEquals(fingerprint(), app.logoutJournal.pendingSession())
      for (requestedCustomer in listOf("old-customer", customer)) {
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("nuxie_api_key", key)
          .putExtra("nuxie_provider_key", providerKey).putExtra("nuxie_distinct_id", requestedCustomer))
        try {
          assertFalse(Nuxie.isSetup)
          assertNull("Startup cannot replay provider login", app.sessionSignIn)
          assertEquals(fingerprint(), app.logoutJournal.pendingSession())
          instrumentation.runOnMainSync {
            fun visit(view: View) {
              if (view is Button && view.text.toString() == "Restore purchases") assertFalse(view.isEnabled)
              if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(activity.window.decorView)
          }
        } finally {
          instrumentation.runOnMainSync { activity.finish() }
          instrumentation.waitForIdleSync()
        }
      }
    } finally {
      runBlocking { Nuxie.shutdownAndAwait() }
      check(prefs.edit().clear().commit())
    }
  }
}
