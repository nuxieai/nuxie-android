package ai.nuxie.example

import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.NuxieConfiguration
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView

class MainActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    Nuxie.setup(
      applicationContext,
      NuxieConfiguration(apiKey = getString(R.string.nuxie_api_key)),
    )

    setContentView(
      TextView(this).apply {
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
        gravity = Gravity.CENTER
        text = getString(R.string.setup_status, Nuxie.version)
      },
    )
  }
}
