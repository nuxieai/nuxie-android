package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.Delivery
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.runtime.NuxieRuntimeFile
import ai.nuxie.sdk.runtime.NuxieRuntimeLane
import ai.nuxie.sdk.runtime.NuxieTypedRuntimeNative
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
    private data class Emitted(val name: String, val properties: Map<String, Any?>)

    private class Lease : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() {
            closed.set(true)
        }
    }

    private class BlockingClaimActivity : PresentationActivityHandle {
        private val terminal = TerminalCloseClaim()
        val releaseStarted = CountDownLatch(1)
        val continueRelease = CountDownLatch(1)

        override fun claimFromService(reason: CloseReason): Boolean = terminal.tryClaim(reason)

        override fun claimedCloseReason(): CloseReason? = terminal.reason

        override fun releaseServiceClaim(reason: CloseReason): Boolean {
            releaseStarted.countDown()
            check(continueRelease.await(5, TimeUnit.SECONDS)) {
                "reservation release was not allowed to continue"
            }
            return terminal.release(reason)
        }

        override fun finishAfterServiceClaim() = Unit
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
    fun hostDismissalAwaitsSemanticCompletionBeforePresentationTeardown() = runTest {
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

        val dismissal = async { service.dismissFromHost("customer-1") }
        runCurrent()

        val pendingOutcome = semanticStarted.await()
        assertEquals(CloseReason.HostDismissed, pendingOutcome.reason)
        assertEquals("customer-1", pendingOutcome.ownerDistinctId)
        assertEquals("customer-1", pendingOutcome.initiatingDistinctId)
        assertFalse(dismissal.isCompleted)
        assertFalse(lease.closed.get())

        releaseSemanticCompletion.complete(Unit)
        dismissal.await()
        assertTrue(lease.closed.get())
    }

    @Test
    fun hostDismissalAfterIdentityChangedCannotClaimTheOldPresentation() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        var semanticCalls = 0
        val reservations = mutableListOf<PresentationOutcome>()
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reserveHostDismissal = { outcome ->
                reservations += outcome
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

        assertEquals("customer-1", reservations.single().ownerDistinctId)
        assertEquals("customer-2", reservations.single().initiatingDistinctId)
        assertEquals(0, semanticCalls)
        assertFalse(lease.closed.get())
        assertTrue(PresentationRegistry.resolve(launched.single()) != null)
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
    fun admittedHostDismissalKeepsItsOwnerAfterIdentityChanges() = runTest {
        val launched = mutableListOf<String>()
        val reservationEstablished = CompletableDeferred<Unit>()
        val continueAfterIdentityChange = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<PresentationOutcome>()
        var currentDistinctId = "customer-1"
        val service = service(
            launch = launched::add,
            reserveHostDismissal = { outcome ->
                val admitted = outcome.ownerDistinctId == currentDistinctId &&
                    outcome.initiatingDistinctId == currentDistinctId
                reservationEstablished.complete(Unit)
                continueAfterIdentityChange.await()
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

        val dismissal = async { service.dismissFromHost("customer-1") }
        reservationEstablished.await()
        currentDistinctId = "customer-2"
        continueAfterIdentityChange.complete(Unit)
        dismissal.await()

        assertEquals("customer-1", outcomes.single().ownerDistinctId)
        assertEquals("customer-1", outcomes.single().initiatingDistinctId)
    }

    @Test
    fun reentrantHostDismissalsJoinTheSameAttempt() = runTest {
        val launched = mutableListOf<String>()
        val semanticStarted = CompletableDeferred<Unit>()
        val releaseSemanticCompletion = CompletableDeferred<Unit>()
        var semanticCalls = 0
        val service = service(
            launch = launched::add,
            reportOutcome = {
                semanticCalls += 1
                semanticStarted.complete(Unit)
                releaseSemanticCompletion.await()
                true
            },
        )
        val shown = async { service.present("v1", "journey-7") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        val first = async { service.dismissFromHost("customer-1") }
        semanticStarted.await()
        val second = async { service.dismissFromHost("customer-1") }
        runCurrent()

        assertEquals(1, semanticCalls)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        releaseSemanticCompletion.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, semanticCalls)
    }

    @Test
    fun failedHostTerminalizationKeepsThePresentationForAnExplicitRetry() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        var attempts = 0
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reportOutcome = {
                attempts += 1
                attempts > 1
            },
        )
        val shown = async { service.present("v1", "journey-7") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        service.dismissFromHost("customer-1")
        assertFalse(lease.closed.get())
        assertTrue(PresentationRegistry.resolve(launched.single()) != null)

        service.dismissFromHost("customer-1")
        assertTrue(lease.closed.get())
        assertEquals(2, attempts)
    }

    @Test
    fun failedHostTerminalizationKeepsRetryOwnershipThroughActivityTeardown() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        var terminalizationAttempts = 0
        var journeyReservations = 0
        var journeyReservationReleases = 0
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reserveHostDismissal = {
                journeyReservations += 1
                true
            },
            releaseHostDismissalReservation = {
                journeyReservationReleases += 1
            },
            reportOutcome = {
                terminalizationAttempts += 1
                terminalizationAttempts > 1
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

        service.dismissFromHost("customer-1")
        NuxieExperienceActivity::class.java
            .getDeclaredMethod("onDestroy")
            .apply { isAccessible = true }
            .invoke(activity)

        assertFalse("retry ownership must reject competing teardown", lease.closed.get())
        assertTrue(PresentationRegistry.resolve(presentationId) != null)
        assertEquals(1, journeyReservationReleases)

        service.dismissFromHost("customer-1")

        assertTrue(lease.closed.get())
        assertEquals(2, journeyReservations)
        assertEquals(2, terminalizationAttempts)
        assertEquals(1, journeyReservationReleases)
    }

    @Test
    fun hostDismissalReservationWinsACompetingUserDismissal() = runTest {
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

        assertFalse(lease.closed.get())
        assertEquals(listOf<CloseReason>(CloseReason.HostDismissed), outcomes)

        finishSemantic.complete(Unit)
        hostDismissal.await()
        assertTrue(lease.closed.get())
        assertEquals(listOf<CloseReason>(CloseReason.HostDismissed), outcomes)
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
    fun reservationReleaseRacingDetachDoesNotPublishHostDismissedWithoutAdmission() {
        val presentationId = "rejected-host-dismissal"
        val dismissed = mutableListOf<CloseReason>()
        val activity = BlockingClaimActivity()
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
        assertTrue(PresentationRegistry.attach(presentationId, activity))
        assertTrue(
            PresentationRegistry.reserveDismissal(presentationId, CloseReason.HostDismissed),
        )

        val releaseFailure = AtomicReference<Throwable?>()
        val releaseThread = Thread {
            runCatching {
                PresentationRegistry.releaseDismissalReservation(
                    presentationId,
                    CloseReason.HostDismissed,
                )
            }.exceptionOrNull()?.let(releaseFailure::set)
        }
        releaseThread.start()
        assertTrue(activity.releaseStarted.await(2, TimeUnit.SECONDS))

        val detachFailure = AtomicReference<Throwable?>()
        val detachFinished = CountDownLatch(1)
        val detachThread = Thread {
            try {
                PresentationRegistry.detach(presentationId, activity)
            } catch (error: Throwable) {
                detachFailure.set(error)
            } finally {
                detachFinished.countDown()
            }
        }
        detachThread.start()
        val contentionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (detachFinished.count != 0L &&
            detachThread.state != Thread.State.BLOCKED &&
            System.nanoTime() < contentionDeadline
        ) {
            Thread.yield()
        }

        activity.continueRelease.countDown()
        releaseThread.join(2_000)
        detachThread.join(2_000)

        assertFalse("reservation release did not finish", releaseThread.isAlive)
        assertFalse("detach did not finish", detachThread.isAlive)
        assertEquals(null, releaseFailure.get())
        assertEquals(null, detachFailure.get())
        assertEquals(emptyList<CloseReason>(), dismissed)
        assertEquals(null, activity.claimedCloseReason())
        assertTrue(PresentationRegistry.resolve(presentationId) != null)
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
    fun cancellingOneJoinedHostDismissalKeepsSharedAttemptUntilNativeHandleRelease() = runTest {
        val launched = mutableListOf<String>()
        val lease = Lease()
        var semanticCalls = 0
        val service = service(
            launch = launched::add,
            acquire = { acquired("exp-1", "v1", lease) },
            reportOutcome = {
                semanticCalls += 1
                true
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

        val cancelled = async { service.dismissFromHost("customer-1") }
        runCurrent()
        val survivor = async { service.dismissFromHost("customer-1") }
        runCurrent()
        NuxieExperienceActivity::class.java
            .getDeclaredMethod("onDestroy")
            .apply { isAccessible = true }
            .invoke(activity)
        runCurrent()

        assertFalse(cancelled.isCompleted)
        assertFalse(survivor.isCompleted)
        cancelled.cancelAndJoin()
        runCurrent()
        val fresh = async { service.dismissFromHost("customer-1") }
        runCurrent()

        try {
            assertFalse("joined dismissal completed before native release", survivor.isCompleted)
            assertFalse("fresh dismissal completed before native release", fresh.isCompleted)
            assertEquals("fresh dismissal started a second attempt", 1, semanticCalls)
            assertFalse("acquisition lease closed before native release", lease.closed.get())
        } finally {
            unblockLane.countDown()
        }

        survivor.await()
        fresh.await()
        assertTrue(lease.closed.get())
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
        reportOutcome: suspend (PresentationOutcome) -> Boolean = { true },
        reserveHostDismissal: suspend (PresentationOutcome) -> Boolean = { true },
        releaseHostDismissalReservation: suspend (PresentationOutcome) -> Unit = {},
        firstFrameTimeoutMillis: Long = 30_000,
    ) = ExperiencePresentationService(
        releases = provider,
        acquire = acquire,
        emit = emit,
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        runtimeAvailable = { runtimeAvailable },
        launch = launch,
        reportOutcome = reportOutcome,
        reserveHostDismissal = reserveHostDismissal,
        releaseHostDismissalReservation = releaseHostDismissalReservation,
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
