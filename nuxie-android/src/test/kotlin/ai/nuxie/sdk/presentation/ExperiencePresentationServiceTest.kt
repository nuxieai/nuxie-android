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

        override fun close() {
            closed.set(true)
        }
    }

    private class AttachedHost : PresentationActivityHandle {
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
        assertTrue(launched.isEmpty())
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
    }

    @Test
    fun `completed dismissal prevents same Journey destination acquisition`() = runTest {
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
                    acquired(release.identity, Lease())
                },
                onOutcome = {},
            )
        }

        assertEquals(ExperiencePresentationException.Reason.JOURNEY_COMPLETED, error.reason)
        assertFalse(secondAcquired)
        assertTrue(firstLease.closed.get())
        assertEquals(1, launched.size)
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

    private fun renderedJourneyRelease(): AuthenticatedJourneyRelease {
        val fixture = Json.parseToJsonElement(
            FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText(),
        ).jsonObject
        val entry = fixture.getValue("renderedEntry").jsonObject
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
