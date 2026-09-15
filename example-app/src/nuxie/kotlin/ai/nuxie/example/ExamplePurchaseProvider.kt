package ai.nuxie.example

import ai.nuxie.sdk.NuxieConfiguration

internal object ExamplePurchaseProvider {
  const val name = "Nuxie-managed Play"
  const val supportsAnonymousReset = true
  fun configure(app: ExampleApplication, key: String?, customer: String?, configuration: NuxieConfiguration) = Unit
}
