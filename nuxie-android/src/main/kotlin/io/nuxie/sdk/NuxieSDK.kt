package io.nuxie.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.room.Room
import io.nuxie.sdk.config.EventLinkingPolicy
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.errors.NuxieError
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.features.DefaultFeatureService
import io.nuxie.sdk.features.FeatureAccess
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.features.FeatureInfo
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.FeatureUsageResult
import io.nuxie.sdk.flows.FlowService
import io.nuxie.sdk.flows.FlowColorSchemeMode
import io.nuxie.sdk.flows.FlowView
import io.nuxie.sdk.flows.NuxieFlowActivity
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.gating.GatePlan
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.journey.FileJourneyStore
import io.nuxie.sdk.journey.JourneyService
import io.nuxie.sdk.journey.JourneyTriggerResult
import io.nuxie.sdk.lifecycle.CurrentActivityTracker
import io.nuxie.sdk.logging.NuxieLogSink
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApi
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.profile.DefaultProfileService
import io.nuxie.sdk.profile.FileCachedProfileStore
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.plugins.NuxiePlugin
import io.nuxie.sdk.plugins.NuxiePluginHost
import io.nuxie.sdk.plugins.PluginError
import io.nuxie.sdk.plugins.PluginService
import io.nuxie.sdk.purchases.GooglePlayBillingPurchaseObserver
import io.nuxie.sdk.purchases.PlayStorePurchase
import io.nuxie.sdk.purchases.PurchaseSyncService
import io.nuxie.sdk.segments.FileSegmentMembershipStore
import io.nuxie.sdk.segments.SegmentService
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.session.SessionService
import io.nuxie.sdk.storage.KeyValueStore
import io.nuxie.sdk.storage.RoomEventHistoryStore
import io.nuxie.sdk.storage.RoomEventQueueStore
import io.nuxie.sdk.storage.SharedPreferencesKeyValueStore
import io.nuxie.sdk.storage.db.NuxieDatabase
import io.nuxie.sdk.util.Iso8601
import io.nuxie.sdk.util.UuidV7
import io.nuxie.sdk.util.toJsonObject
import io.nuxie.sdk.triggers.DefaultTriggerBroker
import io.nuxie.sdk.triggers.EntitlementUpdate
import io.nuxie.sdk.triggers.GateSource
import io.nuxie.sdk.triggers.JourneyRef
import io.nuxie.sdk.triggers.TriggerDecision
import io.nuxie.sdk.triggers.TriggerError
import io.nuxie.sdk.triggers.TriggerBroker
import io.nuxie.sdk.triggers.TriggerHandle
import io.nuxie.sdk.triggers.TriggerUpdate
import io.nuxie.sdk.features.PurchaseResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android entrypoint.
 *
 * This will evolve to match the iOS SDK surface area.
 */
class NuxieSDK private constructor() {
  private enum class TriggerMode {
    IMMEDIATE,
    FLOW,
    REQUIRE_FEATURE,
  }

  companion object {
    @Volatile private var instance: NuxieSDK? = null

    @JvmStatic
    fun shared(): NuxieSDK = instance ?: synchronized(this) {
      instance ?: NuxieSDK().also { instance = it }
    }
  }

  @Volatile
  var configuration: NuxieConfiguration? = null
    private set

  @Volatile
  var delegate: NuxieDelegate? = null

  internal var identityService: IdentityService? = null
    private set

  internal var sessionService: SessionService? = null
    private set

  internal var api: NuxieApiProtocol? = null
    private set

  internal var eventQueueStore: EventQueueStore? = null
    private set

  internal var networkQueue: NuxieNetworkQueue? = null
    private set

  internal var eventService: EventService? = null
    private set

  internal var profileService: ProfileService? = null
    private set

  internal var featureInfo: FeatureInfo? = null
    private set

  internal var featureService: FeatureService? = null
    private set

  internal var flowService: FlowService? = null
    private set

  internal var segmentService: SegmentService? = null
    private set

  internal var journeyService: JourneyService? = null
    private set

  internal var triggerBroker: TriggerBroker? = null
    private set

  internal var irRuntime: IRRuntime? = null
    private set

  internal var purchaseSyncService: PurchaseSyncService? = null
    private set

  private var scope: CoroutineScope? = null
  private var database: NuxieDatabase? = null
  private var activityTracker: CurrentActivityTracker? = null
  private var pluginService: PluginService? = null
  private var playBillingObserver: GooglePlayBillingPurchaseObserver? = null

  val isSetup: Boolean
    get() {
      if (configuration == null) {
        NuxieLogger.warning("SDK not configured. Call setup() first.")
      }
      return configuration != null
    }

