package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.EventTriggerConfig
import io.nuxie.sdk.campaigns.ExitPolicy
import io.nuxie.sdk.campaigns.GoalConfig
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.events.SystemEventNames
import io.nuxie.sdk.events.store.EventHistoryStore
import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.InMemoryEventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
import io.nuxie.sdk.events.store.StoredEvent
import io.nuxie.sdk.features.FeatureAccess
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.FeatureType
import io.nuxie.sdk.features.PurchaseFeature
import io.nuxie.sdk.flows.BuildManifest
import io.nuxie.sdk.flows.BuildManifestFile
import io.nuxie.sdk.flows.CloseReason
import io.nuxie.sdk.flows.FlowBundleRef
import io.nuxie.sdk.flows.FlowService
import io.nuxie.sdk.flows.Interaction
import io.nuxie.sdk.flows.InteractionAction
import io.nuxie.sdk.flows.InteractionTrigger
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.flows.RemoteFlowScreen
import io.nuxie.sdk.flows.ViewModel
import io.nuxie.sdk.flows.ViewModelInstance
import io.nuxie.sdk.flows.ViewModelProperty
import io.nuxie.sdk.flows.ViewModelPropertyType
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.ir.IREnvelope
import io.nuxie.sdk.ir.IRExpr
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.ActiveJourney
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ExperimentAssignment
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.segments.SegmentService
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import io.nuxie.sdk.triggers.DefaultTriggerBroker
import io.nuxie.sdk.triggers.JourneyExitReason
import io.nuxie.sdk.triggers.SuppressReason
import io.nuxie.sdk.triggers.TriggerUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JourneyServiceTest {

  private class FakeApi(
    private val profile: ProfileResponse,
    private val remoteFlow: RemoteFlow,
    private val trackDelayMillis: Long = 0,
    private val trackFailure: Throwable? = null,
    private val trackFailureEventNames: Set<String>? = null,
  ) : NuxieApiProtocol {
    data class TrackedEvent(
      val event: String,
      val distinctId: String,
      val properties: JsonObject?,
    )

    val trackedEvents: MutableList<TrackedEvent> = mutableListOf()

    private fun recordTrackedEvent(
      event: String,
      distinctId: String,
      properties: JsonObject?,
    ) {
      trackedEvents += TrackedEvent(
        event = event,
        distinctId = distinctId,
        properties = properties,
      )
    }

    override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse = profile

    override suspend fun trackEvent(
      event: String,
      distinctId: String,
      anonDistinctId: String?,
      properties: JsonObject?,
      uuid: String,
      value: Double?,
      entityId: String?,
      timestamp: String,
    ): EventResponse {
      if (trackDelayMillis > 0) {
        delay(trackDelayMillis)
      }
      if (trackFailure != null && (trackFailureEventNames == null || event in trackFailureEventNames)) {
        throw trackFailure
      }
      recordTrackedEvent(event = event, distinctId = distinctId, properties = properties)
      return EventResponse(status = "ok")
    }

    override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
      batch.batch.forEach { item ->
        recordTrackedEvent(
          event = item.event,
          distinctId = item.distinctId,
          properties = item.properties,
        )
      }
      return BatchResponse(status = "ok", processed = batch.batch.size, failed = 0, total = batch.batch.size)
    }

    override suspend fun fetchFlow(flowId: String): RemoteFlow = remoteFlow

    override suspend fun checkFeature(
      customerId: String,
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
    ): FeatureCheckResult {
      return FeatureCheckResult(
        customerId = customerId,
        featureId = featureId,
        requiredBalance = requiredBalance ?: 1,
        code = "feature_denied",
        allowed = false,
        unlimited = false,
        balance = 0,
        type = FeatureType.BOOLEAN,
      )
    }
  }

  private class FakeFeatureService : FeatureService {
    override suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? = null
    override suspend fun getAllCached(): Map<String, FeatureAccess> = emptyMap()
    override suspend fun check(featureId: String, requiredBalance: Int?, entityId: String?): FeatureCheckResult {
      return FeatureCheckResult(
        customerId = "user_1",
        featureId = featureId,
        requiredBalance = requiredBalance ?: 1,
        code = "feature_denied",
        allowed = false,
        unlimited = false,
        balance = 0,
        type = FeatureType.BOOLEAN,
      )
    }

    override suspend fun checkWithCache(
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
      forceRefresh: Boolean,
    ): FeatureAccess = FeatureAccess.notFound

    override suspend fun clearCache() {}
    override suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {}
    override suspend fun syncFeatureInfo() {}
    override suspend fun updateFromPurchase(features: List<PurchaseFeature>) {}
  }

  private class FakeProfileService(
    private val profile: ProfileResponse,
  ) : ProfileService {
    override suspend fun fetchProfile(distinctId: String): ProfileResponse = profile
    override suspend fun getCachedProfile(distinctId: String): ProfileResponse? = profile
    override suspend fun clearCache(distinctId: String) {}
    override suspend fun clearAllCache() {}
    override suspend fun cleanupExpired(): Int = 0
    override suspend fun getCacheStats(): Map<String, Any?> = emptyMap()
    override suspend fun refetchProfile(): ProfileResponse = profile
    override suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {}
    override suspend fun onAppBecameActive() {}
    override fun shutdown() {}
  }

  private class FailingInsertHistoryStore(
    private val delegate: EventHistoryStore = InMemoryEventHistoryStore(),
  ) : EventHistoryStore by delegate {
    override suspend fun insert(event: StoredEvent) {
      throw IllegalStateException("history_insert_failed")
    }
  }

  private class SelectiveFailingInsertHistoryStore(
    private val failingEventNames: Set<String>,
    private val delegate: EventHistoryStore = InMemoryEventHistoryStore(),
  ) : EventHistoryStore by delegate {
    override suspend fun insert(event: StoredEvent) {
      if (event.name in failingEventNames) {
        throw IllegalStateException("history_insert_failed")
      }
      delegate.insert(event)
    }
  }

  private class DroppingEventQueueStore(
    private val delegate: EventQueueStore = InMemoryEventQueueStore(),
    private val droppedEventNames: Set<String>,
  ) : EventQueueStore {
    override suspend fun enqueue(event: io.nuxie.sdk.events.queue.QueuedEvent): Boolean {
      if (event.name in droppedEventNames) {
        return false
      }
      return delegate.enqueue(event)
    }

    override suspend fun size(): Int = delegate.size()

    override suspend fun peek(limit: Int): List<io.nuxie.sdk.events.queue.QueuedEvent> = delegate.peek(limit)

    override suspend fun delete(ids: List<String>) {
      delegate.delete(ids)
    }

    override suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int {
      return delegate.reassignDistinctId(fromDistinctId, toDistinctId)
    }

    override suspend fun clear() {
      delegate.clear()
    }
  }

  @Test
  fun handleEventForTrigger_startsJourney() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.EveryTime)
    try {
      harness.service.initialize()
      val event = NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
      val results = harness.service.handleEventForTrigger(event)

      val started = results.filterIsInstance<JourneyTriggerResult.Started>().firstOrNull()
      assertNotNull(started)
      assertEquals(1, harness.presented.size)
      assertEquals("flow_1", harness.presented.first().first)
      assertEquals(started!!.journey.id, harness.presented.first().second)

      val startEvent = harness.api.trackedEvents.firstOrNull { it.event == "\$journey_start" }
      assertNotNull(startEvent)
      assertEquals(started.journey.id, startEvent?.properties?.get("session_id")?.jsonPrimitive?.contentOrNull)
      assertEquals("camp_1", startEvent?.properties?.get("campaign_id")?.jsonPrimitive?.contentOrNull)
    } finally {
      harness.close()
    }
  }

  @Test
  fun handleEventForTrigger_doesNotStartJourneyWhenStartPersistenceFails() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      trackFailure = IllegalStateException("network_down"),
    )
    try {
      harness.service.initialize()
      val event = NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
      val results = harness.service.handleEventForTrigger(event)

      assertTrue(results.filterIsInstance<JourneyTriggerResult.Started>().isEmpty())
      assertTrue(harness.presented.isEmpty())
      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      assertTrue(harness.journeyStore.loadActiveJourneys().isEmpty())
      assertTrue(harness.eventService.getEventsForUser("user_1").none { it.name == "\$journey_start" })
    } finally {
      harness.close()
    }
  }

  @Test
  fun handleEventForTrigger_suppressesConcurrentStartWhileStartPersistenceIsInFlight() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      trackDelayMillis = 250,
    )
    try {
      harness.service.initialize()
      val first = async {
        harness.service.handleEventForTrigger(
          NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
        )
      }

      delay(50)
      val secondResults = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_2", name = "paywall_trigger", distinctId = "user_1")
      )
      val firstResults = first.await()

      assertEquals(1, firstResults.filterIsInstance<JourneyTriggerResult.Started>().size)
      val suppressed = secondResults.filterIsInstance<JourneyTriggerResult.Suppressed>().firstOrNull()
      assertNotNull(suppressed)
      assertEquals(SuppressReason.AlreadyActive, suppressed?.reason)
      assertEquals(1, harness.service.getActiveJourneys("user_1").size)
      assertEquals(1, harness.presented.size)
      assertEquals(1, harness.api.trackedEvents.count { it.event == "\$journey_start" })
    } finally {
      harness.close()
    }
  }

  @Test
  fun handleEventForTrigger_doesNotPresentJourneyCompletedWhileStartPersistenceIsInFlight() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = "permission_granted",
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
      trackDelayMillis = 250,
    )
    try {
      harness.service.initialize()
      val first = async {
        harness.service.handleEventForTrigger(
          NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
        )
      }

      delay(50)
      val inFlightJourney = harness.service.getActiveJourneys("user_1").single()
      harness.service.handleScopedPermissionEvent(
        journeyId = inFlightJourney.id,
        eventName = "permission_granted",
        properties = emptyMap(),
      )

      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      val firstResults = first.await()

      assertTrue(firstResults.filterIsInstance<JourneyTriggerResult.Started>().isEmpty())
      assertTrue(harness.presented.isEmpty())
      assertEquals(1, harness.api.trackedEvents.count { it.event == "\$journey_start" })
    } finally {
      harness.close()
    }
  }

  @Test
  fun handleEventForTrigger_updatesLastFlowShownAnchorWhenFlowPresents() = runBlocking {
    var now = 1_000L
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      nowEpochMillis = { now },
      beforePresentFlow = { _, _ -> now = 5_000L },
    )
    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_anchor", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      assertEquals(5_000L, started.journey.conversionAnchorAtEpochMillis)
      assertEquals(5_000L, started.journey.updatedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun explicitGoalAction_defersExitUntilDismissWhenFlowIsPresented() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(InteractionAction.Goal(goalId = "primary")),
          enabled = true,
        )
      )
    )
    val explicitGoal = GoalConfig(
      kind = GoalConfig.Kind.EVENT,
      eventName = JourneyEvents.journeyGoalHit,
      eventFilter = IREnvelope(
        irVersion = 1,
        expr = IRExpr.PredAnd(
          listOf(
            IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
            IRExpr.Pred("eq", "goal_id", IRExpr.String("primary")),
          )
        ),
      ),
      window = 3600.0,
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      goal = explicitGoal,
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
    )
    try {
      harness.service.initialize()
      val updates = mutableListOf<TriggerUpdate>()
      harness.broker.register("evt_1") { updates += it }

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(started.journey.id, active.first().id)
      assertNotNull(active.first().convertedAtEpochMillis)
      assertTrue(updates.none { it is TriggerUpdate.Journey })

      harness.service.handleRuntimeDismiss(started.journey.id, CloseReason.UserDismissed)
      delay(80)

      val journeyUpdate = updates.filterIsInstance<TriggerUpdate.Journey>().firstOrNull()
      assertNotNull(journeyUpdate)
      assertEquals(JourneyExitReason.GOAL_MET, journeyUpdate?.journey?.exitReason)
    } finally {
      harness.close()
    }
  }

  @Test
  fun explicitGoalAction_honorsBeforeSendDrop() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(InteractionAction.Goal(goalId = "primary")),
          enabled = true,
        )
      )
    )
    val explicitGoal = GoalConfig(
      kind = GoalConfig.Kind.EVENT,
      eventName = JourneyEvents.journeyGoalHit,
      eventFilter = IREnvelope(
        irVersion = 1,
        expr = IRExpr.PredAnd(
          listOf(
            IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
            IRExpr.Pred("eq", "goal_id", IRExpr.String("primary")),
          )
        ),
      ),
      window = 3600.0,
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      goal = explicitGoal,
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
      beforeSend = { event ->
        if (event.name == JourneyEvents.journeyGoalHit) null else event
      },
    )
    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_drop", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(started.journey.id, active.first().id)
      assertEquals(null, active.first().convertedAtEpochMillis)
      assertTrue(!harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun explicitGoalAction_fallsBackToFullEvaluationWhenGoalFilterHasExtraPredicates() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(InteractionAction.Goal(goalId = "primary")),
          enabled = true,
        )
      )
    )
    val explicitGoal = GoalConfig(
      kind = GoalConfig.Kind.EVENT,
      eventName = JourneyEvents.journeyGoalHit,
      eventFilter = IREnvelope(
        irVersion = 1,
        expr = IRExpr.PredAnd(
          listOf(
            IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
            IRExpr.Pred("eq", "goal_id", IRExpr.String("primary")),
            IRExpr.Pred("eq", "screen_id", IRExpr.String("screen_1")),
          )
        ),
      ),
      window = 3600.0,
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      goal = explicitGoal,
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
    )
    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_scope_mismatch", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(started.journey.id, active.first().id)
      assertEquals(null, active.first().convertedAtEpochMillis)
      assertTrue(!harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun runtimeGoalAction_routesToScopedGoalHandler() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = JourneyEvents.journeyGoalHit,
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_runtime_goal", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleRuntimeMessage(
        journeyId = started.id,
        type = "action/goal",
        payload = JsonObject(
          mapOf(
            "goalId" to JsonPrimitive(" signup_complete "),
            "goalLabel" to JsonPrimitive(" Signed Up "),
            "screenId" to JsonPrimitive("screen_1"),
            "interactionId" to JsonPrimitive("int_goal"),
          ),
        ),
        id = null,
      )
      delay(80)

      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      assertTrue(harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))

      val tracked = harness.api.trackedEvents.last { it.event == JourneyEvents.journeyGoalHit }
      assertEquals("signup_complete", tracked.properties?.get("goal_id")?.jsonPrimitive?.contentOrNull)
      assertEquals("Signed Up", tracked.properties?.get("goal_label")?.jsonPrimitive?.contentOrNull)
      assertEquals("screen_1", tracked.properties?.get("screen_id")?.jsonPrimitive?.contentOrNull)
      assertEquals("int_goal", tracked.properties?.get("interaction_id")?.jsonPrimitive?.contentOrNull)
    } finally {
      harness.close()
    }
  }

  @Test
  fun explicitGoalAction_fallbackReevaluationIncludesPriorGoalHits() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.Goal(goalId = "signup_complete"),
            InteractionAction.Goal(goalId = "profile_complete"),
          ),
          enabled = true,
        )
      )
    )
    val compositeGoal = GoalConfig(
      kind = GoalConfig.Kind.ATTRIBUTE,
      attributeExpr = IREnvelope(
        irVersion = 1,
        expr = IRExpr.Compare(
          op = ">",
          left = IRExpr.EventsCount(
            name = JourneyEvents.journeyGoalHit,
            whereExpr = IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
          ),
          right = IRExpr.Number(1.0),
        ),
      ),
      window = 3600.0,
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      goal = compositeGoal,
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
    )
    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_count", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(started.journey.id, active.first().id)
      assertNotNull(active.first().convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun explicitGoalAction_bufferParticipatesInLaterGoalReevaluationAfterHistoryInsertFails() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(InteractionAction.Goal(goalId = "signup_complete")),
          enabled = true,
        )
      )
    )
    val compositeGoal = GoalConfig(
      kind = GoalConfig.Kind.ATTRIBUTE,
      attributeExpr = IREnvelope(
        irVersion = 1,
        expr = IRExpr.And(
          listOf(
            IRExpr.EventsExists(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.PredAnd(
                listOf(
                  IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
                  IRExpr.Pred("eq", "goal_id", IRExpr.String("signup_complete")),
                )
              ),
            ),
            IRExpr.EventsExists(name = "purchase_complete"),
          )
        ),
      ),
      window = 3600.0,
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      goal = compositeGoal,
      exitPolicy = null,
      historyStore = SelectiveFailingInsertHistoryStore(
        failingEventNames = setOf(JourneyEvents.journeyGoalHit),
      ),
    )
    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_explicit_buffer_later_eval", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      delay(80)

      val afterGoal = harness.service.getActiveJourneys("user_1")
      assertEquals(1, afterGoal.size)
      assertEquals(null, afterGoal.first().convertedAtEpochMillis)

      val purchaseEvent = harness.eventService.trackForTrigger(
        event = "purchase_complete",
        persistToHistory = true,
      ).first
      harness.service.handleEventForTrigger(purchaseEvent)
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertNotNull(active.first().convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun explicitGoalAction_reusesScopedFallbackHitsDuringImmediateReevaluation() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(InteractionAction.Goal(goalId = "profile_complete")),
          enabled = true,
        )
      )
    )
    val compositeGoal = GoalConfig(
      kind = GoalConfig.Kind.ATTRIBUTE,
      attributeExpr = IREnvelope(
        irVersion = 1,
        expr = IRExpr.And(
          listOf(
            IRExpr.EventsExists(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.PredAnd(
                listOf(
                  IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
                  IRExpr.Pred("eq", "goal_id", IRExpr.String("signup_complete")),
                )
              ),
            ),
            IRExpr.EventsExists(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.PredAnd(
                listOf(
                  IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
                  IRExpr.Pred("eq", "goal_id", IRExpr.String("profile_complete")),
                )
              ),
            ),
          )
        ),
      ),
      window = 3600.0,
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      goal = compositeGoal,
      exitPolicy = null,
      historyStore = SelectiveFailingInsertHistoryStore(
        failingEventNames = setOf(JourneyEvents.journeyGoalHit),
      ),
    )
    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_explicit_scoped_buffer", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = null,
        screenId = "screen_1",
      )
      delay(80)
      assertEquals(null, harness.service.getActiveJourneys("user_1").first().convertedAtEpochMillis)

      harness.service.handleRuntimeMessage(
        journeyId = started.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertNotNull(active.first().convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun completion_emitsJourneyUpdateToBroker() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.EveryTime)
    try {
      harness.service.initialize()
      val updates = mutableListOf<TriggerUpdate>()
      harness.broker.register("evt_origin") { updates += it }

      val event = NuxieEvent(id = "evt_origin", name = "paywall_trigger", distinctId = "user_1")
      val started = harness.service.handleEventForTrigger(event)
        .filterIsInstance<JourneyTriggerResult.Started>()
        .first()

      harness.service.handleRuntimeDismiss(started.journey.id, CloseReason.UserDismissed)
      delay(50)

      assertTrue(updates.any { it is TriggerUpdate.Journey })
      val journeyUpdate = updates.filterIsInstance<TriggerUpdate.Journey>().first().journey
      assertEquals(started.journey.id, journeyUpdate.journeyId)
      assertEquals(io.nuxie.sdk.triggers.JourneyExitReason.DISMISSED, journeyUpdate.exitReason)
    } finally {
      harness.close()
    }
  }

  @Test
  fun goalMetDuringPresentedFlow_defersExitUntilDismiss() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = "goal_event",
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
    )

    try {
      harness.service.initialize()

      val updates = mutableListOf<TriggerUpdate>()
      harness.broker.register("evt_origin") { updates += it }

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_origin", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.eventService.track("goal_event")
      delay(80)
      val goalEvent = NuxieEvent(id = "evt_goal", name = "goal_event", distinctId = "user_1")
      harness.service.handleEventForTrigger(goalEvent)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(started.journey.id, active.first().id)
      assertNotNull(active.first().convertedAtEpochMillis)
      assertTrue(updates.none { it is TriggerUpdate.Journey })

      harness.service.handleRuntimeDismiss(started.journey.id, CloseReason.UserDismissed)
      delay(80)

      val journeyUpdate = updates.filterIsInstance<TriggerUpdate.Journey>().firstOrNull()
      assertNotNull(journeyUpdate)
      assertEquals(JourneyExitReason.GOAL_MET, journeyUpdate?.journey?.exitReason)
    } finally {
      harness.close()
    }
  }

  @Test
  fun dismissTriggeredGoalEvent_exitsAsGoalMetAfterDismissProcessing() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = mapOf(
        "__global__" to listOf(
          Interaction(
            id = "dismiss_goal",
            trigger = InteractionTrigger.Event(eventName = SystemEventNames.screenDismissed),
            actions = listOf(
              InteractionAction.SendEvent(eventName = "goal_event")
            ),
            enabled = true,
          )
        )
      ),
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = "goal_event",
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
    )

    try {
      harness.service.initialize()

      val updates = mutableListOf<TriggerUpdate>()
      harness.broker.register("evt_origin") { updates += it }

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_origin", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeDismiss(started.journey.id, CloseReason.UserDismissed)
      delay(80)

      val journeyUpdate = updates.filterIsInstance<TriggerUpdate.Journey>().firstOrNull()
      assertNotNull(journeyUpdate)
      assertEquals(JourneyExitReason.GOAL_MET, journeyUpdate?.journey?.exitReason)
      assertTrue(journeyUpdate?.journey?.goalMet == true)
      assertNotNull(journeyUpdate?.journey?.goalMetAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedNotificationPermissionExit_doesNotLeaveJourneyPersistedAsActive() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = mapOf(
        "__global__" to listOf(
          Interaction(
            id = "notifications_exit",
            trigger = InteractionTrigger.Event(eventName = SystemEventNames.notificationsEnabled),
            actions = listOf(
              InteractionAction.Exit(reason = "completed")
            ),
            enabled = true,
          )
        )
      ),
    )

    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_origin", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleScopedNotificationPermissionEvent(
        journeyId = started.journey.id,
        eventName = SystemEventNames.notificationsEnabled,
        properties = mapOf("journey_id" to started.journey.id),
      )

      waitUntil {
        harness.service.getActiveJourneys("user_1").isEmpty() &&
          harness.journeyStore.loadActiveJourneys().isEmpty()
      }
    } finally {
      harness.close()
    }
  }

  @Test
  fun oneTimeReentry_suppressesSecondJourney() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.OneTime)
    try {
      harness.service.initialize()

      val firstEvent = NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
      val firstStart = harness.service.handleEventForTrigger(firstEvent)
        .filterIsInstance<JourneyTriggerResult.Started>()
        .first()
      harness.service.handleRuntimeDismiss(firstStart.journey.id, CloseReason.UserDismissed)
      delay(50)

      val secondEvent = NuxieEvent(id = "evt_2", name = "paywall_trigger", distinctId = "user_1")
      val second = harness.service.handleEventForTrigger(secondEvent)
      val suppressed = second.filterIsInstance<JourneyTriggerResult.Suppressed>().firstOrNull()
      assertNotNull(suppressed)
      assertEquals(SuppressReason.ReentryLimited, suppressed?.reason)
    } finally {
      harness.close()
    }
  }

  @Test
  fun callDelegateAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val delegateCalls = mutableListOf<DelegateCall>()
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_1",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.CallDelegate(
              message = "e2e_delegate",
              payload = JsonObject(mapOf("k" to JsonPrimitive("v"))),
            )
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      onCallDelegate = { journeyId, campaignId, message, payload ->
        delegateCalls += DelegateCall(
          journeyId = journeyId,
          campaignId = campaignId,
          message = message,
          payload = payload,
        )
      },
    )
    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/press",
        payload = JsonObject(mapOf("screenId" to JsonPrimitive("screen_1"))),
        id = null,
      )
      delay(80)

      assertEquals(1, delegateCalls.size)
      val callback = delegateCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("e2e_delegate", callback.message)

      val payload = callback.payload as? Map<*, *>
      assertNotNull(payload)
      assertEquals("v", payload?.get("k"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun purchaseAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val purchaseCalls = mutableListOf<PurchaseCall>()
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_purchase",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.Purchase(
              placementIndex = JsonPrimitive(2),
              productId = JsonPrimitive("prod_annual"),
            )
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      onPurchaseRequested = { journeyId, campaignId, screenId, productId, placementIndex ->
        purchaseCalls += PurchaseCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
          productId = productId,
          placementIndex = placementIndex,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_purchase", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/press",
        payload = JsonObject(mapOf("screenId" to JsonPrimitive("screen_1"))),
        id = null,
      )
      delay(80)

      assertEquals(1, purchaseCalls.size)
      val callback = purchaseCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_1", callback.screenId)
      assertEquals("prod_annual", callback.productId)
      assertEquals(2, callback.placementIndex)
    } finally {
      harness.close()
    }
  }

  @Test
  fun restoreAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val restoreCalls = mutableListOf<RestoreCall>()
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_restore",
          trigger = InteractionTrigger.Press,
          actions = listOf(InteractionAction.Restore),
          enabled = true,
        )
      )
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      onRestoreRequested = { journeyId, campaignId, screenId ->
        restoreCalls += RestoreCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_restore", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/press",
        payload = JsonObject(mapOf("screenId" to JsonPrimitive("screen_1"))),
        id = null,
      )
      delay(80)

      assertEquals(1, restoreCalls.size)
      val callback = restoreCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_1", callback.screenId)
    } finally {
      harness.close()
    }
  }

  @Test
  fun openLinkAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val openLinkCalls = mutableListOf<OpenLinkCall>()
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_link",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.OpenLink(
              url = JsonPrimitive("https://nuxie.io/pricing"),
              target = "_blank",
            )
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      onOpenLinkRequested = { journeyId, campaignId, screenId, url, target ->
        openLinkCalls += OpenLinkCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
          url = url,
          target = target,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_link", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/press",
        payload = JsonObject(mapOf("screenId" to JsonPrimitive("screen_1"))),
        id = null,
      )
      delay(80)

      assertEquals(1, openLinkCalls.size)
      val callback = openLinkCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_1", callback.screenId)
      assertEquals("https://nuxie.io/pricing", callback.url)
      assertEquals("_blank", callback.target)
    } finally {
      harness.close()
    }
  }

  @Test
  fun backAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val backCalls = mutableListOf<BackCall>()
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      onBackRequested = { journeyId, campaignId, screenId, steps ->
        backCalls += BackCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
          steps = steps,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_back", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      started.journey.flowState.currentScreenId = "screen_1"
      started.journey.flowState.navigationStack = mutableListOf("screen_prev")

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/back",
        payload = JsonObject(mapOf("steps" to JsonPrimitive(1))),
        id = null,
      )
      delay(80)

      assertEquals(1, backCalls.size)
      val callback = backCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_prev", callback.screenId)
      assertEquals(1, callback.steps)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedNotificationPermissionEvent_onlyAdvancesTheRequestingJourney() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = SystemEventNames.notificationsEnabled,
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_scope_1", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey
      val resumedJourneyId = "journey_scope_2"
      harness.service.resumeFromServerState(
        journeys = listOf(
          ActiveJourney(
            sessionId = resumedJourneyId,
            campaignId = "camp_1",
            currentNodeId = "screen_1",
            context = JsonObject(emptyMap()),
          )
        ),
        campaigns = harness.campaigns,
      )
      delay(80)

      harness.service.handleScopedNotificationPermissionEvent(
        journeyId = started.id,
        eventName = SystemEventNames.notificationsEnabled,
        properties = mapOf("journey_id" to started.id),
      )
      delay(80)

      val historyAfterScopedEvent = harness.eventService.getEventsForUser("user_1", limit = 100)
      assertTrue(historyAfterScopedEvent.none { it.name == SystemEventNames.notificationsEnabled })

      harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_scope_noop", name = "noop", distinctId = "user_1")
      )
      delay(80)

      val activeById = harness.service.getActiveJourneys("user_1").associateBy { it.id }
      assertEquals(1, activeById.size)
      assertTrue(!activeById.containsKey(started.id))
      assertNotNull(activeById[resumedJourneyId])
      assertTrue(activeById[resumedJourneyId]?.convertedAtEpochMillis == null)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedPermissionEvent_onlyAdvancesTheRequestingJourney() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = SystemEventNames.permissionGranted,
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_permission_scope_1", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey
      val resumedJourneyId = "journey_permission_scope_2"
      harness.service.resumeFromServerState(
        journeys = listOf(
          ActiveJourney(
            sessionId = resumedJourneyId,
            campaignId = "camp_1",
            currentNodeId = "screen_1",
            context = JsonObject(emptyMap()),
          )
        ),
        campaigns = harness.campaigns,
      )
      delay(80)

      harness.service.handleScopedPermissionEvent(
        journeyId = started.id,
        eventName = SystemEventNames.permissionGranted,
        properties = mapOf("journey_id" to started.id, "type" to "camera"),
      )
      delay(80)

      val historyAfterScopedEvent = harness.eventService.getEventsForUser("user_1", limit = 100)
      assertTrue(historyAfterScopedEvent.none { it.name == SystemEventNames.permissionGranted })

      harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_permission_scope_noop", name = "noop", distinctId = "user_1")
      )
      delay(80)

      val activeById = harness.service.getActiveJourneys("user_1").associateBy { it.id }
      assertEquals(1, activeById.size)
      assertTrue(!activeById.containsKey(started.id))
      assertNotNull(activeById[resumedJourneyId])
      assertTrue(activeById[resumedJourneyId]?.convertedAtEpochMillis == null)
    } finally {
      harness.close()
    }
  }

  @Test
  fun explicitGoalAction_includesPriorNonNativeGoalHitsFromHistory() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(InteractionAction.Goal(goalId = "profile_complete")),
          enabled = true,
        )
      )
    )
    val compositeGoal = GoalConfig(
      kind = GoalConfig.Kind.ATTRIBUTE,
      attributeExpr = IREnvelope(
        irVersion = 1,
        expr = IRExpr.And(
          listOf(
            IRExpr.EventsExists(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.PredAnd(
                listOf(
                  IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
                  IRExpr.Pred("eq", "goal_id", IRExpr.String("signup_complete")),
                )
              ),
            ),
            IRExpr.EventsExists(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.PredAnd(
                listOf(
                  IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
                  IRExpr.Pred("eq", "goal_id", IRExpr.String("profile_complete")),
                )
              ),
            ),
          )
        ),
      ),
      window = 3600.0,
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      goal = compositeGoal,
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
    )
    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_history", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.eventService.trackForTrigger(
        JourneyEvents.journeyGoalHit,
        properties = JourneyEvents.journeyGoalHitProperties(
          journey = started.journey,
          screenId = "screen_1",
          goalId = "signup_complete",
          goalLabel = "Signed Up",
        ),
      )

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(started.journey.id, active.first().id)
      assertNotNull(active.first().convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_completesPresentedJourneyAndUsesStandardJourneyKeys() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = JourneyEvents.journeyGoalHit,
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_action", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)

      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      assertTrue(harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))

      val tracked = harness.api.trackedEvents.last { it.event == JourneyEvents.journeyGoalHit }
      assertEquals("user_1", tracked.distinctId)
      assertEquals(started.id, tracked.properties?.get("journey_id")?.jsonPrimitive?.contentOrNull)
      assertEquals("camp_1", tracked.properties?.get("campaign_id")?.jsonPrimitive?.contentOrNull)
      assertEquals("signup_complete", tracked.properties?.get("goal_id")?.jsonPrimitive?.contentOrNull)
      assertEquals("screen_1", tracked.properties?.get("screen_id")?.jsonPrimitive?.contentOrNull)
      assertEquals("Signed Up", tracked.properties?.get("goal_label")?.jsonPrimitive?.contentOrNull)
      assertEquals(null, tracked.properties?.get("journeyId"))
      assertEquals(null, tracked.properties?.get("campaignId"))
      assertEquals(null, tracked.properties?.get("goalId"))
      assertEquals(null, tracked.properties?.get("goalLabel"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_dispatchesGoalHitTriggersIntoTheOwningJourney() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = mapOf(
        "__global__" to listOf(
          Interaction(
            id = "goal_hit_exit",
            trigger = InteractionTrigger.Event(eventName = JourneyEvents.journeyGoalHit),
            actions = listOf(
              InteractionAction.Exit(reason = "completed")
            ),
            enabled = true,
          )
        )
      ),
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_trigger", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)

      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      assertTrue(harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_resumesWaitUntilWork() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.EveryTime)

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_wait", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      started.flowState.pendingAction = FlowPendingAction(
        interactionId = "wait_goal",
        screenId = null,
        componentId = null,
        actionIndex = 0,
        kind = FlowPendingActionKind.WAIT_UNTIL,
        resumeAtEpochMillis = null,
        condition = null,
        maxTimeMs = null,
        startedAtEpochMillis = System.currentTimeMillis(),
        resumeActions = listOf(InteractionAction.Exit(reason = "completed")),
      )

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)

      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      assertTrue(harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_persistsScopedGoalHitsForCompositeGoals() = runBlocking {
    val goal = GoalConfig(
      kind = GoalConfig.Kind.ATTRIBUTE,
      attributeExpr = IREnvelope(
        irVersion = 1,
        expr = IRExpr.And(
          listOf(
            IRExpr.EventsExists(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.PredAnd(
                listOf(
                  IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
                  IRExpr.Pred("eq", "goal_id", IRExpr.String("signup_complete")),
                )
              ),
            ),
            IRExpr.EventsExists(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.PredAnd(
                listOf(
                  IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
                  IRExpr.Pred("eq", "goal_id", IRExpr.String("profile_complete")),
                )
              ),
            ),
          )
        ),
      ),
      window = 60.0,
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = goal,
      exitPolicy = null,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_combo", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)

      val afterFirst = harness.service.getActiveJourneys("user_1").first()
      assertEquals(null, afterFirst.convertedAtEpochMillis)

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "profile_complete",
        goalLabel = "Completed Profile",
        screenId = "screen_1",
      )
      delay(80)

      val afterSecond = harness.service.getActiveJourneys("user_1").first()
      assertNotNull(afterSecond.convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_tracksGoalHitBeforeCompletionEvents() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = JourneyEvents.journeyGoalHit,
      ),
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
      trackDelayMillis = 750,
      presentFlowResult = false,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_order", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)
      harness.eventService.flushEvents()
      delay(80)

      val trackedEvents = harness.api.trackedEvents.map { it.event }
      val goalHitIndex = trackedEvents.indexOf(JourneyEvents.journeyGoalHit)
      val goalMetIndex = trackedEvents.indexOf(JourneyEvents.journeyGoalMet)
      val completedIndex = trackedEvents.indexOf("\$journey_completed")

      assertTrue(goalHitIndex >= 0)
      assertTrue(goalMetIndex > goalHitIndex)
      assertTrue(completedIndex > goalHitIndex)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_requeuesGoalHitWhenDirectSendFails() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = JourneyEvents.journeyGoalHit,
      ),
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
      trackFailure = IllegalStateException("track_failed"),
      trackFailureEventNames = setOf(JourneyEvents.journeyGoalHit),
      presentFlowResult = false,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_requeue", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)
      harness.eventService.flushEvents()
      delay(80)

      val trackedEvents = harness.api.trackedEvents.map { it.event }
      val goalHitIndex = trackedEvents.indexOf(JourneyEvents.journeyGoalHit)
      val goalMetIndex = trackedEvents.indexOf(JourneyEvents.journeyGoalMet)
      val completedIndex = trackedEvents.indexOf("\$journey_completed")

      assertTrue(goalHitIndex >= 0)
      assertTrue(goalMetIndex > goalHitIndex)
      assertTrue(completedIndex > goalHitIndex)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_doesNotEvaluateWhenFallbackQueueDropsGoalHit() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = JourneyEvents.journeyGoalHit,
      ),
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
      trackFailure = IllegalStateException("track_failed"),
      trackFailureEventNames = setOf(JourneyEvents.journeyGoalHit),
      queueStore = DroppingEventQueueStore(
        droppedEventNames = setOf(JourneyEvents.journeyGoalHit),
      ),
      presentFlowResult = false,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_drop", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)
      harness.eventService.flushEvents()
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(null, active.first().convertedAtEpochMillis)
      assertTrue(
        harness.eventService.getEventsForUser("user_1", limit = 20)
          .none { it.name == JourneyEvents.journeyGoalHit },
      )
      assertTrue(harness.api.trackedEvents.none { it.event == JourneyEvents.journeyGoalMet })
      assertTrue(harness.api.trackedEvents.none { it.event == "\$journey_completed" })
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_keepsTransientHitWhenHistoryInsertFails() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = JourneyEvents.journeyGoalHit,
      ),
      exitPolicy = ExitPolicy(ExitPolicy.Mode.ON_GOAL),
      historyStore = FailingInsertHistoryStore(),
      presentFlowResult = false,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_history_failure", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)

      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      assertTrue(harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_preservesPriorHitsWhenHistoryInsertFails() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.ATTRIBUTE,
        attributeExpr = IREnvelope(
          irVersion = 1,
          expr = IRExpr.Compare(
            op = ">",
            left = IRExpr.EventsCount(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
            ),
            right = IRExpr.Number(1.0),
          ),
        ),
      ),
      exitPolicy = null,
      historyStore = FailingInsertHistoryStore(),
      presentFlowResult = false,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_history_buffer", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_started",
        goalLabel = null,
        screenId = "screen_1",
      )
      delay(80)
      assertEquals(null, harness.service.getActiveJourneys("user_1").first().convertedAtEpochMillis)

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_completed",
        goalLabel = null,
        screenId = "screen_1",
      )
      delay(80)

      assertNotNull(harness.service.getActiveJourneys("user_1").first().convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_bufferParticipatesInLaterGoalReevaluationAfterHistoryInsertFails() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.ATTRIBUTE,
        attributeExpr = IREnvelope(
          irVersion = 1,
          expr = IRExpr.And(
            listOf(
              IRExpr.EventsExists(
                name = JourneyEvents.journeyGoalHit,
                whereExpr = IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
              ),
              IRExpr.EventsExists(name = "purchase_complete"),
            ),
          ),
        ),
      ),
      exitPolicy = null,
      historyStore = SelectiveFailingInsertHistoryStore(
        failingEventNames = setOf(JourneyEvents.journeyGoalHit),
      ),
      presentFlowResult = false,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_buffer_later_eval", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = null,
        screenId = "screen_1",
      )
      delay(80)
      assertEquals(null, harness.service.getActiveJourneys("user_1").first().convertedAtEpochMillis)

      val purchaseEvent = harness.eventService.trackForTrigger(
        event = "purchase_complete",
        persistToHistory = true,
      ).first
      harness.service.handleEventForTrigger(purchaseEvent)
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertNotNull(active.first().convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_doesNotDoubleCountSingleGoalHitDuringImmediateEvaluation() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.ATTRIBUTE,
        attributeExpr = IREnvelope(
          irVersion = 1,
          expr = IRExpr.Compare(
            op = ">",
            left = IRExpr.EventsCount(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
            ),
            right = IRExpr.Number(1.0),
          ),
        ),
      ),
      exitPolicy = null,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_single", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(null, active.first().convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedGoalEvent_doesNotDoubleCountSingleGoalHitAfterTrackFailure() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.ATTRIBUTE,
        attributeExpr = IREnvelope(
          irVersion = 1,
          expr = IRExpr.Compare(
            op = ">",
            left = IRExpr.EventsCount(
              name = JourneyEvents.journeyGoalHit,
              whereExpr = IRExpr.Pred("eq", "journey_id", IRExpr.JourneyId),
            ),
            right = IRExpr.Number(1.0),
          ),
        ),
      ),
      exitPolicy = null,
      trackFailure = IllegalStateException("track_failed"),
      trackFailureEventNames = setOf(JourneyEvents.journeyGoalHit),
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_goal_single_failure", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedGoalEvent(
        journeyId = started.id,
        goalId = "signup_complete",
        goalLabel = "Signed Up",
        screenId = "screen_1",
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(null, active.first().convertedAtEpochMillis)
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedNotificationPermissionEvent_resumesWaitUntilBeforeTrackReturns() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      trackDelayMillis = 750,
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_scope_wait", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      started.flowState.pendingAction = FlowPendingAction(
        interactionId = "wait_notifications",
        screenId = null,
        componentId = null,
        actionIndex = 0,
        kind = FlowPendingActionKind.WAIT_UNTIL,
        resumeAtEpochMillis = null,
        condition = null,
        maxTimeMs = null,
        startedAtEpochMillis = System.currentTimeMillis(),
        resumeActions = listOf(InteractionAction.Exit(reason = "completed")),
      )

      harness.service.handleScopedNotificationPermissionEvent(
        journeyId = started.id,
        eventName = SystemEventNames.notificationsEnabled,
        properties = mapOf("journey_id" to started.id),
      )

      delay(80)

      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      assertTrue(harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedNotificationPermissionEvent_honorsBeforeSendDrop() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = SystemEventNames.notificationsEnabled,
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
      beforeSend = { event ->
        if (event.name == SystemEventNames.notificationsEnabled) null else event
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_scope_drop", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedNotificationPermissionEvent(
        journeyId = started.id,
        eventName = SystemEventNames.notificationsEnabled,
        properties = mapOf("journey_id" to started.id),
      )
      delay(80)

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals(started.id, active.first().id)
      assertEquals(null, active.first().convertedAtEpochMillis)
      assertTrue(!harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun scopedNotificationPermissionEvent_completesPresentedJourneyAfterGoalAction() = runBlocking {
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = mapOf(
        "__global__" to listOf(
          Interaction(
            id = "notification_goal",
            trigger = InteractionTrigger.Event(eventName = SystemEventNames.notificationsEnabled),
            actions = listOf(InteractionAction.Goal(goalId = "signup_complete")),
            enabled = true,
          )
        )
      ),
      goal = GoalConfig(
        kind = GoalConfig.Kind.EVENT,
        eventName = JourneyEvents.journeyGoalHit,
      ),
      exitPolicy = ExitPolicy(mode = ExitPolicy.Mode.ON_GOAL),
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_scope_notification_goal", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first().journey

      harness.service.handleScopedNotificationPermissionEvent(
        journeyId = started.id,
        eventName = SystemEventNames.notificationsEnabled,
        properties = mapOf("journey_id" to started.id),
      )
      delay(80)

      assertTrue(harness.service.getActiveJourneys("user_1").isEmpty())
      assertTrue(harness.journeyStore.hasCompletedCampaign("user_1", "camp_1"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun runtimeDismiss_forwardsDismissedCallbackWithReasonAndError() = runBlocking {
    val dismissCalls = mutableListOf<DismissCall>()
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      onDismissed = { journeyId, campaignId, screenId, reason, error ->
        dismissCalls += DismissCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
          reason = reason,
          error = error,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_dismiss", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      started.journey.flowState.currentScreenId = "screen_1"
      harness.service.handleRuntimeDismiss(
        started.journey.id,
        CloseReason.Error(IllegalStateException("dismiss_failed")),
      )
      delay(80)

      assertEquals(1, dismissCalls.size)
      val callback = dismissCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_1", callback.screenId)
      assertEquals("error", callback.reason)
      assertEquals("dismiss_failed", callback.error)
    } finally {
      harness.close()
    }
  }

  @Test
  fun resumeFromServerState_restoresPausedJourney() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.EveryTime)
    try {
      harness.service.initialize()

      harness.service.resumeFromServerState(
        journeys = listOf(
          ActiveJourney(
            sessionId = "srv_journey_1",
            campaignId = "camp_1",
            currentNodeId = "screen_server",
            context = JsonObject(mapOf("server" to JsonPrimitive("state"))),
          )
        ),
        campaigns = harness.campaigns,
      )

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals("srv_journey_1", active.first().id)
      assertEquals(JourneyStatus.PAUSED, active.first().status)
      assertEquals("screen_server", active.first().flowState.currentScreenId)
      assertEquals("state", active.first().getContext("server")?.jsonPrimitive?.contentOrNull)
    } finally {
      harness.close()
    }
  }

  private data class DelegateCall(
    val journeyId: String,
    val campaignId: String?,
    val message: String,
    val payload: Any?,
  )

  private data class PurchaseCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
    val productId: String,
    val placementIndex: Any?,
  )

  private data class RestoreCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
  )

  private data class OpenLinkCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
    val url: String,
    val target: String?,
  )

  private data class DismissCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
    val reason: String,
    val error: String?,
  )

  private data class BackCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
    val steps: Int,
  )

  private data class Harness(
    val scope: CoroutineScope,
    val service: JourneyService,
    val api: FakeApi,
    val eventService: EventService,
    val journeyStore: JourneyStore,
    val broker: DefaultTriggerBroker,
    val presented: MutableList<Pair<String, String>>,
    val campaigns: List<Campaign>,
  ) {
    fun close() {
      scope.cancel()
    }
  }

  private suspend fun waitUntil(condition: suspend () -> Boolean) {
    withTimeout(1_000) {
      while (!condition()) {
        delay(10)
      }
    }
  }

  private fun newHarness(
    reentry: CampaignReentry,
    interactions: Map<String, List<Interaction>> = emptyMap(),
    goal: GoalConfig? = null,
    exitPolicy: ExitPolicy? = null,
    beforeSend: ((NuxieEvent) -> NuxieEvent?)? = null,
    trackDelayMillis: Long = 0,
    trackFailure: Throwable? = null,
    trackFailureEventNames: Set<String>? = null,
    historyStore: EventHistoryStore = InMemoryEventHistoryStore(),
    queueStore: EventQueueStore = InMemoryEventQueueStore(),
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    beforePresentFlow: (flowId: String, journeyId: String) -> Unit = { _, _ -> },
    presentFlowResult: Boolean = true,
    onCallDelegate: suspend (journeyId: String, campaignId: String?, message: String, payload: Any?) -> Unit = { _, _, _, _ -> },
    onPurchaseRequested: suspend (journeyId: String, campaignId: String?, screenId: String?, productId: String, placementIndex: Any?) -> Unit = { _, _, _, _, _ -> },
    onRestoreRequested: suspend (journeyId: String, campaignId: String?, screenId: String?) -> Unit = { _, _, _ -> },
    onOpenLinkRequested: suspend (journeyId: String, campaignId: String?, screenId: String?, url: String, target: String?) -> Unit = { _, _, _, _, _ -> },
    onDismissed: suspend (journeyId: String, campaignId: String?, screenId: String?, reason: String, error: String?) -> Unit = { _, _, _, _, _ -> },
    onBackRequested: suspend (journeyId: String, campaignId: String?, screenId: String?, steps: Int) -> Unit = { _, _, _, _ -> },
  ): Harness {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val identity = DefaultIdentityService(InMemoryKeyValueStore())
    identity.setDistinctId("user_1")

    val campaign = Campaign(
      id = "camp_1",
      name = "Campaign",
      flowId = "flow_1",
      flowNumber = 1,
      flowName = null,
      reentry = reentry,
      publishedAt = "2026-01-01T00:00:00Z",
      trigger = CampaignTrigger.Event(EventTriggerConfig(eventName = "paywall_trigger")),
      goal = goal,
      exitPolicy = exitPolicy,
      conversionAnchor = null,
      campaignType = null,
    )

    val remoteFlow = buildFlow(interactions)
    val profile = ProfileResponse(
      campaigns = listOf(campaign),
      segments = emptyList(),
      flows = listOf(remoteFlow),
      journeys = listOf<ActiveJourney>(),
      experiments = mapOf(
        "exp_1" to ExperimentAssignment(
          experimentKey = "exp_1",
          variantKey = "var_a",
          status = "running",
          isHoldout = false,
        )
      )
    )

    val api = FakeApi(
      profile = profile,
      remoteFlow = remoteFlow,
      trackDelayMillis = trackDelayMillis,
      trackFailure = trackFailure,
      trackFailureEventNames = trackFailureEventNames,
    )
    val config = NuxieConfiguration("test_key").apply {
      this.beforeSend = beforeSend
    }
    val networkQueue = NuxieNetworkQueue(
      store = queueStore,
      api = api,
      scope = scope,
      flushAt = 20,
      flushIntervalSeconds = 30,
      maxQueueSize = 1000,
      maxBatchSize = 50,
      maxRetries = 2,
      baseRetryDelaySeconds = 1,
    )
    val eventService = EventService(
      identityService = identity,
      sessionService = io.nuxie.sdk.session.DefaultSessionService(),
      configuration = config,
      api = api,
      store = queueStore,
      historyStore = historyStore,
      networkQueue = networkQueue,
      scope = scope,
    )
    val irRuntime = IRRuntime()
    val segmentService = SegmentService(
      identityService = identity,
      events = eventService,
      irRuntime = irRuntime,
      scope = scope,
      enableMonitoring = false,
    )
    val profileService = FakeProfileService(profile)
    val featureService = FakeFeatureService()
    val cacheDir = Files.createTempDirectory("nuxie_journey_flow_cache").toFile()
    val flowService = FlowService(
      api = api,
      configuration = config,
      scope = scope,
      cacheDirectory = cacheDir,
    )
    val journeyStore = FileJourneyStore(File(cacheDir, "journey_store"))
    val broker = DefaultTriggerBroker()
    val presented = mutableListOf<Pair<String, String>>()

    val service = JourneyService(
      scope = scope,
      configuration = config,
      identityService = identity,
      eventService = eventService,
      profileService = profileService,
      segmentService = segmentService,
      featureService = featureService,
      flowService = flowService,
      journeyStore = journeyStore,
      triggerBroker = broker,
      irRuntime = irRuntime,
      nowEpochMillis = nowEpochMillis,
      presentFlow = { flowId, journeyId ->
        beforePresentFlow(flowId, journeyId)
        presented += flowId to journeyId
        presentFlowResult
      },
      onCallDelegate = onCallDelegate,
      onPurchaseRequested = onPurchaseRequested,
      onRestoreRequested = onRestoreRequested,
      onOpenLinkRequested = onOpenLinkRequested,
      onDismissed = onDismissed,
      onBackRequested = onBackRequested,
    )

    return Harness(
      scope = scope,
      service = service,
      api = api,
      eventService = eventService,
      journeyStore = journeyStore,
      broker = broker,
      presented = presented,
      campaigns = listOf(campaign),
    )
  }

  private fun buildFlow(interactions: Map<String, List<Interaction>>): RemoteFlow {
    val vm = ViewModel(
      id = "vm_main",
      name = "vm_main",
      viewModelPathId = 100,
      properties = mapOf(
        "count" to ViewModelProperty(
          type = ViewModelPropertyType.NUMBER,
          propertyId = 1,
          defaultValue = JsonPrimitive(0),
        )
      ),
    )

    return RemoteFlow(
      id = "flow_1",
      bundle = FlowBundleRef(
        url = "https://example.com/flow_1",
        manifest = BuildManifest(
          totalFiles = 1,
          totalSize = 1L,
          contentHash = "sha256:abc",
          files = listOf(
            BuildManifestFile(
              path = "index.html",
              size = 1L,
              contentType = "text/html",
            )
          ),
        ),
      ),
      fontManifest = null,
      screens = listOf(
        RemoteFlowScreen(id = "screen_1", defaultViewModelId = "vm_main", defaultInstanceId = "vm_inst_1"),
      ),
      interactions = interactions,
      viewModels = listOf(vm),
      viewModelInstances = listOf(
        ViewModelInstance(
          viewModelId = "vm_main",
          instanceId = "vm_inst_1",
          name = "default",
          values = mapOf("count" to JsonPrimitive(0)),
        )
      ),
      converters = null,
    )
  }
}
