package ai.nuxie.example

import ai.nuxie.example.revenuecat.NuxieRevenueCatPurchaseDelegate
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.billing.PurchaseHandlingMode
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

internal object ExamplePurchaseProvider {
  const val name = "RevenueCat-owned Play"
  const val supportsAnonymousReset = false
  private var configuredCustomer: String? = null

  fun configure(app: ExampleApplication, key: String?, customer: String?, configuration: NuxieConfiguration) {
    require(!key.isNullOrBlank() && !customer.isNullOrBlank()) { "Provider public key and customer required." }
    check(!configuration.testStoreEnabled) { "Test Store bypasses providers; disable it for this example." }
    if (configuredCustomer == null) {
      Purchases.configure(PurchasesConfiguration.Builder(app, key).appUserID(customer).build())
      configuredCustomer = customer
    }
    check(configuredCustomer == customer) { "Restart the example before changing customer." }
    configuration.purchaseHandlingMode = PurchaseHandlingMode.APP_MANAGED
    configuration.purchaseDelegate = NuxieRevenueCatPurchaseDelegate({ app.currentActivity }, expectedCustomerId = customer)
  }
}
