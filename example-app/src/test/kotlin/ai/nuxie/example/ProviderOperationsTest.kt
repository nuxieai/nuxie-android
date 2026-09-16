package ai.nuxie.example

import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderOperationsTest {
  @Test fun alreadyCancelledCallerCannotStartProviderWork() = runTest {
    var calls = 0
    val delegate = object : NuxiePurchaseDelegate {
      override suspend fun purchase(product: StoreProduct) = PurchaseResult.Cancelled
      override suspend fun restorePurchases(): RestoreResult { calls++; return RestoreResult.NoPurchases }
    }
    val owner = ProviderOperations(delegate, StandardTestDispatcher(testScheduler))
    val caller = launch( start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
      kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
      owner.restorePurchases()
    }
    caller.join()
    testScheduler.runCurrent()
    owner.closeAndAwait()
    assertEquals(0, calls)
  }

  @Test fun cancellationCannotReleaseOwnershipAndCloseRejectsNewWork() = runTest {
    val release = CompletableDeferred<Unit>()
    var calls = 0
    var completions = 0
    val delegate = object : NuxiePurchaseDelegate {
      override suspend fun purchase(product: StoreProduct): PurchaseResult {
        calls++
        release.await()
        completions++
        return PurchaseResult.Purchased
      }
      override suspend fun restorePurchases(): RestoreResult {
        calls++
        release.await()
        completions++
        return RestoreResult.Restored
      }
    }
    val owner = ProviderOperations(delegate, StandardTestDispatcher(testScheduler))
    val purchase = async { owner.purchase(mock(StoreProduct::class.java)) }
    val restore = async { owner.restorePurchases() }
    testScheduler.runCurrent()
    assertEquals(2, calls)
    purchase.cancelAndJoin()
    restore.cancelAndJoin()
    val firstDrain = async { owner.closeAndAwait() }
    val secondDrain = async { owner.closeAndAwait() }
    testScheduler.runCurrent()
    assertFalse(firstDrain.isCompleted)
    assertFalse(secondDrain.isCompleted)
    firstDrain.cancelAndJoin()
    assertTrue(owner.purchase(mock(StoreProduct::class.java)) is PurchaseResult.Failed)
    assertTrue(owner.restorePurchases() is RestoreResult.Failed)
    assertEquals(2, calls)
    assertEquals(0, completions)
    release.complete(Unit)
    secondDrain.await()
    assertEquals(2, completions)
    owner.closeAndAwait()
  }

  @Test fun closeDrainsWorkAdmittedBeforeItsDispatcherRuns() = runTest {
    var called = false
    val delegate = object : NuxiePurchaseDelegate {
      override suspend fun purchase(product: StoreProduct) = PurchaseResult.Cancelled
      override suspend fun restorePurchases(): RestoreResult {
        called = true
        return RestoreResult.NoPurchases
      }
    }
    val owner = ProviderOperations(delegate, StandardTestDispatcher(testScheduler))
    val admitted = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { owner.restorePurchases() }
    assertFalse(called)
    owner.closeAndAwait()
    assertTrue(called)
    assertEquals(RestoreResult.NoPurchases, admitted.await())
    assertTrue(owner.restorePurchases() is RestoreResult.Failed)
  }
}
