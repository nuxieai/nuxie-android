package ai.nuxie.example

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSignInTest {
  @Test fun cancelledActivityDoesNotAbandonLoginAndConcurrentCallersJoin() = runTest {
    val callback = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()
    val owner = SessionSignIn("session", { calls += "intent" }, {
      calls += "login"
      callback.await()
    }, { calls += "complete" }, StandardTestDispatcher(testScheduler))
    val first = async { owner.signIn() }
    testScheduler.runCurrent()
    first.cancelAndJoin()
    val recreated = async { owner.signIn() }
    testScheduler.runCurrent()
    assertEquals(listOf("intent", "login"), calls)
    assertEquals(SessionSignIn.State.RUNNING, owner.state.value)
    callback.complete(Unit)
    assertEquals(SessionSignIn.State.COMPLETE, recreated.await())
    assertEquals(SessionSignIn.State.COMPLETE, owner.signIn())
    assertEquals(listOf("intent", "login", "complete"), calls)
  }

  @Test fun failedIntentNeverDispatchesProviderAndFailedCompletionNeverRepeatsSuccessfulLogin() = runTest {
    var intentFails = true
    var completionFails = true
    var logins = 0
    val owner = SessionSignIn("session", { check(!intentFails) }, { logins++ },
      { check(!completionFails) }, StandardTestDispatcher(testScheduler))
    assertEquals(SessionSignIn.State.FAILED, owner.signIn())
    assertEquals(0, logins)
    intentFails = false
    assertEquals(SessionSignIn.State.FAILED, owner.signIn())
    assertEquals(1, logins)
    completionFails = false
    assertEquals(SessionSignIn.State.COMPLETE, owner.signIn())
    assertEquals(1, logins)
  }

  @Test fun providerFailureKeepsAdmissionClosedAndExplicitRetryReachesCompletion() = runTest {
    var fails = true
    var completions = 0
    val owner = SessionSignIn("session", {}, { check(!fails) }, { completions++ },
      StandardTestDispatcher(testScheduler))
    assertEquals(SessionSignIn.State.FAILED, owner.signIn())
    assertEquals(0, completions)
    fails = false
    assertEquals(SessionSignIn.State.COMPLETE, owner.signIn())
    assertEquals(1, completions)
  }
}
