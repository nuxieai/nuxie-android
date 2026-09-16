package ai.nuxie.example

import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderOperationJournalTest {
  private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("operation-test", Context.MODE_PRIVATE)
  private fun delegate(restore: suspend () -> RestoreResult) = object : NuxiePurchaseDelegate {
    override suspend fun purchase(product: StoreProduct) = PurchaseResult.Purchased
    override suspend fun restorePurchases() = restore()
  }

  @Test fun markerPrecedesProviderAndSurvivesWaiterCancellationUntilCompletion() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val preferences = prefs()
    val journal = ProviderOperationJournal(preferences, dispatcher)
    val release = CompletableDeferred<Unit>()
    val owner = ProviderOperations(delegate {
      assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
      release.await()
      RestoreResult.Restored
    }, dispatcher, journal)
    val caller = async { owner.restorePurchases() }
    testScheduler.runCurrent()
    caller.cancelAndJoin()
    assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
    val drain = async { owner.closeAndAwait() }
    testScheduler.runCurrent()
    assertFalse(drain.isCompleted)
    release.complete(Unit)
    drain.await()
    assertFalse(ProviderOperationJournal(preferences).hasUnfinished())
  }

  @Test fun pendingPaymentAndUnexpectedFailureRetainRecoveryMarkers() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val preferences = prefs()
    val journal = ProviderOperationJournal(preferences, dispatcher)
    val pending = object : NuxiePurchaseDelegate {
      override suspend fun purchase(product: StoreProduct) = PurchaseResult.Pending
      override suspend fun restorePurchases() = RestoreResult.NoPurchases
    }
    val owner = ProviderOperations(pending, dispatcher, journal)
    assertEquals(PurchaseResult.Pending, owner.purchase(mock(StoreProduct::class.java)))
    assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
    assertTrue(runCatching { owner.closeAndAwait() }.exceptionOrNull() is ProviderRecoveryRequired)
    assertTrue(preferences.edit().remove("provider-operations").commit())
    val failed = ProviderOperations(delegate { error("unexpected callback failure") }, dispatcher, journal)
    assertTrue(runCatching { failed.restorePurchases() }.isFailure)
    assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
    assertTrue(runCatching { failed.closeAndAwait() }.exceptionOrNull() is ProviderRecoveryRequired)
  }

  @Test fun commitFailuresBlockDispatchOrCleanDrainEvenWhenPreferencesMemoryChanges() = runTest {
    for (failAt in listOf(1, 2)) {
      val dispatcher = StandardTestDispatcher(testScheduler)
      val real = prefs()
      assertTrue(real.edit().clear().commit())
      var writes = 0
      val preferences = object : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor {
          val actual = real.edit()
          return object : SharedPreferences.Editor by actual {
            override fun commit(): Boolean {
              val committed = actual.commit()
              writes++
              return committed && writes != failAt
            }
          }
        }
      }
      var calls = 0
      val owner = ProviderOperations(delegate { calls++; RestoreResult.NoPurchases }, dispatcher,
        ProviderOperationJournal(preferences, dispatcher))
      assertTrue(runCatching { owner.restorePurchases() }.isFailure)
      assertEquals(if (failAt == 1) 0 else 1, calls)
      assertTrue(runCatching { owner.closeAndAwait() }.exceptionOrNull() is ProviderRecoveryRequired)
    }
  }

  @Test fun reportedFailuresCannotCertifyACompletedExternalOperation() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val preferences = prefs()
    for (purchase in listOf(true, false)) {
      assertTrue(preferences.edit().clear().commit())
      val failure = IllegalStateException("Outcome unknown")
      val delegate = object : NuxiePurchaseDelegate {
        override suspend fun purchase(product: StoreProduct) = PurchaseResult.Failed(failure)
        override suspend fun restorePurchases() = RestoreResult.Failed(failure)
      }
      val owner = ProviderOperations(delegate, dispatcher, ProviderOperationJournal(preferences, dispatcher))
      if (purchase) assertTrue(owner.purchase(mock(StoreProduct::class.java)) is PurchaseResult.Failed)
      else assertTrue(owner.restorePurchases() is RestoreResult.Failed)
      assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
      assertTrue(runCatching { owner.closeAndAwait() }.exceptionOrNull() is ProviderRecoveryRequired)
    }
  }

  @Test fun retiringOneCallCannotClearAnotherAndMalformedDataBlocksRead() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val preferences = prefs()
    val journal = ProviderOperationJournal(preferences, dispatcher)
    val first = journal.begin()
    val second = journal.begin()
    journal.finish(first, false)
    assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
    assertTrue(runCatching { journal.finish(first, false) }.isFailure)
    journal.finish(second, false)
    assertFalse(journal.hasUnfinished())
    for (raw in listOf("broken", "{}", """{"version":2,"operations":{}}""")) {
      assertTrue(preferences.edit().putString("provider-operations", raw).commit())
      assertThrows(Exception::class.java) { journal.hasUnfinished() }
    }
  }
}
