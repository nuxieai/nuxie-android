package ai.nuxie.example

import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLogoutTest {
  private fun delegate(restore: suspend () -> RestoreResult = { RestoreResult.NoPurchases }) =
    object : NuxiePurchaseDelegate {
      override suspend fun purchase(product: StoreProduct) = PurchaseResult.Purchased
      override suspend fun restorePurchases() = restore()
    }

  @Test fun logoutWaitsForOwnedCallbacksAndSurvivesCancelledWaiters() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val provider = CompletableDeferred<Unit>()
    val actions = mutableListOf<String>()
    val owner = ProviderOperations(delegate { provider.await(); RestoreResult.Restored }, dispatcher)
    val restore = async { owner.restorePurchases() }
    testScheduler.runCurrent()
    restore.cancelAndJoin()
    val logout = SessionLogout(owner, { actions += it.name }, { actions += "reset" },
      { actions += "retire" }, { actions += "logout" }, dispatcher)
    val first = async { logout.logout() }
    val second = async { logout.logout() }
    testScheduler.runCurrent()
    first.cancelAndJoin()
    assertFalse(second.isCompleted)
    assertEquals(listOf("REQUESTED"), actions)
    assertTrue(owner.restorePurchases() is RestoreResult.Failed)
    provider.complete(Unit)
    assertEquals(SessionLogout.State.COMPLETE, second.await())
    assertEquals(listOf("REQUESTED", "DRAINED", "reset", "SDK_RESET", "retire", "SDK_RETIRED", "logout", "COMPLETE"), actions)
    logout.logout()
    assertEquals(1, actions.count { it == "logout" })
  }

  @Test fun persistenceFailuresNeverRepeatCompletedSideEffects() = runTest {
    for (failedStage in LogoutJournal.Stage.entries) {
      val dispatcher = StandardTestDispatcher(testScheduler)
      val owner = ProviderOperations(delegate(), dispatcher)
      var fail = true
      var resets = 0
      var retirements = 0
      var logouts = 0
      val logout = SessionLogout(owner, { stage ->
        if (stage == failedStage && fail) { fail = false; error("injected write failure") }
      }, { resets++ }, { retirements++ }, { logouts++ }, dispatcher)
      assertEquals(SessionLogout.State.FAILED, logout.logout())
      assertTrue(owner.restorePurchases() is RestoreResult.Failed)
      if (failedStage == LogoutJournal.Stage.REQUESTED) assertEquals(0, resets)
      assertEquals(SessionLogout.State.COMPLETE, logout.logout())
      assertEquals(1, resets)
      assertEquals(1, retirements)
      assertEquals(1, logouts)
    }
  }

  @Test fun teardownAndProviderFailureRetainEarlierSuccessfulStages() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val owner = ProviderOperations(delegate(), dispatcher)
    var resets = 0
    var retirements = 0
    var logouts = 0
    val logout = SessionLogout(owner, {}, { resets++ }, {
      retirements++
      if (retirements == 1) error("teardown failed")
    }, {
      logouts++
      if (logouts == 1) error("provider failed")
    }, dispatcher)
    assertEquals(SessionLogout.State.FAILED, logout.logout())
    assertEquals(1, resets)
    assertEquals(0, logouts)
    assertEquals(SessionLogout.State.FAILED, logout.logout())
    assertEquals(1, resets)
    assertEquals(2, retirements)
    assertEquals(SessionLogout.State.COMPLETE, logout.logout())
    assertEquals(1, resets)
    assertEquals(2, retirements)
    assertEquals(2, logouts)
  }

  @Test fun pendingPaymentNeverPermitsIdentityMutation() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val delegate = object : NuxiePurchaseDelegate {
      override suspend fun purchase(product: StoreProduct) = PurchaseResult.Pending
      override suspend fun restorePurchases() = RestoreResult.NoPurchases
    }
    val owner = ProviderOperations(delegate, dispatcher)
    assertEquals(PurchaseResult.Pending, owner.purchase(mock(StoreProduct::class.java)))
    var mutations = 0
    val logout = SessionLogout(owner, {}, { mutations++ }, { mutations++ }, { mutations++ }, dispatcher)
    assertEquals(SessionLogout.State.RECOVERY_REQUIRED, logout.logout())
    assertEquals(0, mutations)
    assertTrue(owner.restorePurchases() is RestoreResult.Failed)
  }
}
