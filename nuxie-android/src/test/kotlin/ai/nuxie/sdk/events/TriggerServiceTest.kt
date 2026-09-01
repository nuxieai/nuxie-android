package ai.nuxie.sdk.events

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.FeatureAccessUpdate
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.TriggerDecision
import ai.nuxie.sdk.TriggerErrorCode
import ai.nuxie.sdk.TriggerUpdate
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.experiences.AcquiredRelease
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.Delivery
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.journey.JourneyEventNames
import ai.nuxie.sdk.journey.JourneyPlane
import ai.nuxie.sdk.journey.JourneyRun
import ai.nuxie.sdk.journey.JourneyRunState
import ai.nuxie.sdk.journey.JourneyStore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.presentation.CloseReason
import ai.nuxie.sdk.presentation.ExperiencePresentationService
import ai.nuxie.sdk.presentation.PresentationRegistry
import ai.nuxie.sdk.presentation.PresentationRelease
import ai.nuxie.sdk.presentation.PresentationReleaseProvider
import ai.nuxie.sdk.testsupport.FakeTransport
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TriggerServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun transportWithGate(gateJson: String?): FakeTransport = FakeTransport().apply {
        respond = { request ->
            when (request.url.path) {
                "/event" -> {
                    val payload = gateJson?.let { """{"status":"ok","payload":{"gate":$it}}""" }
                        ?: """{"status":"ok"}"""
                    HttpTransport.Response(200, payload.encodeToByteArray())
                }
                "/profile" -> HttpTransport.Response(200, """{"segments":[]}""".encodeToByteArray())
                else -> HttpTransport.Response(200, ByteArray(0))
            }
        }
    }

    private fun core(
        transport: FakeTransport,
        journeys: TriggerService.JourneyRouter? = null,
        features: TriggerService.FeatureGate? = null,
        presenter: TriggerService.ExperiencePresenter? = null,
        presentationFactory: NuxieCore.PresentationFactory? = null,
        beforeSend: ((NuxieEvent) -> NuxieEvent?)? = null,
    ): NuxieCore = NuxieCore(
        context = RuntimeEnvironment.getApplication(),
        apiKey = "pk_test_trigger",
        environment = NuxieEnvironment.DEVELOPMENT,
        logLevel = LogLevel.NONE,
        beforeSend = beforeSend,
        overrides = NuxieCore.Overrides(
            transport = transport,
            registerLifecycle = false,
            journeys = journeys,
            features = features,
            presenter = presenter ?: journeys?.takeIf { presentationFactory == null }?.let {
                TriggerService.ExperiencePresenter { version, journeyId ->
                    ExperienceRef("exp-1", version, journeyId)
                }
            },
            presentationFactory = presentationFactory,
        ),
    )

    private suspend fun collect(core: NuxieCore, event: String): List<TriggerUpdate> {
        val updates = mutableListOf<TriggerUpdate>()
        core.triggers.trigger(event, null) { updates.add(it) }
        return updates
    }

    @Test
    fun noGatePlanResolvesNoMatch() = runBlocking {
        val core = core(transportWithGate(null))
        val updates = collect(core, "moment")
        assertEquals(
            listOf<TriggerUpdate>(TriggerUpdate.Decision(TriggerDecision.NoMatch)),
            updates,
        )
        core.stop()
    }

    @Test
    fun allowGateResolvesAllowedImmediateAndMarksDelivered() = runBlocking {
        val transport = transportWithGate("""{"decision":"allow"}""")
        val core = core(transport)
        val updates = collect(core, "moment")
        assertEquals(
            listOf<TriggerUpdate>(TriggerUpdate.Decision(TriggerDecision.AllowedImmediate)),
            updates,
        )
        // Decision lane delivered the event: nothing pending for batch resend.
        assertTrue(core.store.pendingBatch(limit = 10).none { it.name == "moment" })
        core.stop()
    }

    @Test
    fun denyGateResolvesDeniedImmediate() = runBlocking {
        val core = core(transportWithGate("""{"decision":"deny"}"""))
        assertEquals(
            listOf<TriggerUpdate>(TriggerUpdate.Decision(TriggerDecision.DeniedImmediate)),
            collect(core, "moment"),
        )
        core.stop()
    }

    @Test
    fun showGateWithoutPresenterFailsTyped() = runBlocking {
        val core = core(transportWithGate("""{"decision":"show_flow","flowId":"ev-1"}"""))
        val updates = collect(core, "moment")
        val error = (updates.single() as TriggerUpdate.Error).error
        assertEquals(TriggerErrorCode.EXPERIENCE_PRESENT_FAILED, error.code)
        core.stop()
    }

    @Test
    fun shownExperienceIsTerminalForItsGateVersion() = runBlocking {
        val core = core(
            transportWithGate("""{"decision":"show_flow","flowId":"version-1"}"""),
            presenter = TriggerService.ExperiencePresenter { _, _ ->
                ExperienceRef("real-experience-id", "version-1", null)
            },
        )

        assertEquals(
            listOf<TriggerUpdate>(
                TriggerUpdate.Decision(
                    TriggerDecision.ExperienceShown(
                        ExperienceRef("real-experience-id", "version-1", null),
                    ),
                ),
            ),
            collect(core, "moment"),
        )
        core.stop()
    }

    @Test
    fun requireFeatureCacheOnlyWithoutAuthorityDenies() = runBlocking {
        val core = core(
            transportWithGate(
                """{"decision":"require_feature","featureId":"pro","policy":"cache_only"}""",
            ),
        )
        assertEquals(
            listOf<TriggerUpdate>(TriggerUpdate.FeatureAccess(FeatureAccessUpdate.Denied)),
            collect(core, "moment"),
        )
        core.stop()
    }

    @Test
    fun requireFeatureTimesOutTyped() = runBlocking {
        val core = core(
            transportWithGate(
                """{"decision":"require_feature","featureId":"pro","timeoutMs":150}""",
            ),
        )
        val updates = collect(core, "moment")
        assertEquals(
            TriggerUpdate.FeatureAccess(FeatureAccessUpdate.Pending),
            updates.first(),
        )
        val error = (updates.last() as TriggerUpdate.Error).error
        assertEquals(TriggerErrorCode.FEATURE_ACCESS_TIMEOUT, error.code)
        core.stop()
    }

    @Test
    fun requireFeatureAllowedFromGateWhenAuthorityGrants() = runBlocking {
        val granting = object : TriggerService.FeatureGate {
            override suspend fun cachedAccess(featureId: String, requiredBalance: Double?, entityId: String?) = true
            override suspend fun checkAccess(featureId: String, requiredBalance: Double?, entityId: String?) = true
        }
        val core = core(
            transportWithGate("""{"decision":"require_feature","featureId":"pro"}"""),
            features = granting,
        )
        assertEquals(
            listOf<TriggerUpdate>(TriggerUpdate.FeatureAccess(FeatureAccessUpdate.Allowed)),
            collect(core, "moment"),
        )
        core.stop()
    }

    @Test
    fun decisionLaneFailureIsTriggerFailedButEventStaysDurable() = runBlocking {
        val transport = FakeTransport().apply {
            respond = { request ->
                when (request.url.path) {
                    "/event" -> throw java.io.IOException("offline")
                    else -> HttpTransport.Response(200, """{"segments":[]}""".encodeToByteArray())
                }
            }
        }
        val core = core(transport)
        val updates = collect(core, "moment")
        val error = (updates.single() as TriggerUpdate.Error).error
        assertEquals(TriggerErrorCode.TRIGGER_FAILED, error.code)
        // Captured durably: batch delivery will carry it when back online.
        assertTrue(core.store.pendingBatch(limit = 10).any { it.name == "moment" })
        core.stop()
    }

    @Test
    fun journeyStartSuppressesNoMatchAndStaysOpenInExperienceMode() = runBlocking {
        val router = TriggerService.JourneyRouter { _ ->
            listOf(
                TriggerService.JourneyTriggerResult.Started(
                    ExperienceRef("exp-1", "v1", "j-1"),
                ),
            )
        }
        val core = core(transportWithGate(null), journeys = router)
        val updates = collect(core, "moment")
        // Enrollment and its linked presentation are both emitted; the stream
        // stays open for the Journey terminal (no NoMatch fallback).
        assertEquals(
            listOf<TriggerUpdate>(
                TriggerUpdate.Decision(
                    TriggerDecision.JourneyStarted(ExperienceRef("exp-1", "v1", "j-1")),
                ),
                TriggerUpdate.Decision(
                    TriggerDecision.ExperienceShown(ExperienceRef("exp-1", "v1", "j-1")),
                ),
            ),
            updates,
        )
        core.stop()
    }

    @Test
    fun journeyStartPresentsThroughCoreWithItsJourneyLinkage() = runBlocking {
        val started = ExperienceRef("exp-1", "v1", "j-1")
        val presented = mutableListOf<Pair<String, String?>>()
        val core = core(
            transportWithGate(null),
            journeys = TriggerService.JourneyRouter { _ ->
                listOf(TriggerService.JourneyTriggerResult.Started(started))
            },
            presenter = TriggerService.ExperiencePresenter { version, journeyId ->
                presented += version to journeyId
                ExperienceRef("exp-1", version, journeyId)
            },
        )

        assertEquals(
            listOf(
                TriggerUpdate.Decision(TriggerDecision.JourneyStarted(started)),
                TriggerUpdate.Decision(TriggerDecision.ExperienceShown(started)),
            ),
            collect(core, "moment"),
        )
        assertEquals(listOf("v1" to "j-1"), presented)
        core.stop()
    }

    @Test
    fun committedSystemEventRoutesLocallyAndPresentsStartedJourney() = runBlocking {
        val routed = mutableListOf<StoredEvent>()
        val presented = mutableListOf<Pair<String, String?>>()
        val transport = transportWithGate(null)
        val core = core(
            transport = transport,
            journeys = TriggerService.JourneyRouter { event ->
                routed += event
                listOf(
                    TriggerService.JourneyTriggerResult.Started(
                        ExperienceRef("exp-1", "v1", "j-1"),
                    ),
                    TriggerService.JourneyTriggerResult.Started(
                        ExperienceRef("exp-2", null, "j-2"),
                    ),
                    TriggerService.JourneyTriggerResult.Failed(
                        ai.nuxie.sdk.TriggerError(
                            TriggerErrorCode.TRIGGER_FAILED,
                            "admission failed",
                        ),
                    ),
                    TriggerService.JourneyTriggerResult.Suppressed(
                        ai.nuxie.sdk.SuppressReason.ALREADY_ACTIVE,
                    ),
                )
            },
            presenter = TriggerService.ExperiencePresenter { version, journeyId ->
                presented += version to journeyId
                ExperienceRef("exp-1", version, journeyId)
            },
        )
        val stored = checkNotNull(
            core.eventLog.captureIdempotentlyWithResult(
                SystemEventNames.PURCHASE_COMPLETED,
                emptyMap(),
                "purchase-event-${UUID.randomUUID()}",
                core.identity.distinctId(),
            ).storedEvent,
        )

        core.triggers.routeCommittedSystemEvent(stored)

        assertEquals(listOf(stored), routed)
        assertEquals(listOf("v1" to "j-1"), presented)
        assertTrue(transport.requests.none { it.url.path == "/event" })
        core.stop()
    }

    @Test
    fun committedSystemEventRouterFailureDoesNotEscape() = runBlocking {
        val core = core(
            transportWithGate(null),
            journeys = TriggerService.JourneyRouter { error("router failed") },
        )
        val eventId = "purchase-event-${UUID.randomUUID()}"

        assertTrue(
            core.capturePurchaseEvent(
                SystemEventNames.PURCHASE_COMPLETED,
                emptyMap(),
                eventId,
                core.identity.distinctId(),
            ),
        )
        assertTrue(core.store.pendingBatch(10).any { it.id == eventId })
        core.stop()
    }

    @Test
    fun journeyCloseReasonReachesJourneyServiceThroughProductionComposition() = runBlocking {
        val journeyId = "composition-${UUID.randomUUID()}"
        val started = ExperienceRef("exp-composed", "v-composed", journeyId)
        val launched = mutableListOf<String>()
        val rivFile = temporaryFolder.newFile("composed.riv").apply { writeBytes(byteArrayOf(1)) }
        val factory = NuxieCore.PresentationFactory { transitionOutcome, reportOutcome ->
            ExperiencePresentationService(
                releases = PresentationReleaseProvider { presentationRelease(started) },
                acquire = {
                    AcquiredRelease(
                        identity = experienceIdentity(started),
                        artifactsByKey = mapOf("renders/composed.riv" to rivFile),
                        rivFile = rivFile,
                        protection = Closeable {},
                    )
                },
                emit = { _, _, _ -> },
                scope = CoroutineScope(Dispatchers.Unconfined),
                runtimeAvailable = { true },
                launch = launched::add,
                transitionOutcome = transitionOutcome,
                reportOutcome = reportOutcome,
            )
        }
        val core = core(
            transportWithGate(null),
            journeys = TriggerService.JourneyRouter { _ ->
                listOf(TriggerService.JourneyTriggerResult.Started(started))
            },
            presentationFactory = factory,
        )
        val distinctId = core.identity.distinctId()
        val store = JourneyStore(RuntimeEnvironment.getApplication().filesDir)
        store.save(
            JourneyRun(
                id = journeyId,
                distinctId = distinctId,
                experienceId = started.experienceId,
                experienceVersion = requireNotNull(started.experienceVersion),
                epoch = 0,
                plane = JourneyPlane.DEVICE,
                settingsSnapshot = buildJsonObject {},
                state = JourneyRunState.ACTIVE,
            ),
        )

        val updates = async { collect(core, "moment") }
        while (launched.isEmpty()) yield()
        PresentationRegistry.reportFirstFrame(launched.single())
        assertEquals(
            listOf(
                TriggerUpdate.Decision(TriggerDecision.JourneyStarted(started)),
                TriggerUpdate.Decision(TriggerDecision.ExperienceShown(started)),
            ),
            updates.await(),
        )

        PresentationRegistry.reportDismissed(launched.single(), CloseReason.GoalMet)

        withTimeout(2_000) {
            while (store.load(distinctId, journeyId)?.state != JourneyRunState.TERMINAL) yield()
        }
        val ended = requireNotNull(store.load(distinctId, journeyId))
        assertEquals(JourneyRunState.TERMINAL, ended.state)
        assertEquals("goal_met", ended.terminalReason)
        core.stop()
    }

    @Test
    fun selectedJourneyOutcomeSurvivesIdentityChangeAndPersistsUnderOwner() = runBlocking {
        val journeyId = "identity-race-${UUID.randomUUID()}"
        val ref = ExperienceRef("exp-identity-race", "v-identity-race", journeyId)
        val launched = mutableListOf<String>()
        val outcomeSelected = CompletableDeferred<Unit>()
        val continueReporting = CompletableDeferred<Unit>()
        val outcomeReported = CompletableDeferred<Unit>()
        val capturedExit = AtomicReference<NuxieEvent?>()
        val rivFile = temporaryFolder.newFile("identity-race.riv").apply {
            writeBytes(byteArrayOf(1))
        }
        val factory = NuxieCore.PresentationFactory { transitionOutcome, reportOutcome ->
            ExperiencePresentationService(
                releases = PresentationReleaseProvider { presentationRelease(ref) },
                acquire = {
                    AcquiredRelease(
                        identity = experienceIdentity(ref),
                        artifactsByKey = mapOf("renders/identity-race.riv" to rivFile),
                        rivFile = rivFile,
                        protection = Closeable {},
                    )
                },
                emit = { _, _, _ -> },
                scope = CoroutineScope(Dispatchers.Unconfined),
                runtimeAvailable = { true },
                launch = launched::add,
                transitionOutcome = transitionOutcome,
                reportOutcome = { outcome ->
                    outcomeSelected.complete(Unit)
                    continueReporting.await()
                    try {
                        reportOutcome(outcome)
                    } finally {
                        outcomeReported.complete(Unit)
                    }
                },
            )
        }
        val core = core(
            transportWithGate(null),
            presentationFactory = factory,
            beforeSend = { event ->
                if (event.name == JourneyEventNames.EXITED) capturedExit.set(event)
                event
            },
        )
        val ownerDistinctId = core.identity.distinctId()
        val replacementDistinctId = "replacement-${UUID.randomUUID()}"
        val store = JourneyStore(RuntimeEnvironment.getApplication().filesDir)
        store.save(
            JourneyRun(
                id = journeyId,
                distinctId = ownerDistinctId,
                experienceId = ref.experienceId,
                experienceVersion = requireNotNull(ref.experienceVersion),
                epoch = 0,
                plane = JourneyPlane.DEVICE,
                settingsSnapshot = buildJsonObject {},
                state = JourneyRunState.ACTIVE,
            ),
        )

        try {
            val shown = async {
                core.presentations.present(ref.experienceVersion!!, journeyId, ownerDistinctId)
            }
            while (launched.isEmpty()) yield()
            PresentationRegistry.reportFirstFrame(launched.single())
            shown.await()

            PresentationRegistry.reportDismissed(launched.single(), CloseReason.GoalMet)
            outcomeSelected.await()
            core.identity.setDistinctId(replacementDistinctId)
            continueReporting.complete(Unit)
            outcomeReported.await()

            withTimeout(2_000L) {
                // Terminal state is saved before the producer enqueues its
                // exit event. Await its durable completion record, not an
                // event-log barrier that can race ahead of that producer.
                while (store.load(ownerDistinctId, journeyId)?.state != JourneyRunState.TERMINAL ||
                    !store.hasCompleted(ownerDistinctId, ref.experienceId)
                ) {
                    yield()
                }
            }
            core.eventLog.awaitBarrier()

            val ended = requireNotNull(store.load(ownerDistinctId, journeyId))
            val exit = requireNotNull(capturedExit.get())
            assertEquals("goal_met", ended.terminalReason)
            assertTrue(store.hasCompleted(ownerDistinctId, ref.experienceId))
            assertEquals(ownerDistinctId, exit.distinctId)
            assertEquals(journeyId, exit.properties["journey_id"])
            assertEquals(null, store.load(replacementDistinctId, journeyId))
        } finally {
            continueReporting.complete(Unit)
            core.stop()
        }
    }

    @Test
    fun coreStartupRecoversPendingHostDismissalTombstones() = runBlocking {
        val core = core(transportWithGate(null))
        val ownerDistinctId = core.identity.distinctId()
        val journeyId = "startup-recovery-${UUID.randomUUID()}"
        val store = JourneyStore(RuntimeEnvironment.getApplication().filesDir)
        store.save(
            JourneyRun(
                id = journeyId,
                distinctId = ownerDistinctId,
                experienceId = "experience-recovery",
                experienceVersion = "version-recovery",
                epoch = 7,
                plane = JourneyPlane.DEVICE,
                settingsSnapshot = buildJsonObject {},
                state = JourneyRunState.TERMINAL,
                terminalReason = "dismissed",
                completedAtMillis = 1_000L,
                pendingHostExitCapture = true,
                pendingHostCompletion = true,
            ),
        )
        try {
            core.start()
            withTimeout(5_000L) {
                while (store.load(ownerDistinctId, journeyId) != null) delay(10L)
            }

            assertTrue(
                core.store.pendingBatch(100).any {
                    it.id == "journey-exited:$journeyId:7"
                },
            )
        } finally {
            core.stop()
        }
    }

    @Test
    fun mixedJourneyOutcomesEmitEveryStartAndNoTerminalError() = runBlocking {
        // A Failed admission alongside a durable enrollment must not
        // terminally complete the stream before (or after) the Started
        // update: the enrollment happened and the caller must see it.
        val router = TriggerService.JourneyRouter { _ ->
            listOf(
                TriggerService.JourneyTriggerResult.Failed(
                    ai.nuxie.sdk.TriggerError(
                        ai.nuxie.sdk.TriggerErrorCode.TRIGGER_FAILED,
                        "one admission failed",
                    ),
                ),
                TriggerService.JourneyTriggerResult.Started(
                    ExperienceRef("exp-1", "v1", "j-1"),
                ),
            )
        }
        val core = core(transportWithGate(null), journeys = router)
        val updates = collect(core, "moment")
        assertEquals(
            listOf<TriggerUpdate>(
                TriggerUpdate.Decision(
                    TriggerDecision.JourneyStarted(ExperienceRef("exp-1", "v1", "j-1")),
                ),
                TriggerUpdate.Decision(
                    TriggerDecision.ExperienceShown(ExperienceRef("exp-1", "v1", "j-1")),
                ),
            ),
            updates,
        )
        core.stop()
    }

    @Test
    fun startedAndSuppressedOutcomesBothReachTheCaller() = runBlocking {
        val router = TriggerService.JourneyRouter { _ ->
            listOf(
                TriggerService.JourneyTriggerResult.Suppressed(
                    ai.nuxie.sdk.SuppressReason.ALREADY_ACTIVE,
                ),
                TriggerService.JourneyTriggerResult.Started(
                    ExperienceRef("exp-1", "v1", "j-1"),
                ),
            )
        }
        val core = core(transportWithGate(null), journeys = router)
        val updates = collect(core, "moment")
        assertEquals(
            listOf<TriggerUpdate>(
                TriggerUpdate.Decision(
                    TriggerDecision.JourneyStarted(ExperienceRef("exp-1", "v1", "j-1")),
                ),
                TriggerUpdate.Decision(
                    TriggerDecision.Suppressed(ai.nuxie.sdk.SuppressReason.ALREADY_ACTIVE),
                ),
                TriggerUpdate.Decision(
                    TriggerDecision.ExperienceShown(ExperienceRef("exp-1", "v1", "j-1")),
                ),
            ),
            updates,
        )
        core.stop()
    }

    @Test
    fun suppressedPlusFailedWithoutStartReportsTheFailure() = runBlocking {
        val router = TriggerService.JourneyRouter { _ ->
            listOf(
                TriggerService.JourneyTriggerResult.Suppressed(
                    ai.nuxie.sdk.SuppressReason.ALREADY_ACTIVE,
                ),
                TriggerService.JourneyTriggerResult.Failed(
                    ai.nuxie.sdk.TriggerError(
                        ai.nuxie.sdk.TriggerErrorCode.TRIGGER_FAILED,
                        "admission failed",
                    ),
                ),
            )
        }
        val core = core(transportWithGate(null), journeys = router)
        val updates = collect(core, "moment")
        val error = (updates.single() as TriggerUpdate.Error).error
        assertEquals(ai.nuxie.sdk.TriggerErrorCode.TRIGGER_FAILED, error.code)
        assertEquals("admission failed", error.message)
        core.stop()
    }

    @Test
    fun allFailedJourneyOutcomesEmitOneTerminalError() = runBlocking {
        val router = TriggerService.JourneyRouter { _ ->
            listOf(
                TriggerService.JourneyTriggerResult.Failed(
                    ai.nuxie.sdk.TriggerError(
                        ai.nuxie.sdk.TriggerErrorCode.TRIGGER_FAILED,
                        "first",
                    ),
                ),
                TriggerService.JourneyTriggerResult.Failed(
                    ai.nuxie.sdk.TriggerError(
                        ai.nuxie.sdk.TriggerErrorCode.TRIGGER_FAILED,
                        "second",
                    ),
                ),
            )
        }
        val core = core(transportWithGate(null), journeys = router)
        val updates = collect(core, "moment")
        val error = (updates.single() as TriggerUpdate.Error).error
        assertEquals(ai.nuxie.sdk.TriggerErrorCode.TRIGGER_FAILED, error.code)
        assertEquals("first", error.message)
        core.stop()
    }

    @Test
    fun suppressionWithoutGateOrJourneyIsTerminal() = runBlocking {
        val router = TriggerService.JourneyRouter { _ ->
            listOf(
                TriggerService.JourneyTriggerResult.Suppressed(
                    ai.nuxie.sdk.SuppressReason.ALREADY_ACTIVE,
                ),
            )
        }
        val core = core(transportWithGate(null), journeys = router)
        val updates = collect(core, "moment")
        assertEquals(
            listOf<TriggerUpdate>(
                TriggerUpdate.Decision(
                    TriggerDecision.Suppressed(ai.nuxie.sdk.SuppressReason.ALREADY_ACTIVE),
                ),
            ),
            updates,
        )
        core.stop()
    }

    private fun presentationRelease(ref: ExperienceRef): PresentationRelease = PresentationRelease(
        release = AuthenticatedRelease(
            keyId = "key",
            descriptorSha256 = "sha",
            identity = experienceIdentity(ref),
            descriptorBytes = ByteArray(0),
            descriptor = buildJsonObject {
                put("render", buildJsonObject {})
                put("presentation", buildJsonObject {})
            },
            releaseSequenceToPromote = 1,
        ),
        delivery = Delivery("https://render.example/", "https://assets.example/"),
    )

    private fun experienceIdentity(ref: ExperienceRef) = ExperienceReleaseIdentity(
        appId = "app",
        environment = "development",
        experienceId = ref.experienceId,
        experienceVersionId = requireNotNull(ref.experienceVersion),
        buildId = "build",
        versionNumber = 1,
        releaseCreatedAt = "2026-08-24T00:00:00Z",
        releaseSequence = 1,
    )
}
