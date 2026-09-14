package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.experiences.AcquiredJourneyRelease
import ai.nuxie.sdk.experiences.AuthenticatedJourneyRelease
import ai.nuxie.sdk.experiences.JourneyReleaseIdentity
import ai.nuxie.sdk.experiences.JourneyReleaseReplayPolicy
import ai.nuxie.sdk.experiences.JourneyReleaseSupportedRuntime
import ai.nuxie.sdk.experiences.JourneyReleaseVerifier
import ai.nuxie.sdk.fixtures.FixtureRunner
import android.util.Base64
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExperiencePresentationServiceTest {
    private data class Emitted(
        val name: String,
        val properties: Map<String, Any?>,
        val distinctId: String?,
    )

    private class Lease : Closeable {
        val closed = AtomicBoolean(false)
        val closeCount = java.util.concurrent.atomic.AtomicInteger()

        override fun close() {
            closeCount.incrementAndGet()
            closed.set(true)
        }
    }

    private class AttachedHost : PresentationScreenHandle {
        var requestedReason: CloseReason? = null
        var finished = false

        override fun requestCloseFromService(reason: CloseReason): Boolean {
            requestedReason = reason
            return true
        }

        override fun screenCloseReason(): CloseReason? = requestedReason

        override fun finishAfterServiceClose() {
            finished = true
        }
    }

    @After
    fun tearDown() {
        PresentationRegistry.clearForTesting()
    }

    @Test
    fun `authored exit wait is outside first frame timeout and precedes dismissal checkpoint`() = runTest {
        val release = renderedJourneyRelease("text-input-navigation.json")
        val launched = mutableListOf<String>()
        val service = service(this, launch = launched::add)
        var checkpoints = 0
        val first = async {
            service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                service.reserveJourney("customer-1"), acquire = { acquired(release.identity, Lease()) },
                onScreenDismissed = { _, _, _ -> checkpoints++; JourneyScreenDismissalResult.HANDLED }, onOutcome = {})
        }
        runCurrent()
        val sourceId = launched.single()
        PresentationRegistry.reportFirstFrame(sourceId)
        first.await()
        val exit = CompletableDeferred<Unit>()
        var activated = false
        val host = object : PresentationScreenHandle {
            var reason: CloseReason? = null
            override fun requestCloseFromService(reason: CloseReason): Boolean { this.reason = reason; return true }
            override fun screenCloseReason() = reason
            override fun finishAfterServiceClose() {
                if (reason != CloseReason.JourneyNavigation) PresentationRegistry.detach(sourceId, this)
            }
            override suspend fun prepareNavigation(id: String, content: PreparedPresentation): PreparedScreenNavigation {
                val source = this
                return object : PreparedScreenNavigation {
                    override suspend fun awaitExit() { exit.await() }
                    override fun activate() {
                        activated = true
                        PresentationRegistry.detach(sourceId, source)
                        PresentationRegistry.reportFirstFrame(id)
                    }
                    override suspend fun abort() = Unit
                }
            }
        }
        PresentationRegistry.attach(sourceId, host)
        val next = async {
            service.presentJourney(release, "screen_details", "journey-1", "customer-1", null,
                acquire = { acquired(release.identity, Lease()) }, onOutcome = {})
        }
        try {
            runCurrent()
            advanceTimeBy(35_000)
            runCurrent()
            assertFalse(next.isCompleted)
            assertFalse(activated)
            assertEquals(0, checkpoints)
            exit.complete(Unit)
            next.await()
            assertTrue(activated)
            assertEquals(1, checkpoints)
        } finally {
            exit.complete(Unit)
            next.cancelAndJoin()
            service.dismissFromHost("customer-1")
        }
    }

    @Test
    fun `authored transition survives native presentation preparation`() = runTest {
        val contract = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/text-input-navigation.json").readText(),
        ).jsonObject
        val release = renderedJourneyRelease("text-input-navigation.json", "transitionEntry")
        val launched = mutableListOf<String>()
        val service = service(this, launch = launched::add)
        val presentation = async {
            service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                service.reserveJourney("customer-1"), acquire = { acquired(release.identity, Lease()) },
                transition = contract.getValue("transition").jsonObject, onOutcome = {})
        }
        runCurrent()
        assertEquals(contract.getValue("transition"), PresentationRegistry.resolve(launched.single())?.transition)
        PresentationRegistry.reportFirstFrame(launched.single())
        presentation.await()
        service.dismissFromHost("customer-1")
    }

    @Test
    fun `navigation acquisition obeys outgoing ownership contract`() = runTest {
        val fixture = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/navigation-acquisition-android.json").readText(),
        ).jsonObject
        for (case in fixture.getValue("cases").jsonArray.map { it.jsonObject }) {
            val action = case.getValue("action").jsonPrimitive.content
            val release = renderedJourneyRelease("text-input-navigation.json")
            val launched = mutableListOf<String>()
            val service = service(this, launch = launched::add)
            val outgoingLease = Lease()
            val destinationLease = Lease()
            var dismissalReports = 0
            var allowed = true
            val first = async {
                service.presentJourney(
                    release, "screen_welcome", "journey-1", "customer-1",
                    service.reserveJourney("customer-1"),
                    acquire = { acquired(release.identity, outgoingLease) },
                    onScreenDismissed = { _, _, _ ->
                        dismissalReports++
                        JourneyScreenDismissalResult.HANDLED
                    }, onOutcome = {},
                )
            }
            runCurrent()
            val outgoingId = launched.single()
            PresentationRegistry.reportFirstFrame(outgoingId)
            first.await()
            val acquisition = CompletableDeferred<AcquiredJourneyRelease>()
            val next = async {
                runCatching {
                    service.presentJourney(
                        release, "screen_details", "journey-1", "customer-1", null,
                        canPresent = { allowed }, acquire = {
                            if (action in listOf("hostClose", "identityShutdown")) withContext(NonCancellable) { acquisition.await() }
                            else acquisition.await()
                        }, onOutcome = {},
                    )
                }
            }
            try {
                runCurrent()
                assertNotNull(action, PresentationRegistry.resolve(outgoingId))
                assertFalse(action, outgoingLease.closed.get())
                assertEquals(action, 0, dismissalReports)
                var close: kotlinx.coroutines.Deferred<Unit>? = null
                when (action) {
                    "failure" -> acquisition.completeExceptionally(java.io.IOException("offline"))
                    "cancel" -> next.cancelAndJoin()
                    "withdraw" -> allowed = false
                    "hostClose" -> close = async { service.dismissFromHost("customer-1") }
                    "identityShutdown" -> close = async { service.shutdownOwnedBy("customer-1") }
                    else -> error("Unknown acquisition action: $action")
                }
                close?.let {
                    runCurrent()
                    assertEquals(action, case.getValue("closeWaitsForAcquisition").jsonPrimitive.boolean, !it.isCompleted)
                }
                if (action !in listOf("failure", "cancel")) {
                    acquisition.complete(acquired(release.identity, destinationLease))
                }
                runCurrent()
                close?.await()
                if (action != "cancel") assertTrue(action, next.await().isFailure)
                val retained = case.getValue("outgoingRetained").jsonPrimitive.boolean
                assertEquals(action, retained, PresentationRegistry.resolve(outgoingId) != null)
                assertEquals(action, !retained, outgoingLease.closed.get())
                assertEquals(action, case.getValue("destinationReleased").jsonPrimitive.boolean,
                    destinationLease.closed.get())
                assertEquals(action, 1, launched.size)
                if (retained) assertEquals(action, 0, dismissalReports)
                if (action in listOf("failure", "cancel")) {
                    val retry = async {
                        service.presentJourney(
                            release, "screen_details", "journey-1", "customer-1", null,
                            acquire = { acquired(release.identity, destinationLease) }, onOutcome = {},
                        )
                    }
                    runCurrent()
                    assertEquals(action, 2, launched.size)
                    PresentationRegistry.reportFirstFrame(launched.last())
                    retry.await()
                    assertTrue(action, outgoingLease.closed.get())
                    assertFalse(action, destinationLease.closed.get())
                    assertEquals(action, 1, dismissalReports)
                }
            } finally {
                next.cancelAndJoin()
                service.dismissFromHost("customer-1")
            }
        }
    }

    @Test
    fun `cancelled native preparation drains before releasing the destination lease`() = runTest {
        val release = renderedJourneyRelease("text-input-navigation.json")
        val launched = mutableListOf<String>()
        val service = service(this, launch = launched::add)
        val outgoingLease = Lease()
        val destinationLease = Lease()
        var checkpoints = 0
        val first = async {
            service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                service.reserveJourney("customer-1"), acquire = { acquired(release.identity, outgoingLease) },
                onScreenDismissed = { _, _, _ -> checkpoints++; JourneyScreenDismissalResult.HANDLED }, onOutcome = {})
        }
        runCurrent()
        val outgoingId = launched.single()
        PresentationRegistry.reportFirstFrame(outgoingId)
        first.await()
        val preparationStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val host = object : PresentationScreenHandle {
            var reason: CloseReason? = null
            override fun requestCloseFromService(reason: CloseReason): Boolean {
                this.reason = reason
                return true
            }
            override fun screenCloseReason(): CloseReason? = reason
            override fun finishAfterServiceClose() = PresentationRegistry.detach(outgoingId, this)
            override suspend fun prepareNavigation(id: String, content: PreparedPresentation): PreparedScreenNavigation? {
                preparationStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        cleanupFinished.await()
                    }
                }
            }
        }
        assertTrue(PresentationRegistry.attach(outgoingId, host))
        val next = async {
            service.presentJourney(release, "screen_details", "journey-1", "customer-1", null,
                acquire = { acquired(release.identity, destinationLease) }, onOutcome = {})
        }
        try {
            preparationStarted.await()
            next.cancel()
            cleanupStarted.await()
            assertFalse("Lease must outlive native cleanup", destinationLease.closed.get())
            assertFalse(outgoingLease.closed.get())
            assertNotNull(PresentationRegistry.resolve(outgoingId))
            assertEquals(0, checkpoints)
            cleanupFinished.complete(Unit)
            next.join()
            assertTrue(destinationLease.closed.get())
            assertFalse(outgoingLease.closed.get())
            assertEquals(1, launched.size)
        } finally {
            cleanupFinished.complete(Unit)
            next.cancelAndJoin()
            service.dismissFromHost("customer-1")
        }
    }

    @Test
    fun `navigation retry waits for the original dismissal checkpoint after caller cancellation`() = runTest {
        val contract = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/persistent-navigation-android.json").readText(),
        ).jsonObject.getValue("checkpointRetry").jsonObject
        val release = renderedJourneyRelease("text-input-navigation.json")
        val launched = mutableListOf<String>()
        val service = service(this, launch = launched::add)
        val firstLease = Lease()
        val checkpoint = CompletableDeferred<JourneyScreenDismissalResult>()
        val started = CompletableDeferred<Unit>()
        var checkpointCalls = 0
        val first = async {
            service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                service.reserveJourney("customer-1"), acquire = { acquired(release.identity, firstLease) },
                onScreenDismissed = { _, _, _ ->
                    checkpointCalls++
                    started.complete(Unit)
                    checkpoint.await()
                }, onOutcome = {})
        }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        first.await()
        suspend fun navigate() = runCatching {
            service.presentJourney(release, contract.getValue("destination").jsonPrimitive.content, "journey-1", "customer-1", null,
                acquire = { acquired(release.identity, Lease()) }, onOutcome = {})
        }
        val cancelled = async { navigate() }
        started.await()
        cancelled.cancelAndJoin()
        val conflict = expectPresentationFailure {
            service.presentJourney(release, contract.getValue("conflictingDestination").jsonPrimitive.content,
                "journey-1", "customer-1", null, acquire = { acquired(release.identity, Lease()) }, onOutcome = {})
        }
        assertEquals(contract.getValue("conflictReason").jsonPrimitive.content, conflict.reason.name)
        val retry = async { navigate() }
        try {
            runCurrent()
            assertEquals("An unsettled checkpoint cannot authorize a destination", contract.getValue("launchCountBeforeResult").jsonPrimitive.int, launched.size)
            assertFalse(firstLease.closed.get())
            assertEquals("A retry must join, not repeat, the checkpoint", contract.getValue("checkpointCalls").jsonPrimitive.int, checkpointCalls)
            checkpoint.complete(JourneyScreenDismissalResult.valueOf(contract.getValue("result").jsonPrimitive.content))
            runCurrent()
            assertTrue(retry.await().isFailure)
            assertEquals(contract.getValue("launchCountAfterResult").jsonPrimitive.int, launched.size)
            assertTrue(firstLease.closed.get())
        } finally {
            checkpoint.complete(JourneyScreenDismissalResult.valueOf(contract.getValue("result").jsonPrimitive.content))
            retry.cancelAndJoin()
            service.dismissFromHost("customer-1")
        }
    }

    @Test
    fun `terminal teardown waits for an admitted navigation checkpoint after waiter cancellation`() = runTest {
        val contract = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/persistent-navigation-android.json").readText(),
        ).jsonObject.getValue("terminalCheckpoint").jsonObject
        for (item in contract.getValue("cases").jsonArray.map { it.jsonObject }) {
            val identityChange = item.getValue("identityChange").jsonPrimitive.boolean
            val cancelWaiter = item.getValue("cancelWaiter").jsonPrimitive.boolean
            val release = renderedJourneyRelease("text-input-navigation.json")
            val launched = mutableListOf<String>()
            val service = service(this, launch = launched::add)
            val checkpoint = CompletableDeferred<JourneyScreenDismissalResult>()
            val started = CompletableDeferred<Unit>()
            val outcomes = mutableListOf<JourneySurfaceOutcome>()
            val outcomeEntered = CompletableDeferred<Unit>()
            val releaseOutcome = CompletableDeferred<Unit>()
            var calls = 0
            val first = async {
                service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                    service.reserveJourney("customer-1"), acquire = { acquired(release.identity, Lease()) },
                    onScreenDismissed = { _, _, _ ->
                        calls++
                        started.complete(Unit)
                        checkpoint.await()
                    }, onOutcome = {
                        outcomeEntered.complete(Unit)
                        releaseOutcome.await()
                        outcomes.add(it)
                    })
            }
            runCurrent()
            PresentationRegistry.reportFirstFrame(launched.single())
            first.await()
            val navigation = async {
                runCatching {
                    service.presentJourney(release, "screen_details", "journey-1", "customer-1", null,
                        acquire = { acquired(release.identity, Lease()) }, onOutcome = {})
                }
            }
            started.await()
            if (cancelWaiter) navigation.cancelAndJoin()
            val teardown = async {
                if (identityChange) service.shutdownOwnedBy("customer-1")
                else service.dismissFromHost("customer-1")
            }
            try {
                runCurrent()
                assertFalse("Teardown must drain the admitted checkpoint (identity=$identityChange)", teardown.isCompleted)
                assertTrue("Terminal outcomes must not overtake an admitted checkpoint", outcomes.isEmpty())
                checkpoint.complete(JourneyScreenDismissalResult.HANDLED)
                outcomeEntered.await()
                runCurrent()
                assertFalse("Teardown must also drain its terminal outcome", teardown.isCompleted)
                releaseOutcome.complete(Unit)
                teardown.await()
                assertEquals(contract.getValue("checkpointCalls").jsonPrimitive.int, calls)
                assertEquals(listOf(if (identityChange) JourneySurfaceOutcome.ABANDONED else JourneySurfaceOutcome.DISMISSED), outcomes)
                assertEquals(contract.getValue("activityLaunches").jsonPrimitive.int, launched.size)
            } finally {
                checkpoint.complete(JourneyScreenDismissalResult.HANDLED)
                releaseOutcome.complete(Unit)
                teardown.await()
                navigation.cancelAndJoin()
            }
        }
    }

    @Test
    fun `text commits require the current attached Activity and an open presentation`() {
        val received = mutableListOf<String>()
        PresentationRegistry.register("edit-owner",
            PreparedPresentation(File("unused.riv"), null, 0, PresentationShell.FullScreen),
            {}, {}, {}, {}, onTextCommitted = { _, text -> received += text })
        val old = AttachedHost()
        val current = AttachedHost()
        assertTrue(PresentationRegistry.attach("edit-owner", old))
        PresentationRegistry.reportTextCommitted("edit-owner", old, "name", "first")
        assertTrue(PresentationRegistry.attach("edit-owner", current))
        PresentationRegistry.reportTextCommitted("edit-owner", old, "name", "stale")
        PresentationRegistry.reportTextCommitted("edit-owner", current, "name", "current")
        PresentationRegistry.dismiss("edit-owner", CloseReason.UserDismissed)
        PresentationRegistry.reportTextCommitted("edit-owner", current, "name", "closed")
        assertEquals(listOf("first", "current"), received)
    }

    @Test
    fun `closing unseen content terminates once without authored dismissal or presentation facts`() = runTest {
        val vector = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/presentation-reveal-android.json").readText()).jsonObject
            .getValue("unseenClose").jsonObject
        for (phase in vector.getValue("phases").jsonArray.map { it.jsonPrimitive.content }) {
            val release = renderedJourneyRelease()
            val launched = mutableListOf<String>()
            val events = mutableListOf<String>()
            val outcomes = mutableListOf<JourneySurfaceOutcome>()
            val releaseWork = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val lease = Lease()
            var checkpoints = 0
            val service = service(this, launch = launched::add, emit = { name, _, _ -> events += name })
            val pending = async {
                runCatching {
                    service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                        service.reserveJourney("customer-1"), acquire = { acquired(release.identity, lease) },
                        onPresentationRevealed = {
                            if (phase == "admission") { entered.complete(Unit); releaseWork.await() }
                        }, onScreenDismissed = { _, _, _ -> checkpoints++; JourneyScreenDismissalResult.HANDLED },
                        onOutcome = outcomes::add)
                }
            }
            runCurrent()
            val id = launched.single()
            val host = AttachedHost()
            PresentationRegistry.attach(id, host)
            try {
                if (phase != "beforeFrame") {
                    PresentationRegistry.reportFirstFrame(id, host) {
                        if (phase == "hostAcknowledgment") { entered.complete(Unit); releaseWork.await() }
                        // A visibility acknowledgment already queued before close can
                        // arrive late. Terminal fact selection must still win.
                        phase == "hostAcknowledgment" || PresentationRegistry.canReveal(id, host)
                    }
                    entered.await()
                }
                val closing = async {
                    service.dismiss(CloseReason.UserDismissed)
                    service.dismissFromHost("customer-1") // Observe the already-selected user close draining.
                }
                runCurrent()
                assertTrue(phase, host.finished)
                assertFalse(phase, closing.isCompleted)
                releaseWork.complete(Unit)
                runCurrent()
                assertTrue("$phase: terminal selection must reject late shown", events.isEmpty())
                assertFalse("$phase: native cleanup still owns the close", closing.isCompleted)
                PresentationRegistry.detach(id, host)
                closing.await()
                assertTrue(phase, pending.await().isFailure)
                assertEquals(phase, vector.getValue("dismissalCheckpoints").jsonPrimitive.int, checkpoints)
                assertEquals(phase, vector.getValue("shownFacts").jsonPrimitive.int,
                    events.count { it == SystemEventNames.EXPERIENCE_SHOWN })
                assertEquals(phase, vector.getValue("closeFacts").jsonPrimitive.int, events.size)
                assertEquals(phase, vector.getValue("terminalOutcomes").jsonPrimitive.int, outcomes.size)
                assertEquals(phase, vector.getValue("leaseCloseCount").jsonPrimitive.int, lease.closeCount.get())
                service.dismiss(CloseReason.HostDismissed)
                assertEquals(1, outcomes.size)
            } finally {
                releaseWork.complete(Unit)
                service.dismiss(CloseReason.HostDismissed)
                PresentationRegistry.detach(id, host)
                service.shutdownOwnedBy("customer-1")
            }
        }
    }

    @Test
    fun `recreation retires a queued reveal without replaying durable admission or shown`() = runTest {
        val release = renderedJourneyRelease()
        val launched = mutableListOf<String>()
        val events = mutableListOf<String>()
        val service = service(this, launch = launched::add, emit = { name, _, _ -> events += name })
        val admission = CompletableDeferred<Unit>()
        val oldFrameEntered = CompletableDeferred<Unit>()
        val oldFrameReleased = CompletableDeferred<Unit>()
        var admissions = 0
        var oldVisible = false
        var replacementVisible = false
        val pending = async {
            service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                service.reserveJourney("customer-1"), acquire = { acquired(release.identity, Lease()) },
                onPresentationRevealed = { admissions++; admission.await() }, onOutcome = {})
        }
        runCurrent()
        val id = launched.single()
        val old = AttachedHost()
        PresentationRegistry.attach(id, old)
        PresentationRegistry.reportFirstFrame(id, old) {
            oldFrameEntered.complete(Unit)
            oldFrameReleased.await()
            PresentationRegistry.canReveal(id, old).also { oldVisible = it }
        }
        runCurrent()
        assertFalse(oldFrameEntered.isCompleted)
        assertFalse(pending.isCompleted)
        assertTrue(events.isEmpty())
        admission.complete(Unit)
        oldFrameEntered.await()
        PresentationRegistry.detach(id, old)
        val replacement = AttachedHost()
        PresentationRegistry.attach(id, replacement)
        PresentationRegistry.reportFirstFrame(id, replacement) {
            PresentationRegistry.canReveal(id, replacement).also { replacementVisible = it }
        }
        runCurrent()
        assertFalse(replacementVisible)
        oldFrameReleased.complete(Unit)
        pending.await()
        assertFalse(oldVisible)
        assertTrue(replacementVisible)
        assertEquals(1, admissions)
        assertEquals(1, events.count { it == SystemEventNames.EXPERIENCE_SHOWN })
        service.dismiss(CloseReason.HostDismissed)
        PresentationRegistry.detach(id, replacement)
        service.shutdownOwnedBy("customer-1")
    }

    @Test
    fun `competing preparation is declined without waiting or disturbing the admitted owner`() = runTest {
        val vector = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/presentation-preparation-android.json").readText()).jsonObject
            .getValue("concurrentAdmission").jsonObject
        val release = renderedJourneyRelease()
        val releaseWork = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val lease = Lease()
        val launched = mutableListOf<String>()
        val service = service(this, launch = launched::add)
        val reservation = service.reserveJourney("customer-1")
        val pending = async {
            runCatching {
                service.presentJourney(release, "screen_welcome", "journey-1", "customer-1", reservation,
                    acquire = {
                        withContext(NonCancellable) { started.complete(Unit); releaseWork.await() }
                        acquired(release.identity, lease)
                    }, onOutcome = {})
            }
        }
        started.await()
        var competitorAcquisitions = 0
        val competitor = async {
            runCatching {
                service.presentJourney(release, "screen_welcome", "journey-1", "customer-1", reservation,
                    acquire = { competitorAcquisitions++; error("Competing acquisition must not start") }, onOutcome = {})
            }
        }
        try {
            runCurrent()
            assertTrue("Admission must not queue behind acquisition or user recovery", competitor.isCompleted)
            assertEquals(vector.getValue("failureReason").jsonPrimitive.content,
                (competitor.await().exceptionOrNull() as ExperiencePresentationException).reason.name)
            assertFalse(pending.isCompleted)
            assertEquals(vector.getValue("competitorAcquisitions").jsonPrimitive.int, competitorAcquisitions)
            assertEquals(vector.getValue("shellLaunchCount").jsonPrimitive.int, launched.size)
            assertTrue(PresentationRegistry.observe(launched.single())?.value is PresentationContentState.Acquiring)
            assertNull(service.reserveJourney("customer-2"))
            val close = async { service.dismissFromHost("customer-1") }
            runCurrent()
            assertFalse(close.isCompleted)
            releaseWork.complete(Unit)
            close.await()
            assertEquals(ExperiencePresentationException.Reason.SUPERSEDED,
                (pending.await().exceptionOrNull() as ExperiencePresentationException).reason)
            assertEquals(vector.getValue("lateLeaseCloseCount").jsonPrimitive.int, lease.closeCount.get())
            assertNotNull(service.reserveJourney("customer-2"))
        } finally {
            releaseWork.complete(Unit)
            service.shutdownOwnedBy("customer-1")
        }
    }

    @Test
    fun `pending preparation drains matching close before a host exists`() = runTest {
        val fixture = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/presentation-preparation-android.json").readText()).jsonObject
        for (raw in fixture.getValue("cases").jsonArray) {
            val vector = raw.jsonObject
            val action = vector.getValue("action").jsonPrimitive.content
            val phase = vector.getValue("phase").jsonPrimitive.content
            val label = "$action/$phase"
            val release = renderedJourneyRelease()
            val releaseWork = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val lease = Lease()
            var acquisitions = 0
            val launched = mutableListOf<String>()
            suspend fun waitForRelease() = withContext(NonCancellable) {
                started.complete(Unit)
                releaseWork.await()
            }
            val service = ExperiencePresentationService(
                emit = { _, _, _ -> fail("Unrevealed preparation must not emit presentation facts") },
                scope = this, runtimeAvailable = { true }, launch = launched::add,
                commerce = ai.nuxie.sdk.billing.JourneyCommercePreparing {
                    if (phase == "commerce") waitForRelease()
                    null
                },
            )
            val pending = async {
                runCatching {
                    service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                        service.reserveJourney("customer-1"), acquire = {
                            acquisitions++
                            if (phase == "acquisition") waitForRelease()
                            acquired(release.identity, lease)
                        }, onOutcome = {})
                }
            }
            started.await()
            val close = async {
                when (action) {
                    "hostClose" -> service.dismissFromHost("customer-1")
                    "identityShutdown" -> service.shutdownOwnedBy("customer-1")
                    "journeyShutdown" -> service.shutdownJourney("customer-1", "journey-1")
                    "callerCancel" -> pending.cancelAndJoin()
                    else -> error(action)
                }
            }
            runCurrent()
            assertFalse(label, close.isCompleted)
            assertFalse(label, pending.isCompleted)
            assertNull(label, service.reserveJourney("customer-2"))
            releaseWork.complete(Unit)
            close.await()
            if (action != "callerCancel") {
                val failure = pending.await().exceptionOrNull()
                assertTrue(label, failure is ExperiencePresentationException)
                assertEquals(label, vector.getValue("failureReason").jsonPrimitive.content,
                    (failure as ExperiencePresentationException).reason.name)
            }
            assertEquals(label, vector.getValue("shellLaunchCount").jsonPrimitive.int, launched.size)
            assertNull(label, PresentationRegistry.observe(launched.single()))
            assertEquals(label, if (phase == "acquisition") 1 else 0, acquisitions)
            assertEquals(label, vector.getValue("lateLeaseReleased").jsonPrimitive.boolean, lease.closed.get())
            assertEquals(label, if (phase == "acquisition") 1 else 0, lease.closeCount.get())
            assertNotNull(label, service.reserveJourney("customer-2"))
        }
    }

    @Test
    fun `foreign shutdown preserves pending acquisition and cooperative close leaves caller active`() = runTest {
        val release = renderedJourneyRelease()
        val releaseWork = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val launched = mutableListOf<String>()
        val service = service(this, launch = launched::add)
        val pending = async {
            val result = runCatching {
                service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                    service.reserveJourney("customer-1"), acquire = {
                        try { releaseWork.await(); error("Work was not cancelled") }
                        finally { cancelled.complete(Unit) }
                    }, onOutcome = {})
            }
            assertTrue("Presentation cancellation must not cancel its caller job",
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.isActive)
            result
        }
        runCurrent()
        service.shutdownOwnedBy("customer-2")
        service.shutdownJourney("customer-1", "other-journey")
        assertFalse(cancelled.isCompleted)
        assertFalse(pending.isCompleted)
        service.dismissFromHost("customer-1")
        assertTrue(cancelled.isCompleted)
        assertTrue(pending.await().isFailure)
        assertEquals(1, launched.size)
        assertNull(PresentationRegistry.observe(launched.single()))
    }

    @Test
    fun `initial shell precedes acquisition and keeps its id through content handoff`() = runTest {
        val contract = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/presentation-preparation-android.json").readText()).jsonObject
            .getValue("initialShell").jsonObject
        val release = renderedJourneyRelease()
        val lease = Lease()
        val ready = CompletableDeferred<Unit>()
        val launched = mutableListOf<String>()
        var shown = 0
        val service = service(this, launch = launched::add, emit = { name, _, _ ->
            if (name == SystemEventNames.EXPERIENCE_SHOWN) shown++
        })
        val pending = async {
            service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                service.reserveJourney("customer-1"), acquire = {
                    ready.await()
                    acquired(release.identity, lease)
                }, onOutcome = {})
        }
        runCurrent()
        val id = launched.single()
        val state = requireNotNull(PresentationRegistry.observe(id))
        val shell = state.value as PresentationContentState.Acquiring
        assertEquals(contract.getValue("expectedClearColorArgb").jsonPrimitive.int, shell.screen.clearColor)
        assertNull(PresentationRegistry.resolve(id))
        PresentationRegistry.reportFirstFrame(id)
        assertEquals(contract.getValue("shownBeforeFirstFrame").jsonPrimitive.int, shown)
        assertFalse(pending.isCompleted)
        ready.complete(Unit)
        runCurrent()
        assertEquals(contract.getValue("launchCount").jsonPrimitive.int, launched.size)
        assertEquals(contract.getValue("reusePresentationId").jsonPrimitive.boolean, launched.single() == id)
        assertTrue(state.value is PresentationContentState.Ready)
        assertNotNull(PresentationRegistry.resolve(id))
        assertEquals(contract.getValue("shownBeforeFirstFrame").jsonPrimitive.int, shown)
        PresentationRegistry.reportFirstFrame(id)
        pending.await()
        assertEquals(contract.getValue("shownAfterFirstFrame").jsonPrimitive.int, shown)
        service.dismissFromHost("customer-1")
        assertTrue(state.value is PresentationContentState.Closed)
        assertEquals(1, lease.closeCount.get())
    }

    @Test
    fun `withdrawn acquiring registry entry cannot be upgraded or recreated`() {
        val screen = AuthenticatedPresentationScreen.resolve(renderedJourneyRelease(), "screen_welcome")
        val detached = PresentationRegistry.registerAcquiring("pending-shell", screen) {}
        val state = requireNotNull(PresentationRegistry.observe("pending-shell"))
        val host = AttachedHost()
        assertTrue(PresentationRegistry.attach("pending-shell", host))
        PresentationRegistry.dismiss("pending-shell", CloseReason.UserDismissed)
        assertTrue(host.finished)
        assertFalse(detached.isCompleted)
        val content = PreparedPresentation(File("not-acquired.riv"), screen.artboardName, screen.clearColor,
            screen.shell, screenId = screen.screenId)
        fun publish() = PresentationRegistry.register("pending-shell", content,
            onFirstFrame = { fail("Closing shell cannot reveal") }, onFailure = {}, onDismissed = {}, onOutcome = {},
            requiresAcquiring = true)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { publish() }
        PresentationRegistry.reportFirstFrame("pending-shell")
        PresentationRegistry.detach("pending-shell", host)
        assertTrue(detached.isCompleted)
        assertTrue(state.value is PresentationContentState.Closed)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { publish() }
        assertNull(PresentationRegistry.observe("pending-shell"))
    }

    @Test
    fun `early host failure retains its cause instead of becoming withdrawal`() = runTest {
        val release = renderedJourneyRelease()
        val failure = ExperiencePresentationException(ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE, "No Vulkan surface")
        val service = service(this, launch = { PresentationRegistry.reportFailure(it, failure) })
        val observed = expectPresentationFailure {
            service.presentJourney(release, "screen_welcome", "journey-1", "customer-1",
                service.reserveJourney("customer-1"), acquire = { error("Failed host cannot acquire") }, onOutcome = {})
        }
        org.junit.Assert.assertSame(failure, observed)
        assertNotNull(service.reserveJourney("customer-2"))
    }

    @Test
    fun `authenticated shell resolves without render artifact acquisition`() {
        val release = renderedJourneyRelease()
        val screen = AuthenticatedPresentationScreen.resolve(release, "screen_welcome")
        assertEquals("screen_welcome", screen.screenId)
        assertEquals("Welcome", screen.artboardName)
        assertEquals(0xff0a0a0a.toInt(), screen.clearColor)
        assertEquals(PresentationShell.FullScreen, screen.shell)
        assertEquals(ExperienceArtboardSize(390f, 844f), screen.artboardSize)
        val error = org.junit.Assert.assertThrows(ExperiencePresentationException::class.java) {
            AuthenticatedPresentationScreen.resolve(release, "not-a-signed-screen")
        }
        assertEquals(ExperiencePresentationException.Reason.PREPARATION_FAILED, error.reason)
    }

    @Test
    fun `authenticated Journey shows its signed screen and closes through Journey lifecycle`() = runTest {
        val release = renderedJourneyRelease()
        val emitted = mutableListOf<Emitted>()
        val launched = mutableListOf<String>()
        val revealed = mutableListOf<String>()
        val dismissals = mutableListOf<Triple<String, String?, String>>()
        val lease = Lease()
        val service = service(
            scope = this,
            emit = { name, properties, distinctId ->
                emitted += Emitted(name, properties, distinctId)
            },
            launch = launched::add,
        )
        val reservation = requireNotNull(service.reserveJourney("customer-1"))

        val presentation = async(SupervisorJob()) {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { acquired(release.identity, lease) },
                onPresentationRevealed = revealed::add,
                onScreenDismissed = { screenId, revealingScreenId, method ->
                    dismissals += Triple(screenId, revealingScreenId, method)
                    JourneyScreenDismissalResult.HANDLED
                },
                onOutcome = { fail("handled dismissal must not abandon the Journey") },
            )
        }
        runCurrent()

        val presentationId = launched.single()
        val prepared = requireNotNull(PresentationRegistry.resolve(presentationId))
        assertEquals("Welcome", prepared.artboardName)
        assertEquals("screen_welcome", prepared.screenId)
        assertEquals(release.descriptor, prepared.descriptor)
        PresentationRegistry.reportFirstFrame(presentationId)

        assertEquals(
            ExperienceRef("experience_golden", "version_golden", "journey-1"),
            presentation.await(),
        )
        assertEquals(listOf("screen_welcome"), revealed)
        assertEquals(listOf(SystemEventNames.EXPERIENCE_SHOWN), emitted.map(Emitted::name))
        assertEquals("customer-1", emitted.single().distinctId)
        assertFalse(lease.closed.get())

        service.dismiss()
        runCurrent()

        assertEquals(
            listOf(Triple("screen_welcome", null, "user")),
            dismissals,
        )
        assertEquals(
            listOf(SystemEventNames.EXPERIENCE_SHOWN, SystemEventNames.EXPERIENCE_DISMISSED),
            emitted.map(Emitted::name),
        )
        assertEquals("user", emitted.last().properties["reason"])
        assertTrue(lease.closed.get())
    }

    @Test
    fun `prepared Journey includes every acquired external artifact`() = runTest {
        val release = renderedJourneyRelease()
        val launched = mutableListOf<String>()
        val lease = Lease()
        val asset = File.createTempFile("journey-asset-", ".png")
        val service = service(this, launch = launched::add)
        val reservation = requireNotNull(service.reserveJourney("customer-1"))

        val presentation = async(SupervisorJob()) {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = {
                    acquired(
                        release.identity,
                        lease,
                        mapOf("assets/hero.png" to asset),
                    )
                },
                onOutcome = {},
            )
        }
        runCurrent()

        val presentationId = launched.single()
        assertEquals(
            asset,
            PresentationRegistry.resolve(presentationId)?.artifactsByKey?.get("assets/hero.png"),
        )
        PresentationRegistry.reportFirstFrame(presentationId)
        presentation.await()
        service.dismissFromHost("customer-1")
        assertTrue(lease.closed.get())
    }

    @Test
    fun `renderer unavailability fails before Journey artifact acquisition`() = runTest {
        val release = renderedJourneyRelease()
        var didAcquire = false
        val service = service(this, runtimeAvailable = { false })
        val reservation = requireNotNull(service.reserveJourney("customer-1"))

        val error = expectPresentationFailure {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = {
                    didAcquire = true
                    acquired(release.identity, Lease())
                },
                onOutcome = {},
            )
        }

        assertEquals(ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE, error.reason)
        assertFalse(didAcquire)
    }

    @Test
    fun `artifact acquisition failure is a typed Journey presentation failure`() = runTest {
        val release = renderedJourneyRelease()
        val service = service(this)
        val reservation = requireNotNull(service.reserveJourney("customer-1"))

        val error = expectPresentationFailure {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { throw java.io.IOException("offline") },
                onOutcome = {},
            )
        }

        assertEquals(ExperiencePresentationException.Reason.ACQUISITION_FAILED, error.reason)
        assertTrue(error.cause is java.io.IOException)
    }

    @Test
    fun `Journey admission is rechecked after artifact acquisition`() = runTest {
        val release = renderedJourneyRelease()
        val acquisitionStarted = CompletableDeferred<Unit>()
        val continueAcquisition = CompletableDeferred<Unit>()
        val launched = mutableListOf<String>()
        val lease = Lease()
        var canPresent = true
        val service = service(this, launch = launched::add)
        val reservation = requireNotNull(service.reserveJourney("customer-1"))
        val presentation = async(SupervisorJob()) {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                canPresent = { canPresent },
                acquire = {
                    acquisitionStarted.complete(Unit)
                    continueAcquisition.await()
                    acquired(release.identity, lease)
                },
                onOutcome = {},
            )
        }

        acquisitionStarted.await()
        canPresent = false
        continueAcquisition.complete(Unit)
        runCurrent()

        val error = expectPresentationFailure { presentation.await() }
        assertEquals(ExperiencePresentationException.Reason.SUPERSEDED, error.reason)
        assertTrue(lease.closed.get())
        assertEquals(1, launched.size)
        assertNull(PresentationRegistry.observe(launched.single()))
    }

    @Test
    fun `one Journey prevents another Journey from stealing its surface`() = runTest {
        val release = renderedJourneyRelease()
        val launched = mutableListOf<String>()
        val firstLease = Lease()
        var secondAcquired = false
        val service = service(this, launch = launched::add)
        val reservation = requireNotNull(service.reserveJourney("customer-1"))
        val first = async {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { acquired(release.identity, firstLease) },
                onOutcome = {},
            )
        }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        first.await()

        val error = expectPresentationFailure {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-2",
                ownerDistinctId = "customer-1",
                reservation = null,
                acquire = {
                    secondAcquired = true
                    acquired(release.identity, Lease())
                },
                onOutcome = {},
            )
        }

        assertEquals(ExperiencePresentationException.Reason.DECLINED, error.reason)
        assertFalse(secondAcquired)
        assertEquals(1, launched.size)
        service.shutdownJourney("customer-1", "journey-1")
        assertTrue(firstLease.closed.get())
    }

    @Test
    fun `same Journey navigation replaces the screen without a false terminal outcome`() = runTest {
        val release = renderedJourneyRelease()
        val emitted = mutableListOf<String>()
        val launched = mutableListOf<String>()
        val lifecycle = mutableListOf<Triple<String, String?, String>>()
        val firstOutcomes = mutableListOf<JourneySurfaceOutcome>()
        val secondOutcomes = mutableListOf<JourneySurfaceOutcome>()
        val firstLease = Lease()
        val secondLease = Lease()
        val service = service(
            scope = this,
            emit = { name, _, _ -> emitted += name },
            launch = launched::add,
        )
        val reservation = requireNotNull(service.reserveJourney("customer-1"))
        val first = async {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { acquired(release.identity, firstLease) },
                onScreenDismissed = { screenId, revealingScreenId, method ->
                    lifecycle += Triple(screenId, revealingScreenId, method)
                    JourneyScreenDismissalResult.HANDLED
                },
                onOutcome = firstOutcomes::add,
            )
        }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        first.await()
        val originalDraft = requireNotNull(PresentationRegistry.resolve(launched.single()))
            .textInputState.bind()
        val draft = ExperienceTextInputState.Value("iris@example.com", 2, 5)
        assertTrue(originalDraft.write("input_email", draft))

        val second = async {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = null,
                acquire = { acquired(release.identity, secondLease) },
                onOutcome = secondOutcomes::add,
            )
        }
        runCurrent()

        assertEquals(2, launched.size)
        assertFalse(originalDraft.isCurrent())
        assertFalse(originalDraft.write("input_email", draft.copy(text = "late")))
        val restoredDraft = requireNotNull(PresentationRegistry.resolve(launched.last()))
            .textInputState.bind()
        assertEquals(draft, restoredDraft.read("input_email"))
        assertTrue(firstLease.closed.get())
        assertTrue(firstOutcomes.isEmpty())
        assertEquals(
            listOf(Triple("screen_welcome", "screen_welcome", "navigate")),
            lifecycle,
        )
        assertEquals(0, emitted.count { it == SystemEventNames.EXPERIENCE_DISMISSED })

        PresentationRegistry.reportFirstFrame(launched.last())
        second.await()
        service.dismissFromHost("customer-1")

        assertEquals(listOf(JourneySurfaceOutcome.DISMISSED), secondOutcomes)
        assertTrue(secondLease.closed.get())
        assertEquals(2, emitted.count { it == SystemEventNames.EXPERIENCE_SHOWN })
        assertEquals(1, emitted.count { it == SystemEventNames.EXPERIENCE_DISMISSED })

        val nextReservation = requireNotNull(service.reserveJourney("customer-1"))
        val nextJourney = async {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-2",
                ownerDistinctId = "customer-1",
                reservation = nextReservation,
                acquire = { acquired(release.identity, Lease()) },
                onOutcome = {},
            )
        }
        runCurrent()
        val freshDraft = requireNotNull(PresentationRegistry.resolve(launched.last()))
            .textInputState.bind()
        assertNull(freshDraft.read("input_email"))
        PresentationRegistry.reportFirstFrame(launched.last())
        nextJourney.await()
        service.dismissFromHost("customer-1")
    }

    @Test
    fun `navigation restores independent screen drafts and discards them for a new build`() = runTest {
        val release = renderedJourneyRelease("text-input-navigation.json")
        val nextBuild = renderedJourneyRelease("text-input-navigation.json", "nextBuildEntry")
        val launched = mutableListOf<String>()
        val batches = mutableListOf<JourneyScreenEmissionBatch>()
        val service = service(this, launch = launched::add)
        val lifecycles = mutableListOf<ExperienceScreenLifecycle>()
        var first = true
        suspend fun show(
            screenId: String,
            selectedRelease: AuthenticatedJourneyRelease = release,
        ): ExperienceTextInputState.Session {
            val reservation = if (first) service.reserveJourney("customer-1") else null
            first = false
            val pending = async {
                service.presentJourney(
                    release = selectedRelease,
                    screenId = screenId,
                    journeyId = "journey-1",
                    ownerDistinctId = "customer-1",
                    reservation = reservation,
                    acquire = { acquired(selectedRelease.identity, Lease()) },
                    onEmissionBatch = { batches += it; true },
                    onOutcome = {},
                )
            }
            runCurrent()
            val presentation = requireNotNull(PresentationRegistry.resolve(launched.last()))
            lifecycles += presentation.screenLifecycle
            assertEquals(screenId, presentation.screenId)
            PresentationRegistry.reportFirstFrame(launched.last())
            pending.await()
            return presentation.textInputState.bind()
        }
        fun commit(text: String) {
            val host = AttachedHost()
            val id = launched.last()
            assertTrue(PresentationRegistry.attach(id, host))
            PresentationRegistry.reportTextCommitted(id, host, "input_email", text)
            runCurrent()
            PresentationRegistry.detach(id, host)
        }
        val welcome = show("screen_welcome")
        val welcomeValue = ExperienceTextInputState.Value("Iris", 1, 3)
        assertTrue(welcome.write("input_email", welcomeValue))
        commit("Iris")
        assertEquals(1, batches.size)
        val details = show("screen_details")
        assertNull(details.read("input_email"))
        assertFalse(welcome.write("input_email", welcomeValue.copy(text = "stale")))
        val detailsValue = ExperienceTextInputState.Value("Separate", 2, 2)
        assertTrue(details.write("input_email", detailsValue))
        commit("Separate")
        assertEquals(2, batches.size)
        assertEquals(welcomeValue, show("screen_welcome").read("input_email"))
        commit("Iris")
        assertEquals(2, batches.size)
        assertEquals(detailsValue, show("screen_details").read("input_email"))
        commit("Separate")
        assertEquals(2, batches.size)
        assertNull(show("screen_welcome", nextBuild).read("input_email"))
        commit("Iris")
        assertEquals(3, batches.size)
        assertNull(show("screen_details", nextBuild).read("input_email"))
        // Returning to an earlier build must not resurrect its old drafts either.
        assertNull(show("screen_welcome", release).read("input_email"))
        assertTrue(lifecycles[0] === lifecycles[2])
        assertTrue(lifecycles[1] === lifecycles[3])
        assertFalse(lifecycles[0] === lifecycles[1])
        assertFalse(lifecycles[0] === lifecycles[4])
        assertFalse(lifecycles[1] === lifecycles[5])
        assertFalse(lifecycles[0] === lifecycles[6])
        service.dismissFromHost("customer-1")
    }

    @Test
    fun `completed dismissal releases acquired destination without launching it`() = runTest {
        val contract = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/navigation-acquisition-android.json").readText(),
        ).jsonObject.getValue("completedDismissal").jsonObject
        val release = renderedJourneyRelease()
        val launched = mutableListOf<String>()
        val firstLease = Lease()
        var secondAcquired = false
        val secondLease = Lease()
        val service = service(this, launch = launched::add)
        val reservation = requireNotNull(service.reserveJourney("customer-1"))
        val first = async {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { acquired(release.identity, firstLease) },
                onScreenDismissed = { _, _, _ -> JourneyScreenDismissalResult.COMPLETED },
                onOutcome = {},
            )
        }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        first.await()

        val error = expectPresentationFailure {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = null,
                acquire = {
                    secondAcquired = true
                    acquired(release.identity, secondLease)
                },
                onOutcome = {},
            )
        }

        assertEquals(contract.getValue("reason").jsonPrimitive.content, error.reason.name)
        assertEquals(contract.getValue("destinationAcquired").jsonPrimitive.boolean, secondAcquired)
        assertEquals(contract.getValue("destinationReleased").jsonPrimitive.boolean, secondLease.closed.get())
        assertTrue(firstLease.closed.get())
        assertEquals(contract.getValue("launchCount").jsonPrimitive.int, launched.size)
    }

    @Test
    fun `first frame timeout releases the Journey artifact lease`() = runTest {
        val release = renderedJourneyRelease()
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            scope = this,
            launch = launched::add,
            firstFrameTimeoutMillis = 10,
        )
        val reservation = requireNotNull(service.reserveJourney("customer-1"))
        val presentation = async(SupervisorJob()) {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { acquired(release.identity, lease) },
                onOutcome = {},
            )
        }
        runCurrent()

        assertEquals(1, launched.size)
        advanceTimeBy(11)
        runCurrent()

        val error = expectPresentationFailure { presentation.await() }
        assertEquals(ExperiencePresentationException.Reason.FIRST_FRAME_TIMEOUT, error.reason)
        assertTrue(lease.closed.get())
        assertNull(PresentationRegistry.resolve(launched.single()))
    }

    @Test
    fun `renderer failure is typed and releases the Journey artifact lease`() = runTest {
        val release = renderedJourneyRelease()
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(this, launch = launched::add)
        val reservation = requireNotNull(service.reserveJourney("customer-1"))
        val presentation = async(SupervisorJob()) {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { acquired(release.identity, lease) },
                onOutcome = {},
            )
        }
        runCurrent()

        PresentationRegistry.reportFailure(launched.single(), IllegalStateException("renderer failed"))
        runCurrent()

        val error = expectPresentationFailure { presentation.await() }
        assertEquals(ExperiencePresentationException.Reason.HOST_FAILED, error.reason)
        assertTrue(lease.closed.get())
        assertNull(PresentationRegistry.resolve(launched.single()))
    }

    @Test
    fun `identity shutdown only tears down the departing owners Journey`() = runTest {
        val release = renderedJourneyRelease()
        val emitted = mutableListOf<String>()
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            scope = this,
            emit = { name, _, _ -> emitted += name },
            launch = launched::add,
        )
        val reservation = requireNotNull(service.reserveJourney("customer-1"))
        val presentation = async {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { acquired(release.identity, lease) },
                onOutcome = {},
            )
        }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        presentation.await()

        service.shutdownOwnedBy("customer-2")
        assertFalse(lease.closed.get())
        assertNotNull(PresentationRegistry.resolve(launched.single()))

        service.shutdownOwnedBy("customer-1")
        assertTrue(lease.closed.get())
        assertNull(PresentationRegistry.resolve(launched.single()))
        assertEquals(listOf(SystemEventNames.EXPERIENCE_SHOWN), emitted)
    }

    @Test
    fun `host dismissal waits for its attached runtime to detach`() = runTest {
        val release = renderedJourneyRelease()
        val launched = mutableListOf<String>()
        val lease = Lease()
        val outcomes = mutableListOf<JourneySurfaceOutcome>()
        val service = service(this, launch = launched::add)
        val reservation = requireNotNull(service.reserveJourney("customer-1"))
        val presentation = async {
            service.presentJourney(
                release = release,
                screenId = "screen_welcome",
                journeyId = "journey-1",
                ownerDistinctId = "customer-1",
                reservation = reservation,
                acquire = { acquired(release.identity, lease) },
                onOutcome = outcomes::add,
            )
        }
        runCurrent()
        val presentationId = launched.single()
        val host = AttachedHost()
        assertTrue(PresentationRegistry.attach(presentationId, host))
        PresentationRegistry.reportFirstFrame(presentationId)
        presentation.await()

        val dismissal = async { service.dismissFromHost("customer-1") }
        runCurrent()

        assertFalse(dismissal.isCompleted)
        assertEquals(CloseReason.HostDismissed, host.requestedReason)
        assertTrue(host.finished)
        assertFalse(lease.closed.get())
        assertEquals(listOf(JourneySurfaceOutcome.DISMISSED), outcomes)

        PresentationRegistry.detach(presentationId, host)
        runCurrent()
        dismissal.await()

        assertTrue(lease.closed.get())
        assertNull(PresentationRegistry.resolve(presentationId))
    }

    private fun service(
        scope: CoroutineScope,
        runtimeAvailable: () -> Boolean = { true },
        emit: (String, Map<String, Any?>, String?) -> Unit = { _, _, _ -> },
        launch: (String) -> Unit = {},
        firstFrameTimeoutMillis: Long = 30_000,
    ) = ExperiencePresentationService(
        emit = emit,
        scope = scope,
        runtimeAvailable = runtimeAvailable,
        launch = launch,
        firstFrameTimeoutMillis = firstFrameTimeoutMillis,
    )

    private suspend fun expectPresentationFailure(
        block: suspend () -> Unit,
    ): ExperiencePresentationException = try {
        block()
        fail("presentation should fail")
        error("unreachable")
    } catch (error: ExperiencePresentationException) {
        error
    }

    private fun acquired(
        identity: JourneyReleaseIdentity,
        lease: Lease,
        extraArtifacts: Map<String, File> = emptyMap(),
    ): AcquiredJourneyRelease {
        val file = File.createTempFile("journey-presentation-", ".riv").apply {
            writeBytes(byteArrayOf(1))
        }
        return AcquiredJourneyRelease(
            identity = identity,
            artifactsByKey = mapOf("renders/main.riv" to file) + extraArtifacts,
            rivFile = file,
            protection = lease,
        )
    }

    private fun renderedJourneyRelease(
        file: String = "release.json",
        entryKey: String = "renderedEntry",
    ): AuthenticatedJourneyRelease {
        val fixture = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/$file").readText(),
        ).jsonObject
        val entry = fixture.getValue(entryKey).jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val descriptor = Json.parseToJsonElement(
            Base64.decode(
                envelope.getValue("descriptorBytesBase64").jsonPrimitive.content,
                Base64.NO_WRAP,
            ).decodeToString(),
        ).jsonObject
        val identity = requireNotNull(
            JourneyReleaseIdentity.fromJson(
                entry.getValue("locator").jsonObject,
                additionalKeys = setOf("legId"),
            ),
        )
        val requirements = descriptor.getValue("requirements").jsonObject
        val luau = requirements.getValue("luau").jsonObject
        val scene = requirements.getValue("sceneFormat").jsonObject
        val timezone = requirements.getValue("timezoneData").jsonObject
        val runtime = JourneyReleaseSupportedRuntime(
            currentSdkVersion = requirements.getValue("minimumSdkVersion").jsonPrimitive.content,
            supportedRuntimeRevisions = setOf(
                requirements.getValue("runtimeRevision").jsonPrimitive.content,
            ),
            supportedLuauRevisions = mapOf(
                luau.getValue("revision").jsonPrimitive.content to
                    luau.getValue("bytecodeVersions").jsonArray
                        .map { it.jsonPrimitive.int }
                        .toSet(),
            ),
            sceneFormatMajor = scene.getValue("major").jsonPrimitive.int,
            sceneFormatMinor = scene.getValue("minor").jsonPrimitive.int,
            timezoneDataRevision = timezone.getValue("revision").jsonPrimitive.content,
            timezoneDataSha256 = timezone.getValue("sha256").jsonPrimitive.content,
            supportedCapabilities = requirements.getValue("requiredCapabilities").jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
        val trustedKeys = mapOf(
            "TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
                fixture.getValue("publicKeyBase64").jsonPrimitive.content,
                Base64.NO_WRAP,
            ),
        )
        return JourneyReleaseVerifier.authenticate(
            envelopeBytes = envelope.toString().encodeToByteArray(),
            trustedKeys = trustedKeys,
            expectedIdentity = identity,
            expectedLegId = "a".repeat(64),
            supportedRuntime = runtime,
            replayPolicy = JourneyReleaseReplayPolicy.Active(0),
        )
    }
}
