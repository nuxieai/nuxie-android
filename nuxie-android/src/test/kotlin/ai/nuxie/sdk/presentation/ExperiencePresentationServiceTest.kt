package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.Delivery
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.runtime.NuxieRuntimeFile
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExperiencePresentationServiceTest {
    private data class Emitted(
        val name: String,
        val properties: Map<String, Any?>,
        val distinctId: String? = null,
    )

    private class Lease : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() {
            closed.set(true)
        }
    }

    @After
    fun tearDown() {
        PresentationRegistry.clearForTesting()
    }

    @Test
    fun successfulPresentReturnsRefAndEmitsShownOnceAtFirstFrame() = runTest {
        val emitted = mutableListOf<Emitted>()
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            emit = { name, properties, _ -> emitted += Emitted(name, properties) },
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
        )

        val result = async { service.present("v1", "journey-1") }
        runCurrent()
        assertEquals(1, launched.size)
        assertFalse(result.isCompleted)

        PresentationRegistry.reportFirstFrame(launched.single())
        PresentationRegistry.reportFirstFrame(launched.single())

        assertEquals(ExperienceRef("exp-1", "v1", "journey-1"), result.await())
        assertEquals(listOf("\$experience_shown"), emitted.map { it.name })
        assertEquals("journey-1", emitted.single().properties["journey_id"])
        assertFalse("acquisition lease spans the visible presentation", lease.closed.get())

        service.dismiss()
        assertTrue(lease.closed.get())
    }

    @Test
    fun shownEmissionKeepsItsOwnerAfterIdentityChanges() = runTest {
        val emitted = mutableListOf<Emitted>()
        val launched = mutableListOf<String>()
        var currentDistinctId = "customer-1"
        val service = service(
            emit = { name, properties, distinctIdOverride ->
                emitted += Emitted(
                    name,
                    properties,
                    distinctIdOverride ?: currentDistinctId,
                )
            },
            launch = launched::add,
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()

        currentDistinctId = "customer-2"
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        val event = emitted.single()
        assertEquals("\$experience_shown", event.name)
        assertEquals("customer-1", event.distinctId)
    }

    @Test
    fun acquiredExternalArtifactsReachPreparedPresentationWithoutDiagnosticFallback() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val imageKey = "assets/sha256/${"b".repeat(64)}.png"
        val imageFile = File.createTempFile("prepared-image-", ".png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            deleteOnExit()
        }
        val service = service(
            launch = launched::add,
            acquire = {
                acquired(
                    experienceId = "exp-1",
                    version = "v1",
                    lease = lease,
                    extraArtifacts = mapOf(imageKey to imageFile),
                )
            },
        )

        val shown = async { service.present("v1") }
        runCurrent()

        val prepared = PresentationRegistry.resolve(launched.single())
            ?: error("prepared presentation missing")
        assertEquals(imageFile, prepared.artifactsByKey[imageKey])
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()
        service.dismiss()
    }

    @Test
    fun runtimeUnavailableIsTypedAndNeverResolvesOrLaunches() = runTest {
        var resolved = false
        var launched = false
        val service = service(
            runtimeAvailable = false,
            provider = PresentationReleaseProvider {
                resolved = true
                release("exp-1", it)
            },
            launch = { launched = true },
        )

        val error = expectPresentationFailure { service.present("v1") }
        assertEquals(ExperiencePresentationException.Reason.RUNTIME_UNAVAILABLE, error.reason)
        assertFalse(resolved)
        assertFalse(launched)
    }

    @Test
    fun secondPresentDismissesFirstEvenBeforeItsFirstFrame() = runTest {
        val launched = mutableListOf<String>()
        val leases = mutableMapOf("v1" to Lease(), "v2" to Lease())
        val service = service(
            provider = PresentationReleaseProvider { version -> release("exp-$version", version) },
            launch = launched::add,
            acquire = { admitted ->
                acquired(
                    admitted.release.identity.experienceId,
                    admitted.release.identity.experienceVersionId,
                    leases.getValue(admitted.release.identity.experienceVersionId),
                )
            },
        )

        val first = async(SupervisorJob()) { service.present("v1") }
        runCurrent()
        val second = async { service.present("v2") }
        runCurrent()

        val firstError = try {
            first.await()
            fail("first presentation should be superseded")
            error("unreachable")
        } catch (error: ExperiencePresentationException) {
            error
        }
        assertEquals(ExperiencePresentationException.Reason.SUPERSEDED, firstError.reason)
        assertTrue(leases.getValue("v1").closed.get())
        assertEquals(2, launched.size)

        PresentationRegistry.reportFirstFrame(launched.last())
        assertEquals("v2", second.await().experienceVersion)
        assertFalse(leases.getValue("v2").closed.get())
    }

    @Test
    fun acquisitionFailureSurfacesAsTypedPresentationError() = runTest {
        val service = service(
            acquire = { throw java.io.IOException("offline") },
        )

        val error = expectPresentationFailure { service.present("v1") }
        assertEquals(ExperiencePresentationException.Reason.ACQUISITION_FAILED, error.reason)
        assertTrue(error.cause is java.io.IOException)
    }

    @Test
    fun hostFailureSurfacesTypedAndReleasesAcquisitionLease() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
        )
        val result = async(SupervisorJob()) { service.present("v1") }
        runCurrent()

        PresentationRegistry.reportFailure(launched.single(), IllegalStateException("renderer"))

        val error = try {
            result.await()
            fail("host failure should fail presentation")
            error("unreachable")
        } catch (error: ExperiencePresentationException) {
            error
        }
        assertEquals(ExperiencePresentationException.Reason.HOST_FAILED, error.reason)
        assertTrue(lease.closed.get())
    }

    @Test
    fun launchThatNeverAttachesTimesOutAndReleasesRegistryAndLease() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            firstFrameTimeoutMillis = 1_000,
        )
        val result = async(SupervisorJob()) { service.present("v1") }
        runCurrent()

        advanceTimeBy(1_001)
        runCurrent()

        val error = try {
            result.await()
            fail("missing Activity attachment should time out")
            error("unreachable")
        } catch (error: ExperiencePresentationException) {
            error
        }
        assertEquals(ExperiencePresentationException.Reason.FIRST_FRAME_TIMEOUT, error.reason)
        assertEquals(null, PresentationRegistry.resolve(launched.single()))
        assertTrue(lease.closed.get())
    }

    @Test
    fun closeOutcomeKeepsJourneyLinkage() = runTest {
        val launched = mutableListOf<String>()
        val outcomes = mutableListOf<PresentationOutcome>()
        val service = service(
            launch = launched::add,
            reportOutcome = { outcomes += it; true },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        assertEquals("journey-7", shown.await().journeyId)

        service.dismiss(CloseReason.GoalMet)
        runCurrent()

        assertEquals("journey-7", outcomes.single().ref.journeyId)
        assertEquals(CloseReason.GoalMet, outcomes.single().reason)
    }

    @Test
    fun purchaseCloseEmissionKeepsItsOwnerAfterIdentityChanges() = runTest {
        val emitted = mutableListOf<Emitted>()
        val launched = mutableListOf<String>()
        var currentDistinctId = "customer-1"
        val service = service(
            emit = { name, properties, distinctIdOverride ->
                emitted += Emitted(
                    name,
                    properties,
                    distinctIdOverride ?: currentDistinctId,
                )
            },
            launch = launched::add,
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        currentDistinctId = "customer-2"
        service.dismiss(CloseReason.PurchaseCompleted)

        val close = emitted.last()
        assertEquals("\$experience_purchased", close.name)
        assertEquals("customer-1", close.distinctId)
    }

    @Test
    fun timeoutCloseEmissionKeepsItsOwnerAfterIdentityChanges() = runTest {
        val emitted = mutableListOf<Emitted>()
        val launched = mutableListOf<String>()
        var currentDistinctId = "customer-1"
        val service = service(
            emit = { name, properties, distinctIdOverride ->
                emitted += Emitted(
                    name,
                    properties,
                    distinctIdOverride ?: currentDistinctId,
                )
            },
            launch = launched::add,
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        currentDistinctId = "customer-2"
        service.dismiss(CloseReason.Timeout)

        val close = emitted.last()
        assertEquals("\$experience_timed_out", close.name)
        assertEquals("customer-1", close.distinctId)
    }

    @Test
    fun errorCloseEmissionKeepsItsOwnerAfterIdentityChanges() = runTest {
        val emitted = mutableListOf<Emitted>()
        val launched = mutableListOf<String>()
        var currentDistinctId = "customer-1"
        val service = service(
            emit = { name, properties, distinctIdOverride ->
                emitted += Emitted(
                    name,
                    properties,
                    distinctIdOverride ?: currentDistinctId,
                )
            },
            launch = launched::add,
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        val presentationId = launched.single()
        PresentationRegistry.reportFirstFrame(presentationId)
        shown.await()

        currentDistinctId = "customer-2"
        PresentationRegistry.reportFailure(
            presentationId,
            IllegalStateException("renderer failed"),
        )

        val close = emitted.last()
        assertEquals("\$experience_errored", close.name)
        assertEquals("customer-1", close.distinctId)
    }

    @Test
    fun hostDismissalRequestsTeardownBeforeBlockedRunTransition() = runTest {
        val launched = mutableListOf<String>()
        val transitionStarted = CompletableDeferred<Unit>()
        val releaseTransition = CompletableDeferred<Unit>()
        val service = service(
            launch = launched::add,
            markOutcomeInMemory = {
                transitionStarted.complete(Unit)
                releaseTransition.await()
                true
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        val presentationId = launched.single()
        PresentationRegistry.reportFirstFrame(presentationId)
        shown.await()
        val activity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(activity, "presentationId", presentationId)
        assertTrue(PresentationRegistry.attach(presentationId, activity))

        val dismissal = async { service.dismissFromHost("customer-1") }
        transitionStarted.await()

        try {
            assertEquals(CloseReason.HostDismissed, activity.claimedCloseReason())
            assertTrue("blocked run transition kept the screen open", activity.isFinishing)
            assertFalse("dismissal completed before the run transition", dismissal.isCompleted)
        } finally {
            releaseTransition.complete(Unit)
            runCurrent()
            if (activity.isFinishing) invokeOnDestroy(activity)
        }

        dismissal.await()
    }

    @Test
    fun concurrentHostDismissalLoserWaitsForWinnerRunTransitionAfterTeardown() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val transitionStarted = CompletableDeferred<Unit>()
        val releaseTransition = CompletableDeferred<Unit>()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            markOutcomeInMemory = {
                transitionStarted.complete(Unit)
                releaseTransition.await()
                true
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        val presentationId = launched.single()
        PresentationRegistry.reportFirstFrame(presentationId)
        shown.await()
        val activity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(activity, "presentationId", presentationId)
        assertTrue(PresentationRegistry.attach(presentationId, activity))

        val winner = async { service.dismissFromHost("customer-1") }
        transitionStarted.await()
        val loser = async { service.dismissFromHost("customer-1") }
        runCurrent()

        try {
            invokeOnDestroy(activity)
            runCurrent()

            assertTrue("Activity teardown did not release the presentation", lease.closed.get())
            assertFalse("winner completed before its run transition", winner.isCompleted)
            assertFalse("loser completed before the winner's run transition", loser.isCompleted)
        } finally {
            releaseTransition.complete(Unit)
            if (activity.isFinishing && !lease.closed.get()) invokeOnDestroy(activity)
        }

        winner.await()
        loser.await()
    }

    @Test
    fun hostDismissalReturnsWhenEndedWinsSemanticRace() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val outcomes = mutableListOf<CloseReason>()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            markOutcomeInMemory = { false },
            reportOutcome = {
                outcomes += it.reason
            },
            beforeHostSemanticClaimForTesting = {
                PresentationRegistry.reportDismissed(
                    launched.single(),
                    CloseReason.GoalMet,
                )
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        withTimeout(1_000) {
            service.dismissFromHost("customer-1")
        }

        assertTrue(lease.closed.get())
        assertEquals(listOf<CloseReason>(CloseReason.GoalMet), outcomes)
    }

    @Test
    fun hostDismissalReturnsWhenFailedWinsSemanticRace() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val outcomes = mutableListOf<CloseReason>()
        val failure = IllegalStateException("renderer failed")
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            markOutcomeInMemory = { false },
            reportOutcome = {
                outcomes += it.reason
            },
            beforeHostSemanticClaimForTesting = {
                PresentationRegistry.reportFailure(launched.single(), failure)
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        withTimeout(1_000) {
            service.dismissFromHost("customer-1")
        }

        assertTrue(lease.closed.get())
        val reason = outcomes.single() as CloseReason.Error
        assertEquals(failure, reason.cause.cause)
    }

    @Test
    fun hostDismissalDoesNotOverwriteHealthyClaimedWinnerBeforeMemoryTransition() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val userReportStarted = CompletableDeferred<Unit>()
        val releaseUserReport = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<CloseReason>()
        var runIsActive = true
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            markOutcomeInMemory = { outcome ->
                if (!runIsActive) {
                    false
                } else {
                    runIsActive = false
                    outcomes += outcome.reason
                    true
                }
            },
            reportOutcome = { outcome ->
                if (outcome.reason == CloseReason.UserDismissed) {
                    userReportStarted.complete(Unit)
                    releaseUserReport.await()
                    if (runIsActive) {
                        runIsActive = false
                        outcomes += outcome.reason
                    }
                }
            },
            beforeHostSemanticClaimForTesting = {
                PresentationRegistry.reportDismissed(
                    launched.single(),
                    CloseReason.UserDismissed,
                )
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        val hostDismissal = async { service.dismissFromHost("customer-1") }
        userReportStarted.await()
        runCurrent()

        try {
            assertTrue("healthy winner had not transitioned memory", runIsActive)
            assertEquals(emptyList<CloseReason>(), outcomes)
            assertFalse("host dismissal returned before the winner's transition", hostDismissal.isCompleted)
        } finally {
            releaseUserReport.complete(Unit)
        }

        hostDismissal.await()
        assertFalse("healthy winner did not transition memory", runIsActive)
        assertEquals(listOf<CloseReason>(CloseReason.UserDismissed), outcomes)
        assertTrue(lease.closed.get())
    }

    @Test
    fun hostDismissalFallsBackToHostTombstoneWhenFailedWinnerDoesNotTransitionRun() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        var runIsActive = true
        var hasRecoverableHostTombstone = false
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            markOutcomeInMemory = { outcome ->
                assertEquals(CloseReason.HostDismissed, outcome.reason)
                runIsActive = false
                hasRecoverableHostTombstone = true
                true
            },
            reportOutcome = { outcome ->
                if (outcome.reason is CloseReason.Error) {
                    throw IllegalStateException("failed before the run transition")
                }
            },
            beforeHostSemanticClaimForTesting = {
                PresentationRegistry.reportFailure(
                    launched.single(),
                    IllegalStateException("renderer failed"),
                )
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        service.dismissFromHost("customer-1")

        assertFalse("dismiss() returned with the Journey still active", runIsActive)
        assertTrue("host fallback did not leave a recoverable tombstone", hasRecoverableHostTombstone)
        assertTrue(lease.closed.get())
    }

    @Test
    fun hostDismissalReturnsAfterPresentationTeardownWithoutAwaitingBookkeeping() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val semanticStarted = CompletableDeferred<PresentationOutcome>()
        val releaseSemanticCompletion = CompletableDeferred<Unit>()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reportOutcome = { outcome ->
                semanticStarted.complete(outcome)
                releaseSemanticCompletion.await()
                true
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        try {
            service.dismissFromHost("customer-1")

            val pendingOutcome = semanticStarted.await()
            assertEquals(CloseReason.HostDismissed, pendingOutcome.reason)
            assertEquals("customer-1", pendingOutcome.ownerDistinctId)
            assertEquals("customer-1", pendingOutcome.initiatingDistinctId)
            assertTrue(lease.closed.get())
        } finally {
            releaseSemanticCompletion.complete(Unit)
        }
    }

    @Test
    fun hostDismissalTearsDownWhenBookkeepingFails() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reportOutcome = { throw IllegalStateException("bookkeeping failed") },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        service.dismissFromHost("customer-1")
        runCurrent()

        assertTrue(lease.closed.get())
        assertEquals(null, PresentationRegistry.resolve(launched.single()))
    }

    @Test
    fun hostDismissalAfterIdentityChangedClosesWithoutAttributingTheOldPresentation() = runTest {
        val launched = mutableListOf<String>()
        val emitted = mutableListOf<String>()
        val lease = Lease()
        var semanticCalls = 0
        val marks = mutableListOf<PresentationOutcome>()
        val service = service(
            emit = { name, _, _ -> emitted += name },
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            markOutcomeInMemory = { outcome ->
                marks += outcome
                outcome.ownerDistinctId == outcome.initiatingDistinctId
            },
            reportOutcome = {
                semanticCalls += 1
                true
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        service.dismissFromHost("customer-2")

        assertEquals("customer-1", marks.single().ownerDistinctId)
        assertEquals("customer-2", marks.single().initiatingDistinctId)
        assertEquals(0, semanticCalls)
        assertFalse("identity transition emitted a dismissal", "\$experience_dismissed" in emitted)
        assertTrue(lease.closed.get())
        assertEquals(null, PresentationRegistry.resolve(launched.single()))
    }

    @Test
    fun identityChangeShutdownOnlyClosesTheDepartingCustomersPresentation() = runTest {
        val emitted = mutableListOf<String>()
        val launched = mutableListOf<String>()
        val lease = Lease()
        var semanticCalls = 0
        val service = service(
            emit = { name, _, _ -> emitted += name },
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reportOutcome = {
                semanticCalls += 1
                true
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-2") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        service.shutdownOwnedBy("customer-1")

        assertFalse(lease.closed.get())
        assertTrue(PresentationRegistry.resolve(launched.single()) != null)

        service.shutdownOwnedBy("customer-2")

        assertTrue(lease.closed.get())
        assertEquals(null, PresentationRegistry.resolve(launched.single()))
        assertEquals(listOf("\$experience_shown"), emitted)
        assertEquals(0, semanticCalls)
    }

    @Test
    fun identityShutdownRejectsSameOwnerPresentationStillAcquiring() = runTest {
        val acquisitionStarted = CompletableDeferred<Unit>()
        val continueAcquisition = CompletableDeferred<Unit>()
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            launch = launched::add,
            acquire = {
                acquisitionStarted.complete(Unit)
                continueAcquisition.await()
                acquired("exp-1", "v1", lease)
            },
        )
        val presentation = async(SupervisorJob()) {
            service.present("v1", "journey-7", "customer-1")
        }

        try {
            acquisitionStarted.await()
            service.shutdownOwnedBy("customer-1")

            continueAcquisition.complete(Unit)
            runCurrent()

            assertTrue("rejected acquisition must finish", presentation.isCompleted)
            assertTrue("rejected acquisition must release its lease", lease.closed.get())
            assertTrue("old-owner presentation launched after identity teardown", launched.isEmpty())
            val error = try {
                presentation.await()
                fail("old-owner presentation should be superseded")
                error("unreachable")
            } catch (error: ExperiencePresentationException) {
                error
            }
            assertEquals(ExperiencePresentationException.Reason.SUPERSEDED, error.reason)
        } finally {
            presentation.cancelAndJoin()
        }
    }

    @Test
    fun identityShutdownRejectsSameOwnerPresentationQueuedBeforeTeardown() = runTest {
        val firstAcquisitionStarted = CompletableDeferred<Unit>()
        val continueFirstAcquisition = CompletableDeferred<Unit>()
        val launched = mutableListOf<String>()
        var acquisitionCount = 0
        val service = service(
            launch = launched::add,
            acquire = { admitted ->
                acquisitionCount += 1
                if (acquisitionCount == 1) {
                    firstAcquisitionStarted.complete(Unit)
                    continueFirstAcquisition.await()
                }
                acquired(
                    admitted.release.identity.experienceId,
                    admitted.release.identity.experienceVersionId,
                    Lease(),
                )
            },
        )
        val mutexHolder = async(SupervisorJob()) {
            service.present("v1", "journey-7", "customer-2")
        }

        firstAcquisitionStarted.await()
        val queuedOldOwner = async(SupervisorJob()) {
            service.present("v1", "journey-7", "customer-1")
        }

        try {
            runCurrent()
            service.shutdownOwnedBy("customer-1")

            continueFirstAcquisition.complete(Unit)
            runCurrent()

            assertEquals("queued old-owner request launched after identity teardown", 1, launched.size)
            assertTrue("queued old-owner request must finish", queuedOldOwner.isCompleted)
            val error = try {
                queuedOldOwner.await()
                fail("queued old-owner request should be superseded")
                error("unreachable")
            } catch (error: ExperiencePresentationException) {
                error
            }
            assertEquals(ExperiencePresentationException.Reason.SUPERSEDED, error.reason)
        } finally {
            continueFirstAcquisition.complete(Unit)
            runCurrent()
            service.shutdownOwnedBy("customer-1")
            service.shutdownOwnedBy("customer-2")
            queuedOldOwner.cancelAndJoin()
            mutexHolder.cancelAndJoin()
        }
    }

    @Test
    fun identityShutdownLeavesDifferentOwnerPresentationAcquiring() = runTest {
        val acquisitionStarted = CompletableDeferred<Unit>()
        val continueAcquisition = CompletableDeferred<Unit>()
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            launch = launched::add,
            acquire = {
                acquisitionStarted.complete(Unit)
                continueAcquisition.await()
                acquired("exp-1", "v1", lease)
            },
        )
        val presentation = async(SupervisorJob()) {
            service.present("v1", "journey-7", "customer-2")
        }

        try {
            acquisitionStarted.await()
            service.shutdownOwnedBy("customer-1")

            continueAcquisition.complete(Unit)
            runCurrent()

            PresentationRegistry.reportFirstFrame(launched.single())
            assertEquals("v1", presentation.await().experienceVersion)
            assertFalse("different-owner acquisition was rejected", lease.closed.get())
        } finally {
            continueAcquisition.complete(Unit)
            runCurrent()
            service.shutdownOwnedBy("customer-2")
            presentation.cancelAndJoin()
        }
        assertTrue(lease.closed.get())
    }

    @Test
    fun admittedHostDismissalKeepsItsOwnerAfterIdentityChanges() = runTest {
        val launched = mutableListOf<String>()
        val outcomes = mutableListOf<PresentationOutcome>()
        var currentDistinctId = "customer-1"
        val service = service(
            launch = launched::add,
            markOutcomeInMemory = { outcome ->
                val admitted = outcome.ownerDistinctId == currentDistinctId &&
                    outcome.initiatingDistinctId == currentDistinctId
                currentDistinctId = "customer-2"
                admitted
            },
            reportOutcome = { outcome ->
                outcomes += outcome
                true
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        service.dismissFromHost("customer-1")
        runCurrent()

        assertEquals("customer-1", outcomes.single().ownerDistinctId)
        assertEquals("customer-1", outcomes.single().initiatingDistinctId)
    }

    @Test
    fun hostDismissalSelectionWinsACompetingUserDismissal() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val semanticStarted = CompletableDeferred<Unit>()
        val finishSemantic = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<CloseReason>()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reportOutcome = { outcome ->
                outcomes += outcome.reason
                semanticStarted.complete(Unit)
                finishSemantic.await()
                true
            },
        )
        val shown = async { service.present("v1", "journey-7") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        val hostDismissal = async { service.dismissFromHost("customer-1") }
        semanticStarted.await()
        service.dismiss(CloseReason.UserDismissed)
        runCurrent()

        assertTrue(lease.closed.get())
        assertEquals(listOf<CloseReason>(CloseReason.HostDismissed), outcomes)

        finishSemantic.complete(Unit)
        hostDismissal.await()
        assertEquals(listOf<CloseReason>(CloseReason.HostDismissed), outcomes)
    }

    @Test
    fun hostDismissalTeardownSelectionSurvivesARacingFirstFrameFailure() = runTest {
        val launched = mutableListOf<String>()
        val failure = IllegalStateException("first frame failed")
        val service = service(
            launch = launched::add,
            markOutcomeInMemory = {
                PresentationRegistry.reportFailure(launched.single(), failure)
                true
            },
        )
        val presentation = async(SupervisorJob()) { service.present("v1", ownerDistinctId = "customer-1") }
        runCurrent()
        val presentationId = launched.single()
        val activity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(activity, "presentationId", presentationId)
        assertTrue(PresentationRegistry.attach(presentationId, activity))

        val dismissal = async { service.dismissFromHost("customer-1") }
        runCurrent()

        assertFalse("dismissal completed before Activity teardown", dismissal.isCompleted)
        assertEquals(CloseReason.HostDismissed, activity.claimedCloseReason())
        assertTrue("Activity finish was not delivered", activity.isFinishing)
        assertTrue("registry completed before Activity teardown", PresentationRegistry.resolve(presentationId) != null)

        NuxieExperienceActivity::class.java
            .getDeclaredMethod("onDestroy")
            .apply { isAccessible = true }
            .invoke(activity)

        dismissal.await()
        assertEquals(null, PresentationRegistry.resolve(presentationId))
        val error = try {
            presentation.await()
            fail("first-frame failure should fail presentation")
            error("unreachable")
        } catch (error: ExperiencePresentationException) {
            error
        }
        assertEquals(ExperiencePresentationException.Reason.SUPERSEDED, error.reason)
    }

    @Test
    fun hostDismissalWithoutAPresentationIsANoop() = runTest {
        var semanticCalls = 0
        val service = service(
            reportOutcome = {
                semanticCalls += 1
                true
            },
        )

        service.dismissFromHost("customer-1")

        assertEquals(0, semanticCalls)
    }

    @Test
    fun signedDrawerShellIsPreparedForTheActivity() = runTest {
        val launched = mutableListOf<String>()
        val presentation = buildJsonObject {
            put("style", "drawer")
            put("backgroundColor", "#112233FF")
            put("drawer", buildJsonObject {
                put("edge", "trailing")
                put("extentRatio", 0.4)
                put("cornerRadius", 12)
                put("dismissible", false)
            })
        }
        val service = service(
            provider = PresentationReleaseProvider { release("exp-1", it, presentation) },
            launch = launched::add,
        )

        val shown = async { service.present("v1") }
        runCurrent()

        assertEquals(
            PresentationShell.Drawer(
                edge = PresentationShell.Drawer.Edge.TRAILING,
                extentRatio = 0.4f,
                cornerRadiusDp = 12f,
                dismissible = false,
            ),
            PresentationRegistry.resolve(launched.single())?.shell,
        )
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()
    }

    @Test
    fun coldRecreatedActivityFinishesWhenProcessLocalStateIsAbsent() {
        val presentationId = "process-death"
        val intent = Intent(
            RuntimeEnvironment.getApplication(),
            NuxieExperienceActivity::class.java,
        ).putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, presentationId)

        val activity = Robolectric.buildActivity(NuxieExperienceActivity::class.java, intent)
            .create(Bundle())
            .get()

        assertTrue(activity.isFinishing)
        assertEquals(null, PresentationRegistry.resolve(presentationId))
    }

    @Test
    fun savedStateWithLiveRegistryEntryIsSameProcessRecreation() {
        val presentationId = "configuration-change"
        PresentationRegistry.register(
            id = presentationId,
            content = PreparedPresentation(
                File("does-not-exist"),
                null,
                0,
                PresentationShell.FullScreen,
            ),
            onFirstFrame = {},
            onFailure = {},
            onDismissed = {},
        )

        assertFalse(
            NuxieExperienceActivity.isColdRecreation(Bundle(), presentationId),
        )
        PresentationRegistry.clearForTesting()
        assertTrue(
            NuxieExperienceActivity.isColdRecreation(Bundle(), presentationId),
        )
    }

    @Test
    fun dismissalBetweenConfigurationInstancesEndsPresentation() {
        val presentationId = "configuration-handoff"
        val dismissed = mutableListOf<CloseReason>()
        val oldActivity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        PresentationRegistry.register(
            id = presentationId,
            content = PreparedPresentation(
                File("does-not-exist"),
                null,
                0,
                PresentationShell.FullScreen,
            ),
            onFirstFrame = {},
            onFailure = {},
            onDismissed = dismissed::add,
        )
        assertTrue(PresentationRegistry.attach(presentationId, oldActivity))

        PresentationRegistry.detach(presentationId, oldActivity)
        PresentationRegistry.dismiss(presentationId, CloseReason.GoalMet)

        assertEquals(listOf<CloseReason>(CloseReason.GoalMet), dismissed)
        assertEquals(null, PresentationRegistry.resolve(presentationId))
    }

    @Test
    fun claimedReasonIsPublishedAtomicallyWithConfigurationDetach() {
        val presentationId = "configuration-terminal"
        val dismissed = mutableListOf<CloseReason>()
        val oldActivity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        PresentationRegistry.register(
            id = presentationId,
            content = PreparedPresentation(
                File("does-not-exist"),
                null,
                0,
                PresentationShell.FullScreen,
            ),
            onFirstFrame = {},
            onFailure = {},
            onDismissed = dismissed::add,
        )
        assertTrue(PresentationRegistry.attach(presentationId, oldActivity))
        assertTrue(oldActivity.claimFromService(CloseReason.GoalMet))

        PresentationRegistry.detach(presentationId, oldActivity)
        PresentationRegistry.dismiss(presentationId, CloseReason.Timeout)

        assertEquals(listOf<CloseReason>(CloseReason.GoalMet), dismissed)
        assertEquals(null, PresentationRegistry.resolve(presentationId))
    }

    @Test
    fun recreatedActivityIsRejectedAfterDismissalSelectsOldInstance() = runTest {
        val launched = mutableListOf<String>()
        val service = service(launch = launched::add)
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        val presentationId = launched.single()
        PresentationRegistry.reportFirstFrame(presentationId)
        shown.await()

        val oldActivity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(oldActivity, "presentationId", presentationId)
        assertTrue(PresentationRegistry.attach(presentationId, oldActivity))

        val dismissal = async { service.dismissFromHost("customer-1") }
        runCurrent()
        assertEquals(CloseReason.HostDismissed, oldActivity.claimedCloseReason())
        assertFalse("dismissal completed before Activity teardown", dismissal.isCompleted)

        val attachCandidate = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        assertFalse(
            "recreated Activity replaced the dismissal-selected instance",
            PresentationRegistry.attach(presentationId, attachCandidate),
        )
        val recreationIntent = Intent(
            RuntimeEnvironment.getApplication(),
            NuxieExperienceActivity::class.java,
        ).putExtra(NuxieExperienceActivity.EXTRA_PRESENTATION_ID, presentationId)
        val recreatedActivity = Robolectric.buildActivity(
            NuxieExperienceActivity::class.java,
            recreationIntent,
        ).create(Bundle()).get()
        assertTrue("recreated Activity remained visible", recreatedActivity.isFinishing)

        NuxieExperienceActivity::class.java
            .getDeclaredMethod("onDestroy")
            .apply { isAccessible = true }
            .invoke(oldActivity)
        dismissal.await()

        assertEquals(null, PresentationRegistry.resolve(presentationId))
    }

    @Test
    fun firstTerminalCloseReasonWinsAtomically() {
        val reported = mutableListOf<CloseReason>()
        val claim = TerminalCloseClaim(reported::add)

        assertTrue(claim.tryClaim(CloseReason.UserDismissed))
        assertFalse(claim.tryClaim(CloseReason.GoalMet))
        assertEquals(CloseReason.UserDismissed, claim.reason)
        assertEquals(emptyList<CloseReason>(), reported)

        claim.reportAtTeardown(isChangingConfigurations = false)

        assertEquals(listOf<CloseReason>(CloseReason.UserDismissed), reported)
    }

    @Test
    fun activityOnDestroyReturnsPromptlyWhenRuntimeLaneIsStuck() {
        val lane = NuxieRuntimeLane()
        val laneBlocked = CountDownLatch(1)
        val unblockLane = CountDownLatch(1)
        assertTrue(lane.enqueue {
            laneBlocked.countDown()
            check(unblockLane.await(5, TimeUnit.SECONDS)) { "runtime lane was not released" }
        })
        assertTrue(laneBlocked.await(2, TimeUnit.SECONDS))

        val activity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(activity, "lane", lane)

        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val startedAtNanos = System.nanoTime()
        try {
            NuxieExperienceActivity::class.java
                .getDeclaredMethod("onDestroy")
                .apply { isAccessible = true }
                .invoke(activity)
        } finally {
            unblockLane.countDown()
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

        assertTrue("onDestroy blocked on the runtime lane for ${elapsedMillis}ms", elapsedMillis < 250)
        assertTrue("Runtime lane did not finish", lane.awaitQuiescence(2_000))
    }

    @Test
    fun hostDismissalCompletesOnlyAfterQueuedNativeHandleRelease() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        val presentationId = launched.single()
        PresentationRegistry.reportFirstFrame(presentationId)
        shown.await()

        val lane = NuxieRuntimeLane()
        val laneBlocked = CountDownLatch(1)
        val unblockLane = CountDownLatch(1)
        val releaseThread = AtomicReference<String?>()
        val file = NuxieRuntimeFile(
            handle = 42L,
            native = object : NuxieTypedRuntimeNative {
                override fun freeFile(handle: Long) {
                    assertEquals(42L, handle)
                    releaseThread.set(Thread.currentThread().name)
                }
            },
        )
        assertTrue(lane.enqueue {
            laneBlocked.countDown()
            check(unblockLane.await(5, TimeUnit.SECONDS)) { "runtime lane was not released" }
        })
        assertTrue(laneBlocked.await(2, TimeUnit.SECONDS))
        assertTrue(lane.enqueue(file::close))

        val activity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(activity, "presentationId", presentationId)
        setActivityField(activity, "lane", lane)
        assertTrue(PresentationRegistry.attach(presentationId, activity))

        val dismissal = async { service.dismissFromHost("customer-1") }
        runCurrent()
        assertEquals(CloseReason.HostDismissed, activity.claimedCloseReason())

        try {
            NuxieExperienceActivity::class.java
                .getDeclaredMethod("onDestroy")
                .apply { isAccessible = true }
                .invoke(activity)
            runCurrent()

            assertFalse("dismiss completed before native release", dismissal.isCompleted)
            assertFalse("acquisition lease closed before native release", lease.closed.get())
            assertEquals(null, releaseThread.get())
        } finally {
            unblockLane.countDown()
        }

        dismissal.await()
        assertTrue(lease.closed.get())
        assertEquals("com.nuxie.runtime.android.native", releaseThread.get())
    }

    @Test
    fun hostDismissalAfterRecreationWaitsForBothActivitiesNativeHandleRelease() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        val presentationId = launched.single()
        PresentationRegistry.reportFirstFrame(presentationId)
        shown.await()

        val oldLane = NuxieRuntimeLane()
        val oldLaneBlocked = CountDownLatch(1)
        val unblockOldLane = CountDownLatch(1)
        val oldNativeReleased = AtomicBoolean(false)
        val oldFile = NuxieRuntimeFile(
            handle = 41L,
            native = object : NuxieTypedRuntimeNative {
                override fun freeFile(handle: Long) {
                    assertEquals(41L, handle)
                    oldNativeReleased.set(true)
                }
            },
        )
        assertTrue(oldLane.enqueue {
            oldLaneBlocked.countDown()
            check(unblockOldLane.await(5, TimeUnit.SECONDS)) { "old runtime lane was not released" }
        })
        assertTrue(oldLaneBlocked.await(2, TimeUnit.SECONDS))
        assertTrue(oldLane.enqueue(oldFile::close))

        val oldActivity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(oldActivity, "presentationId", presentationId)
        setActivityField(oldActivity, "lane", oldLane)
        setChangingConfigurations(oldActivity)
        assertTrue(PresentationRegistry.attach(presentationId, oldActivity))
        invokeOnDestroy(oldActivity)

        val newLane = NuxieRuntimeLane()
        val newLaneBlocked = CountDownLatch(1)
        val unblockNewLane = CountDownLatch(1)
        val newNativeReleased = AtomicBoolean(false)
        val newFile = NuxieRuntimeFile(
            handle = 42L,
            native = object : NuxieTypedRuntimeNative {
                override fun freeFile(handle: Long) {
                    assertEquals(42L, handle)
                    newNativeReleased.set(true)
                }
            },
        )
        assertTrue(newLane.enqueue {
            newLaneBlocked.countDown()
            check(unblockNewLane.await(5, TimeUnit.SECONDS)) { "new runtime lane was not released" }
        })
        assertTrue(newLaneBlocked.await(2, TimeUnit.SECONDS))
        assertTrue(newLane.enqueue(newFile::close))

        val recreatedActivity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(recreatedActivity, "presentationId", presentationId)
        setActivityField(recreatedActivity, "lane", newLane)
        assertTrue(PresentationRegistry.attach(presentationId, recreatedActivity))

        val dismissal = async { service.dismissFromHost("customer-1") }
        runCurrent()
        assertEquals(CloseReason.HostDismissed, recreatedActivity.claimedCloseReason())
        invokeOnDestroy(recreatedActivity)

        try {
            unblockNewLane.countDown()
            assertTrue("recreated runtime lane did not finish", newLane.awaitQuiescence(2_000))
            runCurrent()

            assertTrue("recreated Activity did not release its native handle", newNativeReleased.get())
            assertFalse("old Activity released before its lane was unblocked", oldNativeReleased.get())
            assertFalse("dismissal ignored the old Activity teardown", dismissal.isCompleted)
            assertFalse("acquisition lease closed before both Activities tore down", lease.closed.get())
        } finally {
            unblockOldLane.countDown()
            unblockNewLane.countDown()
        }

        dismissal.await()
        assertTrue("old Activity did not release its native handle", oldNativeReleased.get())
        assertTrue(lease.closed.get())
        assertEquals(null, PresentationRegistry.resolve(presentationId))
    }

    @Test
    fun cancellingHostDismissalOnlyDetachesCallerWhileBookkeepingCompletes() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        val bookkeepingStarted = CompletableDeferred<PresentationOutcome>()
        val allowBookkeepingToComplete = CompletableDeferred<Unit>()
        val bookkeepingCompleted = CompletableDeferred<Unit>()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reportOutcome = { outcome ->
                bookkeepingStarted.complete(outcome)
                allowBookkeepingToComplete.await()
                bookkeepingCompleted.complete(Unit)
            },
        )
        val shown = async { service.present("v1", "journey-7", "customer-1") }
        runCurrent()
        val presentationId = launched.single()
        PresentationRegistry.reportFirstFrame(presentationId)
        shown.await()

        val lane = NuxieRuntimeLane()
        val laneBlocked = CountDownLatch(1)
        val unblockLane = CountDownLatch(1)
        val file = NuxieRuntimeFile(
            handle = 42L,
            native = object : NuxieTypedRuntimeNative {
                override fun freeFile(handle: Long) = Unit
            },
        )
        assertTrue(lane.enqueue {
            laneBlocked.countDown()
            check(unblockLane.await(5, TimeUnit.SECONDS)) { "runtime lane was not released" }
        })
        assertTrue(laneBlocked.await(2, TimeUnit.SECONDS))
        assertTrue(lane.enqueue(file::close))

        val activity = Robolectric.buildActivity(NuxieExperienceActivity::class.java).get()
        setActivityField(activity, "presentationId", presentationId)
        setActivityField(activity, "lane", lane)
        assertTrue(PresentationRegistry.attach(presentationId, activity))

        val dismissal = async { service.dismissFromHost("customer-1") }
        runCurrent()
        val outcome = bookkeepingStarted.await()
        assertEquals(CloseReason.HostDismissed, outcome.reason)
        assertEquals("customer-1", outcome.ownerDistinctId)
        assertEquals("customer-1", outcome.initiatingDistinctId)

        NuxieExperienceActivity::class.java
            .getDeclaredMethod("onDestroy")
            .apply { isAccessible = true }
            .invoke(activity)
        runCurrent()

        try {
            assertFalse("dismissal completed before native release", dismissal.isCompleted)
            assertFalse("acquisition lease closed before native release", lease.closed.get())

            dismissal.cancelAndJoin()
            assertTrue("dismissal caller was not cancelled", dismissal.isCancelled)
            assertFalse("caller cancellation completed bookkeeping", bookkeepingCompleted.isCompleted)
            assertFalse("caller cancellation completed teardown", lease.closed.get())

            allowBookkeepingToComplete.complete(Unit)
            runCurrent()

            assertTrue(
                "caller cancellation cancelled detached bookkeeping",
                bookkeepingCompleted.isCompleted,
            )
            assertFalse("bookkeeping completion bypassed native teardown", lease.closed.get())
        } finally {
            unblockLane.countDown()
        }

        assertTrue("Runtime lane did not finish", lane.awaitQuiescence(2_000))
        assertTrue(lease.closed.get())
        assertEquals(null, PresentationRegistry.resolve(presentationId))
    }

    private fun setActivityField(
        activity: NuxieExperienceActivity,
        name: String,
        value: Any?,
    ) {
        NuxieExperienceActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }

    private fun setChangingConfigurations(activity: Activity) {
        Activity::class.java.getDeclaredField("mChangingConfigurations").apply {
            isAccessible = true
            setBoolean(activity, true)
        }
    }

    private fun invokeOnDestroy(activity: NuxieExperienceActivity) {
        NuxieExperienceActivity::class.java
            .getDeclaredMethod("onDestroy")
            .apply { isAccessible = true }
            .invoke(activity)
    }

    private fun service(
        runtimeAvailable: Boolean = true,
        provider: PresentationReleaseProvider = PresentationReleaseProvider { release("exp-1", it) },
        acquire: suspend (PresentationRelease) -> AcquiredRelease = { admitted ->
            acquired(
                admitted.release.identity.experienceId,
                admitted.release.identity.experienceVersionId,
                Lease(),
            )
        },
        emit: (String, Map<String, Any?>, String?) -> Unit = { _, _, _ -> },
        launch: (String) -> Unit = {},
        markOutcomeInMemory: suspend (PresentationOutcome) -> Boolean = { true },
        reportOutcome: suspend (PresentationOutcome) -> Unit = {},
        firstFrameTimeoutMillis: Long = 30_000,
        beforeHostSemanticClaimForTesting: () -> Unit = {},
    ) = ExperiencePresentationService(
        releases = provider,
        acquire = acquire,
        emit = emit,
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        runtimeAvailable = { runtimeAvailable },
        launch = launch,
        markOutcomeInMemory = markOutcomeInMemory,
        reportOutcome = reportOutcome,
        firstFrameTimeoutMillis = firstFrameTimeoutMillis,
        beforeHostSemanticClaimForTesting = beforeHostSemanticClaimForTesting,
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

    private fun release(
        experienceId: String,
        version: String,
        presentation: JsonObject = buildJsonObject { put("backgroundColor", "#112233") },
    ): PresentationRelease {
        val identity = ExperienceReleaseIdentity(
            appId = "app",
            environment = "development",
            experienceId = experienceId,
            experienceVersionId = version,
            buildId = "build-$version",
            versionNumber = 1,
            publishedAt = "2026-08-24T00:00:00Z",
            publishedAtSeq = 1,
        )
        val descriptor = buildJsonObject {
            put("render", buildJsonObject {
                put("assets", buildJsonArray {})
            })
            put("screenBehaviors", buildJsonArray {})
            put("presentation", presentation)
        }
        return PresentationRelease(
            AuthenticatedRelease("key", "sha", identity, ByteArray(0), descriptor, 1),
            Delivery("https://render.example/", "https://assets.example/"),
        )
    }

    private fun acquired(
        experienceId: String,
        version: String,
        lease: Lease,
        extraArtifacts: Map<String, File> = emptyMap(),
    ): AcquiredRelease {
        val file = File.createTempFile("presentation-", ".riv").apply { writeBytes(byteArrayOf(1)) }
        return AcquiredRelease(
            identity = ExperienceReleaseIdentity(
                "app", "development", experienceId, version, "build", 1, "now", 1,
            ),
            artifactsByKey = mapOf("renders/main.riv" to file) + extraArtifacts,
            rivFile = file,
            protection = lease,
        )
    }
}