  fun setup(context: Context, configuration: NuxieConfiguration) {
    if (configuration.apiKey.isBlank()) {
      throw NuxieError.InvalidConfiguration("API key cannot be empty")
    }

    if (this.configuration != null) {
      NuxieLogger.warning("SDK already configured. Skipping setup.")
      return
    }

    this.configuration = configuration

    // Configure logging early.
    NuxieLogger.configure(
      logLevel = configuration.logLevel,
      enableConsoleLogging = configuration.enableConsoleLogging,
      enableFileLogging = configuration.enableFileLogging,
      redactSensitiveData = configuration.redactSensitiveData,
    )
    NuxieLogger.setSink(AndroidLogcatSink())

    val appContext = context.applicationContext
    val app = appContext as? Application
    val kv: KeyValueStore = SharedPreferencesKeyValueStore(appContext)
    identityService = DefaultIdentityService(kv)
    sessionService = DefaultSessionService()

    val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope = sdkScope

    val db = Room.databaseBuilder(appContext, NuxieDatabase::class.java, "nuxie-sdk.db")
      .fallbackToDestructiveMigration()
      .build()
    database = db

    val api = NuxieApi(
      apiKey = configuration.apiKey,
      baseUrl = configuration.apiEndpoint,
      useGzipCompression = configuration.enableCompression,
    )

    val store = RoomEventQueueStore(db.eventQueueDao())
    val historyStore = RoomEventHistoryStore(db.eventHistoryDao())
    val queue = NuxieNetworkQueue(
      store = store,
      api = api,
      scope = sdkScope,
      flushAt = configuration.flushAt,
      flushIntervalSeconds = configuration.flushIntervalSeconds,
      maxQueueSize = configuration.maxQueueSize,
      maxBatchSize = configuration.eventBatchSize,
      maxRetries = configuration.retryCount,
      baseRetryDelaySeconds = configuration.retryDelaySeconds,
    )
    queue.start()

    val events = EventService(
      identityService = requireNotNull(identityService),
      sessionService = requireNotNull(sessionService),
      configuration = configuration,
      api = api,
      store = store,
      historyStore = historyStore,
      networkQueue = queue,
      scope = sdkScope,
    )

    val profileBaseDir = configuration.customStoragePath?.let { File(it) } ?: File(appContext.cacheDir, "nuxie")
    val profileStore = FileCachedProfileStore(
      directory = File(profileBaseDir, "profiles"),
      ttlMillis = 24L * 60L * 60L * 1000L,
    )
    var profileUpdateHandler: suspend (
      profile: io.nuxie.sdk.network.models.ProfileResponse,
      previousProfile: io.nuxie.sdk.network.models.ProfileResponse?,
      distinctId: String,
    ) -> Unit = { _, _, _ -> }
    val profile = DefaultProfileService(
      identityService = requireNotNull(identityService),
      api = api,
      configuration = configuration,
      store = profileStore,
      scope = sdkScope,
      onProfileUpdated = { nextProfile, previousProfile, distinctId ->
        profileUpdateHandler(nextProfile, previousProfile, distinctId)
      },
    )

    val info = FeatureInfo()
    val features = DefaultFeatureService(
      api = api,
      identityService = requireNotNull(identityService),
      profileService = profile,
      configuration = configuration,
      featureInfo = info,
    )
    val purchases = PurchaseSyncService(
      api = api,
      identityService = requireNotNull(identityService),
      featureService = features,
      eventService = events,
    )

    val flowCacheBaseDir = configuration.customStoragePath?.let { File(it) } ?: appContext.cacheDir
    val flows = FlowService(
      api = api,
      configuration = configuration,
      scope = sdkScope,
      cacheDirectory = flowCacheBaseDir,
    )

    val runtime = IRRuntime()
    val segmentStore = FileSegmentMembershipStore(directory = File(profileBaseDir, "segments"))
    val segments = SegmentService(
      identityService = requireNotNull(identityService),
      events = events,
      irRuntime = runtime,
      featureQueriesProvider = {
        object : io.nuxie.sdk.ir.IRFeatureQueries {
          override suspend fun has(featureId: String): Boolean {
            return features.getCached(featureId, null)?.allowed == true
          }

          override suspend fun isUnlimited(featureId: String): Boolean {
            return features.getCached(featureId, null)?.unlimited == true
          }

          override suspend fun getBalance(featureId: String): Int? {
            return features.getCached(featureId, null)?.balance
          }
        }
      },
      membershipStore = segmentStore,
      scope = sdkScope,
      enableMonitoring = true,
    )

    val broker = DefaultTriggerBroker()

    suspend fun presentFlowForJourney(flowId: String, journeyId: String): Boolean {
      val activity = activityTracker?.getCurrentActivity() ?: return false
      val intent = Intent(activity, NuxieFlowActivity::class.java)
        .putExtra(NuxieFlowActivity.EXTRA_FLOW_ID, flowId)
        .putExtra(NuxieFlowActivity.EXTRA_JOURNEY_ID, journeyId)
      return runCatching {
        withContext(Dispatchers.Main) { activity.startActivity(intent) }
        true
      }.getOrDefault(false)
    }

    val journeys = JourneyService(
      scope = sdkScope,
      configuration = configuration,
      identityService = requireNotNull(identityService),
      eventService = events,
      profileService = profile,
      segmentService = segments,
      featureService = features,
      flowService = flows,
      api = requireNotNull(api),
      journeyStore = FileJourneyStore(File(profileBaseDir, "journeys")),
      triggerBroker = broker,
      irRuntime = runtime,
      presentFlow = ::presentFlowForJourney,
      onCallDelegate = { journeyId, campaignId, message, payload ->
        withContext(Dispatchers.Main) {
          delegate?.flowDelegateCalled(
            message = message,
            payload = payload,
            journeyId = journeyId,
            campaignId = campaignId,
          )
        }
      },
      onPurchaseRequested = { journeyId, campaignId, screenId, productId, placementIndex ->
        withContext(Dispatchers.Main) {
          delegate?.flowPurchaseRequested(
            journeyId = journeyId,
            campaignId = campaignId,
            screenId = screenId,
            productId = productId,
            placementIndex = placementIndex,
          )
        }
      },
      onRestoreRequested = { journeyId, campaignId, screenId ->
        withContext(Dispatchers.Main) {
          delegate?.flowRestoreRequested(
            journeyId = journeyId,
            campaignId = campaignId,
            screenId = screenId,
          )
        }
      },
      onOpenLinkRequested = { journeyId, campaignId, screenId, url, target ->
        withContext(Dispatchers.Main) {
          delegate?.flowOpenLinkRequested(
            journeyId = journeyId,
            campaignId = campaignId,
            screenId = screenId,
            url = url,
            target = target,
          )
        }
      },
      onDismissed = { journeyId, campaignId, screenId, reason, error ->
        withContext(Dispatchers.Main) {
          delegate?.flowDismissed(
            journeyId = journeyId,
            campaignId = campaignId,
            screenId = screenId,
            reason = reason,
            error = error,
          )
        }
      },
      onBackRequested = { journeyId, campaignId, screenId, steps ->
        withContext(Dispatchers.Main) {
          delegate?.flowBackRequested(
            journeyId = journeyId,
            campaignId = campaignId,
            screenId = screenId,
            steps = steps,
          )
        }
      },
    )

    // Forward FeatureInfo changes to delegate on the main thread (parity with iOS @MainActor).
    info.onFeatureChange = { featureId, oldValue, newValue ->
      sdkScope.launch(Dispatchers.Main) {
        delegate?.featureAccessDidChange(featureId, from = oldValue, to = newValue)
      }
    }

    suspend fun syncFlows(newFlows: List<RemoteFlow>, previousFlows: List<RemoteFlow>?) {
      val previous = previousFlows ?: emptyList()
      if (newFlows.isEmpty() && previous.isEmpty()) return

      val previousById = previous.associateBy { it.id }
      val nextById = newFlows.associateBy { it.id }

      val flowsToPrefetch = mutableListOf<RemoteFlow>()
      val flowIdsToRemove = mutableSetOf<String>()

      for (flow in newFlows) {
        val old = previousById[flow.id]
        if (old == null) {
          flowsToPrefetch += flow
        } else if (old.bundle.manifest.contentHash != flow.bundle.manifest.contentHash) {
          flowIdsToRemove += flow.id
          flowsToPrefetch += flow
        }
      }

      for (old in previous) {
        if (nextById[old.id] == null) {
          flowIdsToRemove += old.id
        }
      }

      if (flowIdsToRemove.isNotEmpty()) {
        runCatching { flows.removeFlows(flowIdsToRemove.toList()) }
      }
      if (flowsToPrefetch.isNotEmpty()) {
        flows.prefetchFlows(flowsToPrefetch)
      }
    }

    profileUpdateHandler = { nextProfile, previousProfile, distinctId ->
      if (nextProfile.segments.isNotEmpty()) {
        runCatching { segments.updateSegments(nextProfile.segments, distinctId = distinctId) }
        runCatching { journeys.handleSegmentChange(distinctId, nextProfile.segments.map { it.id }.toSet()) }
      }

      val activeJourneys = nextProfile.journeys.orEmpty()
      if (activeJourneys.isNotEmpty()) {
        runCatching { journeys.resumeFromServerState(activeJourneys, campaigns = nextProfile.campaigns) }
      }

      runCatching { syncFlows(nextProfile.flows, previousProfile?.flows) }
    }

    this.api = api
    this.eventQueueStore = store
    this.networkQueue = queue
    this.eventService = events
    this.profileService = profile
    this.featureInfo = info
    this.featureService = features
    this.flowService = flows
    this.segmentService = segments
    this.journeyService = journeys
    this.triggerBroker = broker
    this.irRuntime = runtime
    this.purchaseSyncService = purchases

    if (configuration.enablePlayStorePurchaseSync) {
      playBillingObserver = GooglePlayBillingPurchaseObserver.create(
        context = appContext,
        scope = sdkScope,
        syncService = purchases,
        consumableProductIds = {
          configuration.consumablePlayStoreProductIds
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()
        },
      ).also { it.start() }
    }

    val plugins = PluginService().also {
      it.initialize(
        object : NuxiePluginHost {
          override fun trigger(
            event: String,
            properties: Map<String, Any?>?,
            userProperties: Map<String, Any?>?,
            userPropertiesSetOnce: Map<String, Any?>?,
          ) {
            this@NuxieSDK.trigger(
              event = event,
              properties = properties,
              userProperties = userProperties,
              userPropertiesSetOnce = userPropertiesSetOnce,
              handler = null,
            )
          }

          override fun getDistinctId(): String = this@NuxieSDK.getDistinctId()
          override fun getAnonymousId(): String = this@NuxieSDK.getAnonymousId()
          override fun isIdentified(): Boolean = this@NuxieSDK.isIdentified
        }
      )
    }
    if (configuration.enablePlugins) {
      for (plugin in configuration.plugins) {
        runCatching {
          plugins.installPlugin(plugin)
          plugins.startPlugin(plugin.pluginId)
        }.onFailure {
          NuxieLogger.warning("Failed to install/start plugin ${plugin.pluginId}: ${it.message}")
        }
      }
    }
    pluginService = plugins

    if (app != null) {
      activityTracker = CurrentActivityTracker(
        application = app,
        onAppWillEnterForeground = {
          sdkScope.launch {
            pluginService?.onAppWillEnterForeground()
          }
        },
        onAppBecameActive = {
          requireNotNull(sessionService).onAppBecameActive()
          sdkScope.launch {
            runCatching { profile.onAppBecameActive() }
            runCatching { features.syncFeatureInfo() }
            runCatching { journeys.checkExpiredTimers() }
            playBillingObserver?.refreshPurchases()
            pluginService?.onAppBecameActive()
          }
        },
        onAppDidEnterBackground = {
          requireNotNull(sessionService).onAppDidEnterBackground()
          sdkScope.launch {
            pluginService?.onAppDidEnterBackground()
          }
        },
      )
    }

    // Initialize journey runtime + prefetch initial profile and sync features (best-effort).
    sdkScope.launch {
      runCatching { journeys.initialize() }
      runCatching { profile.refetchProfile() }
      runCatching { features.syncFeatureInfo() }
    }

    NuxieLogger.info("Setup completed with API key: ${NuxieLogger.logApiKey(configuration.apiKey)}")
  }

