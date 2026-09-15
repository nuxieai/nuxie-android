package ai.nuxie.example

import ai.nuxie.sdk.AppAction
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieActivityInfo
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieListener
import ai.nuxie.sdk.billing.RestoreResult
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.graphics.Rect
import android.view.WindowInsets
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val buttons = mutableListOf<Button>()
  private lateinit var status: TextView
  private lateinit var analytics: TextView
  private val listener = object : NuxieListener {
    override fun onAppActionRequested(sdk: Nuxie, action: AppAction) {
      status.text = "App Action requested: ${action.name}"
    }

    override fun onActivityEmitted(sdk: Nuxie, info: NuxieActivityInfo) {
      ExampleAnalytics.record(info)
      analytics.text = "Last SDK activity: ${info.name}"
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val event = intent.getStringExtra(EXTRA_TRIGGER_EVENT) ?: "example_opened"
    val feature = intent.getStringExtra(EXTRA_FEATURE_ID) ?: "exports"
    status = TextView(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
    analytics = TextView(this).apply { text = "Analytics sink: local only" }
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      val inset = (24 * resources.displayMetrics.density).toInt()
      setPadding(inset, inset, inset, inset)
      addView(TextView(this@MainActivity).apply {
        text = "Nuxie integration example"
        textSize = 24f
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
      })
      addView(status)
    }
    fun button(label: String, action: () -> Unit) {
      val control = Button(this).apply { text = label; setOnClickListener { action() } }
      buttons += control
      content.addView(control, ViewGroup.LayoutParams(-1, -2))
    }
    button("Send $event") {
      Nuxie.trigger(event, mapOf("source" to "android_example"))
      status.text = "Event submitted. A matching Journey decides whether to present."
    }
    button("Check $feature access") {
      runOperation("Checking access…") {
        val access = Nuxie.hasFeature(feature)
        when {
          !access.allowed -> "Access unavailable for $feature."
          access.unlimited -> "Access allowed for $feature (unlimited)."
          access.balance != null -> "Access allowed for $feature. Balance: ${access.balance}."
          else -> "Access allowed for $feature."
        }
      }
    }
    button("Restore purchases") {
      runOperation("Restoring…") {
        when (Nuxie.restorePurchases()) {
          RestoreResult.Restored -> "Purchases restored."
          RestoreResult.NoPurchases -> "No purchases to restore."
          is RestoreResult.Failed -> "Restore failed. Try again."
        }
      }
    }
    button("Reset to anonymous identity") {
      Nuxie.reset()
      status.text = "Using an anonymous identity."
    }
    content.addView(analytics)
    val scroll = ScrollView(this).apply {
      addView(content, ViewGroup.LayoutParams(-1, -2))
      setOnApplyWindowInsetsListener { view, insets ->
        val safe = if (Build.VERSION.SDK_INT >= 30) {
          val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
          Rect(bars.left, bars.top, bars.right, bars.bottom)
        } else {
          @Suppress("DEPRECATION")
          Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
            insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        insets
      }
    }
    setContentView(scroll)
    scroll.requestApplyInsets()

    val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: getString(R.string.nuxie_api_key)
    if (!apiKey.startsWith("pk_test_")) {
      status.text = "Supply a public test key with the nuxie_api_key launch extra."
      buttons.forEach { it.isEnabled = false }
      return
    }
    try {
      val configuration = NuxieConfiguration(apiKey).apply {
        environment = NuxieEnvironment.DEVELOPMENT
        logLevel = LogLevel.DEBUG
        testStoreEnabled = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TEST_STORE, false)
        intent.getStringExtra(EXTRA_API_ENDPOINT)?.let { testingOverrides.apiEndpoint = URL(it) }
      }
      Nuxie.listener = listener
      Nuxie.setup(this, configuration)
      intent.getStringExtra(EXTRA_DISTINCT_ID)?.let(Nuxie::identify)
      status.text = getString(R.string.setup_status, Nuxie.version) +
        if (configuration.testStoreEnabled) " Test Store enabled; no Play charges." else " Play purchase handling enabled."
    } catch (_: Exception) {
      status.text = "Setup failed. Check the test key and endpoint."
      if (Nuxie.listener === listener) Nuxie.listener = null
      buttons.forEach { it.isEnabled = false }
    }
  }

  private fun runOperation(message: String, operation: suspend () -> String) {
    status.text = message
    buttons.forEach { it.isEnabled = false }
    scope.launch {
      try {
        status.text = operation()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        status.text = "Request failed. Check connectivity and try again."
      } finally {
        buttons.forEach { it.isEnabled = true }
      }
    }
  }

  override fun onDestroy() {
    if (Nuxie.listener === listener) Nuxie.listener = null
    scope.cancel()
    // The SDK is app-scoped and remains active across Activity recreation.
    super.onDestroy()
  }

  private companion object {
    const val EXTRA_API_KEY = "nuxie_api_key"
    const val EXTRA_API_ENDPOINT = "nuxie_api_endpoint"
    const val EXTRA_DISTINCT_ID = "nuxie_distinct_id"
    const val EXTRA_TRIGGER_EVENT = "nuxie_trigger_event"
    const val EXTRA_FEATURE_ID = "nuxie_feature_id"
    const val EXTRA_TEST_STORE = "nuxie_test_store"
  }
}
