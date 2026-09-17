package ai.nuxie.example

import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.interfaces.LogInCallback
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class RevenueCatSignInTest {
  @Test fun admissionWaitsForTheActualLoginCallbackAndRequestedIdentity() = runTest {
    for (confirmed in listOf(true, false)) {
      var identity = "anonymous"
      lateinit var callback: LogInCallback
      val provider = mock(Purchases::class.java) {
        when (it.method.name) {
          "getAppUserID" -> identity
          "isAnonymous" -> (identity == "anonymous")
          "logIn" -> {
            assertEquals("new-customer", it.getArgument<String>(0))
            callback = it.getArgument(1)
            null
          }
          else -> RETURNS_DEFAULTS.answer(it)
        }
      }
      val login = async { runCatching { ExamplePurchaseProvider.signInCustomer("new-customer", provider) } }
      testScheduler.runCurrent()
      assertFalse(login.isCompleted)
      identity = if (confirmed) "new-customer" else "different-customer"
      callback.onReceived(mock(CustomerInfo::class.java), false)
      assertEquals(confirmed, login.await().isSuccess)
    }
  }

  @Test fun providerErrorIsNotSuccessfulAdmissionEvenIfIdentityChanged() = runTest {
    lateinit var callback: LogInCallback
    val provider = mock(Purchases::class.java) {
      when (it.method.name) {
        "getAppUserID" -> "new-customer"
        "isAnonymous" -> false
        "logIn" -> { callback = it.getArgument(1); null }
        else -> RETURNS_DEFAULTS.answer(it)
      }
    }
    val login = async { runCatching { ExamplePurchaseProvider.signInCustomer("new-customer", provider) } }
    testScheduler.runCurrent()
    callback.onError(PurchasesError(PurchasesErrorCode.NetworkError))
    assertTrue(login.await().isFailure)
  }
}
