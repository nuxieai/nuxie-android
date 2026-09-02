package ai.nuxie.example

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.TriggerUpdate
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var status: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    status = TextView(this).apply {
      gravity = Gravity.CENTER_HORIZONTAL
    }
    val showExperience = Button(this).apply {
      text = "Show purchase experience"
      setOnClickListener {
        status.text = "Triggering signed experience…"
        Nuxie.trigger(
          intent.getStringExtra(EXTRA_TRIGGER_EVENT) ?: "\$app_opened",
        ) { update ->
          runOnUiThread {
            status.text = when (update) {
              is TriggerUpdate.Error ->
                "${update.error.code}: ${update.error.message}"
              else -> update.toString()
            }
          }
        }
      }
    }
    val restore = Button(this).apply {
      text = "Restore purchases"
      setOnClickListener {
        status.text = "Restoring…"
        scope.launch {
          status.text = Nuxie.restorePurchases().toString()
        }
      }
    }
    setContentView(
      LinearLayout(this).apply {
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val inset = (24 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset, inset, inset)
        addView(status)
        addView(showExperience)
        addView(restore)
      },
    )

    val apiKey = intent.getStringExtra(EXTRA_API_KEY)
      ?: getString(R.string.nuxie_api_key)
    if (apiKey.isBlank() || apiKey == "replace-with-your-api-key") {
      status.text = "Launch with --es $EXTRA_API_KEY <public-test-key>."
      showExperience.isEnabled = false
      restore.isEnabled = false
      return
    }

    val configuration = NuxieConfiguration(apiKey).apply {
      environment = NuxieEnvironment.DEVELOPMENT
      logLevel = LogLevel.DEBUG
      intent.getStringExtra(EXTRA_API_ENDPOINT)?.let {
        testingOverrides.apiEndpoint = URL(it)
      }
    }
    Nuxie.setup(applicationContext, configuration)
    intent.getStringExtra(EXTRA_DISTINCT_ID)?.let(Nuxie::identify)
    status.text = getString(R.string.setup_status, Nuxie.version)
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private companion object {
    const val EXTRA_API_KEY = "nuxie_api_key"
    const val EXTRA_API_ENDPOINT = "nuxie_api_endpoint"
    const val EXTRA_DISTINCT_ID = "nuxie_distinct_id"
    const val EXTRA_TRIGGER_EVENT = "nuxie_trigger_event"
  }
}
