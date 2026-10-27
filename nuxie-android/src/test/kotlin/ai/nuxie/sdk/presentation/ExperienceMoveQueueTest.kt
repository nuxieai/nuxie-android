package ai.nuxie.sdk.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
