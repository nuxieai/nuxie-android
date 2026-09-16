package ai.nuxie.example

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned transition; a cancelled UI waiter cannot abandon logout. */
internal class SessionLogout(
  private val operations: ProviderOperations,
  private val persist: suspend (LogoutJournal.Stage) -> Unit,
  private val resetSdk: () -> Unit,
  private val retireSdk: suspend () -> Unit,
  private val logoutProvider: suspend () -> Unit,
  dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
  enum class State { ACTIVE, CLOSING, FAILED, RECOVERY_REQUIRED, COMPLETE }
  private val published = MutableStateFlow(State.ACTIVE)
  val state = published.asStateFlow()
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val lock = Mutex()
  private var transition: Deferred<State>? = null
  private var stage = LogoutJournal.Stage.REQUESTED

  suspend fun logout(): State {
    currentCoroutineContext().ensureActive()
    val work = lock.withLock {
      transition?.takeIf { !it.isCompleted || published.value == State.COMPLETE } ?: run {
        operations.closeAdmission()
        scope.async(start = CoroutineStart.LAZY) { advance() }.also { transition = it }
      }
    }
    work.start()
    return work.await()
  }

  private suspend fun advance(): State {
    published.value = State.CLOSING
    try {
      // Retry first persists known in-process progress. A successful side effect
      // is never repeated just because its following persistence failed.
      persist(stage)
      if (stage == LogoutJournal.Stage.REQUESTED) {
        operations.closeAndAwait()
        stage = LogoutJournal.Stage.DRAINED
        persist(stage)
      }
      if (stage == LogoutJournal.Stage.DRAINED) {
        resetSdk()
        stage = LogoutJournal.Stage.SDK_RESET
        persist(stage)
      }
      if (stage == LogoutJournal.Stage.SDK_RESET) {
        retireSdk()
        stage = LogoutJournal.Stage.SDK_RETIRED
        persist(stage)
      }
      if (stage == LogoutJournal.Stage.SDK_RETIRED) {
        logoutProvider()
        stage = LogoutJournal.Stage.COMPLETE
        persist(stage)
      }
      published.value = State.COMPLETE
    } catch (_: ProviderRecoveryRequired) {
      published.value = State.RECOVERY_REQUIRED
    } catch (_: Exception) {
      published.value = State.FAILED
    }
    return published.value
  }
}
