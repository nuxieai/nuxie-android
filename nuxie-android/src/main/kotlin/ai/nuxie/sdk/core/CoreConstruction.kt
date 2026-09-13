package ai.nuxie.sdk.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Retains rollback ownership until construction returns a complete graph. */
internal class CoreConstruction {
    var scope: CoroutineScope? = null
    private val disposals = mutableListOf<suspend () -> Unit>()

    fun onFailure(dispose: suspend () -> Unit) { disposals += dispose }

    fun <T> build(beforeRollback: () -> Unit = {}, create: (CoreConstruction) -> T): T = try {
        create(this)
    } catch (failure: Throwable) {
        // The lifecycle remains Starting until this rollback finishes, so another
        // setup cannot publish a graph while failed-constructor workers are live.
        runBlocking {
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (next: Throwable) {
                    if (next !== failure) failure.addSuppressed(next)
                }
            }
            attempt { beforeRollback() }
            attempt { scope?.cancel(); scope?.coroutineContext?.get(Job)?.join() }
            disposals.asReversed().forEach { attempt(it) }
        }
        throw failure
    } finally {
        scope = null
        disposals.clear()
    }
}
