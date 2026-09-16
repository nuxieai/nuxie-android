package ai.nuxie.example

import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Force-stop the live hold process after status 71, then run verify in a fresh process. */
class ProviderOperationStartupTest {
  @Test(timeout = 120_000) fun hold() = runBlocking {
    assumeTrue(InstrumentationRegistry.getArguments().getString("providerPhase") == "hold")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as ExampleApplication
    val prefs = app.getSharedPreferences("example-session", Context.MODE_PRIVATE)
    check(!prefs.contains("operation-test-backup-present")) { "Restore the previous test backup first." }
    assertNull("This scenario must precede logout intent", prefs.getString("logout", null))
    assertTrue(prefs.edit().putBoolean("operation-test-backup-present", true)
      .putString("operation-test-backup", prefs.getString("provider-operations", null))
      .putInt("operation-test-pid", Process.myPid()).remove("provider-operations").commit())
    val entered = CompletableDeferred<Unit>()
    val configuration = NuxieConfiguration("pk_test_operation_startup").apply {
      purchaseDelegate = object : NuxiePurchaseDelegate {
        override suspend fun purchase(product: StoreProduct) = PurchaseResult.Cancelled
        override suspend fun restorePurchases(): RestoreResult {
          assertTrue("Marker must precede provider dispatch", app.providerOperationJournal.hasUnfinished())
          entered.complete(Unit)
          awaitCancellation()
        }
      }
    }
    app.ownProviderOperations(configuration)
    val caller = async { requireNotNull(configuration.purchaseDelegate).restorePurchases() }
    entered.await()
    assertFalse("Provider call must still be running", caller.isCompleted)
    instrumentation.sendStatus(71, Bundle().apply {
      putString("providerJournal", "ready")
      putInt("providerPid", Process.myPid())
    })
    caller.await()
    fail("The held provider must be interrupted by process death, not return.")
  }

  @Test fun verify() {
    assumeTrue(InstrumentationRegistry.getArguments().getString("providerPhase") == "verify")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val prefs = context.getSharedPreferences("example-session", Context.MODE_PRIVATE)
    var activity: MainActivity? = null
    try {
      assertTrue("Hold must save the original record", prefs.getBoolean("operation-test-backup-present", false))
      assertNotEquals("Must be a fresh process", prefs.getInt("operation-test-pid", -1), Process.myPid())
      assertTrue("Unfinished operation must survive process death", ProviderOperationJournal(prefs).hasUnfinished())
      assertNull("Recovery must work without logout intent", prefs.getString("logout", null))
      assertFalse(Nuxie.isSetup)
      activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("nuxie_api_key", "pk_test_operation_startup")
        .putExtra("nuxie_distinct_id", "old-launch-customer")) as MainActivity
      instrumentation.waitForIdleSync()
      assertFalse("Startup must not install an SDK graph", Nuxie.isSetup)
      assertEquals("", Nuxie.distinctId)
      instrumentation.runOnMainSync {
        val buttons = mutableListOf<Button>()
        val text = mutableListOf<String>()
        fun visit(view: View) {
          if (view is Button) buttons += view
          if (view is TextView) text += view.text.toString()
          if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(activity!!.window.decorView)
        assertTrue(buttons.isNotEmpty())
        assertTrue(buttons.all { !it.isEnabled })
        assertTrue(text.any { it.contains("purchase activity needs recovery") })
      }
    } finally {
      try {
        activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
        instrumentation.waitForIdleSync()
        runBlocking { Nuxie.shutdownAndAwait() }
      } finally {
        if (prefs.getBoolean("operation-test-backup-present", false)) {
          val backup = prefs.getString("operation-test-backup", null)
          assertTrue(prefs.edit().putString("provider-operations", backup).remove("operation-test-backup")
            .remove("operation-test-backup-present").remove("operation-test-pid").commit())
          assertEquals(backup, prefs.getString("provider-operations", null))
        }
      }
    }
  }
}
