package ai.nuxie.sdk.core

import ai.nuxie.sdk.fixtures.FixtureRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SdkLifecycleTest {
    @Test
    fun sharedShutdownVectorsDrainEveryAcceptedOperationBeforeDisposal() {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot().resolve("sdk/shutdown.json").readText()).jsonObject
        val expected = fixture.getValue("expected").jsonObject
        FixtureRunner.run("sdk/shutdown.json", "sdk/shutdown") { vector ->
            runTest {
                var disposals = 0
                val lifecycle = SdkLifecycle<String>({}, { disposals++ }, backgroundScope)
                lifecycle.install({ "graph" })
                val operations = List(vector.body.getValue("admittedOperations").jsonPrimitive.int) { lifecycle.admit()!! }
                val closing = lifecycle.requestShutdown()
                assertEquals(expected.getValue("admitAfterShutdown").jsonPrimitive.boolean, lifecycle.admit() != null)
                assertEquals(expected.getValue("setupWhileStopping").jsonPrimitive.boolean, lifecycle.install({ "overlap" }))
                operations.dropLast(1).forEach { it.finish() }
                runCurrent()
                if (operations.isNotEmpty()) {
                    assertEquals(expected.getValue("disposeBeforeDrain").jsonPrimitive.boolean, disposals != 0)
                    operations.last().finish()
                }
                runCurrent()
                closing.await()
                assertEquals(expected.getValue("teardownCount").jsonPrimitive.int, disposals)
                assertEquals(expected.getValue("setupAfterCompletion").jsonPrimitive.boolean, lifecycle.install({ "fresh" }))
                lifecycle.requestShutdown()
                runCurrent()
            }
        }
    }

    @Test
    fun admissionClosesImmediatelyAndAcceptedOperationsDrainBeforeOneTeardown() = runTest {
        val actions = mutableListOf<String>()
        val lifecycle = SdkLifecycle<String>({ actions += "prepare:$it" }, { actions += "stop:$it" }, backgroundScope)
        assertTrue(lifecycle.install({ "first" }))
        val operation = lifecycle.admit()!!
        val first = lifecycle.requestShutdown()
        assertSame(first, lifecycle.requestShutdown())
        assertNull(lifecycle.admit())
        assertNull(lifecycle.graph)
        assertFalse(lifecycle.install({ error("must not construct while stopping") }))
        runCurrent()
        assertEquals(listOf("prepare:first"), actions)
        assertFalse(first.isCompleted)
        operation.finish()
        operation.finish()
        runCurrent()
        first.await()
        assertEquals(listOf("prepare:first", "stop:first"), actions)
        assertTrue(lifecycle.install({ "second" }))
        lifecycle.requestShutdown()
        runCurrent()
        assertEquals(listOf("prepare:first", "stop:first", "prepare:second", "stop:second"), actions)
    }

    @Test
    fun cancellingAnOperationReleasesAdmissionButCancellingAWaiterDoesNotCancelTeardown() = runTest {
        val releaseStop = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val lifecycle = SdkLifecycle<String>({}, { releaseStop.await() }, backgroundScope)
        lifecycle.install({ "graph" })
        val operation = launch { lifecycle.withOperation { started.complete(Unit); awaitCancellation() } }
        runCurrent()
        started.await()
        val firstWaiter = launch { lifecycle.shutdownAndAwait() }
        runCurrent()
        firstWaiter.cancel()
        operation.cancel()
        runCurrent()
        val secondWaiter = async { lifecycle.shutdownAndAwait() }
        runCurrent()
        assertFalse(secondWaiter.isCompleted)
        releaseStop.complete(Unit)
        runCurrent()
        secondWaiter.await()
        assertTrue(lifecycle.install({ "new" }))
        lifecycle.requestShutdown()
        runCurrent()
    }

    @Test
    fun shutdownDuringConstructionNeverPublishesTheGraph() = runTest {
        val stopped = mutableListOf<String>()
        val lifecycle = SdkLifecycle<String>({}, { stopped += it }, backgroundScope)
        var shutdown: kotlinx.coroutines.Deferred<Unit>? = null
        assertFalse(lifecycle.install({
            shutdown = lifecycle.requestShutdown()
            assertNull(lifecycle.graph)
            assertFalse(lifecycle.install({ "overlap" }))
            "constructed"
        }))
        assertNull(lifecycle.graph)
        runCurrent()
        shutdown!!.await()
        assertEquals(listOf("constructed"), stopped)
    }

    @Test
    fun shutdownOnAnotherThreadDoesNotWaitForTheConstructorLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val stopped = CompletableDeferred<Unit>()
        val lifecycle = SdkLifecycle<String>({}, { stopped.complete(Unit) })
        val setup = async(Dispatchers.Default) {
            lifecycle.install({
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                "constructed"
            })
        }
        try {
            withTimeout(3_000) {
                entered.await()
                val requested = async(Dispatchers.Default) { lifecycle.requestShutdown() }
                val completion = try {
                    requested.await().also {
                        assertFalse(it.isCompleted)
                        assertNull(lifecycle.graph)
                    }
                } finally { release.countDown() }
                assertFalse(setup.await())
                completion.await()
                assertTrue(stopped.isCompleted)
            }
        } finally { release.countDown(); setup.join() }
    }

    @Test
    fun failedStartDisposesTheConstructedGraphBeforeAllowingAnotherSetup() = runTest {
        val stopped = mutableListOf<String>()
        val lifecycle = SdkLifecycle<String>({}, { stopped += it }, backgroundScope)
        assertTrue(runCatching { lifecycle.install({ "bad" }, { error("start failed") }) }.isFailure)
        val closing = lifecycle.requestShutdown()
        assertFalse(lifecycle.install({ "too-early" }))
        runCurrent()
        assertTrue(runCatching { closing.await() }.isFailure)
        assertEquals(listOf("bad"), stopped)
        assertTrue(lifecycle.install({ "good" }))
        lifecycle.requestShutdown()
        runCurrent()
    }

    @Test
    fun ownedOperationsKeepTheCallerDispatcher() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lifecycle = SdkLifecycle<String>({ scope.cancel() }, {})
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            lifecycle.install({ "graph" })
            withContext(dispatcher) {
                val callerThread = Thread.currentThread()
                lifecycle.executeOwned(lifecycle.admit()!!, { scope }) {
                    assertSame(callerThread, Thread.currentThread())
                }
            }
            lifecycle.shutdownAndAwait()
        } finally { scope.cancel(); dispatcher.close() }
    }

    @Test
    fun cancellationBeforeDispatchReleasesTheSynchronousAdmission() = runTest {
        var ran = false
        var stopped = false
        val lifecycle = SdkLifecycle<String>({}, { stopped = true }, backgroundScope)
        lifecycle.install({ "graph" })
        val scheduled = lifecycle.launchOperation({ this }) { ran = true }!!
        val shutdown = lifecycle.requestShutdown()
        scheduled.cancel()
        runCurrent()
        shutdown.await()
        assertFalse(ran)
        assertTrue(stopped)
    }

    @Test
    fun constructionFailureReleasesTheSlotAndPreparationFailureStillDisposesTheGraph() = runTest {
        val stopped = mutableListOf<String>()
        val lifecycle = SdkLifecycle<String>({ error("prepare failed") }, { stopped += it }, backgroundScope)
        assertTrue(runCatching { lifecycle.install({ error("construction failed") }) }.isFailure)
        assertTrue(lifecycle.install({ "graph" }))
        val shutdown = lifecycle.requestShutdown()
        runCurrent()
        assertTrue(runCatching { shutdown.await() }.isFailure)
        assertEquals(listOf("graph"), stopped)
        assertNull(lifecycle.graph)
    }

    @Test
    fun selfAwaitFailsWithoutClosingAdmissionButInitiationCanReturnFromAnOperation() = runTest {
        val lifecycle = SdkLifecycle<String>({}, {}, backgroundScope)
        lifecycle.install({ "graph" })
        lifecycle.withOperation {
            assertTrue(runCatching { lifecycle.shutdownAndAwait() }.exceptionOrNull() is IllegalStateException)
            assertEquals("graph", lifecycle.graph)
            lifecycle.requestShutdown()
        }
        runCurrent()
        assertNull(lifecycle.graph)
        assertTrue(lifecycle.install({ "again" }))
        lifecycle.requestShutdown()
        runCurrent()
    }
}
