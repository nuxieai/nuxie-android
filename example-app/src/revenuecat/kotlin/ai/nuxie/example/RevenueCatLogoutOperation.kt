package ai.nuxie.example

import com.revenuecat.purchases.CacheFetchPolicy
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogOut

/** One session's logout attempt, serialized and retained by SessionLogout. */
internal class RevenueCatLogoutOperation(
  private val purchases: Purchases,
  private val expectedCustomer: String,
) {
  init { require(expectedCustomer.isNotBlank()) { "Expected RevenueCat customer is required." } }
  private var anonymousCustomer: String? = null
  private var completed = false

  suspend fun logout() {
    val previousAnonymous = anonymousCustomer
    if (previousAnonymous == null) {
      check(!purchases.isAnonymous && purchases.appUserID == expectedCustomer) {
        "RevenueCat logout no longer owns the expected customer."
      }
      try {
        purchases.awaitLogOut()
      } catch (failure: PurchasesException) {
        // The callback can report a refresh failure after identity reset. Keep
        // that exact identity for explicit retry; the failed attempt still fails.
        anonymousCustomer = currentAnonymous()
        throw failure
      }
      anonymousCustomer = checkNotNull(currentAnonymous()) { "RevenueCat logout did not finish anonymously." }
    } else {
      check(currentAnonymous() == previousAnonymous) { "RevenueCat identity changed after logout." }
      if (!completed) {
        purchases.awaitCustomerInfo(CacheFetchPolicy.FETCH_CURRENT)
        check(currentAnonymous() == previousAnonymous) { "RevenueCat identity changed during logout recovery." }
      }
    }
    completed = true
  }

  private fun currentAnonymous(): String? {
    val customer = purchases.appUserID
    return customer.takeIf { it.isNotBlank() && it != expectedCustomer && purchases.isAnonymous && purchases.appUserID == it }
  }
}
