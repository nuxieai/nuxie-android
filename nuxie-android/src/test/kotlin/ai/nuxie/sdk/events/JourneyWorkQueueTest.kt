package ai.nuxie.sdk.events

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyWorkQueueTest {
    @Test fun failedReconciliationDoesNotObstructAdmissionChecksOrCompletedRouteCleanup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = AtomicLong(0L)
        val retry = CompletableDeferred<Unit>()
        val tick = CompletableDeferred<Unit>()
        val queue = JourneyWorkQueue(scope, { tick.complete(Unit) }, { retry.await() }, clock::get)
        val observed = mutableListOf<String>()
        var recovered = false
        try {
            queue.add(JourneyWork("reconcile:customer", "customer", repairsCustomer = true) {
                observed += "reconcile"
                if (!recovered) JourneyWork.Result.Retry else JourneyWork.Result.Done()
            })
            queue.drain()
            queue.add(JourneyWork("event:start", "customer", kind = JourneyWork.Kind.EVENT) {
                observed += "admission"
                if (!recovered) JourneyWork.Result.Retry else JourneyWork.Result.Done()
            })
            queue.drain()
            assertEquals(listOf("reconcile", "admission"), observed)

            // A profile publication can recover the journal before the repair's retry tick.
            recovered = true
            queue.add(JourneyWork("event:report", "customer", kind = JourneyWork.Kind.EVENT) {
                observed += "report acknowledged"
                JourneyWork.Result.Done()
            })
            clock.set(1_000L); retry.complete(Unit)
            withTimeout(1_000L) { tick.await() }; queue.retryReady(); queue.drain()
            assertEquals(listOf("reconcile", "admission", "reconcile", "admission", "report acknowledged"), observed)

            // A still-deferred independent repair cannot hold an already deliverable route.
            var deferredAttempts = 0
            queue.add(JourneyWork("reconcile:deferred", "customer", repairsCustomer = true, initiallyDeferred = true) {
                deferredAttempts++
                JourneyWork.Result.Done()
            })
            queue.add(JourneyWork("event:completed", "customer", kind = JourneyWork.Kind.EVENT) {
                observed += "completed acknowledged"
                JourneyWork.Result.Done()
            })
            queue.drain()
            assertEquals("completed acknowledged", observed.last())
            assertEquals("The repair must retain its backoff", 0, deferredAttempts)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun failedWorkKeepsItsPositionAndBackoffWhileUnrelatedRunsAndCustomersProgress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = AtomicLong(0L)
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val sleeps = CopyOnWriteArrayList<Pair<Long, CompletableDeferred<Unit>>>()
        val queue = JourneyWorkQueue(scope, { ticks.trySend(Unit) }, { duration ->
            val done = CompletableDeferred<Unit>()
            sleeps += duration to done
            done.await()
        }, clock::get)
        val observed = mutableListOf<String>()
        var attempts = 0
        var available = false
        fun presentation() = JourneyWork("presentation:a", "customer", "a") {
            attempts++
            // Re-parking changes the real-clock checkpoint, never this key or retry eligibility.
            if (!available) JourneyWork.Result.Retry else { observed += "presented"; JourneyWork.Result.Done() }
        }
        try {
            queue.add(presentation())
            queue.drain()
            queue.add(JourneyWork("input:a", "customer", "a") { observed += "input:a"; JourneyWork.Result.Done() })
            queue.add(JourneyWork("input:b", "customer", "b") { observed += "input:b"; JourneyWork.Result.Done() })
            queue.add(JourneyWork("input:other", "other", "a") { observed += "input:other"; JourneyWork.Result.Done() })
            repeat(5) { clock.incrementAndGet(); queue.add(presentation()); queue.drain() }
            assertEquals(1, attempts)
            assertEquals(listOf("input:b", "input:other"), observed)
            assertEquals(listOf(1_000L), sleeps.map { it.first })
            clock.set(1_000L); sleeps.last().second.complete(Unit)
            withTimeout(1_000L) { ticks.receive() }; queue.retryReady(); queue.drain()
            assertEquals(2, attempts)
            repeat(5) { clock.incrementAndGet(); queue.add(presentation()); queue.drain() }
            assertEquals(2, attempts)
            assertEquals(2, sleeps.size)
            available = true
            clock.set(2_000L); sleeps.last().second.complete(Unit)
            withTimeout(1_000L) { ticks.receive() }; queue.retryReady(); queue.drain()
            assertEquals(3, attempts)
            assertEquals(listOf("input:b", "input:other", "presented", "input:a"), observed)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun backgroundEligibilityPauseKeepsBackgroundInputSelectionAndArmsNoRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var sleeps = 0
        val queue = JourneyWorkQueue(scope, {}, { sleeps++ })
        val observed = mutableListOf<String>()
        var foreground = false
        var matchingInput = false
        try {
            queue.add(JourneyWork("deadline", "customer", "run") {
                if (!foreground) JourneyWork.Result.Blocked else {
                    observed += if (matchingInput) "satisfied" else "timed_out"
                    JourneyWork.Result.Done()
                }
            })
            queue.add(JourneyWork("input", "customer", "run") {
                matchingInput = true; observed += "input"; JourneyWork.Result.Done()
            })
            queue.drain()
            assertEquals(listOf("input"), observed)
            assertEquals(0, sleeps)
            foreground = true
            queue.drain()
            assertEquals(listOf("input", "satisfied"), observed)
            assertEquals(0, sleeps)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun allFailureKindsShareOneTimerAndRetryWithoutAnAttemptLimit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = AtomicLong(0L)
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        val sleeps = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        val queue = JourneyWorkQueue(scope, { ticks.trySend(Unit) }, {
            assertTrue(it in 1L..1_000L)
            val done = CompletableDeferred<Unit>(); sleeps += done; done.await()
        }, clock::get)
        val attempts = mutableMapOf<String, Int>()
        val kinds = listOf("checkpoint", "native", "presentation", "saved-input", "reconciliation", "deadline")
        try {
            for (kind in kinds) queue.add(JourneyWork(kind, "customer", kind) {
                attempts[kind] = (attempts[kind] ?: 0) + 1
                if (clock.get() < 5_000L) JourneyWork.Result.Retry else JourneyWork.Result.Done()
            })
            queue.drain()
            assertEquals(kinds.size, attempts.size)
            repeat(5) { tick ->
                assertEquals(tick + 1, sleeps.size)
                assertTrue(attempts.values.all { it == tick + 1 })
                clock.addAndGet(1_000L); sleeps.last().complete(Unit)
                withTimeout(1_000L) { ticks.receive() }; queue.retryReady(); queue.drain()
            }
            assertTrue(attempts.values.all { it == 6 })
            assertEquals(5, sleeps.size)
        } finally { queue.close(); scope.cancel() }
    }
}