  /**
   * Trigger an event and emit progressive [TriggerUpdate]s.
   */
  fun trigger(
    event: String,
    properties: Map<String, Any?>? = null,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
    handler: ((TriggerUpdate) -> Unit)? = null,
  ): TriggerHandle {
    if (!isSetup) return TriggerHandle.empty
    val events = eventService ?: return TriggerHandle.empty
    val featureSvc = featureService
    val featureInfo = featureInfo
    val journeys = journeyService
    val broker = triggerBroker
    val sdkScope = scope ?: return TriggerHandle.empty

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    var registeredEventId: String? = null

    suspend fun emitMain(update: TriggerUpdate) {
      if (handler == null) return
      withContext(Dispatchers.Main) { handler.invoke(update) }
    }

    fun modeFor(plan: GatePlan?): TriggerMode {
      if (plan == null) return TriggerMode.FLOW
      return when (plan.decision) {
        GatePlan.Decision.ALLOW, GatePlan.Decision.DENY -> TriggerMode.IMMEDIATE
        GatePlan.Decision.SHOW_FLOW -> TriggerMode.FLOW
        GatePlan.Decision.REQUIRE_FEATURE -> TriggerMode.REQUIRE_FEATURE
      }
    }

    fun shouldComplete(
      update: TriggerUpdate,
      mode: TriggerMode,
      hasGatePlan: Boolean,
      startedJourney: Boolean,
    ): Boolean {
      return when (update) {
        is TriggerUpdate.Error -> true
        is TriggerUpdate.Decision -> when (update.decision) {
          TriggerDecision.AllowedImmediate,
          TriggerDecision.DeniedImmediate,
          TriggerDecision.NoMatch,
          -> true
          is TriggerDecision.Suppressed -> !hasGatePlan
          is TriggerDecision.FlowShown -> mode == TriggerMode.FLOW && !startedJourney
          else -> false
        }
        is TriggerUpdate.Entitlement -> when (update.entitlement) {
          is EntitlementUpdate.Allowed,
          EntitlementUpdate.Denied,
          -> true
          EntitlementUpdate.Pending -> false
        }
        is TriggerUpdate.Journey -> mode == TriggerMode.FLOW
      }
    }

    fun hasAccess(access: FeatureAccess?, requiredBalance: Int?): Boolean {
      if (access == null) return false
      if (access.type == io.nuxie.sdk.features.FeatureType.BOOLEAN) return access.allowed
      if (access.unlimited) return true
      val required = requiredBalance ?: 1
      return (access.balance ?: 0) >= required
    }

    suspend fun presentFlow(flowId: String): Boolean {
      val activity = activityTracker?.getCurrentActivity() ?: return false
      val intent = Intent(activity, NuxieFlowActivity::class.java)
        .putExtra(NuxieFlowActivity.EXTRA_FLOW_ID, flowId)
      return runCatching {
        withContext(Dispatchers.Main) { activity.startActivity(intent) }
        true
      }.getOrDefault(false)
    }

    val job = sdkScope.launch {
      try {
        val (nuxieEvent, response) = events.trackForTrigger(
          event = event,
          properties = properties,
          userProperties = userProperties,
          userPropertiesSetOnce = userPropertiesSetOnce,
        )

        val gatePlan: GatePlan? = response.gatePlan(json)
        val triggerMode = modeFor(gatePlan)
        val eventId = nuxieEvent.id
        registeredEventId = eventId

        suspend fun emit(update: TriggerUpdate) {
          if (broker != null) {
            broker.emit(eventId, update)
          } else {
            emitMain(update)
          }
        }

        val startedJourney = AtomicBoolean(false)
        if (broker != null && handler != null) {
          broker.register(eventId) { update ->
            emitMain(update)
            if (shouldComplete(update, triggerMode, gatePlan != null, startedJourney.get())) {
              broker.complete(eventId)
            }
          }
        }

        var emittedJourneyDecision = false
        if (journeys != null) {
          val journeyResults = journeys.handleEventForTrigger(nuxieEvent)
          for (result in journeyResults) {
            when (result) {
              is JourneyTriggerResult.Started -> {
                startedJourney.set(true)
                emittedJourneyDecision = true
                val ref = JourneyRef(
                  journeyId = result.journey.id,
                  campaignId = result.journey.campaignId,
                  flowId = result.journey.flowId,
                )
                emit(TriggerUpdate.Decision(TriggerDecision.JourneyStarted(ref)))
              }
              is JourneyTriggerResult.Suppressed -> {
                emittedJourneyDecision = true
                emit(TriggerUpdate.Decision(TriggerDecision.Suppressed(result.reason)))
              }
            }
          }
        }

        if (emittedJourneyDecision && gatePlan == null) {
          return@launch
        }

        if (gatePlan == null) {
          emit(TriggerUpdate.Decision(TriggerDecision.NoMatch))
          return@launch
        }

        when (gatePlan.decision) {
          GatePlan.Decision.ALLOW -> emit(TriggerUpdate.Decision(TriggerDecision.AllowedImmediate))
          GatePlan.Decision.DENY -> emit(TriggerUpdate.Decision(TriggerDecision.DeniedImmediate))
          GatePlan.Decision.SHOW_FLOW -> {
            val flowId = gatePlan.flowId
            if (flowId.isNullOrBlank()) {
              emit(TriggerUpdate.Error(TriggerError("flow_missing", "Missing flowId for show_flow decision")))
              return@launch
            }
            val ok = presentFlow(flowId)
            if (ok) {
              val ref = JourneyRef(
                journeyId = UuidV7.generateString(),
                campaignId = "flow:$flowId",
                flowId = flowId,
              )
              emit(TriggerUpdate.Decision(TriggerDecision.FlowShown(ref = ref)))
            } else {
              emit(TriggerUpdate.Error(TriggerError("flow_present_failed", "Failed to present flow")))
            }
          }
          GatePlan.Decision.REQUIRE_FEATURE -> {
            val featureId = gatePlan.featureId
            if (featureId.isNullOrBlank()) {
              emit(TriggerUpdate.Error(TriggerError("feature_missing", "Missing featureId for require_feature decision")))
              return@launch
            }

            if (gatePlan.policy == GatePlan.Policy.CACHE_ONLY) {
              val cached = featureInfo?.feature(featureId)
              if (hasAccess(cached, requiredBalance = gatePlan.requiredBalance)) {
                emit(TriggerUpdate.Entitlement(EntitlementUpdate.Allowed(GateSource.CACHE)))
              } else {
                emit(TriggerUpdate.Entitlement(EntitlementUpdate.Denied))
              }
              return@launch
            }

            // Cache-first check before presenting the flow.
            val cachedAllowed = runCatching {
              featureSvc?.checkWithCache(
                featureId = featureId,
                requiredBalance = gatePlan.requiredBalance,
                entityId = gatePlan.entityId,
                forceRefresh = false,
              )
            }.getOrNull()?.let { access ->
              hasAccess(access, requiredBalance = gatePlan.requiredBalance)
            } == true

            if (cachedAllowed) {
              emit(TriggerUpdate.Entitlement(EntitlementUpdate.Allowed(GateSource.CACHE)))
              return@launch
            }

            emit(TriggerUpdate.Entitlement(EntitlementUpdate.Pending))

            val flowId = gatePlan.flowId
            if (!flowId.isNullOrBlank()) {
              val ok = presentFlow(flowId)
              if (!ok) {
                emit(TriggerUpdate.Error(TriggerError("flow_present_failed", "Failed to present flow")))
                return@launch
              }
              val ref = JourneyRef(
                journeyId = UuidV7.generateString(),
                campaignId = "flow:$flowId",
                flowId = flowId,
              )
              emit(TriggerUpdate.Decision(TriggerDecision.FlowShown(ref)))
            }

            val timeoutMs = gatePlan.timeoutMs ?: 30_000
            val deadline = System.currentTimeMillis() + timeoutMs.toLong()
            while (System.currentTimeMillis() < deadline && isActive) {
              val access = featureInfo?.feature(featureId)
              if (hasAccess(access, requiredBalance = gatePlan.requiredBalance)) {
                emit(TriggerUpdate.Entitlement(EntitlementUpdate.Allowed(GateSource.PURCHASE)))
                return@launch
              }
              delay(350)
            }

            emit(TriggerUpdate.Error(TriggerError("entitlement_timeout", "Timed out waiting for entitlement")))
          }
        }
      } catch (t: Throwable) {
        emitMain(TriggerUpdate.Error(TriggerError("trigger_failed", t.message ?: "trigger_failed")))
      }
    }

    return TriggerHandle(
      cancelHandler = {
        job.cancel()
        val eventId = registeredEventId
        if (!eventId.isNullOrBlank() && broker != null) {
          sdkScope.launch { broker.complete(eventId) }
        }
      }
    )
  }

