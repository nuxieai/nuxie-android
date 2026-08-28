package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.Delivery
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.presentation.ExperiencePresentationService
import ai.nuxie.sdk.presentation.PresentationRegistry
import ai.nuxie.sdk.presentation.PresentationRelease
import ai.nuxie.sdk.presentation.PresentationReleaseProvider
import ai.nuxie.sdk.testsupport.FakeTransport
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieIdentityPresentationTest {
    private class Lease : Closeable {
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
        }
    }

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
        PresentationRegistry.clearForTesting()
    }

    @Test
    fun identifyShutsDownPresentationOwnedByPreviousIdentity() = runBlocking {
        assertIdentityTransitionShutsDownPresentation {
            Nuxie.identify("identified-customer")
        }
    }

    @Test
    fun resetShutsDownPresentationOwnedByPreviousIdentity() = runBlocking {
        assertIdentityTransitionShutsDownPresentation(initiallyIdentified = true) {
            Nuxie.reset()
        }
    }

    private suspend fun assertIdentityTransitionShutsDownPresentation(
        initiallyIdentified: Boolean = false,
        transition: () -> Unit,
    ) {
        val launched = mutableListOf<String>()
        val emitted = mutableListOf<String>()
        var semanticOutcomes = 0
        val lease = Lease()
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = FakeTransport(),
            presentationFactory = NuxieCore.PresentationFactory { _, _, releaseReservation ->
                ExperiencePresentationService(
                    releases = PresentationReleaseProvider { release(it) },
                    acquire = { acquired(it, lease) },
                    emit = { name, _, _ -> emitted += name },
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    runtimeAvailable = { true },
                    launch = launched::add,
                    reportOutcome = {
                        semanticOutcomes += 1
                        true
                    },
                    reserveHostDismissal = { outcome ->
                        outcome.ownerDistinctId == outcome.initiatingDistinctId
                    },
                    releaseHostDismissalReservation = releaseReservation,
                )
            },
        )
        Nuxie.setup(
            RuntimeEnvironment.getApplication(),
            NuxieConfiguration("pk_test_identity_presentation"),
        )
        val core = requireNotNull(Nuxie.core)
        if (initiallyIdentified) {
            Nuxie.identify("identified-customer")
            core.userTransitions.drain()
        }
        val ownerDistinctId = Nuxie.distinctId
        val shown = CoroutineScope(Dispatchers.Unconfined).async(start = CoroutineStart.UNDISPATCHED) {
            core.presentations.present("version-1", "journey-1", ownerDistinctId)
        }
        PresentationRegistry.reportFirstFrame(launched.single())
        shown.await()

        transition()
        core.userTransitions.drain()
        Nuxie.dismiss()

        assertTrue("identity transition must dismiss the old presentation", lease.closed.get())
        assertEquals(listOf("\$experience_shown"), emitted)
        assertEquals(0, semanticOutcomes)
    }

    private fun release(version: String): PresentationRelease {
        val identity = ExperienceReleaseIdentity(
            appId = "app",
            environment = "development",
            experienceId = "experience-1",
            experienceVersionId = version,
            buildId = "build-$version",
            versionNumber = 1,
            publishedAt = "2026-08-28T00:00:00Z",
            publishedAtSeq = 1,
        )
        val descriptor = buildJsonObject {
            put("render", buildJsonObject { put("assets", buildJsonArray {}) })
            put("screenBehaviors", buildJsonArray {})
            put("presentation", buildJsonObject { put("backgroundColor", "#112233") })
        }
        return PresentationRelease(
            AuthenticatedRelease("key", "sha", identity, ByteArray(0), descriptor, 1),
            Delivery("https://render.example/", "https://assets.example/"),
        )
    }

    private fun acquired(release: PresentationRelease, lease: Lease): AcquiredRelease {
        val file = File.createTempFile("identity-presentation-", ".riv").apply {
            writeBytes(byteArrayOf(1))
            deleteOnExit()
        }
        return AcquiredRelease(
            identity = release.release.identity,
            artifactsByKey = mapOf("renders/main.riv" to file),
            rivFile = file,
            protection = lease,
        )
    }
}
