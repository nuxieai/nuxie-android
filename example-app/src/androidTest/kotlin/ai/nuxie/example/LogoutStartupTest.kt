package ai.nuxie.example

import ai.nuxie.sdk.Nuxie
import android.content.Context
import android.content.Intent
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run seed and verify as separate instrumentation processes with force-stop between them. */
class LogoutStartupTest {
  @Test fun seed() {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue(arguments.getString("logoutPhase") == "seed")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val prefs = context.getSharedPreferences("example-session", Context.MODE_PRIVATE)
    val stage = LogoutJournal.Stage.valueOf(requireNotNull(arguments.getString("logoutStage")))
    runBlocking { Nuxie.shutdownAndAwait() }
    if (!prefs.contains("test-backup-present")) {
      assertTrue(prefs.edit().putBoolean("test-backup-present", true)
        .putString("test-backup", prefs.getString("logout", null)).commit())
    }
    assertTrue(prefs.edit().remove("logout").putInt("test-seed-pid", Process.myPid()).commit())
    LogoutJournal(prefs).write(LogoutJournal.Record("a".repeat(64), stage))
    assertEquals(stage, LogoutJournal(prefs).read()?.stage)
  }

  @Test fun verify() {
    val arguments = InstrumentationRegistry.getArguments()
    assumeTrue(arguments.getString("logoutPhase") == "verify")
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val prefs = context.getSharedPreferences("example-session", Context.MODE_PRIVATE)
    var activity: MainActivity? = null
    try {
      assertTrue("Seed must have saved the original record", prefs.getBoolean("test-backup-present", false))
      assertNotEquals("Must be a fresh process", prefs.getInt("test-seed-pid", -1), Process.myPid())
      val expected = LogoutJournal.Stage.valueOf(requireNotNull(arguments.getString("logoutStage")))
      assertEquals(expected, LogoutJournal(prefs).read()?.stage)
      assertFalse(Nuxie.isSetup)
      activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("nuxie_api_key", "pk_test_logout_startup")
        .putExtra("nuxie_distinct_id", "old-launch-customer")) as MainActivity
      instrumentation.waitForIdleSync()
      assertFalse("Startup must not install an SDK graph", Nuxie.isSetup)
      assertEquals("", Nuxie.distinctId)
      val buttons = mutableListOf<Button>()
      val text = mutableListOf<String>()
      instrumentation.runOnMainSync {
        fun visit(view: View) {
          if (view is Button) buttons += view
          if (view is TextView) text += view.text.toString()
          if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(activity!!.window.decorView)
        assertTrue(buttons.isNotEmpty())
        assertTrue(buttons.all { !it.isEnabled })
      }
      assertTrue(text.any { if (expected == LogoutJournal.Stage.COMPLETE) it.startsWith("Signed out.") else it.contains("sign-out needs recovery") })
    } finally {
      try {
        activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
        instrumentation.waitForIdleSync()
        runBlocking { Nuxie.shutdownAndAwait() }
      } finally {
        if (prefs.getBoolean("test-backup-present", false)) {
          val backup = prefs.getString("test-backup", null)
          assertTrue(prefs.edit().putString("logout", backup).remove("test-backup")
            .remove("test-backup-present").remove("test-seed-pid").commit())
          assertEquals(backup, prefs.getString("logout", null))
        }
      }
    }
  }
}