  fun identify(
    distinctId: String,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
  ) {
    if (!isSetup) return
    val config = configuration ?: return
    val identity = identityService ?: return
    val events = eventService ?: return

    val oldDistinctId = identity.getDistinctId()
    val wasIdentified = identity.isIdentified
    val hasDifferentDistinctId = distinctId != oldDistinctId

    identity.setDistinctId(distinctId)
    sessionService?.startSession()

    val currentDistinctId = identity.getDistinctId()

    if (hasDifferentDistinctId) {
      scope?.launch {
        profileService?.handleUserChange(fromOldDistinctId = oldDistinctId, toNewDistinctId = currentDistinctId)
        featureService?.handleUserChange(fromOldDistinctId = oldDistinctId, toNewDistinctId = currentDistinctId)
        segmentService?.handleUserChange(fromOldDistinctId = oldDistinctId, toNewDistinctId = currentDistinctId)
        journeyService?.handleUserChange(fromOldDistinctId = oldDistinctId, toNewDistinctId = currentDistinctId)
      }
    }

    if (
      !wasIdentified &&
      hasDifferentDistinctId &&
      config.eventLinkingPolicy == EventLinkingPolicy.MIGRATE_ON_IDENTIFY
    ) {
      scope?.launch {
        val reassigned = runCatching { events.reassignEvents(oldDistinctId, currentDistinctId) }.getOrDefault(0)
        if (reassigned > 0) {
          NuxieLogger.info("Migrated $reassigned anonymous events to identified user: ${NuxieLogger.logDistinctId(currentDistinctId)}")
        }
      }
    }

    val props = buildMap<String, Any?> {
      put("distinct_id", distinctId)
      if (!wasIdentified && hasDifferentDistinctId) {
        put("\$anon_distinct_id", oldDistinctId)
      }
    }

    events.track(
      "\$identify",
      properties = props,
      userProperties = userProperties,
      userPropertiesSetOnce = userPropertiesSetOnce,
    )
  }

