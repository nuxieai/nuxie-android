package ai.nuxie.example

import ai.nuxie.example.revenuecat.NuxieRevenueCatPurchaseDelegate
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class RevenueCatLogoutTest {
  @Test fun actualProviderLogoutStartsAfterRestoreDrainAndWaitsForItsOwnCallback() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    lateinit var restoreCallback: ReceiveCustomerInfoCallback
    lateinit var logoutCallback: ReceiveCustomerInfoCallback
    var logoutCalls = 0
    val actions = mutableListOf<String>()
    val purchases = mock(Purchases::class.java) {
      when (it.method.name) {
        "getAppUserID" -> "alice"
        "restorePurchases" -> { restoreCallback = it.getArgument(0); null }
        "logOut" -> {
          assertEquals(listOf("REQUESTED", "DRAINED", "reset", "SDK_RESET", "retire", "SDK_RETIRED"), actions)
          logoutCalls++
          logoutCallback = it.getArgument(0)
          null
        }
        else -> RETURNS_DEFAULTS.answer(it)
      }
    }
    val owner = ProviderOperations(NuxieRevenueCatPurchaseDelegate({ null }, purchases), dispatcher)
    val restore = async { owner.restorePurchases() }
    testScheduler.runCurrent()
    restore.cancelAndJoin()
    val transition = SessionLogout(owner, { actions += it.name }, { actions += "reset" },
      { actions += "retire" }, { ExamplePurchaseProvider.logout(purchases) }, dispatcher)
    val waiter = async { transition.logout() }
    testScheduler.runCurrent()
    waiter.cancelAndJoin()
    assertEquals(0, logoutCalls)
    val info = mock(CustomerInfo::class.java, RETURNS_DEEP_STUBS)
    `when`(info.entitlements.active).thenReturn(emptyMap())
    restoreCallback.onReceived(info)
    testScheduler.runCurrent()
    assertEquals(1, logoutCalls)
    assertEquals(SessionLogout.State.CLOSING, transition.state.value)
    assertFalse(actions.contains("COMPLETE"))
    logoutCallback.onReceived(info)
    assertEquals(SessionLogout.State.COMPLETE, transition.logout())
    assertEquals("COMPLETE", actions.last())
    assertEquals(1, logoutCalls)
  }
}
