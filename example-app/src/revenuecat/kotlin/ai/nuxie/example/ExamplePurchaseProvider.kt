package ai.nuxie.example

import ai.nuxie.example.revenuecat.NuxieRevenueCatPurchaseDelegate
import ai.nuxie.sdk.NuxieConfiguration
import ai.nuxie.sdk.billing.PurchaseHandlingMode
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.awaitLogIn

internal object ExamplePurchaseProvider {
  const val supportsLogout = true

  const val name = "RevenueCat-owned Play"
  const val supportsAnonymousReset = false
  private var configuredCustomer: String? = null
  private var configuredKey: String? = null

  fun logoutOperation(expectedCustomer: String, purchases: Purchases = Purchases.sharedInstance): suspend () -> Unit {
    val operation = RevenueCatLogoutOperation(purchases, expectedCustomer)
    return {
      operation.logout()
      configuredCustomer = null
    }
  }

  suspend fun signIn(app: ExampleApplication, key: String?, customer: String) {
    require(!key.isNullOrBlank() && customer.isNotBlank()) { "Provider public key and customer required." }
    if (!Purchases.isConfigured) {
      // Preserve the cached identity while recovering explicit sign-in intent.
      Purchases.configure(PurchasesConfiguration.Builder(app, key).build())
      configuredKey = key
    }
    check(configuredKey == key) { "Provider project cannot change within this application process." }
    signInCustomer(customer)
  }

  internal suspend fun signInCustomer(customer: String, purchases: Purchases = Purchases.sharedInstance) {
    require(customer.isNotBlank()) { "Provider customer required." }
    purchases.awaitLogIn(customer)
    check(purchases.appUserID == customer && !purchases.isAnonymous) {
      "Provider sign-in did not confirm the requested customer."
    }
    configuredCustomer = customer
  }

  fun configure(app: ExampleApplication, key: String?, customer: String?, configuration: NuxieConfiguration) {
    require(!key.isNullOrBlank() && !customer.isNullOrBlank()) { "Provider public key and customer required." }
    check(!configuration.testStoreEnabled) { "Test Store bypasses providers; disable it for this example." }
    if (configuredCustomer == null) {
      Purchases.configure(PurchasesConfiguration.Builder(app, key).appUserID(customer).build())
      configuredCustomer = customer
      configuredKey = key
    }
    check(configuredKey == key) { "Provider project cannot change within this application process." }
    check(configuredCustomer == customer) { "Restart the example before changing customer." }
    configuration.purchaseHandlingMode = PurchaseHandlingMode.APP_MANAGED
    configuration.purchaseDelegate = NuxieRevenueCatPurchaseDelegate({ app.currentActivity }, expectedCustomerId = customer)
  }
}