  fun reset(keepAnonymousId: Boolean = true) {
    if (!isSetup) return
    val identity = identityService ?: return
    val prevDistinctId = identity.getDistinctId()

    identity.reset(keepAnonymousId)
    sessionService?.resetSession()

    val newDistinctId = identity.getDistinctId()
    scope?.launch {
      profileService?.clearCache(prevDistinctId)
      profileService?.handleUserChange(fromOldDistinctId = prevDistinctId, toNewDistinctId = newDistinctId)
      featureService?.handleUserChange(fromOldDistinctId = prevDistinctId, toNewDistinctId = newDistinctId)
      segmentService?.handleUserChange(fromOldDistinctId = prevDistinctId, toNewDistinctId = newDistinctId)
      journeyService?.handleUserChange(fromOldDistinctId = prevDistinctId, toNewDistinctId = newDistinctId)
      flowService?.clearCache()
    }
  }

  fun getDistinctId(): String = identityService?.getDistinctId().orEmpty()

  fun getAnonymousId(): String = identityService?.getAnonymousId().orEmpty()

  val isIdentified: Boolean
    get() = identityService?.isIdentified == true

  fun startNewSession() {
    if (!isSetup) return
    sessionService?.startSession()
  }

  fun getCurrentSessionId(): String? {
    if (!isSetup) return null
    return sessionService?.getSessionId(readOnly = true)
  }

