package ai.nuxie.example

import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll

/** App-owned provider calls: cancelling a waiter does not abandon its callback. */
internal class ProviderOperations(
  private val delegate: NuxiePurchaseDelegate,
  dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : NuxiePurchaseDelegate {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val lock = Any()
  private val pending = mutableSetOf<Deferred<*>>()
  private var closed = false
  private var pendingPayment = false

  override suspend fun purchase(product: StoreProduct): PurchaseResult =
    submit {
      delegate.purchase(product).also { result ->
        if (result == PurchaseResult.Pending) synchronized(lock) { pendingPayment = true }
      }
    } ?: PurchaseResult.Failed(closedFailure())

  override suspend fun restorePurchases(): RestoreResult =
    submit { delegate.restorePurchases() } ?: RestoreResult.Failed(closedFailure())

  /**
   * Permanently close admission and await admitted callbacks. Concurrent callers
   * may join this drain; cancelling one caller leaves ownership intact.
   * This does not log out the provider or certify finality of a pending payment.
   */
  fun closeAdmission() { synchronized(lock) { closed = true } }

  suspend fun closeAndAwait() {
    val admitted = synchronized(lock) {
      closed = true
      pending.toList()
    }
    admitted.joinAll()
    scope.cancel()
    if (synchronized(lock) { pendingPayment }) throw ProviderRecoveryRequired()
  }

  private suspend fun <T : Any> submit(operation: suspend () -> T): T? {
    val caller = currentCoroutineContext()
    val job = synchronized(lock) {
      caller.ensureActive()
      if (closed) return null
      // Register before dispatch so close cannot miss an admitted operation.
      scope.async(start = CoroutineStart.LAZY) { operation() }.also { pending += it }
    }
    job.invokeOnCompletion { failure ->
      synchronized(lock) { pending -= job }
      if (failure != null) {
        // A cancelled waiter may no longer observe this failure. Never log
        // provider exception messages, which may contain customer data.
        Log.w("NuxieExample", "Provider operation failed before session drain.")
      }
    }
    job.start()
    return job.await()
  }

  private fun closedFailure() = IllegalStateException("Provider session is closing.")
}

internal class ProviderRecoveryRequired : IllegalStateException("Pending payment requires reconciliation.")
