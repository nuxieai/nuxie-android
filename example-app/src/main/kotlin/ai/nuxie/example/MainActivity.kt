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
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val buttons = mutableListOf<Button>()
  private lateinit var status: TextView
  private lateinit var analytics: TextView
  private lateinit var featureStatus: TextView
  private lateinit var observedFeature: String
  private var observeFeatures = false
  private var readyForOperations = false
  private var operationRunning = false
  private var featureObservation: Job? = null
  private var sessionObservation: Job? = null
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
    observedFeature = feature
    status = TextView(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
    analytics = TextView(this).apply { text = "Analytics sink: local only" }
    featureStatus = TextView(this).apply {
      text = "Feature access will appear after setup."
      accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
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
      addView(featureStatus)
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
    if (ExamplePurchaseProvider.supportsAnonymousReset) button("Reset to anonymous identity") {
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
    val app = application as ExampleApplication
    try {
      if (app.sessionLogout?.state?.value?.let { it != SessionLogout.State.ACTIVE } == true) {
        buttons.forEach { it.isEnabled = false }
        return
      }
      if (!Nuxie.isSetup && app.providerOperationJournal.hasUnfinished()) {
        status.text = "Previous purchase activity needs recovery before starting again."
        buttons.forEach { it.isEnabled = false }
        return
      }
      if (!Nuxie.isSetup && app.logoutJournal.read() != null) {
        status.text = if (app.logoutJournal.read()?.stage == LogoutJournal.Stage.COMPLETE) {
          "Signed out. Starting another session requires an explicit sign-in."
        } else {
          "A previous sign-out needs recovery before starting again."
        }
        buttons.forEach { it.isEnabled = false }
        return
      }
      val configuration = NuxieConfiguration(apiKey).apply {
        environment = NuxieEnvironment.DEVELOPMENT
        logLevel = LogLevel.DEBUG
        testStoreEnabled = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TEST_STORE, false)
        intent.getStringExtra(EXTRA_API_ENDPOINT)?.let { testingOverrides.apiEndpoint = URL(it) }
      }
      Nuxie.listener = listener
      if (!Nuxie.isSetup) {
        ExamplePurchaseProvider.configure(
          application as ExampleApplication, intent.getStringExtra("nuxie_provider_key"),
          intent.getStringExtra(EXTRA_DISTINCT_ID), configuration,
        )
        (application as ExampleApplication).ownProviderOperations(configuration)
        Nuxie.setup(this, configuration)
        intent.getStringExtra(EXTRA_DISTINCT_ID)?.let(Nuxie::identify)
        if (ExamplePurchaseProvider.supportsLogout) {
          val session = listOf(ExamplePurchaseProvider.name, apiKey, Nuxie.distinctId).joinToString("\u0000")
          val digest = MessageDigest.getInstance("SHA-256").digest(session.toByteArray()).joinToString("") { "%02x".format(it) }
          app.prepareLogout(digest) { ExamplePurchaseProvider.logout() }
        }
      }
      observeFeatures = true
      readyForOperations = true
      status.text = getString(R.string.setup_status, Nuxie.version) +
        if (configuration.testStoreEnabled) " Test Store enabled; no Play charges." else " ${ExamplePurchaseProvider.name}."
    } catch (_: Exception) {
      status.text = "Setup failed. Check keys, customer, endpoint and Test Store compatibility for ${ExamplePurchaseProvider.name}."
      if (Nuxie.listener === listener) Nuxie.listener = null
      buttons.forEach { it.isEnabled = false }
    }
  }

  private fun runOperation(message: String, operation: suspend () -> String) {
    status.text = message
    operationRunning = true
    updateButtonState()
    scope.launch {
      try {
        status.text = operation()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        status.text = "Request failed. Check connectivity and try again."
      } finally {
        operationRunning = false
        updateButtonState()
      }
    }
  }

  private fun updateButtonState() {
    val state = (application as ExampleApplication).sessionLogout?.state?.value
    val enabled = readyForOperations && !operationRunning && (state == null || state == SessionLogout.State.ACTIVE)
    buttons.forEach { it.isEnabled = enabled }
  }

  override fun onStart() {
    super.onStart()
    (application as ExampleApplication).sessionLogout?.let { session ->
      sessionObservation = scope.launch {
        session.state.collect { state ->
          updateButtonState()
          when (state) {
            SessionLogout.State.ACTIVE -> Unit
            SessionLogout.State.CLOSING -> status.text = "Signing out; waiting for current purchases to finish…"
            SessionLogout.State.FAILED -> status.text = "Sign out could not finish. Retry before continuing."
            SessionLogout.State.COMPLETE -> status.text = "Signed out."
            SessionLogout.State.RECOVERY_REQUIRED -> status.text = "Previous purchase activity needs recovery before sign out can finish."
          }
        }
      }
    }
    if (observeFeatures) {
      featureObservation = scope.launch {
        Nuxie.features.snapshot.collect { snapshot ->
          val summary = featureAccessSummary(observedFeature, snapshot)
          if (featureStatus.text.toString() != summary) featureStatus.text = summary
        }
      }
    }
  }

  override fun onStop() {
    sessionObservation?.cancel()
    sessionObservation = null
    featureObservation?.cancel()
    featureObservation = null
    super.onStop()
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
