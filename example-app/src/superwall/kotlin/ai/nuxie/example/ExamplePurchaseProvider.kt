package ai.nuxie.example

import ai.nuxie.example.superwall.NuxieSuperwallPurchaseDelegate
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.billing.PurchaseHandlingMode
import com.superwall.sdk.Superwall
import com.superwall.sdk.identity.identify

internal object ExamplePurchaseProvider {
  suspend fun signIn(app: ExampleApplication, key: String?, customer: String) {
    error("Coordinated sign-in is not available for this provider.")
  }

  const val supportsLogout = false
  fun logoutOperation(expectedCustomer: String): suspend () -> Unit = {
    error("Coordinated logout is not available for this provider.")
  }

  const val name = "Superwall-owned Play"
  const val supportsAnonymousReset = false
  private var configuredCustomer: String? = null

  fun configure(app: ExampleApplication, key: String?, customer: String?, configuration: NuxieConfiguration) {
    require(!key.isNullOrBlank() && !customer.isNullOrBlank()) { "Provider public key and customer required." }
    check(!configuration.testStoreEnabled) { "Test Store bypasses providers; disable it for this example." }
    if (configuredCustomer == null) {
      Superwall.configure(app, key)
      Superwall.instance.identify(customer)
      configuredCustomer = customer
    }
    check(configuredCustomer == customer) { "Restart the example before changing customer." }
    configuration.purchaseHandlingMode = PurchaseHandlingMode.APP_MANAGED
    configuration.purchaseDelegate = NuxieSuperwallPurchaseDelegate(expectedCustomerId = customer)
  }
}