  fun setSessionId(sessionId: String) {
    if (!isSetup) return
    sessionService?.setSessionId(sessionId)
  }

  fun endSession() {
    if (!isSetup) return
    sessionService?.endSession()
  }

  fun resetSession() {
    if (!isSetup) return
    sessionService?.resetSession()
  }

  @Throws(PluginError::class)
  fun installPlugin(plugin: NuxiePlugin) {
    if (!isSetup) throw NuxieError.NotConfigured
    val service = pluginService ?: throw NuxieError.NotConfigured
    service.installPlugin(plugin)
  }

  @Throws(PluginError::class)
  fun uninstallPlugin(pluginId: String) {
    if (!isSetup) throw NuxieError.NotConfigured
    val service = pluginService ?: throw NuxieError.NotConfigured
    service.uninstallPlugin(pluginId)
  }

  fun startPlugin(pluginId: String) {
    if (!isSetup) return
    pluginService?.startPlugin(pluginId)
  }

  fun stopPlugin(pluginId: String) {
    if (!isSetup) return
    pluginService?.stopPlugin(pluginId)
  }

  fun isPluginInstalled(pluginId: String): Boolean {
    if (!isSetup) return false
    return pluginService?.isPluginInstalled(pluginId) == true
  }

  suspend fun flushEvents(): Boolean = networkQueue?.flush(forceSend = true) ?: false

