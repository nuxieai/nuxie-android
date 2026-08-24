package ai.nuxie.sdk.events

import ai.nuxie.sdk.ExperienceRef
import ai.nuxie.sdk.FeatureAccessUpdate
import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.TriggerDecision
import ai.nuxie.sdk.TriggerErrorCode
import ai.nuxie.sdk.TriggerUpdate
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TriggerServiceTest {
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
    ): NuxieCore = NuxieCore(
        context = RuntimeEnvironment.getApplication(),
        apiKey = "pk_test_trigger",
        environment = NuxieEnvironment.DEVELOPMENT,
        logLevel = LogLevel.NONE,
        beforeSend = null,
        overrides = NuxieCore.Overrides(
            transport = transport,
            registerLifecycle = false,
            journeys = journeys,
            features = features,
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
        // journeyStarted is emitted and the stream stays open for the journey
        // terminal (no NoMatch fallback).
        assertEquals(
            listOf<TriggerUpdate>(
                TriggerUpdate.Decision(
                    TriggerDecision.JourneyStarted(ExperienceRef("exp-1", "v1", "j-1")),
                ),
            ),
            updates,
        )
        core.stop()
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
            ),
            updates,
        )
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
}
