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
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderOperationJournalTest {
  private fun product() = mock(StoreProduct::class.java).also { `when`(it.storeProductId).thenReturn("test-product") }
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
    }, dispatcher, journal, "a".repeat(64))
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
    val owner = ProviderOperations(pending, dispatcher, journal, "a".repeat(64))
    assertEquals(PurchaseResult.Pending, owner.purchase(product()))
    assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
    assertTrue(runCatching { owner.closeAndAwait() }.exceptionOrNull() is ProviderRecoveryRequired)
    assertTrue(preferences.edit().remove("provider-operations").commit())
    val failed = ProviderOperations(delegate { error("unexpected callback failure") }, dispatcher, journal, "a".repeat(64))
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
        ProviderOperationJournal(preferences, dispatcher), "a".repeat(64))
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
      val owner = ProviderOperations(delegate, dispatcher, ProviderOperationJournal(preferences, dispatcher), "a".repeat(64))
      if (purchase) assertTrue(owner.purchase(product()) is PurchaseResult.Failed)
      else assertTrue(owner.restorePurchases() is RestoreResult.Failed)
      assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
      assertTrue(runCatching { owner.closeAndAwait() }.exceptionOrNull() is ProviderRecoveryRequired)
    }
  }

  @Test fun restartRetainsOperationKindProductAndExactSession() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val preferences = prefs()
    val journal = ProviderOperationJournal(preferences, dispatcher)
    val session = "a".repeat(64)
    val purchase = journal.begin(session, ProviderOperationJournal.Kind.PURCHASE, "annual")
    val restore = journal.begin(session, ProviderOperationJournal.Kind.RESTORE)
    val restarted = ProviderOperationJournal(preferences, dispatcher)
    assertEquals(setOf(
      ProviderOperationJournal.Operation(purchase, "active", session, ProviderOperationJournal.Kind.PURCHASE, "annual"),
      ProviderOperationJournal.Operation(restore, "active", session, ProviderOperationJournal.Kind.RESTORE, null),
    ), restarted.snapshot().toSet())
    assertEquals(ProviderOperationJournal.Recovery.PURCHASE, restarted.recoveryFor(session))
    assertEquals(ProviderOperationJournal.Recovery.OTHER_SESSION, restarted.recoveryFor("b".repeat(64)))
    assertTrue(runCatching { restarted.begin("b".repeat(64), ProviderOperationJournal.Kind.RESTORE) }.isFailure)
    assertTrue(runCatching { restarted.finish(purchase, "b".repeat(64), false) }.isFailure)
    assertEquals(2, restarted.snapshot().size)
    restarted.finish(purchase, session, true)
    assertEquals("pending", restarted.snapshot().single { it.id == purchase }.state)
    restarted.finish(restore, session, false)
    assertEquals(listOf(purchase), restarted.snapshot().map { it.id })
  }

  @Test fun legacyRecordsCannotBeClaimedByTheNextLaunch() = runTest {
    val preferences = prefs()
    val id = "00000000-0000-0000-0000-000000000001"
    val original = """{"version":1,"operations":{"$id":"pending"}}"""
    assertTrue(preferences.edit().putString("provider-operations", original).commit())
    val journal = ProviderOperationJournal(preferences, StandardTestDispatcher(testScheduler))
    assertEquals(listOf(ProviderOperationJournal.Operation(id, "pending", null, null, null)), journal.snapshot())
    assertEquals(ProviderOperationJournal.Recovery.LEGACY, journal.recoveryFor("a".repeat(64)))
    assertTrue(runCatching { journal.begin("a".repeat(64), ProviderOperationJournal.Kind.RESTORE) }.isFailure)
    assertTrue(runCatching { journal.finish(id, "a".repeat(64), false) }.isFailure)
    assertEquals(original, preferences.getString("provider-operations", null))
  }

  @Test fun invalidContextCannotBePersistedAndRestoreCannotBecomePending() = runTest {
    val journal = ProviderOperationJournal(prefs(), StandardTestDispatcher(testScheduler))
    val session = "a".repeat(64)
    assertTrue(runCatching { journal.begin("customer", ProviderOperationJournal.Kind.RESTORE) }.isFailure)
    assertTrue(runCatching { journal.begin(session, ProviderOperationJournal.Kind.PURCHASE) }.isFailure)
    assertTrue(runCatching { journal.begin(session, ProviderOperationJournal.Kind.PURCHASE, "x".repeat(513)) }.isFailure)
    assertTrue(runCatching { journal.begin(session, ProviderOperationJournal.Kind.RESTORE, "annual") }.isFailure)
    assertFalse(journal.hasUnfinished())
    val restore = journal.begin(session, ProviderOperationJournal.Kind.RESTORE)
    assertTrue(runCatching { journal.finish(restore, session, true) }.isFailure)
    assertEquals(ProviderOperationJournal.Recovery.RESTORE, journal.recoveryFor(session))
    journal.finish(restore, session, false)
    assertEquals(ProviderOperationJournal.Recovery.NONE, journal.recoveryFor(session))
  }

  @Test fun malformedContextCannotBecomeARecoveryClaim() {
    val preferences = prefs()
    val id = "00000000-0000-0000-0000-000000000001"
    val session = "a".repeat(64)
    val records = listOf(
      """{"state":"pending","session":"$session","kind":"RESTORE","productId":null}""",
      """{"state":"active","session":null,"kind":"PURCHASE","productId":"annual"}""",
      """{"state":"active","session":"$session","kind":"PURCHASE","productId":123}""",
      """{"state":"active","session":"$session","kind":"UNKNOWN","productId":null}""",
      """{"state":"active","session":"$session","kind":"RESTORE"}""",
    )
    for (record in records) {
      assertTrue(preferences.edit().putString("provider-operations", """{"version":2,"operations":{"$id":$record}}""").commit())
      assertThrows(Exception::class.java) { ProviderOperationJournal(preferences).recoveryFor(session) }
    }
  }

  @Test fun retiringOneCallCannotClearAnotherAndMalformedDataBlocksRead() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val preferences = prefs()
    val journal = ProviderOperationJournal(preferences, dispatcher)
    val first = journal.begin("a".repeat(64), ProviderOperationJournal.Kind.RESTORE)
    val second = journal.begin("a".repeat(64), ProviderOperationJournal.Kind.RESTORE)
    journal.finish(first, "a".repeat(64), false)
    assertTrue(ProviderOperationJournal(preferences).hasUnfinished())
    assertTrue(runCatching { journal.finish(first, "a".repeat(64), false) }.isFailure)
    journal.finish(second, "a".repeat(64), false)
    assertFalse(journal.hasUnfinished())
    for (raw in listOf("broken", "{}", """{"version":3,"operations":{}}""")) {
      assertTrue(preferences.edit().putString("provider-operations", raw).commit())
      assertThrows(Exception::class.java) { journal.hasUnfinished() }
    }
  }
}
