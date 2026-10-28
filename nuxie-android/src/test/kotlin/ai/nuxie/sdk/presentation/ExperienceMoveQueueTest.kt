package ai.nuxie.sdk.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperienceMoveQueueTest {
    @Test
    fun `moves run one at a time in the order they were asked for`() = runTest {
        val queue = ExperienceMoveQueue()
        val trace = mutableListOf<String>()
        val firstLands = CompletableDeferred<Unit>()
        val first = launch { queue.run { trace += "first"; firstLands.await(); trace += "first landed" } }
        runCurrent()
        val second = launch { queue.run { trace += "second" } }
        val third = launch { queue.run { trace += "third" } }
        runCurrent()
        assertEquals(listOf("first"), trace)
        firstLands.complete(Unit)
        first.join(); second.join(); third.join()
        assertEquals(listOf("first", "first landed", "second", "third"), trace)
        assertFalse(queue.isBusy)
    }

    @Test
    fun `work the running move calls into joins it instead of waiting on itself`() = runTest {
        val queue = ExperienceMoveQueue()
        val result = queue.run { outer ->
            queue.run { inner -> assertSame(outer, inner); "joined" }
        }
        assertEquals("joined", result)
        assertNull(queue.running)
    }

    @Test
    fun `a request cancelled while it waits leaves the line`() = runTest {
        val queue = ExperienceMoveQueue()
        val lands = CompletableDeferred<Unit>()
        val trace = mutableListOf<String>()
        val first = launch { queue.run { lands.await() } }
        runCurrent()
        val cancelled = launch { queue.run { trace += "cancelled ran" } }
        val kept = launch { queue.run { trace += "kept" } }
        runCurrent()
        cancelled.cancel()
        lands.complete(Unit)
        first.join(); kept.join()
        assertEquals(listOf("kept"), trace)
        assertFalse(queue.isBusy)
    }

    @Test
    fun `a drag takes the slot only when nothing runs or waits, and holds it until released`() = runTest {
        val queue = ExperienceMoveQueue()
        val lands = CompletableDeferred<Unit>()
        val running = launch { queue.run { lands.await() } }
        runCurrent()
        assertNull("a drag is refused while a move runs", queue.tryTake())
        lands.complete(Unit)
        running.join()
        val drag = checkNotNull(queue.tryTake())
        val trace = mutableListOf<String>()
        val queued = launch { queue.run { trace += "after the drag" } }
        runCurrent()
        assertTrue(trace.isEmpty())
        drag.release()
        drag.release()
        queued.join()
        assertEquals(listOf("after the drag"), trace)
    }

    @Test
    fun `revoking completes what waits as closed and refuses later requests, leaving the running move`() = runTest {
        val queue = ExperienceMoveQueue()
        val lands = CompletableDeferred<Unit>()
        val running = launch { queue.run { lands.await() } }
        runCurrent()
        val queued = async { runCatching { queue.run { "ran" } } }
        runCurrent()
        val move = queue.revoke()
        assertSame(queue.running, move)
        runCurrent()
        assertTrue(queued.await().exceptionOrNull() is ExperienceMoveQueue.Closed)
        assertTrue(runCatching { queue.run { } }.exceptionOrNull() is ExperienceMoveQueue.Closed)
        assertNull(queue.tryTake())
        lands.complete(Unit)
        running.join()
        assertFalse(queue.isBusy)
    }

    @Test
    fun `a close waits for the running move to land unless the Activity is stopped`() = runTest {
        val queue = ExperienceMoveQueue()
        val lands = CompletableDeferred<Unit>()
        val running = launch { queue.run { it.start(); lands.await() } }
        runCurrent()
        val move = checkNotNull(queue.running)
        val waited = async { queue.awaitLanding(move) }
        runCurrent()
        assertFalse(waited.isCompleted)
        lands.complete(Unit)
        assertTrue(waited.await())
        running.join()

        val held = CompletableDeferred<Unit>()
        val second = launch { queue.run { held.await() } }
        runCurrent()
        val stoppedWait = async { queue.awaitLanding(checkNotNull(queue.running)) }
        runCurrent()
        queue.setStopped(true)
        assertFalse("a stopped Activity ends the wait", stoppedWait.await())
        held.complete(Unit)
        second.join()
    }

    @Test
    fun `a move's first change is admitted only until a close revokes the queue`() = runTest {
        val queue = ExperienceMoveQueue()
        val lands = CompletableDeferred<Unit>()
        val started = launch { queue.run { assertTrue(queue.markStarted()); lands.await() } }
        runCurrent()
        assertSame(queue.running, queue.revoke())
        assertTrue("a move that started before the close stays started", checkNotNull(queue.running).started)
        lands.complete(Unit)
        started.join()

        val closing = ExperienceMoveQueue()
        val prepared = CompletableDeferred<Unit>()
        var admitted: Boolean? = null
        val preparing = launch { closing.run { prepared.await(); admitted = closing.markStarted() } }
        runCurrent()
        closing.revoke()
        prepared.complete(Unit)
        preparing.join()
        assertEquals("a move still preparing never starts once the Experience is closing", false, admitted)
        assertTrue("work outside a move proceeds while the Experience is open", ExperienceMoveQueue().markStarted())
        assertFalse(closing.markStarted())
    }

    @Test
    fun `a close lets a started move land first and does not wait for one still preparing`() = runTest {
        val queue = ExperienceMoveQueue()
        val trace = mutableListOf<String>()
        val lands = CompletableDeferred<Unit>()
        val move = launch { queue.run { queue.markStarted(); lands.await(); trace += "landed" } }
        runCurrent()
        val close = launch { queue.closeAfterLanding(); trace += "closed" }
        runCurrent()
        assertTrue("the close waits for the started move", trace.isEmpty())
        lands.complete(Unit)
        move.join(); close.join()
        assertEquals(listOf("landed", "closed"), trace)

        val preparingQueue = ExperienceMoveQueue()
        val prepared = CompletableDeferred<Unit>()
        val preparing = launch { preparingQueue.run { prepared.await() } }
        runCurrent()
        val preparingClose = async { preparingQueue.closeAfterLanding() }
        runCurrent()
        assertTrue("a move still preparing has changed nothing, so the close does not wait", preparingClose.isCompleted)
        assertTrue(preparingQueue.isRevoked)
        prepared.complete(Unit)
        preparing.join()
    }

    @Test
    fun `a close from the started move's own work does not wait on itself`() = runTest {
        val queue = ExperienceMoveQueue()
        val result = queue.run { queue.markStarted(); queue.closeAfterLanding(); "closed from inside" }
        assertEquals("closed from inside", result)
        assertTrue(queue.isRevoked)
    }

    @Test
    fun `a close stops waiting for a wedged started move at its limit`() = runTest {
        val queue = ExperienceMoveQueue()
        val wedged = launch { queue.run { queue.markStarted(); awaitCancellation() } }
        runCurrent()
        val close = async { queue.closeAfterLanding() }
        advanceTimeBy(4_999); runCurrent()
        assertFalse("the close waits within its limit", close.isCompleted)
        advanceTimeBy(2); runCurrent()
        assertTrue("past its limit the close proceeds", close.isCompleted)
        close.await()
        assertTrue("the wedged move still holds its slot", queue.isBusy)
        wedged.cancel()
        wedged.join()
    }

    @Test
    fun `a close while the Activity is stopped ends the started move as closed`() = runTest {
        val queue = ExperienceMoveQueue()
        val heard = mutableListOf<Boolean>()
        queue.onBusyChanged { heard += it }
        val lands = CompletableDeferred<Unit>()
        var nested: Throwable? = null
        val move = launch {
            queue.run {
                assertTrue(queue.markStarted())
                withContext(NonCancellable) { lands.await() }
                nested = runCatching { queue.run { } }.exceptionOrNull()
            }
        }
        runCurrent()
        // The move can't be cancelled, so a failed check must still let it land, or the test never ends.
        try {
            val behind = async { runCatching { queue.run { } } }
            runCurrent()
            queue.setStopped(true)
            queue.closeAfterLanding()
            assertFalse("the close frees the slot without waiting for the move", queue.isBusy)
            assertNull(queue.running)
            assertTrue(behind.await().exceptionOrNull() is ExperienceMoveQueue.Closed)
        } finally { lands.complete(Unit) }
        move.join()
        assertTrue("the ended move's own work no longer joins", nested is ExperienceMoveQueue.Closed)
        assertEquals("idle is heard once", listOf(false, true, false), heard)
    }

    @Test
    fun `a close waiting for a started move ends it when the Activity stops`() = runTest {
        val queue = ExperienceMoveQueue()
        val lands = CompletableDeferred<Unit>()
        val move = launch { queue.run { queue.markStarted(); withContext(NonCancellable) { lands.await() } } }
        runCurrent()
        try {
            val close = async { queue.closeAfterLanding() }
            runCurrent()
            assertFalse("while started, the close waits", close.isCompleted)
            queue.setStopped(true)
            runCurrent()
            assertTrue("at stop the close goes ahead", close.isCompleted)
            assertFalse(queue.isBusy)
        } finally { lands.complete(Unit) }
        move.join()
    }

    @Test
    fun `ending the queue as closed frees any running move's slot`() = runTest {
        val queue = ExperienceMoveQueue()
        val drag = checkNotNull(queue.tryTake())
        val behind = async { runCatching { queue.run { } } }
        runCurrent()
        queue.endAsClosed()
        assertFalse(queue.isBusy)
        assertTrue(behind.await().exceptionOrNull() is ExperienceMoveQueue.Closed)
        drag.release()
        assertFalse("a late release changes nothing", queue.isBusy)
        assertNull("nothing is admitted afterwards", queue.tryTake())
    }

    @Test
    fun `the busy listener hears each change between idle and busy`() = runTest {
        val queue = ExperienceMoveQueue()
        val heard = mutableListOf<Boolean>()
        queue.onBusyChanged { heard += it }
        val lands = CompletableDeferred<Unit>()
        val first = launch { queue.run { lands.await() } }
        runCurrent()
        val second = launch { queue.run { } }
        runCurrent()
        lands.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf(false, true, false), heard)
    }
}
