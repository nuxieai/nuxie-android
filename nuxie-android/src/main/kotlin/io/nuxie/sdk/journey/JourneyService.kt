package io.nuxie.sdk.journey

import androidx.activity.ComponentActivity
import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.ExitPolicy
import io.nuxie.sdk.campaigns.GoalConfig
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.events.SystemEventNames
import io.nuxie.sdk.events.store.StoredEvent
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.flows.Flow
import io.nuxie.sdk.flows.FlowRuntimeDelegate
import io.nuxie.sdk.flows.FlowService
import io.nuxie.sdk.flows.FlowView
import io.nuxie.sdk.flows.NotificationPermissionEventReceiver
import io.nuxie.sdk.flows.PermissionEventReceiver
import io.nuxie.sdk.flows.InteractionTrigger
import io.nuxie.sdk.flows.VmPathRef
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.ir.IRFeatureQueries
import io.nuxie.sdk.ir.IRExpr
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.ir.IRSegmentQueries
import io.nuxie.sdk.ir.IRUserProps
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.ActiveJourney
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.segments.SegmentService
import io.nuxie.sdk.triggers.DefaultTriggerBroker
import io.nuxie.sdk.triggers.JourneyExitReason
import io.nuxie.sdk.triggers.JourneyRef
import io.nuxie.sdk.triggers.JourneyUpdate
import io.nuxie.sdk.triggers.SuppressReason
import io.nuxie.sdk.triggers.TriggerBroker
import io.nuxie.sdk.triggers.TriggerUpdate
import io.nuxie.sdk.util.fromJsonElement
import io.nuxie.sdk.util.Iso8601
import io.nuxie.sdk.util.toJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed class JourneyTriggerResult {
  data class Started(val journey: Journey) : JourneyTriggerResult()
  data class Suppressed(val reason: SuppressReason) : JourneyTriggerResult()
}

