package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.logging.NuxieLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * One move at a time (nuxie-handoff pages/magic-move/design-reset/sdk-android-pr1-port.md,
 * sections 1 and 2). A move is a Journey move, a Back, the close of a page, system Back or a
 * sheet the person drags away. It holds the one slot from its start until it has fully landed,
 * the shutdown of any page it removed included.
 *
 * Nothing is dropped: a request waits its turn, in order. A move never waits on itself: work the
 * running move calls into directly carries the move in its coroutine context and joins it. A close
 * of the Experience revokes the queue: what waits completes as closed at once, and the running move
 * lands first unless it is still preparing (S1c).
 */
internal class ExperienceMoveQueue {
    /** A request whose Experience closed before it could run. Not a cancellation of its caller. */
    class Closed : IllegalStateException("The Experience closed before this move ran")

    inner class Move internal constructor(
        /** The page this move closes, if any. Its own dismissal checkpoint may ask to close it again. */
        val closes: Any?,
    ) : AbstractCoroutineContextElement(Key) {
        /** Set at the move's first change on screen (S1c): from then on it lands. */
        @Volatile var started = false
            private set
        internal val released = CompletableDeferred<Unit>()

        /**
         * Admits the move's first change on screen (S1c). Refused once a close has revoked the
         * queue: either the move started first and the close waits for it, or it never starts.
         */
        fun start(): Boolean = synchronized(lock) {
            if (!revoked) started = true
            started
        }

        /** Ends a move taken with [tryTake], once. */
        fun release() = this@ExperienceMoveQueue.release(this)

        internal val queue: ExperienceMoveQueue get() = this@ExperienceMoveQueue
    }

    companion object Key : CoroutineContext.Key<Move>

    private val lock = Any()
    private var current: Move? = null
    private val waiting = ArrayDeque<Pair<Move, CompletableDeferred<Unit>>>()
    private var revoked = false
    private var busyListener: ((Boolean) -> Unit)? = null
    private val stopped = MutableStateFlow(false)

    val running: Move? get() = synchronized(lock) { current }
    val isBusy: Boolean get() = synchronized(lock) { current != null || waiting.isNotEmpty() }
    val isRevoked: Boolean get() = synchronized(lock) { revoked }

    /** Told whenever the queue becomes busy or idle, on the thread that changed it. */
    fun onBusyChanged(listener: ((Boolean) -> Unit)?) {
        busyListener = listener
        listener?.invoke(isBusy)
    }

    /** The running move, when the caller is that move's own work. */
    suspend fun joined(): Move? = coroutineContext[Key]?.takeIf { it.queue === this && it === running }

    /** Admits the caller's first change on screen; false once the Experience is closing. Work outside a move proceeds. */
    suspend fun markStarted(): Boolean = joined()?.start() ?: !isRevoked

    /** Runs [block] in the slot once every move ahead of it has landed. */
    suspend fun <T> run(closes: Any? = null, block: suspend (Move) -> T): T {
        joined()?.let { return block(it) }
        val move = Move(closes)
        val turn = CompletableDeferred<Unit>()
        val becameBusy = synchronized(lock) {
            if (revoked) throw Closed()
            val idle = current == null && waiting.isEmpty()
            if (current == null) { current = move; turn.complete(Unit) } else waiting.addLast(move to turn)
            idle
        }
        if (becameBusy) busyListener?.invoke(true)
        try {
            turn.await()
        } catch (error: Throwable) {
            // Cancelled or closed while waiting: leave the line, or give back a turn just handed over.
            val handedOver = synchronized(lock) {
                waiting.removeAll { it.first === move }
                current === move
            }
            if (handedOver) release(move)
            throw error
        }
        try {
            return withContext(move) { block(move) }
        } finally {
            withContext(NonCancellable) { release(move) }
        }
    }

    /** A sheet drag takes the slot only when nothing runs or waits. */
    fun tryTake(closes: Any? = null): Move? {
        val move = Move(closes)
        synchronized(lock) {
            if (revoked || current != null || waiting.isNotEmpty()) return null
            current = move
        }
        busyListener?.invoke(true)
        return move
    }

    private fun release(move: Move) {
        val idle = synchronized(lock) {
            if (current !== move) return
            move.released.complete(Unit)
            val next = waiting.removeFirstOrNull()
            current = next?.first
            next?.second?.complete(Unit)
            next == null
        }
        if (idle) busyListener?.invoke(false)
    }

    /**
     * A close of the Experience: nothing more is admitted and what waits completes as closed.
     * Returns the running move, which the close lets land (or ends, while the Activity is stopped).
     */
    fun revoke(): Move? {
        val (running, dropped) = synchronized(lock) {
            revoked = true
            val dropped = waiting.toList()
            waiting.clear()
            current to dropped
        }
        dropped.forEach { (_, turn) -> turn.completeExceptionally(Closed()) }
        if (running == null && dropped.isNotEmpty()) busyListener?.invoke(false)
        return running
    }

    /**
     * A close of the Experience while the Activity is stopped, or its destruction: revokes the
     * queue and ends the running move as closed. Nothing is on screen to protect, so the slot is
     * free at once. The move's leftover work no longer joins, and its own release changes nothing.
     */
    fun endAsClosed() {
        revoke()?.let(::release)
    }

    /**
     * A close of the Experience (S1c): revokes the queue, then lets a started move land within the
     * close's time limit, unless the caller is that move's own work. While the Activity is stopped
     * the move ends as closed instead. A move still preparing has changed nothing on screen, so the
     * caller cancels it as before.
     */
    suspend fun closeAfterLanding() {
        val running = revoke()?.takeIf { it.started } ?: return
        if (joined() === running) return
        try {
            if (!awaitPresentationRetirement { awaitLanding(running) }) endAsClosed()
        } catch (stalled: ExperiencePresentationException) {
            // A wedged native step must not hold the close: the close proceeds, and the step's
            // own retirement still owns its native work.
            NuxieLog.w("ExperienceMoveQueue", "A started move did not land within the close's limit", stalled)
        }
    }

    /** The Activity's started fact: while stopped nothing is on screen, so a close need not wait. */
    fun setStopped(value: Boolean) { stopped.value = value }

    /**
     * Waits for [move] to land (S1c). False when the Activity is or becomes stopped first: a close
     * then ends the move as closed instead of spending its time limit on drawing that has stopped.
     */
    suspend fun awaitLanding(move: Move): Boolean = coroutineScope {
        val stop = async { stopped.first { it } }
        try {
            select { move.released.onAwait { true }; stop.onAwait { false } }
        } finally { stop.cancel() }
    }
}