  suspend fun getQueuedEventCount(): Int = eventQueueStore?.size() ?: 0

  suspend fun pauseEventQueue() {
    networkQueue?.pause()
  }

  suspend fun resumeEventQueue() {
    networkQueue?.resume()
  }

  suspend fun shutdown() {
    // Best-effort cleanup.
    networkQueue?.stop()
    playBillingObserver?.stop()
    profileService?.shutdown()
    journeyService?.shutdown()
    pluginService?.cleanup()
    playBillingObserver = null
    triggerBroker?.reset()
    activityTracker?.stop()
    activityTracker = null
    scope?.cancel()
    database?.close()
    database = null

    eventService = null
    networkQueue = null
    eventQueueStore = null
    api = null
    sessionService = null
    identityService = null
    featureService = null
    featureInfo = null
    profileService = null
    flowService = null
    segmentService = null
    journeyService = null
    triggerBroker = null
    irRuntime = null
    purchaseSyncService = null
    pluginService = null
    delegate = null
    configuration = null
  }

  val version: String
    get() = NuxieVersion.current

  val features: FeatureInfo
    get() = featureInfo ?: throw NuxieError.NotConfigured

  suspend fun refreshProfile(): io.nuxie.sdk.network.models.ProfileResponse {
    if (!isSetup) throw NuxieError.NotConfigured
    val profile = profileService ?: throw NuxieError.NotConfigured
    val features = featureService ?: throw NuxieError.NotConfigured
    val res = profile.refetchProfile()
    features.syncFeatureInfo()
    return res
  }

  fun showFlow(
    flowId: String,
    colorSchemeMode: FlowColorSchemeMode = FlowColorSchemeMode.LIGHT,
  ) {
    if (!isSetup) {
      NuxieLogger.warning("showFlow called before SDK setup")
      return
    }
    val activity = activityTracker?.getCurrentActivity()
    if (activity == null) {
      NuxieLogger.warning("showFlow requires a foreground Activity")
      return
    }
    val intent = Intent(activity, NuxieFlowActivity::class.java)
      .putExtra(NuxieFlowActivity.EXTRA_FLOW_ID, flowId)
      .putExtra(NuxieFlowActivity.EXTRA_COLOR_SCHEME_MODE, colorSchemeMode.rawValue)

    if (Looper.myLooper() == Looper.getMainLooper()) {
      activity.startActivity(intent)
    } else {
      Handler(Looper.getMainLooper()).post { activity.startActivity(intent) }
    }
  }

  suspend fun getFlowView(
    activity: ComponentActivity,
    flowId: String,
    viewId: Int,
    colorSchemeMode: FlowColorSchemeMode = FlowColorSchemeMode.LIGHT,
  ): FlowView {
    if (!isSetup) throw NuxieError.NotConfigured
    val svc = flowService ?: throw NuxieError.NotConfigured
    return svc.getFlowView(
      activity,
      flowId,
      viewId,
      runtimeDelegate = null,
      colorSchemeMode = colorSchemeMode,
    )
  }

  internal suspend fun getFlowViewForJourney(activity: ComponentActivity, flowId: String, journeyId: String): FlowView {
    if (!isSetup) throw NuxieError.NotConfigured
    val svc = journeyService ?: throw NuxieError.NotConfigured
    return svc.createFlowViewForJourney(activity, flowId, journeyId)
  }

