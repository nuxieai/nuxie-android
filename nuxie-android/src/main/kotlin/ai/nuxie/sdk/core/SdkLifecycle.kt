package ai.nuxie.sdk.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns graph publication, operation admission and exactly-once asynchronous teardown. */
internal class SdkLifecycle<G : Any>(
    private val prepareStop: suspend (G) -> Unit,
    private val stop: suspend (G) -> Unit,
    private val shutdownScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private sealed interface State<out G>
    private data object Idle : State<Nothing>
    private class Starting<G> : State<G> {
        var stopRequested = false
        val completion = CompletableDeferred<Unit>()
    }
    private class Running<G>(val graph: G) : State<G> {
        var operations = 0
        val drained = CompletableDeferred<Unit>()
    }
    private class Stopping<G>(
        val running: Running<G>,
        val completion: CompletableDeferred<Unit>,
    ) : State<G>

    class Operation<G> internal constructor(val graph: G, private val release: () -> Unit) {
        private val finished = AtomicBoolean(false)
        val isActive: Boolean get() = !finished.get()
        fun finish() { if (finished.compareAndSet(false, true)) release() }
    }

    private class OperationContext(
        val owner: Any,
        val operation: Operation<*>,
        val previous: OperationContext?,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OperationContext>
        fun contains(owner: Any): Boolean =
            (this.owner === owner && operation.isActive) || previous?.contains(owner) == true
    }

    private val lock = Any()
    private var state: State<G> = Idle

    val graph: G? get() = synchronized(lock) { (state as? Running<G>)?.graph }

    /** Build outside the lock; a shutdown during construction prevents publication. */
    fun install(create: () -> G, start: (G) -> Unit = {}): Boolean {
        val starting = synchronized(lock) {
            if (state !== Idle) return false
            Starting<G>().also { state = it }
        }
        var constructed: G? = null
        try {
            val graph = create().also { constructed = it }
            start(graph)
            val closing = synchronized(lock) {
                val running = Running(graph)
                if (starting.stopRequested) {
                    beginStop(running, starting.completion)
                } else {
                    state = running
                    starting.completion.complete(Unit)
                    null
                }
            }
            if (closing != null) launchStop(closing)
            return closing == null
        } catch (failure: Throwable) {
            val graph = constructed
            val closing = synchronized(lock) {
                if (graph == null) {
                    state = Idle
                    starting.completion.completeExceptionally(failure)
                    null
                } else beginStop(Running(graph), starting.completion)
            }
            if (closing != null) launchStop(closing, failure)
            throw failure
        }
    }

    fun admit(): Operation<G>? = synchronized(lock) {
        val running = state as? Running<G> ?: return null
        running.operations++
        Operation(running.graph) {
            synchronized(lock) {
                running.operations--
                if ((state as? Stopping<G>)?.running === running && running.operations == 0) {
                    running.drained.complete(Unit)
                }
            }
        }
    }

    suspend fun <T> withOperation(block: suspend (G) -> T): T {
        val operation = admit() ?: throw IllegalStateException("Call Nuxie.setup first; shutdown closes operation admission.")
        return try {
            withContext(OperationContext(this, operation, currentCoroutineContext()[OperationContext])) {
                block(operation.graph)
            }
        } finally { operation.finish() }
    }

    /** Admission precedes scheduling; completion releases it even if the body never starts. */
    fun launchOperation(scopeForGraph: (G) -> CoroutineScope, block: suspend (G) -> Unit): Job? {
        val operation = admit() ?: return null
        return try {
            scopeForGraph(operation.graph).launch(OperationContext(this, operation, null)) {
                block(operation.graph)
            }.also { job -> job.invokeOnCompletion { operation.finish() } }
        } catch (failure: Throwable) {
            operation.finish()
            throw failure
        }
    }

    /** Returns immediately with the shared completion, including while construction is in progress. */
    fun requestShutdown(): Deferred<Unit> {
        var owner: Stopping<G>? = null
        val completion = synchronized(lock) {
            when (val current = state) {
                Idle -> CompletableDeferred(Unit)
                is Starting -> {
                    current.stopRequested = true
                    current.completion
                }
                is Running -> beginStop(current, CompletableDeferred()).also { owner = it }.completion
                is Stopping -> current.completion
            }
        }
        owner?.let(::launchStop)
        return completion
    }

    suspend fun shutdownAndAwait() {
        check(currentCoroutineContext()[OperationContext]?.contains(this) != true) {
            "An admitted operation cannot await its own shutdown. Initiate shutdown and await it independently."
        }
        requestShutdown().await()
    }

    /** Called under lock, before teardown is dispatched. */
    private fun beginStop(running: Running<G>, completion: CompletableDeferred<Unit>): Stopping<G> {
        val closing = Stopping(running, completion)
        state = closing
        if (running.operations == 0) running.drained.complete(Unit)
        return closing
    }

    private fun launchStop(closing: Stopping<G>, initialFailure: Throwable? = null) {
        shutdownScope.launch {
            var failure = initialFailure
            suspend fun attempt(block: suspend () -> Unit) {
                try { block() } catch (next: Throwable) {
                    val first = failure
                    if (first == null) failure = next else if (first !== next) first.addSuppressed(next)
                }
            }
            attempt { prepareStop(closing.running.graph) }
            closing.running.drained.await()
            attempt { stop(closing.running.graph) }
            synchronized(lock) {
                check(state === closing)
                state = Idle
                val error = failure
                if (error == null) closing.completion.complete(Unit)
                else closing.completion.completeExceptionally(error)
            }
        }
    }
}
