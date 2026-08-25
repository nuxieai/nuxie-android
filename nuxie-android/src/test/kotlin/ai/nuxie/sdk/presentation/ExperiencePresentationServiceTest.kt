package ai.nuxie.sdk.presentation

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.Delivery
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import android.content.Intent
import android.os.Bundle
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
            emit = { name, properties -> emitted += Emitted(name, properties) },
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
            reportOutcome = { outcomes += it },
        )
        val shown = async { service.present("v1", "journey-7") }
        runCurrent()
        PresentationRegistry.reportFirstFrame(launched.single())
        assertEquals("journey-7", shown.await().journeyId)

        service.dismiss(CloseReason.GoalMet)
        runCurrent()

        assertEquals("journey-7", outcomes.single().ref.journeyId)
        assertEquals(CloseReason.GoalMet, outcomes.single().reason)
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
        emit: (String, Map<String, Any?>) -> Unit = { _, _ -> },
        launch: (String) -> Unit = {},
        reportOutcome: suspend (PresentationOutcome) -> Unit = {},
        firstFrameTimeoutMillis: Long = 30_000,
    ) = ExperiencePresentationService(
        releases = provider,
        acquire = acquire,
        emit = emit,
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        runtimeAvailable = { runtimeAvailable },
        launch = launch,
        reportOutcome = reportOutcome,
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