  suspend fun hasFeature(featureId: String): FeatureAccess {
    if (!isSetup) throw NuxieError.NotConfigured
    val svc = featureService ?: throw NuxieError.NotConfigured
    return svc.checkWithCache(featureId = featureId, requiredBalance = null, entityId = null, forceRefresh = false)
  }

  suspend fun hasFeature(featureId: String, requiredBalance: Int, entityId: String? = null): FeatureAccess {
    if (!isSetup) throw NuxieError.NotConfigured
    val svc = featureService ?: throw NuxieError.NotConfigured
    return svc.checkWithCache(featureId = featureId, requiredBalance = requiredBalance, entityId = entityId, forceRefresh = false)
  }

  suspend fun getCachedFeature(featureId: String, entityId: String? = null): FeatureAccess? {
    if (!isSetup) return null
    return featureService?.getCached(featureId = featureId, entityId = entityId)
  }

  suspend fun checkFeature(featureId: String, requiredBalance: Int? = null, entityId: String? = null): FeatureCheckResult {
    if (!isSetup) throw NuxieError.NotConfigured
    val svc = featureService ?: throw NuxieError.NotConfigured
    return runCatching { svc.check(featureId = featureId, requiredBalance = requiredBalance, entityId = entityId) }
      .getOrElse { throw NuxieError.FeatureCheckFailed(featureId, it) }
  }

  suspend fun refreshFeature(featureId: String, requiredBalance: Int? = null, entityId: String? = null): FeatureCheckResult {
    return checkFeature(featureId = featureId, requiredBalance = requiredBalance, entityId = entityId)
  }

  suspend fun syncPlayStorePurchase(purchase: PlayStorePurchase): PurchaseResponse {
    if (!isSetup) throw NuxieError.NotConfigured
    val service = purchaseSyncService ?: throw NuxieError.NotConfigured
    return service.syncPlayStorePurchase(purchase)
  }

  internal fun refreshPlayStorePurchases() {
    if (configuration == null) {
      return
    }
    playBillingObserver?.refreshPurchases()
  }

  fun useFeature(
    featureId: String,
    amount: Double = 1.0,
    entityId: String? = null,
    metadata: Map<String, Any?>? = null,
  ) {
    if (!isSetup) {
      NuxieLogger.warning("useFeature called before SDK setup")
      return
    }
    scope?.launch {
      runCatching {
        useFeatureAndWait(
          featureId = featureId,
          amount = amount,
          entityId = entityId,
          metadata = metadata,
        )
      }.onFailure { error ->
        NuxieLogger.warning("useFeature failed", error)
      }
    }
  }

  suspend fun useFeatureAndWait(
    featureId: String,
    amount: Double = 1.0,
    entityId: String? = null,
    setUsage: Boolean = false,
    metadata: Map<String, Any?>? = null,
  ): FeatureUsageResult {
    if (!isSetup) throw NuxieError.NotConfigured
    val identity = identityService ?: throw NuxieError.NotConfigured
    val api = api ?: throw NuxieError.NotConfigured

    val distinctId = identity.getDistinctId()
    val props = buildMap<String, Any?> {
      put("feature_extId", featureId)
      if (setUsage) put("setUsage", true)
      if (metadata != null) put("metadata", metadata)
    }

    val response = api.trackEvent(
      event = "\$feature_used",
      distinctId = distinctId,
      anonDistinctId = null,
      properties = toJsonObject(props),
      uuid = UuidV7.generateString(),
      value = amount,
      entityId = entityId,
      timestamp = Iso8601.now(),
    )

    response.usage?.remaining?.let { remaining ->
      scope?.launch(Dispatchers.Main) {
        featureInfo?.setBalance(featureId, balance = remaining.toInt())
      }
    }

    return FeatureUsageResult(
      success = response.status == "ok" || response.status == "success",
      featureId = featureId,
      amountUsed = amount,
      message = response.message,
      usage = response.usage?.let { usage ->
        FeatureUsageResult.UsageInfo(
          current = usage.current,
          limit = usage.limit,
          remaining = usage.remaining,
        )
      },
    )
  }
}

private class AndroidLogcatSink : NuxieLogSink {
  override fun log(level: io.nuxie.sdk.config.LogLevel, message: String, throwable: Throwable?) {
    // Avoid depending on Android's Log in unit tests; this class only runs on Android.
    val tag = "NuxieSDK"
    val msg = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
    when (level) {
      io.nuxie.sdk.config.LogLevel.VERBOSE -> android.util.Log.v(tag, msg)
      io.nuxie.sdk.config.LogLevel.DEBUG -> android.util.Log.d(tag, msg)
      io.nuxie.sdk.config.LogLevel.INFO -> android.util.Log.i(tag, msg)
      io.nuxie.sdk.config.LogLevel.WARNING -> android.util.Log.w(tag, msg)
      io.nuxie.sdk.config.LogLevel.ERROR -> android.util.Log.e(tag, msg)
      io.nuxie.sdk.config.LogLevel.NONE -> Unit
    }
  }
}
