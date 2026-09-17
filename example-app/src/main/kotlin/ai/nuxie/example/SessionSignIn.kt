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

/** One explicit session admission, owned by the application rather than an Activity. */
internal class SessionSignIn(
  val session: String,
  private val begin: suspend () -> Unit,
  private val authenticate: suspend () -> Unit,
  private val complete: suspend () -> Unit,
  dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
  enum class State { READY, RUNNING, FAILED, COMPLETE }
  private val published = MutableStateFlow(State.READY)
  val state = published.asStateFlow()
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val lock = Mutex()
  private var transition: Deferred<State>? = null
  private var authenticated = false
  private var intentRecorded = false

  suspend fun signIn(): State {
    currentCoroutineContext().ensureActive()
    val work = lock.withLock {
      transition?.takeIf { !it.isCompleted || published.value == State.COMPLETE } ?: run {
        scope.async(start = CoroutineStart.LAZY) {
          published.value = State.RUNNING
          try {
            if (!intentRecorded) {
              begin()
              intentRecorded = true
            }
            if (!authenticated) {
              authenticate()
              authenticated = true
            }
            complete()
            published.value = State.COMPLETE
          } catch (_: Exception) {
            published.value = State.FAILED
          }
          published.value
        }.also { transition = it }
      }
    }
    work.start()
    return work.await()
  }
}
