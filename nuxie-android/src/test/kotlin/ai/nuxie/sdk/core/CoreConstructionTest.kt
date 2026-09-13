package ai.nuxie.sdk.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreConstructionTest {
    @Test
    fun failedConstructionJoinsWorkersBeforeDisposalAndRetainsCleanupFailures() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaningUp = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = CopyOnWriteArrayList<String>()
        val original = IllegalStateException("construction failed")
        val disposalFailure = IllegalStateException("disposal failed")
        withTimeout(5_000) {
            val result = async(Dispatchers.Default) {
                runCatching {
                    CoreConstruction().build(beforeRollback = { order += "withdraw" }) { construction ->
                        construction.scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        construction.onFailure { order += "storage" }
                        construction.onFailure { order += "surface"; throw disposalFailure }
                        construction.scope!!.launch {
                            entered.complete(Unit)
                            try { awaitCancellation() } finally {
                                withContext(NonCancellable) {
                                    cleaningUp.complete(Unit)
                                    release.await()
                                    order += "worker"
                                }
                            }
                        }
                        runBlocking { entered.await() }
                        throw original
                    }
                }
            }
            try {
                cleaningUp.await()
                assertEquals(listOf("withdraw"), order.toList())
                assertFalse(result.isCompleted)
                release.complete(Unit)
                val failure = result.await().exceptionOrNull()
                assertTrue(failure === original)
                assertEquals(listOf(disposalFailure), original.suppressed.toList())
                assertEquals(listOf("withdraw", "worker", "surface", "storage"), order.toList())
            } finally { release.complete(Unit) }
        }
    }
}
