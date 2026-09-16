package ai.nuxie.example

import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.features.FeatureCheckPolicy
import ai.nuxie.sdk.features.FeatureInfo
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class FeatureObservationLifecycleTest {
  @Test fun stoppedActivityResumesWithLatestAccessAndCurrentIdentity() {
    assumeTrue("Uses the managed example without external provider credentials", ExamplePurchaseProvider.supportsAnonymousReset)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext.applicationContext as Application
    val stopped = AtomicReference<Activity?>()
    val callbacks = object : Application.ActivityLifecycleCallbacks {
      override fun onActivityStopped(activity: Activity) { stopped.set(activity) }
      override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
      override fun onActivityStarted(activity: Activity) = Unit
      override fun onActivityResumed(activity: Activity) = Unit
      override fun onActivityPaused(activity: Activity) = Unit
      override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
      override fun onActivityDestroyed(activity: Activity) = Unit
    }
    runBlocking { Nuxie.shutdownAndAwait() }
    var activity: Activity? = null
    FeatureServer().use { server ->
      app.registerActivityLifecycleCallbacks(callbacks)
      try {
        val launch = Intent(app, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          .putExtra("nuxie_api_key", "pk_test_feature_lifecycle_${SystemClock.elapsedRealtime()}")
          .putExtra("nuxie_api_endpoint", server.url)
          .putExtra("nuxie_distinct_id", "feature-lifecycle-first-${SystemClock.elapsedRealtime()}")
          .putExtra("nuxie_test_store", true)
        activity = instrumentation.startActivitySync(launch)
        val host = activity!!
        fun panel(target: Activity = host): TextView {
          var result: TextView? = null
          instrumentation.runOnMainSync {
            fun find(view: View) {
              if (view is TextView && view.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE) result = view
              if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))
            }
            find(target.window.decorView)
          }
          return requireNotNull(result)
        }
        val status = panel()
        fun displayed(view: TextView = status): String {
          var text = ""
          instrumentation.runOnMainSync { text = view.text.toString() }
          return text
        }
        await("initial profile and panel") {
          Nuxie.features.snapshot.value.state == FeatureInfo.State.Ready && displayed().contains("Balance: 3.0")
        }
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        await("Activity stopped") { stopped.get() === host }
        server.balance = 8
        val refreshed = runBlocking { Nuxie.hasFeature("exports", policy = FeatureCheckPolicy.REMOTE) }
        assertEquals(8.0, refreshed.balance!!, 0.0)
        assertEquals(8.0, Nuxie.features.snapshot.value.all.getValue("exports").balance!!, 0.0)
        instrumentation.waitForIdleSync()
        assertTrue("Stopped collector must not rewrite the hidden view", displayed().contains("Balance: 3.0"))
        app.startActivity(Intent(app, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("latest access after resume") { displayed().contains("Balance: 8.0") }
        assertSame("Resume must use the same Activity", host, (app as ExampleApplication).currentActivity)

        stopped.set(null)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        await("second Activity stop") { stopped.get() === host }
        server.rejectProfiles = true
        instrumentation.runOnMainSync { Nuxie.identify("feature-lifecycle-second-${SystemClock.elapsedRealtime()}") }
        await("new customer unknown") { Nuxie.features.snapshot.value.state == FeatureInfo.State.Unknown }
        assertTrue(Nuxie.features.snapshot.value.all.isEmpty())
        assertTrue(displayed().contains("Balance: 8.0"))
        app.startActivity(Intent(app, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await("new customer waiting panel") { displayed() == "Waiting for exports access for the current customer." }
        assertSame(host, app.currentActivity)
        val switchedCustomer = Nuxie.distinctId
        instrumentation.runOnMainSync { host.recreate() }
        await("recreated Activity resumed") { app.currentActivity is MainActivity && app.currentActivity !== host }
        activity = app.currentActivity
        assertEquals("Recreation must preserve the current customer", switchedCustomer, Nuxie.distinctId)
        instrumentation.runOnMainSync { Nuxie.reset() }
        val anonymousCustomer = Nuxie.distinctId
        assertNotEquals(switchedCustomer, anonymousCustomer)
        val beforeResetRecreation = activity!!
        instrumentation.runOnMainSync { beforeResetRecreation.recreate() }
        await("anonymous Activity recreated") {
          app.currentActivity is MainActivity && app.currentActivity !== beforeResetRecreation
        }
        activity = app.currentActivity
        assertEquals("Recreation must preserve anonymous reset", anonymousCustomer, Nuxie.distinctId)
        val recreatedStatus = panel(activity!!)
        await("recreated observer attached to anonymous session") {
          displayed(recreatedStatus) == "Waiting for exports access for the current customer."
        }
      } finally {
        try {
          activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
          instrumentation.waitForIdleSync()
        } finally {
          try {
            runBlocking { Nuxie.shutdownAndAwait() }
          } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
          }
        }
      }
    }
  }

  private fun await(description: String, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (!condition()) {
      check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $description" }
      SystemClock.sleep(25)
    }
  }

  /** Real loopback HTTP; no internal Feature publication or lifecycle hooks. */
  private class FeatureServer : AutoCloseable {
    private val socket = ServerSocket(0)
    val url = "http://127.0.0.1:${socket.localPort}"
    @Volatile var balance = 3
    @Volatile var rejectProfiles = false
    private val worker = thread(name = "feature-observation-http") {
      try {
        while (!socket.isClosed) socket.accept().use { connection ->
          connection.soTimeout = 5000
          val input = connection.getInputStream().buffered()
          fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
              val value = input.read()
              check(value >= 0) { "Truncated HTTP headers" }
              if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
              bytes.write(value)
            }
          }
          val request = line()
          var length = 0
          while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
          }
          val requestBytes = ByteArray(length)
          var offset = 0
          while (offset < length) {
            val read = input.read(requestBytes, offset, length - offset)
            check(read > 0)
            offset += read
          }
          val path = request.split(' ')[1]
          val status: Int
          val response: String
          when {
            path == "/profile" && !rejectProfiles -> {
              status = 200
              response = """{"schemaVersion":"nuxie.journey-plane-profile.v1","status":"ok","delivery":{"renderBaseUrl":"https://render.example/","assetBaseUrl":"https://assets.example/"},"features":[{"id":"exports","type":"metered","balance":$balance,"unlimited":false,"nextResetAt":null,"interval":null}],"facts":{"properties":{},"memberships":{},"assignments":{}},"armedLegs":[],"releases":[]}"""
            }
            path == "/entitled" -> {
              status = 200
              response = JSONObject().put("customerId", JSONObject(String(requestBytes, Charsets.UTF_8)).getString("customerId"))
                .put("featureId", "exports").put("requiredBalance", 1).put("code", "allowed")
                .put("type", "metered").put("allowed", true).put("unlimited", false).put("balance", balance).toString()
            }
            else -> { status = 503; response = "{}" }
          }
          val bytes = response.toByteArray()
          connection.getOutputStream().apply {
            write(("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
              "Nuxie-App-Id: app_golden\r\nNuxie-App-Environment: test\r\nETag: \"feature-$balance\"\r\nConnection: close\r\n\r\n").toByteArray())
            write(bytes)
            flush()
          }
        }
      } catch (closed: SocketException) {
        if (!socket.isClosed) throw closed
      }
    }
    override fun close() {
      socket.close()
      worker.join(6000)
      check(!worker.isAlive) { "HTTP fixture did not stop" }
    }
  }
}