class JourneyService(
  private val scope: CoroutineScope,
  private val configuration: NuxieConfiguration,
  private val identityService: IdentityService,
  private val eventService: EventService,
  private val profileService: ProfileService,
  private val segmentService: SegmentService,
  private val featureService: FeatureService,
  private val flowService: FlowService,
  private val api: NuxieApiProtocol? = null,
  private val journeyStore: JourneyStore,
  private val triggerBroker: TriggerBroker = DefaultTriggerBroker(),
  private val irRuntime: IRRuntime = IRRuntime(),
  private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
  private val presentFlow: suspend (flowId: String, journeyId: String) -> Boolean,
  private val onCallDelegate: suspend (
    journeyId: String,
    campaignId: String?,
    message: String,
    payload: Any?,
  ) -> Unit = { _, _, _, _ -> },
  private val onPurchaseRequested: suspend (
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    productId: String,
    placementIndex: Any?,
  ) -> Unit = { _, _, _, _, _ -> },
  private val onRestoreRequested: suspend (
    journeyId: String,
    campaignId: String?,
    screenId: String?,
  ) -> Unit = { _, _, _ -> },
  private val onOpenLinkRequested: suspend (
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    url: String,
    target: String?,
  ) -> Unit = { _, _, _, _, _ -> },
  private val onDismissed: suspend (
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    reason: String,
    error: String?,
  ) -> Unit = { _, _, _, _, _ -> },
  private val onBackRequested: suspend (
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    steps: Int,
  ) -> Unit = { _, _, _, _ -> },
) {
  companion object {
    private const val EXPLICIT_GOAL_HITS_CONTEXT_KEY = "__explicit_goal_hits"
    private const val EXPLICIT_GOAL_EVENTS_CONTEXT_KEY = "__explicit_goal_events"
    private const val SCOPED_GOAL_EVENTS_CONTEXT_KEY = "__scoped_goal_events"
    private const val MAX_EXPLICIT_GOAL_EVENTS = 32
    private const val MAX_SCOPED_GOAL_EVENTS = 32
  }

  private val inMemoryJourneysById: MutableMap<String, Journey> = mutableMapOf()
  private val flowRunners: MutableMap<String, FlowJourneyRunner> = mutableMapOf()
  private val runtimeDelegates: MutableMap<String, FlowRuntimeDelegateAdapter> = mutableMapOf()
  private val presentedJourneyIds: MutableSet<String> = mutableSetOf()
  private val activeTasks: MutableMap<String, Job> = mutableMapOf()
  private val journeyPersistenceMutex = Mutex()
  private var segmentMonitoringJob: Job? = null

  private val goalEvaluator: GoalEvaluator = DefaultGoalEvaluator(
    loadEventsForUser = { distinctId, limit -> eventService.getEventsForUser(distinctId, limit) },
    segmentQueries = segmentService as IRSegmentQueries,
    featureQueries = object : IRFeatureQueries {
      override suspend fun has(featureId: String): Boolean {
        return featureService.getCached(featureId, null)?.allowed == true
      }

      override suspend fun isUnlimited(featureId: String): Boolean {
        return featureService.getCached(featureId, null)?.unlimited == true
      }

      override suspend fun getBalance(featureId: String): Int? {
        return featureService.getCached(featureId, null)?.balance
      }
    },
    userProps = IRUserProps { key ->
      identityService.userProperty(key)?.let { fromJsonElement(it) }
    },
    eventQueries = eventService,
    irRuntime = irRuntime,
    nowEpochMillis = nowEpochMillis,
  )

  suspend fun initialize() {
    val restored = journeyStore.loadActiveJourneys()
    for (journey in restored) {
      if (!journey.status.isLive) continue
      inMemoryJourneysById[journey.id] = journey
      val resumeAt = journey.flowState.pendingAction?.resumeAtEpochMillis
      if (resumeAt != null) {
        scheduleResume(journey.id, resumeAt)
      }
    }
    checkExpiredTimers()
    registerForSegmentChanges()
  }

  suspend fun shutdown() {
    segmentMonitoringJob?.cancel()
    segmentMonitoringJob = null
    cancelAllTasks()
  }

  suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {
    val oldJourneys = getActiveJourneys(fromOldDistinctId)
    for (journey in oldJourneys) {
      cancelJourney(journey)
    }

    inMemoryJourneysById.entries.removeAll { it.value.distinctId == fromOldDistinctId }

    val restored = journeyStore.loadActiveJourneys().filter { it.distinctId == toNewDistinctId && it.status.isLive }
    for (journey in restored) {
      inMemoryJourneysById[journey.id] = journey
      val resumeAt = journey.flowState.pendingAction?.resumeAtEpochMillis
      if (resumeAt != null) {
        scheduleResume(journey.id, resumeAt)
      }
    }
    checkExpiredTimers()
  }

  suspend fun handleEventForTrigger(event: NuxieEvent): List<JourneyTriggerResult> {
    val campaigns = getAllCampaigns(event.distinctId) ?: return emptyList()
    val results = mutableListOf<JourneyTriggerResult>()

    for (campaign in campaigns) {
      if (!shouldTriggerFromEvent(campaign, event)) continue
      val reason = suppressionReason(campaign, event.distinctId)
      if (reason != null) {
        results += JourneyTriggerResult.Suppressed(reason)
        continue
      }
      val journey = startJourneyInternal(campaign, event.distinctId, originEventId = event.id)
      if (journey != null) {
        results += JourneyTriggerResult.Started(journey)
      } else {
        results += JourneyTriggerResult.Suppressed(SuppressReason.Unknown("start_failed"))
      }
    }

    val activeJourneys = getActiveJourneys(event.distinctId)
    for (journey in activeJourneys) {
      val campaign = campaigns.firstOrNull { it.id == journey.campaignId } ?: continue
      evaluateGoalIfNeeded(journey, campaign)
      if (!shouldDeferExitDecision(journey.id)) {
        val exit = exitDecision(journey, campaign)
        if (exit != null) {
          completeJourney(journey, exit)
          continue
        }
      }

      val pending = journey.flowState.pendingAction
      if (pending != null && pending.kind == FlowPendingActionKind.WAIT_UNTIL) {
        val runner = flowRunners[journey.id]
        if (runner != null) {
          val outcome = runner.resumePendingAction(ResumeReason.EVENT, event)
          handleOutcome(outcome, journey)
        }
        continue
      }

      val runner = flowRunners[journey.id]
      if (runner != null) {
        val outcome = runner.dispatchEventTrigger(event)
        handleOutcome(outcome, journey)
      }
    }

    return results
  }

  suspend fun handleSegmentChange(distinctId: String, segments: Set<String>) {
    val campaigns = getAllCampaigns(distinctId) ?: return

    for (campaign in campaigns) {
      val trigger = campaign.trigger as? CampaignTrigger.Segment ?: continue
      val matches = evalConditionIR(trigger.config.condition, event = null)
      if (matches) {
        startJourney(campaign, distinctId, originEventId = null)
      }
    }

    val journeys = getActiveJourneys(distinctId)
    for (journey in journeys) {
      val campaign = campaigns.firstOrNull { it.id == journey.campaignId } ?: continue
      evaluateGoalIfNeeded(journey, campaign)
      if (!shouldDeferExitDecision(journey.id)) {
        val exit = exitDecision(journey, campaign)
        if (exit != null) {
          completeJourney(journey, exit)
        }
      }
    }
  }

  suspend fun checkExpiredTimers() {
    val now = nowEpochMillis()
    val liveJourneys = inMemoryJourneysById.values.filter { it.status.isLive }
    for (journey in liveJourneys) {
      val pending = journey.flowState.pendingAction
      val resumeAt = pending?.resumeAtEpochMillis
      if (resumeAt != null && resumeAt <= now) {
        resumeJourney(journey)
      }
    }
  }

  suspend fun getActiveJourneys(distinctId: String): List<Journey> {
    return inMemoryJourneysById.values.filter { it.distinctId == distinctId && it.status.isLive }
  }

  suspend fun startJourney(campaign: Campaign, distinctId: String, originEventId: String?): Journey? {
    val reason = suppressionReason(campaign, distinctId)
    if (reason != null) return null
    return startJourneyInternal(campaign, distinctId, originEventId)
  }

  suspend fun createFlowViewForJourney(activity: ComponentActivity, flowId: String, journeyId: String): FlowView {
    val delegate = runtimeDelegates[journeyId] ?: FlowRuntimeDelegateAdapter(journeyId, this, scope).also {
      runtimeDelegates[journeyId] = it
    }

    val view = flowService.getFlowView(
      activity = activity,
      flowId = flowId,
      viewId = io.nuxie.sdk.R.id.nuxie_flow_view,
      runtimeDelegate = delegate,
    )
    delegate.attachFlowView(view)
    presentedJourneyIds += journeyId
    flowRunners[journeyId]?.attach(delegate)
    return view
  }

  suspend fun handleRuntimeMessage(
    journeyId: String,
    type: String,
    payload: JsonObject,
    id: String?,
  ) {
    val journey = inMemoryJourneysById[journeyId] ?: return
    val runner = flowRunners[journeyId] ?: return

    when (type) {
      "runtime/ready" -> {
        val outcome = runner.handleRuntimeReady()
        handleOutcome(outcome, journey)
      }

      "runtime/screen_changed" -> {
        val screenId = payload.string("screenId") ?: return
        val outcome = runner.handleScreenChanged(screenId)
        handleOutcome(outcome, journey)
        persistJourney(journey)

        eventService.track(
          "\$journey_node_executed",
          properties = mapOf(
            "session_id" to journey.id,
            "node_id" to screenId,
            "async" to true,
            "context" to journey.context.mapValues { (_, v) -> fromJsonElement(v) },
          )
        )
      }

      "action/did_set" -> {
        val path = parsePathRef(payload) ?: return
        val value = payload["value"] ?: JsonNull
        val source = payload.string("source")
        val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
        val instanceId = payload.string("instanceId")
        val outcome = runner.handleDidSet(path, value, source, screenId, instanceId)
        handleOutcome(outcome, journey)
        persistJourney(journey)
      }

      "action/event" -> {
        val name = payload.string("name") ?: ""
        if (name.isNotBlank()) {
          val props = (payload["properties"] as? JsonObject)?.toMapAny()
          eventService.track(name, properties = props)
        }
      }

      "action/goal" -> {
        val goalId = payload.string("goalId")?.trim().orEmpty()
        if (goalId.isBlank()) return
        val rawGoalLabel = payload.string("goalLabel") ?: payload.string("label")
        val goalLabel = rawGoalLabel?.trim()?.takeIf { it.isNotEmpty() }
        val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
        val interactionId = payload.string("interactionId")
        handleScopedGoalEvent(
          journeyId = journey.id,
          goalId = goalId,
          goalLabel = goalLabel,
          screenId = screenId,
          interactionId = interactionId,
        )
      }

      "action/purchase" -> {
        val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
        val instanceId = payload.string("instanceId")
        val resolvedProductId = runner.resolveRuntimeValue(payload["productId"] ?: JsonPrimitive(""), screenId, instanceId) as? String
        if (!resolvedProductId.isNullOrBlank()) {
          val placementIndex = runner.resolveRuntimeValue(payload["placementIndex"] ?: JsonNull, screenId, instanceId)
          runtimeDelegates[journeyId]?.performPurchase(resolvedProductId, placementIndex)
        }
      }

      "action/restore" -> {
        runtimeDelegates[journeyId]?.performRestore()
      }

      "action/request_notifications" -> {
        runtimeDelegates[journeyId]?.performRequestNotifications(journey.id)
      }

      "action/request_permission" -> {
        val permissionType = payload.string("permissionType") ?: return
        runtimeDelegates[journeyId]?.performRequestPermission(permissionType, journey.id)
      }

      "action/open_link" -> {
        val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
        val instanceId = payload.string("instanceId")
        val target = payload.string("target")
        runner.handleRuntimeOpenLink(payload["url"] ?: JsonPrimitive(""), target, screenId, instanceId)
      }

      "action/back" -> {
        val steps = payload.int("steps") ?: payload.int("step")
        val transition = payload["transition"]
        runner.handleRuntimeBack(steps, transition)
      }

      else -> {
        if (type.startsWith("action/")) {
          val trigger = parseRuntimeTrigger(type, payload)
          if (trigger != null) {
            val screenId = payload.string("screenId") ?: journey.flowState.currentScreenId
            val componentId = payload.string("componentId")
            val instanceId = payload.string("instanceId")
            val outcome = runner.dispatchTrigger(
              trigger = trigger,
              screenId = screenId,
              componentId = componentId,
              instanceId = instanceId,
              event = null,
            )
            handleOutcome(outcome, journey)
            persistJourney(journey)
          }
        }
      }
    }
  }

  suspend fun handleRuntimeDismiss(journeyId: String, reason: io.nuxie.sdk.flows.CloseReason) {
    val journey = inMemoryJourneysById[journeyId] ?: return
    val runner = flowRunners[journeyId] ?: return
    presentedJourneyIds.remove(journeyId)

    val method = when (reason) {
      io.nuxie.sdk.flows.CloseReason.UserDismissed -> "user"
      io.nuxie.sdk.flows.CloseReason.PurchaseCompleted -> "purchase_completed"
      io.nuxie.sdk.flows.CloseReason.Timeout -> "timeout"
      is io.nuxie.sdk.flows.CloseReason.Error -> "error"
    }
    val callbackReason = when (reason) {
      io.nuxie.sdk.flows.CloseReason.UserDismissed -> "user_dismissed"
      io.nuxie.sdk.flows.CloseReason.PurchaseCompleted -> "purchase_completed"
      io.nuxie.sdk.flows.CloseReason.Timeout -> "timeout"
      is io.nuxie.sdk.flows.CloseReason.Error -> "error"
    }
    val callbackError = (reason as? io.nuxie.sdk.flows.CloseReason.Error)?.throwable?.message

    val props = mutableMapOf<String, Any?>(
      "method" to method,
    )
    val screenId = journey.flowState.currentScreenId
    if (!screenId.isNullOrBlank()) {
      props["screen_id"] = screenId
    }

    val event = NuxieEvent(
      name = SystemEventNames.screenDismissed,
      distinctId = journey.distinctId,
      properties = props,
    )

    val outcome = runner.dispatchEventTrigger(event)
    handleOutcome(outcome, journey)
    runner.abandonResponseDraftsIfNeeded()
    dispatchDismissedCallback(
      journeyId = journeyId,
      reason = callbackReason,
      error = callbackError,
    )

    if (journey.status.isLive) {
      val campaign = getCampaign(journey.campaignId, journey.distinctId)
      if (campaign != null) {
        evaluateGoalIfNeeded(journey, campaign)
        val exit = exitDecision(journey, campaign)
        if (exit != null) {
          completeJourney(journey, exit)
          return
        }
      }
    }

    if (journey.status.isLive && !runner.hasPendingWork()) {
      val exitReason = when (reason) {
        io.nuxie.sdk.flows.CloseReason.UserDismissed -> JourneyExitReason.DISMISSED
        is io.nuxie.sdk.flows.CloseReason.Error -> JourneyExitReason.ERROR
        io.nuxie.sdk.flows.CloseReason.PurchaseCompleted,
        io.nuxie.sdk.flows.CloseReason.Timeout -> JourneyExitReason.COMPLETED
      }
      completeJourney(journey, exitReason)
    }
  }

  suspend fun handleScopedNotificationPermissionEvent(
    journeyId: String,
    eventName: String,
    properties: Map<String, Any?>,
  ) {
    val journey = inMemoryJourneysById[journeyId] ?: return
    val campaign = getCampaign(journey.campaignId, journey.distinctId) ?: return

    val event = try {
      eventService.prepareTriggerEvent(
        event = eventName,
        properties = properties,
      )
    } catch (error: Throwable) {
      NuxieLogger.warning(
        "JourneyService: Failed to prepare scoped notification event: ${error.message}",
        error,
      )
      return
    }

    scope.launch {
      runCatching {
        eventService.trackForTrigger(
          eventName,
          properties = properties,
          persistToHistory = false,
        )
      }.onFailure { error ->
        NuxieLogger.warning(
          "JourneyService: Failed to track scoped notification event: ${error.message}",
          error,
        )
      }
    }

    val transientEvent = storedEvent(event)

    evaluateGoalIfNeeded(journey, campaign, transientEvents = listOf(transientEvent))
    if (!shouldDeferExitDecision(journey.id)) {
      val exit = exitDecision(journey, campaign)
      if (exit != null) {
        completeJourney(journey, exit)
        return
      }
    }

    val pending = journey.flowState.pendingAction
    if (pending != null && pending.kind == FlowPendingActionKind.WAIT_UNTIL) {
      val runner = flowRunners[journey.id]
      if (runner != null) {
        val outcome = runner.resumePendingAction(ResumeReason.EVENT, event)
        handleOutcome(outcome, journey)
      }
      if (shouldCompletePresentedScopedGoalJourney(journey, campaign)) {
        completeJourney(journey, JourneyExitReason.GOAL_MET)
        return
      }
      if (journey.status.isLive) {
        persistJourney(journey)
      }
      return
    }

    val runner = flowRunners[journey.id]
    if (runner != null) {
      val outcome = runner.dispatchEventTrigger(event)
      handleOutcome(outcome, journey)
    }
    if (shouldCompletePresentedScopedGoalJourney(journey, campaign)) {
      completeJourney(journey, JourneyExitReason.GOAL_MET)
      return
    }
    if (journey.status.isLive) {
      persistJourney(journey)
    }
  }

  suspend fun handleScopedPermissionEvent(
    journeyId: String,
    eventName: String,
    properties: Map<String, Any?>,
  ) {
    val journey = inMemoryJourneysById[journeyId] ?: return
    val campaign = getCampaign(journey.campaignId, journey.distinctId) ?: return

    val event = try {
      eventService.prepareTriggerEvent(
        event = eventName,
        properties = properties,
      )
    } catch (error: Throwable) {
      NuxieLogger.warning(
        "JourneyService: Failed to prepare scoped permission event: ${error.message}",
        error,
      )
      return
    }

    scope.launch {
      runCatching {
        eventService.trackForTrigger(
          eventName,
          properties = properties,
          persistToHistory = false,
        )
      }.onFailure { error ->
        NuxieLogger.warning(
          "JourneyService: Failed to track scoped permission event: ${error.message}",
          error,
        )
      }
    }

    val transientEvent = storedEvent(event)

    evaluateGoalIfNeeded(journey, campaign, transientEvents = listOf(transientEvent))
    if (!shouldDeferExitDecision(journey.id)) {
      val exit = exitDecision(journey, campaign)
      if (exit != null) {
        completeJourney(journey, exit)
        return
      }
    }

    val pending = journey.flowState.pendingAction
    if (pending != null && pending.kind == FlowPendingActionKind.WAIT_UNTIL) {
      val runner = flowRunners[journey.id]
      if (runner != null) {
        val outcome = runner.resumePendingAction(ResumeReason.EVENT, event)
        handleOutcome(outcome, journey)
      }
      if (shouldCompletePresentedScopedGoalJourney(journey, campaign)) {
        completeJourney(journey, JourneyExitReason.GOAL_MET)
        return
      }
      if (journey.status.isLive) {
        persistJourney(journey)
      }
      return
    }

    val runner = flowRunners[journey.id]
    if (runner != null) {
      val outcome = runner.dispatchEventTrigger(event)
      handleOutcome(outcome, journey)
    }
    if (shouldCompletePresentedScopedGoalJourney(journey, campaign)) {
      completeJourney(journey, JourneyExitReason.GOAL_MET)
      return
    }
    if (journey.status.isLive) {
      persistJourney(journey)
    }
  }

  internal suspend fun handleScopedGoalEvent(
    journeyId: String,
    goalId: String,
    goalLabel: String?,
    screenId: String?,
    interactionId: String? = null,
  ) {
    val journey = inMemoryJourneysById[journeyId] ?: return
    val campaign = getCampaign(journey.campaignId, journey.distinctId) ?: return
    val normalizedGoalId = goalId.trim()
    if (normalizedGoalId.isEmpty()) return
    val normalizedGoalLabel = goalLabel?.trim()?.takeIf { it.isNotEmpty() }
    val preparedEvent = try {
      eventService.prepareTriggerEvent(
        event = JourneyEvents.journeyGoalHit,
        properties = JourneyEvents.journeyGoalHitProperties(
          journey = journey,
          screenId = screenId,
          interactionId = interactionId,
          goalId = normalizedGoalId,
          goalLabel = normalizedGoalLabel,
        ),
      )
    } catch (error: Throwable) {
      NuxieLogger.warning(
        "JourneyService: Failed to prepare scoped goal event: ${error.message}",
        error,
      )
      return
    }

    val tracked = try {
      eventService.trackPreparedForTrigger(
        event = preparedEvent,
        persistToHistory = true,
      )
    } catch (error: Throwable) {
      NuxieLogger.warning(
        "JourneyService: Failed to track scoped goal event: ${error.message}",
        error,
      )
      val requeued = runCatching {
        eventService.enqueuePreparedEvent(
          event = preparedEvent,
          persistToHistory = false,
        )
      }.onFailure { fallbackError ->
        NuxieLogger.warning(
          "JourneyService: Failed to requeue scoped goal event: ${fallbackError.message}",
          fallbackError,
        )
      }.getOrNull()
      if (requeued == false) {
        runCatching { eventService.deleteHistoryEvent(preparedEvent.id) }
        NuxieLogger.warning("JourneyService: Dropping scoped goal evaluation because fallback queue rejected the goal hit")
        return
      }
      null
    }

    val event = tracked?.first ?: preparedEvent
    val storedPreparedEvent = storedEvent(preparedEvent)
    recordScopedGoalEvent(journey, storedPreparedEvent)
    val transientEvents = scopedGoalTransientEvents(journey, storedPreparedEvent)

    evaluateGoalIfNeeded(journey, campaign, transientEvents = transientEvents)
    if (!shouldDeferExitDecision(journey.id)) {
      val exit = exitDecision(journey, campaign)
      if (exit != null) {
        completeJourney(journey, exit)
        return
      }
    }

    val pending = journey.flowState.pendingAction
    if (pending != null && pending.kind == FlowPendingActionKind.WAIT_UNTIL) {
      val runner = flowRunners[journey.id]
      if (runner != null) {
        val outcome = runner.resumePendingAction(ResumeReason.EVENT, event)
        handleOutcome(outcome, journey)
      }
      if (shouldCompletePresentedScopedGoalJourney(journey, campaign)) {
        completeJourney(journey, JourneyExitReason.GOAL_MET)
        return
      }
      if (journey.status.isLive) {
        persistJourney(journey)
      }
      return
    }

    val runner = flowRunners[journey.id]
    if (runner != null) {
      val outcome = runner.dispatchEventTrigger(event)
      handleOutcome(outcome, journey)
    }
    if (shouldCompletePresentedScopedGoalJourney(journey, campaign)) {
      completeJourney(journey, JourneyExitReason.GOAL_MET)
      return
    }
    if (journey.status.isLive) {
      persistJourney(journey)
    }
  }

  suspend fun resumeFromServerState(journeys: List<ActiveJourney>, campaigns: List<Campaign>) {
    for (active in journeys) {
      val existing = inMemoryJourneysById[active.sessionId]
      if (existing != null) {
        existing.setContext("_server_resume", true, nowEpochMillis = nowEpochMillis())
        persistJourney(existing)
        continue
      }

      val campaign = campaigns.firstOrNull { it.id == active.campaignId }
      if (campaign == null) {
        NuxieLogger.warning("Campaign ${active.campaignId} not found for server journey ${active.sessionId}")
        continue
      }

      val journey = Journey(
        campaign = campaign,
        distinctId = identityService.getDistinctId(),
        id = active.sessionId,
        nowEpochMillis = nowEpochMillis(),
      )
      journey.status = JourneyStatus.PAUSED
      journey.flowState.currentScreenId = active.currentNodeId
      journey.context = active.context.toMutableMap()

      inMemoryJourneysById[journey.id] = journey
      persistJourney(journey)

      val resumeAt = journey.flowState.pendingAction?.resumeAtEpochMillis
      if (resumeAt != null) {
        scheduleResume(journey.id, resumeAt)
      }
    }
  }

  suspend fun dispatchDelegateCallback(journeyId: String, message: String, payload: Any?) {
    val journey = inMemoryJourneysById[journeyId]
    val campaignId = journey?.campaignId
    runCatching {
      onCallDelegate(journeyId, campaignId, message, payload)
    }.onFailure { error ->
      NuxieLogger.warning("JourneyService: call_delegate callback failed for journey=$journeyId: ${error.message}")
    }
  }

  suspend fun dispatchPurchaseRequested(journeyId: String, productId: String, placementIndex: Any?) {
    val journey = inMemoryJourneysById[journeyId]
    runCatching {
      onPurchaseRequested(
        journeyId,
        journey?.campaignId,
        journey?.flowState?.currentScreenId,
        productId,
        placementIndex,
      )
    }.onFailure { error ->
      NuxieLogger.warning("JourneyService: purchase callback failed for journey=$journeyId: ${error.message}")
    }
  }

  suspend fun dispatchRestoreRequested(journeyId: String) {
    val journey = inMemoryJourneysById[journeyId]
    runCatching {
      onRestoreRequested(
        journeyId,
        journey?.campaignId,
        journey?.flowState?.currentScreenId,
      )
    }.onFailure { error ->
      NuxieLogger.warning("JourneyService: restore callback failed for journey=$journeyId: ${error.message}")
    }
  }

  suspend fun dispatchOpenLinkRequested(journeyId: String, url: String, target: String?) {
    val journey = inMemoryJourneysById[journeyId]
    runCatching {
      onOpenLinkRequested(
        journeyId,
        journey?.campaignId,
        journey?.flowState?.currentScreenId,
        url,
        target,
      )
    }.onFailure { error ->
      NuxieLogger.warning("JourneyService: open_link callback failed for journey=$journeyId: ${error.message}")
    }
  }

  suspend fun dispatchBackRequested(journeyId: String, steps: Int) {
    val journey = inMemoryJourneysById[journeyId]
    runCatching {
      onBackRequested(
        journeyId,
        journey?.campaignId,
        journey?.flowState?.currentScreenId,
        steps,
      )
    }.onFailure { error ->
      NuxieLogger.warning("JourneyService: back callback failed for journey=$journeyId: ${error.message}")
    }
  }

  suspend fun dispatchDismissedCallback(journeyId: String, reason: String, error: String?) {
    val journey = inMemoryJourneysById[journeyId]
    runCatching {
      onDismissed(
        journeyId,
        journey?.campaignId,
        journey?.flowState?.currentScreenId,
        reason,
        error,
      )
    }.onFailure { err ->
      NuxieLogger.warning("JourneyService: dismiss callback failed for journey=$journeyId: ${err.message}")
    }
  }

  private suspend fun startJourneyInternal(campaign: Campaign, distinctId: String, originEventId: String?): Journey? {
    if (campaign.flowId.isBlank()) return null

    val journey = Journey(campaign = campaign, distinctId = distinctId).apply {
      status = JourneyStatus.ACTIVE
    }
    if (!originEventId.isNullOrBlank()) {
      journey.setContext("_origin_event_id", originEventId)
    }
    inMemoryJourneysById[journey.id] = journey

    val flow = runCatching { flowService.fetchFlow(campaign.flowId) }.getOrNull()
    val entryScreenId = flow?.remoteFlow?.screens?.firstOrNull()?.id

    val startEvent = try {
      eventService.prepareTriggerEvent(
        "\$journey_start",
        properties = mapOf(
          "session_id" to journey.id,
          "campaign_id" to campaign.id,
          "flow_id" to campaign.flowId,
          "entry_node_id" to entryScreenId,
        )
      )
    } catch (error: Throwable) {
      inMemoryJourneysById.remove(journey.id)
      NuxieLogger.warning("JourneyService: Failed to persist journey start: ${error.message}", error)
      return null
    }

    try {
      eventService.trackPreparedForTrigger(
        startEvent,
        persistToHistory = true,
      )
    } catch (error: Throwable) {
      runCatching { eventService.deleteHistoryEvent(startEvent.id) }
      inMemoryJourneysById.remove(journey.id)
      NuxieLogger.warning("JourneyService: Failed to persist journey start: ${error.message}", error)
      return null
    }

    val currentJourney = inMemoryJourneysById[journey.id]
    if (currentJourney !== journey || !currentJourney.status.isLive) {
      return null
    }

    persistJourney(journey)

    eventService.track(
      JourneyEvents.journeyStarted,
      properties = JourneyEvents.journeyStartedProperties(
        journey = journey,
        campaign = campaign,
        triggerEvent = null,
        entryScreenId = entryScreenId,
      )
    )

    val runner = ensureRunner(journey, campaign, flow)
    if (runner == null) {
      completeJourney(journey, JourneyExitReason.ERROR)
      return journey
    }

    return journey
  }

  private suspend fun resumeJourney(journey: Journey) {
    if (journey.status != JourneyStatus.PAUSED && journey.status != JourneyStatus.ACTIVE) return

    val campaign = getCampaign(journey.campaignId, journey.distinctId)
    if (campaign == null) {
      cancelJourney(journey)
      return
    }

    val runner = ensureRunner(journey, campaign, null)
    if (runner == null) {
      completeJourney(journey, JourneyExitReason.ERROR)
      return
    }

    journey.resume(nowEpochMillis())
    inMemoryJourneysById[journey.id] = journey

    val outcome = runner.resumePendingAction(ResumeReason.TIMER, event = null)
    handleOutcome(outcome, journey)

    eventService.track(
      JourneyEvents.journeyResumed,
      properties = JourneyEvents.journeyResumedProperties(
        journey = journey,
        screenId = journey.flowState.currentScreenId,
        resumeReason = "timer",
      )
    )
  }

  private suspend fun ensureRunner(
    journey: Journey,
    campaign: Campaign,
    preloadedFlow: Flow?,
  ): FlowJourneyRunner? {
    val existing = flowRunners[journey.id]
    if (existing != null) return existing

    val flow = preloadedFlow ?: runCatching { flowService.fetchFlow(campaign.flowId) }.getOrNull() ?: return null

    val delegate = runtimeDelegates[journey.id] ?: FlowRuntimeDelegateAdapter(journey.id, this, scope).also {
      runtimeDelegates[journey.id] = it
    }

    val runner = FlowJourneyRunner(
      journey = journey,
      campaign = campaign,
      flow = flow,
      host = delegate,
      eventService = eventService,
      identityService = identityService,
      segmentService = segmentService,
      featureService = featureService,
      profileService = profileService,
      api = api,
      irRuntime = irRuntime,
      scope = scope,
      nowEpochMillis = nowEpochMillis,
      onGoalActionHit = { goalEvent ->
        handleExplicitGoalActionHit(journey, campaign, goalEvent)
      },
    )
    flowRunners[journey.id] = runner

    val shown = presentFlow(campaign.flowId, journey.id)
    if (shown) {
      journey.markFlowShown(nowEpochMillis())
      presentedJourneyIds += journey.id
      persistJourney(journey)
      eventService.track(
        JourneyEvents.flowShown,
        properties = JourneyEvents.flowShownProperties(flowId = campaign.flowId, journey = journey)
      )
    }

    return runner
  }

  private fun handleOutcome(outcome: FlowRunOutcome?, journey: Journey) {
    when (outcome) {
      is FlowRunOutcome.Paused -> {
        journey.pause(outcome.pending.resumeAtEpochMillis, nowEpochMillis())
        persistJourney(journey)
        val resumeAt = outcome.pending.resumeAtEpochMillis
        if (resumeAt != null) {
          scheduleResume(journey.id, resumeAt)
        }
        eventService.track(
          JourneyEvents.journeyPaused,
          properties = JourneyEvents.journeyPausedProperties(
            journey = journey,
            screenId = journey.flowState.currentScreenId,
            resumeAtEpochMillis = resumeAt,
          )
        )
      }
      is FlowRunOutcome.Exited -> {
        completeJourney(journey, outcome.reason)
      }
      null -> Unit
    }
  }

  private fun scheduleResume(journeyId: String, resumeAtEpochMillis: Long) {
    val key = "$journeyId:resume"
    activeTasks[key]?.cancel()
    activeTasks[key] = scope.launch(Dispatchers.Default) {
      val delayMs = maxOf(0L, resumeAtEpochMillis - nowEpochMillis())
      delay(delayMs)
      val journey = inMemoryJourneysById[journeyId] ?: return@launch
      resumeJourney(journey)
      activeTasks.remove(key)
    }
  }

  private fun cancelAllTasks() {
    for ((_, task) in activeTasks) {
      task.cancel()
    }
    activeTasks.clear()
  }

  private fun cancelTasksForJourney(journeyId: String) {
    val keys = activeTasks.keys.filter { it.startsWith("$journeyId:") }
    for (key in keys) {
      activeTasks[key]?.cancel()
      activeTasks.remove(key)
    }
  }

  private fun persistJourney(journey: Journey) {
    scope.launch {
      runCatching {
        journeyPersistenceMutex.withLock {
          if (journey.status.isLive) {
            journeyStore.saveJourney(journey)
            journeyStore.updateCache(journey)
          } else {
            journeyStore.deleteJourney(journey.id)
          }
        }
      }.onFailure {
        NuxieLogger.warning("Failed to persist journey ${journey.id}: ${it.message}", it)
      }
    }
  }

  private fun completeJourney(journey: Journey, reason: JourneyExitReason) {
    journey.complete(reason, nowEpochMillis())

    val durationSeconds = ((journey.completedAtEpochMillis ?: nowEpochMillis()) - journey.startedAtEpochMillis).toDouble() / 1000.0

    eventService.track(
      "\$journey_completed",
      properties = mapOf(
        "session_id" to journey.id,
        "exit_reason" to exitReasonString(reason),
        "goal_met" to (journey.convertedAtEpochMillis != null),
        "goal_met_at" to journey.convertedAtEpochMillis?.let { it / 1000.0 },
        "duration_seconds" to durationSeconds,
      )
    )

    eventService.track(
      JourneyEvents.journeyExited,
      properties = JourneyEvents.journeyExitedProperties(
        journey = journey,
        reason = reason,
        screenId = journey.flowState.currentScreenId,
      )
    )

    val originEventId = (journey.getContext("_origin_event_id") as? JsonPrimitive)?.contentOrNull
      ?: fromJsonElement(journey.getContext("_origin_event_id") ?: JsonNull) as? String
    if (!originEventId.isNullOrBlank()) {
      val update = TriggerUpdate.Journey(
        JourneyUpdate(
          journeyId = journey.id,
          campaignId = journey.campaignId,
          flowId = journey.flowId,
          exitReason = reason,
          goalMet = journey.convertedAtEpochMillis != null,
          goalMetAtEpochMillis = journey.convertedAtEpochMillis,
          durationSeconds = durationSeconds,
          flowExitReason = null,
        )
      )
      scope.launch {
        triggerBroker.emit(originEventId, update)
      }
    }

    cancelTasksForJourney(journey.id)
    presentedJourneyIds.remove(journey.id)
    flowRunners.remove(journey.id)
    runtimeDelegates.remove(journey.id)
    inMemoryJourneysById.remove(journey.id)

    scope.launch {
      runCatching {
        journeyPersistenceMutex.withLock {
          journeyStore.deleteJourney(journey.id)
          journeyStore.recordCompletion(JourneyCompletionRecord(journey))
        }
      }
    }
  }

  private fun cancelJourney(journey: Journey) {
    journey.cancel(nowEpochMillis())
    completeJourney(journey, JourneyExitReason.CANCELLED)
  }

  private suspend fun evaluateGoalIfNeeded(
    journey: Journey,
    campaign: Campaign,
    transientEvents: List<StoredEvent> = emptyList(),
  ) {
    if (journey.convertedAtEpochMillis != null) return
    if (journey.goalSnapshot == null) return

    val result = goalEvaluator.isGoalMet(
      journey,
      campaign,
      transientEvents = mergedGoalTransientEvents(journey, transientEvents),
    )
    if (result.met && result.atEpochMillis != null) {
      val metAt = result.atEpochMillis ?: return
      latchGoalConversion(journey, metAt)
    }
  }

  private suspend fun handleExplicitGoalActionHit(
    journey: Journey,
    campaign: Campaign,
    goalEvent: NuxieEvent,
  ): GoalActionResolution {
    val goalId = explicitGoalIdForJourney(journey, goalEvent) ?: return GoalActionResolution()
    val hitAtEpochMillis = nowEpochMillis()
    val storedGoalEvent = storedEvent(goalEvent)
    val didRecordHit = recordExplicitGoalHit(journey, goalId, hitAtEpochMillis)
    val didRecordGoalEvent = recordExplicitGoalEvent(journey, storedGoalEvent)

    if (journey.convertedAtEpochMillis != null) {
      if (didRecordHit || didRecordGoalEvent) {
        persistJourney(journey)
      }
      return GoalActionResolution(shouldExit = shouldExitImmediatelyOnGoal(journey))
    }

    val metAtEpochMillis =
      journey.goalSnapshot?.let { goal ->
        val explicitGoalIds = compiledExplicitGoalIds(goal)
        if (explicitGoalIds == null) {
          val result =
            goalEvaluator.isGoalMet(
              journey,
              campaign,
              transientEvents = mergedGoalTransientEvents(journey, listOf(storedGoalEvent)),
            )
          if (result.met) {
            result.atEpochMillis ?: hitAtEpochMillis
          } else {
            null
          }
        } else {
          when (goal.kind) {
            GoalConfig.Kind.EVENT -> {
              if (
                explicitGoalIds.size == 1 &&
                  explicitGoalIds.first() == goalId &&
                  isWithinGoalWindow(journey, hitAtEpochMillis)
              ) {
                explicitGoalHitEpochMillis(journey)[goalId]
              } else {
                null
              }
            }
            GoalConfig.Kind.ATTRIBUTE -> {
              if (!isWithinGoalWindow(journey, hitAtEpochMillis)) {
                null
              } else {
                val hits = explicitGoalHitEpochMillis(journey)
                val requiredHits = explicitGoalIds.mapNotNull { hits[it] }
                if (requiredHits.size == explicitGoalIds.size) {
                  requiredHits.maxOrNull()
                } else {
                  val result =
                    goalEvaluator.isGoalMet(
                      journey,
                      campaign,
                      transientEvents = mergedGoalTransientEvents(journey, listOf(storedGoalEvent)),
                    )
                  if (result.met) {
                    result.atEpochMillis ?: hitAtEpochMillis
                  } else {
                    null
                  }
                }
              }
            }
            GoalConfig.Kind.SEGMENT_ENTER,
            GoalConfig.Kind.SEGMENT_LEAVE,
            -> null
          }
        }
      }

    if (metAtEpochMillis == null) {
      if (didRecordHit || didRecordGoalEvent) {
        persistJourney(journey)
      }
      return GoalActionResolution()
    }

    latchGoalConversion(journey, metAtEpochMillis)
    return GoalActionResolution(shouldExit = shouldExitImmediatelyOnGoal(journey))
  }

  private fun latchGoalConversion(journey: Journey, metAtEpochMillis: Long) {
    if (journey.convertedAtEpochMillis != null) return

    journey.convertedAtEpochMillis = metAtEpochMillis
    journey.updatedAtEpochMillis = nowEpochMillis()
    persistJourney(journey)

    eventService.track(
      JourneyEvents.journeyGoalMet,
      properties = mapOf(
        "journey_id" to journey.id,
        "campaign_id" to journey.campaignId,
        "goal_kind" to journey.goalSnapshot?.kind?.name?.lowercase(),
        "met_at" to (metAtEpochMillis / 1000.0),
        "window_seconds" to journey.conversionWindowSeconds,
      )
    )
  }

  private fun shouldExitOnGoal(journey: Journey): Boolean {
    return when (journey.exitPolicySnapshot?.mode ?: ExitPolicy.Mode.NEVER) {
      ExitPolicy.Mode.ON_GOAL,
      ExitPolicy.Mode.ON_GOAL_OR_STOP,
      -> true
      ExitPolicy.Mode.NEVER,
      ExitPolicy.Mode.ON_STOP_MATCHING,
      -> false
    }
  }

  private fun shouldExitImmediatelyOnGoal(journey: Journey): Boolean {
    return shouldExitOnGoal(journey) && !shouldDeferExitDecision(journey.id)
  }

  private fun isWithinGoalWindow(journey: Journey, atEpochMillis: Long): Boolean {
    if (journey.conversionWindowSeconds <= 0) return true
    val windowEndEpochMillis =
      journey.conversionAnchorAtEpochMillis + (journey.conversionWindowSeconds * 1000.0).toLong()
    return atEpochMillis <= windowEndEpochMillis
  }

  private fun explicitGoalHitEpochMillis(journey: Journey): Map<String, Long> {
    val raw = journey.getContextObject(EXPLICIT_GOAL_HITS_CONTEXT_KEY) ?: return emptyMap()
    return raw.mapNotNull { (goalId, value) ->
      val epochMillis =
        value.jsonPrimitive.longOrNull
          ?: value.jsonPrimitive.doubleOrNull?.toLong()
          ?: value.jsonPrimitive.intOrNull?.toLong()
      epochMillis?.let { goalId to it }
    }.toMap()
  }

  private fun explicitGoalEvents(journey: Journey): List<StoredEvent> {
    return contextStoredEvents(journey, EXPLICIT_GOAL_EVENTS_CONTEXT_KEY)
  }

  private fun scopedGoalEvents(journey: Journey): List<StoredEvent> {
    return contextStoredEvents(journey, SCOPED_GOAL_EVENTS_CONTEXT_KEY)
  }

  private fun mergedGoalTransientEvents(
    journey: Journey,
    transientEvents: List<StoredEvent>,
  ): List<StoredEvent> {
    return (explicitGoalEvents(journey) + scopedGoalEvents(journey) + transientEvents)
      .distinctBy { it.id }
      .sortedBy { it.timestampEpochMillis }
  }

  private fun scopedGoalTransientEvents(
    journey: Journey,
    currentEvent: StoredEvent,
  ): List<StoredEvent> {
    return (scopedGoalEvents(journey) + currentEvent)
      .distinctBy { it.id }
      .sortedBy { it.timestampEpochMillis }
  }

  private fun recordExplicitGoalHit(
    journey: Journey,
    goalId: String,
    hitAtEpochMillis: Long,
  ): Boolean {
    val goalHits = explicitGoalHitEpochMillis(journey).toMutableMap()
    if (goalHits.containsKey(goalId)) return false

    goalHits[goalId] = hitAtEpochMillis
    journey.setContextJson(
      EXPLICIT_GOAL_HITS_CONTEXT_KEY,
      JsonObject(goalHits.mapValues { JsonPrimitive(it.value) }),
      nowEpochMillis(),
    )
    return true
  }

  private fun recordExplicitGoalEvent(
    journey: Journey,
    event: StoredEvent,
  ): Boolean {
    return recordContextStoredEvent(
      journey = journey,
      key = EXPLICIT_GOAL_EVENTS_CONTEXT_KEY,
      event = event,
      maxEvents = MAX_EXPLICIT_GOAL_EVENTS,
    )
  }

  private fun recordScopedGoalEvent(
    journey: Journey,
    event: StoredEvent,
  ): Boolean {
    return recordContextStoredEvent(
      journey = journey,
      key = SCOPED_GOAL_EVENTS_CONTEXT_KEY,
      event = event,
      maxEvents = MAX_SCOPED_GOAL_EVENTS,
    )
  }

  private fun contextStoredEvents(
    journey: Journey,
    key: String,
  ): List<StoredEvent> {
    val raw = journey.getContext(key) as? JsonArray ?: return emptyList()
    return raw.mapNotNull(::explicitGoalEventFromJson)
  }

  private fun recordContextStoredEvent(
    journey: Journey,
    key: String,
    event: StoredEvent,
    maxEvents: Int,
  ): Boolean {
    val existing = contextStoredEvents(journey, key).toMutableList()
    if (existing.any { it.id == event.id }) return false

    existing += event
    val trimmed = existing.sortedBy { it.timestampEpochMillis }.takeLast(maxEvents)
    journey.setContextJson(
      key,
      JsonArray(trimmed.map(::explicitGoalEventJson)),
      nowEpochMillis(),
    )
    return true
  }

  private fun explicitGoalEventJson(event: StoredEvent): JsonObject {
    return JsonObject(
      mapOf(
        "id" to JsonPrimitive(event.id),
        "name" to JsonPrimitive(event.name),
        "distinct_id" to JsonPrimitive(event.distinctId),
        "timestamp_epoch_millis" to JsonPrimitive(event.timestampEpochMillis),
        "properties" to toJsonObject(event.properties),
      ),
    )
  }

  private fun explicitGoalEventFromJson(value: JsonElement): StoredEvent? {
    val json = value as? JsonObject ?: return null
    val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val name = json["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val distinctId = json["distinct_id"]?.jsonPrimitive?.contentOrNull ?: return null
    val timestampEpochMillis =
      json["timestamp_epoch_millis"]?.jsonPrimitive?.longOrNull
        ?: json["timestamp_epoch_millis"]?.jsonPrimitive?.doubleOrNull?.toLong()
        ?: json["timestamp_epoch_millis"]?.jsonPrimitive?.intOrNull?.toLong()
        ?: return null
    val properties =
      json["properties"]?.jsonObject?.mapValues { (_, element) -> fromJsonElement(element) }
        ?: emptyMap()
    return StoredEvent(
      id = id,
      name = name,
      distinctId = distinctId,
      timestampEpochMillis = timestampEpochMillis,
      properties = properties,
    )
  }

  private fun explicitGoalIdForJourney(journey: Journey, goalEvent: NuxieEvent): String? {
    if (goalEvent.name != JourneyEvents.journeyGoalHit) return null

    val eventJourneyId = goalEvent.properties["journey_id"] as? String
    if (eventJourneyId != journey.id) return null

    return goalEvent.properties["goal_id"] as? String
  }

  private fun compiledExplicitGoalIds(goal: GoalConfig): List<String>? {
    return when (goal.kind) {
      GoalConfig.Kind.EVENT -> {
        if (goal.eventName != JourneyEvents.journeyGoalHit) return null
        compiledExplicitGoalId(goal.eventFilter?.expr)?.let(::listOf)
      }
      GoalConfig.Kind.ATTRIBUTE -> {
        val expr = goal.attributeExpr?.expr ?: return null
        compiledExplicitGoalIdsFromAttributeExpr(expr)
      }
      GoalConfig.Kind.SEGMENT_ENTER,
      GoalConfig.Kind.SEGMENT_LEAVE,
      -> null
    }
  }

  private fun compiledExplicitGoalIdsFromAttributeExpr(expr: IRExpr): List<String>? {
    val args =
      when (expr) {
        is IRExpr.And -> expr.args
        is IRExpr.EventsExists -> listOf(expr)
        else -> return null
      }
    val goalIds = mutableListOf<String>()
    val seenGoalIds = mutableSetOf<String>()
    for (arg in args) {
      val exists = arg as? IRExpr.EventsExists ?: return null
      if (exists.name != JourneyEvents.journeyGoalHit) return null
      if (exists.since != null || exists.until != null || exists.within != null) return null
      val goalId = compiledExplicitGoalId(exists.whereExpr) ?: return null
      if (!seenGoalIds.add(goalId)) return null
      goalIds += goalId
    }
    return goalIds.takeIf { it.isNotEmpty() }
  }

  private fun compiledExplicitGoalId(expr: IRExpr?): String? {
    val predicates =
      when (expr) {
        is IRExpr.Pred -> listOf(expr)
        is IRExpr.PredAnd -> expr.args
        else -> return null
      }
    if (predicates.size != 2) return null

    var goalId: String? = null
    var hasJourneyScope = false
    for (predicateExpr in predicates) {
      val predicate = predicateExpr as? IRExpr.Pred ?: return null
      if (predicate.op != "eq") return null
      when (predicate.key) {
        "goal_id" -> {
          val value = (predicate.value as? IRExpr.String)?.value ?: return null
          if (goalId != null) return null
          goalId = value
        }
        "journey_id" -> {
          if (predicate.value !is IRExpr.JourneyId) return null
          if (hasJourneyScope) return null
          hasJourneyScope = true
        }
        else -> return null
      }
    }

    return if (hasJourneyScope) goalId else null
  }

  private suspend fun exitDecision(journey: Journey, campaign: Campaign): JourneyExitReason? {
    if (journey.hasExpired(nowEpochMillis())) return JourneyExitReason.EXPIRED

    val mode = journey.exitPolicySnapshot?.mode ?: ExitPolicy.Mode.NEVER
    if ((mode == ExitPolicy.Mode.ON_GOAL || mode == ExitPolicy.Mode.ON_GOAL_OR_STOP) && journey.convertedAtEpochMillis != null) {
      return JourneyExitReason.GOAL_MET
    }

    if (mode == ExitPolicy.Mode.ON_STOP_MATCHING || mode == ExitPolicy.Mode.ON_GOAL_OR_STOP) {
      val trigger = campaign.trigger
      if (trigger is CampaignTrigger.Segment) {
        val stillMatches = evalConditionIR(trigger.config.condition, event = null)
        if (!stillMatches) {
          return JourneyExitReason.TRIGGER_UNMATCHED
        }
      }
    }
    return null
  }

  private fun shouldDeferExitDecision(journeyId: String): Boolean {
    return presentedJourneyIds.contains(journeyId)
  }

  private suspend fun shouldCompletePresentedScopedGoalJourney(journey: Journey, campaign: Campaign): Boolean {
    if (!journey.status.isLive) return false
    if (journey.convertedAtEpochMillis == null) return false
    if (!shouldDeferExitDecision(journey.id)) return false
    return exitDecision(journey, campaign) == JourneyExitReason.GOAL_MET
  }

  private fun storedEvent(event: NuxieEvent): StoredEvent {
    return StoredEvent(
      id = event.id,
      name = event.name,
      distinctId = event.distinctId,
      timestampEpochMillis = Iso8601.parseEpochMillis(event.timestamp) ?: nowEpochMillis(),
      properties = event.properties,
    )
  }

  private suspend fun shouldTriggerFromEvent(campaign: Campaign, event: NuxieEvent): Boolean {
    return when (val trigger = campaign.trigger) {
      is CampaignTrigger.Event -> {
        if (trigger.config.eventName != event.name) return false
        val condition = trigger.config.condition
        if (condition != null) evalConditionIR(condition, event) else true
      }
      is CampaignTrigger.Segment -> false
    }
  }

  private suspend fun evalConditionIR(envelope: io.nuxie.sdk.ir.IREnvelope?, event: NuxieEvent?): Boolean {
    if (envelope == null) return true

    val userAdapter = IRUserProps { key ->
      identityService.userProperty(key)?.let { fromJsonElement(it) }
    }
    val featuresAdapter = object : IRFeatureQueries {
      override suspend fun has(featureId: String): Boolean {
        return featureService.getCached(featureId, null)?.allowed == true
      }

      override suspend fun isUnlimited(featureId: String): Boolean {
        return featureService.getCached(featureId, null)?.unlimited == true
      }

      override suspend fun getBalance(featureId: String): Int? {
        return featureService.getCached(featureId, null)?.balance
      }
    }

    return irRuntime.eval(
      envelope = envelope,
      cfg = IRRuntime.Config(
        event = event,
        user = userAdapter,
        events = eventService,
        segments = segmentService as IRSegmentQueries,
        features = featuresAdapter,
      )
    )
  }

  private suspend fun suppressionReason(campaign: Campaign, distinctId: String): SuppressReason? {
    val live = inMemoryJourneysById.values.any {
      it.distinctId == distinctId && it.campaignId == campaign.id && it.status.isLive
    }
    if (live) return SuppressReason.AlreadyActive

    return when (val reentry = campaign.reentry) {
      is CampaignReentry.EveryTime -> null
      is CampaignReentry.OneTime -> {
        val completed = journeyStore.hasCompletedCampaign(distinctId, campaign.id)
        if (completed) SuppressReason.ReentryLimited else null
      }
      is CampaignReentry.OncePerWindow -> {
        val last = journeyStore.lastCompletionEpochMillis(distinctId, campaign.id) ?: return null
        val intervalSeconds = when (reentry.window.unit) {
          io.nuxie.sdk.campaigns.WindowUnit.MINUTE -> reentry.window.amount.toLong() * 60L
          io.nuxie.sdk.campaigns.WindowUnit.HOUR -> reentry.window.amount.toLong() * 3600L
          io.nuxie.sdk.campaigns.WindowUnit.DAY -> reentry.window.amount.toLong() * 86400L
          io.nuxie.sdk.campaigns.WindowUnit.WEEK -> reentry.window.amount.toLong() * 604800L
        }
        val allowed = (nowEpochMillis() - last) >= (intervalSeconds * 1000L)
        if (allowed) null else SuppressReason.ReentryLimited
      }
    }
  }

  private suspend fun getCampaign(id: String, distinctId: String): Campaign? {
    val profile = profileService.getCachedProfile(distinctId) ?: return null
    return profile.campaigns.firstOrNull { it.id == id }
  }

  private suspend fun getAllCampaigns(distinctId: String): List<Campaign>? {
    val profile = profileService.getCachedProfile(distinctId) ?: return null
    return profile.campaigns
  }

  private fun registerForSegmentChanges() {
    segmentMonitoringJob?.cancel()
    segmentMonitoringJob = scope.launch {
      segmentService.segmentChanges.collectLatest { result ->
        val currentDistinctId = identityService.getDistinctId()
        if (result.distinctId != currentDistinctId) return@collectLatest
        val currentSegments = (result.entered + result.remained).map { it.id }.toSet()
        handleSegmentChange(result.distinctId, currentSegments)
      }
    }
  }

  private fun parseRuntimeTrigger(type: String, payload: JsonObject): InteractionTrigger? {
    val triggerPayload = payload["trigger"] as? JsonObject
    if (triggerPayload != null) {
      return parseTriggerType(triggerPayload.string("type") ?: triggerPayload.string("trigger") ?: return null, triggerPayload)
    }

    val triggerType = payload.string("trigger")
    if (!triggerType.isNullOrBlank()) {
      return parseTriggerType(triggerType, payload)
    }

    val fallback = if (type.startsWith("action/")) type.removePrefix("action/") else type
    return parseTriggerType(fallback, payload)
  }

  private fun parseTriggerType(raw: String, payload: JsonObject): InteractionTrigger? {
    val normalized = raw
      .replace("action/", "")
      .replace("-", "_")
      .lowercase()

    return when (normalized) {
      "long_press", "longpress" -> InteractionTrigger.LongPress(minMs = payload.int("minMs") ?: payload.int("min_ms"))
      "hover" -> InteractionTrigger.Hover
      "press" -> InteractionTrigger.Press
      "drag" -> {
        val direction = when (payload.string("direction")?.lowercase()) {
          "left" -> InteractionTrigger.DragDirection.LEFT
          "right" -> InteractionTrigger.DragDirection.RIGHT
          "up" -> InteractionTrigger.DragDirection.UP
          "down" -> InteractionTrigger.DragDirection.DOWN
          else -> null
        }
        InteractionTrigger.Drag(
          direction = direction,
          threshold = payload.double("threshold"),
        )
      }
      "manual" -> InteractionTrigger.Manual(label = payload.string("label"))
      else -> null
    }
  }

  private fun parsePathRef(payload: JsonObject): VmPathRef? {
    val direct = parsePathRefObject(payload)
    if (direct != null) return direct
    val nested = payload["path"] as? JsonObject
    if (nested != null) return parsePathRefObject(nested)
    return null
  }

  private fun parsePathRefObject(payload: JsonObject): VmPathRef? {
    val pathIds = payload["pathIds"] as? JsonArray ?: return null
    val ids = pathIds.mapNotNull { it.jsonPrimitive.intOrNull }
    if (ids.isEmpty()) return null
    return VmPathRef(
      pathIds = ids,
      isRelative = payload.bool("isRelative"),
      nameBased = payload.bool("nameBased"),
    )
  }

  private fun exitReasonString(reason: JourneyExitReason): String {
    return when (reason) {
      JourneyExitReason.COMPLETED -> "completed"
      JourneyExitReason.DISMISSED -> "dismissed"
      JourneyExitReason.GOAL_MET -> "goal_met"
      JourneyExitReason.TRIGGER_UNMATCHED -> "trigger_unmatched"
      JourneyExitReason.EXPIRED -> "expired"
      JourneyExitReason.ERROR -> "error"
      JourneyExitReason.CANCELLED -> "cancelled"
    }
  }
}

private class FlowRuntimeDelegateAdapter(
  private val journeyId: String,
  private val journeyService: JourneyService,
  private val scope: CoroutineScope,
) : FlowRuntimeDelegate, FlowJourneyHost, NotificationPermissionEventReceiver, PermissionEventReceiver {
  @Volatile private var flowView: FlowView? = null

  fun attachFlowView(view: FlowView) {
    this.flowView = view
  }

  override fun onRuntimeMessage(type: String, payload: JsonObject, id: String?) {
    scope.launch {
      journeyService.handleRuntimeMessage(journeyId, type, payload, id)
    }
  }

  override fun onDismissRequested(reason: io.nuxie.sdk.flows.CloseReason) {
    scope.launch {
      journeyService.handleRuntimeDismiss(journeyId, reason)
    }
  }

  override suspend fun sendRuntimeMessage(type: String, payload: JsonObject, replyTo: String?) {
    flowView?.sendRuntimeMessage(type = type, payload = payload, replyTo = replyTo)
  }

  override suspend fun showScreen(screenId: String, transition: JsonElement?) {
    // Runtime navigation is sent through sendRuntimeMessage("runtime/navigate", ...).
  }

  override suspend fun performPurchase(productId: String, placementIndex: Any?) {
    journeyService.dispatchPurchaseRequested(journeyId, productId, placementIndex)
    flowView?.performPurchase(productId)
  }

  override suspend fun performRestore() {
    journeyService.dispatchRestoreRequested(journeyId)
    flowView?.performRestore()
  }

  override suspend fun performRequestNotifications(journeyId: String?) {
    flowView?.performRequestNotifications(journeyId)
  }

  override suspend fun performRequestPermission(permissionType: String, journeyId: String?) {
    flowView?.performRequestPermission(permissionType, journeyId)
  }

  override fun onNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>,
  ) {
    scope.launch {
      journeyService.handleScopedNotificationPermissionEvent(
        journeyId = journeyId,
        eventName = eventName,
        properties = properties,
      )
    }
  }

  override fun onPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>,
  ) {
    scope.launch {
      journeyService.handleScopedPermissionEvent(
        journeyId = journeyId,
        eventName = eventName,
        properties = properties,
      )
    }
  }

  override suspend fun performOpenLink(url: String, target: String?) {
    journeyService.dispatchOpenLinkRequested(journeyId, url, target)
    flowView?.performOpenLink(url, target)
  }

  override suspend fun performDismiss() {
    flowView?.performDismiss()
  }

  override suspend fun performBack(steps: Int?, transition: JsonElement?) {
    journeyService.dispatchBackRequested(journeyId, steps ?: 1)
  }

  override suspend fun callDelegate(message: String, payload: Any?) {
    journeyService.dispatchDelegateCallback(
      journeyId = journeyId,
      message = message,
      payload = payload,
    )
  }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? {
  val p = this[key] as? JsonPrimitive ?: return null
  return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
}
private fun JsonObject.double(key: String): Double? {
  val p = this[key] as? JsonPrimitive ?: return null
  return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
}
private fun JsonObject.bool(key: String): Boolean? {
  val p = this[key] as? JsonPrimitive ?: return null
  return p.booleanOrNull
}

private fun JsonElement.toAny(): Any? {
  return when (this) {
    JsonNull -> null
    is JsonPrimitive -> {
      if (isString) contentOrNull
      else booleanOrNull ?: intOrNull ?: doubleOrNull ?: contentOrNull
    }
    is JsonArray -> map { it.toAny() }
    is JsonObject -> entries.associate { it.key to it.value.toAny() }
  }
}

private fun JsonObject.toMapAny(): Map<String, Any?> = this.entries.associate { it.key to it.value.toAny() }
