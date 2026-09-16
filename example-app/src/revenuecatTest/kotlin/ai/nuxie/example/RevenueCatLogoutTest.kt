package ai.nuxie.example

import ai.nuxie.example.revenuecat.NuxieRevenueCatPurchaseDelegate
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.CacheFetchPolicy
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
  @Test fun anonymousOrDifferentCustomerCannotStartLogout() = runTest {
    for (customer in listOf("anonymous-other", "bob")) {
      val provider = HeldProvider().apply { this.customer = customer }
      val operation = ExamplePurchaseProvider.logoutOperation("alice", provider.purchases)
      assertTrue(runCatching { operation() }.isFailure)
      assertEquals(0, provider.logouts)
      assertEquals(0, provider.refreshes)
    }
  }

  @Test fun failureBeforeIdentityChangeRetriesLogoutAndSuccessMustConfirmAnonymous() = runTest {
    val provider = HeldProvider()
    val operation = ExamplePurchaseProvider.logoutOperation("alice", provider.purchases)
    val first = async { runCatching { operation() } }
    testScheduler.runCurrent()
    provider.logout.onError(PurchasesError(PurchasesErrorCode.NetworkError))
    assertTrue(first.await().isFailure)
    val second = async { runCatching { operation() } }
    testScheduler.runCurrent()
    assertEquals(2, provider.logouts)
    assertEquals(0, provider.refreshes)
    provider.logout.onReceived(mock(CustomerInfo::class.java))
    assertTrue("A callback with the original identity is not completed logout", second.await().isFailure)
  }

  @Test fun refreshFailureRemainsRetryableWithoutAnotherIdentityReset() = runTest {
    val provider = HeldProvider()
    val operation = ExamplePurchaseProvider.logoutOperation("alice", provider.purchases)
    val first = async { runCatching { operation() } }
    testScheduler.runCurrent()
    provider.customer = "anonymous-owned"
    provider.logout.onError(PurchasesError(PurchasesErrorCode.NetworkError))
    assertTrue(first.await().isFailure)
    val second = async { runCatching { operation() } }
    testScheduler.runCurrent()
    provider.refresh.onError(PurchasesError(PurchasesErrorCode.NetworkError))
    assertTrue(second.await().isFailure)
    val third = async { runCatching { operation() } }
    testScheduler.runCurrent()
    assertEquals(1, provider.logouts)
    assertEquals(2, provider.refreshes)
    provider.refresh.onReceived(mock(CustomerInfo::class.java))
    assertTrue(third.await().isSuccess)
    operation()
    assertEquals(1, provider.logouts)
    assertEquals(2, provider.refreshes)
  }

  @Test fun changedIdentityBeforeOrDuringRefreshCannotCompleteTheOriginalLogout() = runTest {
    for (changeBefore in listOf(true, false)) {
      val provider = HeldProvider()
      val operation = ExamplePurchaseProvider.logoutOperation("alice", provider.purchases)
      val first = async { runCatching { operation() } }
      testScheduler.runCurrent()
      provider.customer = "anonymous-owned"
      provider.logout.onError(PurchasesError(PurchasesErrorCode.NetworkError))
      assertTrue(first.await().isFailure)
      if (changeBefore) provider.customer = "anonymous-other"
      val retry = async { runCatching { operation() } }
      testScheduler.runCurrent()
      if (!changeBefore) {
        provider.customer = "bob"
        provider.refresh.onReceived(mock(CustomerInfo::class.java))
      }
      assertTrue(retry.await().isFailure)
      assertEquals(1, provider.logouts)
      assertEquals(if (changeBefore) 0 else 1, provider.refreshes)
    }
  }

  private class HeldProvider {
    var customer = "alice"
    var logouts = 0
    var refreshes = 0
    lateinit var logout: ReceiveCustomerInfoCallback
    lateinit var refresh: ReceiveCustomerInfoCallback
    val purchases = mock(Purchases::class.java) {
      when (it.method.name) {
        "getAppUserID" -> customer
        "isAnonymous" -> customer.startsWith("anonymous-")
        "logOut" -> { logouts++; logout = it.getArgument(0); null }
        "getCustomerInfo" -> {
          assertEquals(CacheFetchPolicy.FETCH_CURRENT, it.getArgument<CacheFetchPolicy>(0))
          refreshes++
          refresh = it.getArgument(1)
          null
        }
        else -> RETURNS_DEFAULTS.answer(it)
      }
    }
  }

  @Test fun retryAfterAnonymousTransitionWaitsForFreshCustomerInfoWithoutRepeatingLogout() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    var customer = "alice"
    var logoutCalls = 0
    lateinit var refresh: ReceiveCustomerInfoCallback
    var refreshCalls = 0
    val purchases = mock(Purchases::class.java) {
      when (it.method.name) {
        "getAppUserID" -> customer
        "isAnonymous" -> (customer != "alice")
        "logOut" -> {
          logoutCalls++
          val callback = it.getArgument<ReceiveCustomerInfoCallback>(0)
          if (customer == "alice") {
            customer = "anonymous-after-logout"
            callback.onError(PurchasesError(PurchasesErrorCode.NetworkError))
          } else callback.onError(PurchasesError(PurchasesErrorCode.LogOutWithAnonymousUserError))
          null
        }
        "getCustomerInfo" -> {
          assertEquals(CacheFetchPolicy.FETCH_CURRENT, it.getArgument<CacheFetchPolicy>(0))
          refreshCalls++
          refresh = it.getArgument(1)
          null
        }
        else -> RETURNS_DEFAULTS.answer(it)
      }
    }
    val owner = ProviderOperations(NuxieRevenueCatPurchaseDelegate({ null }, purchases), dispatcher)
    var resets = 0
    var retirements = 0
    val stages = mutableListOf<LogoutJournal.Stage>()
    val transition = SessionLogout(owner, { stages += it }, { resets++ }, { retirements++ },
      ExamplePurchaseProvider.logoutOperation("alice", purchases), dispatcher)
    assertEquals(SessionLogout.State.FAILED, transition.logout())
    assertFalse(stages.contains(LogoutJournal.Stage.COMPLETE))
    val retry = async { transition.logout() }
    testScheduler.runCurrent()
    assertEquals(SessionLogout.State.CLOSING, transition.state.value)
    assertEquals(1, logoutCalls)
    assertEquals(1, refreshCalls)
    assertFalse(retry.isCompleted)
    retry.cancelAndJoin()
    refresh.onReceived(mock(CustomerInfo::class.java))
    assertEquals(SessionLogout.State.COMPLETE, transition.logout())
    assertEquals(1, resets)
    assertEquals(1, retirements)
    assertEquals(1, logoutCalls)
  }

  @Test fun actualProviderLogoutStartsAfterRestoreDrainAndWaitsForItsOwnCallback() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    lateinit var restoreCallback: ReceiveCustomerInfoCallback
    lateinit var logoutCallback: ReceiveCustomerInfoCallback
    var logoutCalls = 0
    var customer = "alice"
    val actions = mutableListOf<String>()
    val purchases = mock(Purchases::class.java) {
      when (it.method.name) {
        "getAppUserID" -> customer
        "isAnonymous" -> (customer != "alice")
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
      { actions += "retire" }, ExamplePurchaseProvider.logoutOperation("alice", purchases), dispatcher)
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
    customer = "anonymous-after-logout"
    logoutCallback.onReceived(info)
    assertEquals(SessionLogout.State.COMPLETE, transition.logout())
    assertEquals("COMPLETE", actions.last())
    assertEquals(1, logoutCalls)
  }
}
