package ai.nuxie.example

import ai.nuxie.sdk.NuxieConfiguration

internal object ExamplePurchaseProvider {
  const val supportsLogout = false
  fun logoutOperation(expectedCustomer: String): suspend () -> Unit = {
    error("Coordinated logout is not available for this provider.")
  }

  const val name = "Nuxie-managed Play"
  const val supportsAnonymousReset = true
  fun configure(app: ExampleApplication, key: String?, customer: String?, configuration: NuxieConfiguration) = Unit
}
