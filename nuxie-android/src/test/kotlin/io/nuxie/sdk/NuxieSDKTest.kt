package io.nuxie.sdk

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.EventTriggerConfig
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.queue.InMemoryEventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
import io.nuxie.sdk.features.FeatureAccess
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.features.FeatureInfo
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.FeatureType
import io.nuxie.sdk.features.PurchaseFeature
import io.nuxie.sdk.features.PurchaseResponse
import io.nuxie.sdk.flows.BuildManifest
import io.nuxie.sdk.flows.BuildManifestFile
import io.nuxie.sdk.flows.FlowBundleRef
import io.nuxie.sdk.flows.FlowService
import io.nuxie.sdk.flows.CloseReason
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.flows.RemoteFlowScreen
import io.nuxie.sdk.flows.ViewModel
import io.nuxie.sdk.flows.ViewModelInstance
import io.nuxie.sdk.flows.ViewModelProperty
import io.nuxie.sdk.flows.ViewModelPropertyType
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.journey.FileJourneyStore
import io.nuxie.sdk.journey.JourneyService
import io.nuxie.sdk.lifecycle.CurrentActivityTracker
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.ActiveJourney
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.PlayStorePurchaseRequest
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.purchases.GooglePlayBillingPurchaseObserver
import io.nuxie.sdk.purchases.PlayBillingClient
import io.nuxie.sdk.purchases.PlayBillingProductDetailsSnapshot
import io.nuxie.sdk.purchases.PlayBillingPurchaseSnapshot
import io.nuxie.sdk.purchases.PlayBillingResult
import io.nuxie.sdk.purchases.PlayStorePurchase
import io.nuxie.sdk.purchases.PlayStoreProductType
import io.nuxie.sdk.purchases.PurchaseSyncService
import io.nuxie.sdk.segments.SegmentService
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import io.nuxie.sdk.triggers.DefaultTriggerBroker
import io.nuxie.sdk.triggers.SuppressReason
import io.nuxie.sdk.triggers.TriggerDecision
import io.nuxie.sdk.triggers.TriggerUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class NuxieSDKTest {
  private class FakeApi(
    private val profile: ProfileResponse,
    private val remoteFlow: RemoteFlow,
  ) : NuxieApiProtocol {
    data class TrackedEvent(
      val event: String,
      val uuid: String,
    )

    var gatePayload: JsonObject? = allowGatePayload()
    val trackedEvents: MutableList<TrackedEvent> = mutableListOf()
    val purchaseRequests: MutableList<PlayStorePurchaseRequest> = mutableListOf()

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
      trackedEvents += TrackedEvent(event = event, uuid = uuid)
      return EventResponse(
        status = "ok",
        payload = if (event == "paywall_trigger") gatePayload else null,
        event = EventResponse.EventInfo(id = uuid, processed = true),
      )
    }

    override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
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

    override suspend fun syncPlayStorePurchase(request: PlayStorePurchaseRequest): PurchaseResponse {
      purchaseRequests += request
      return PurchaseResponse(success = true, customerId = request.distinctId, features = emptyList())
    }

    companion object {
      fun allowGatePayload(): JsonObject {
        return JsonObject(
          mapOf(
            "gate" to JsonObject(
              mapOf("decision" to JsonPrimitive("allow"))
            )
          )
        )
      }

      fun showFlowGatePayload(flowId: String): JsonObject {
        return JsonObject(
          mapOf(
            "gate" to JsonObject(
              mapOf(
                "decision" to JsonPrimitive("show_flow"),
                "flowId" to JsonPrimitive(flowId),
              )
            )
          )
        )
      }
    }
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

  private class FakeFeatureService : FeatureService {
    override suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? = null
    override suspend fun getAllCached(): Map<String, FeatureAccess> = emptyMap()
    override suspend fun check(featureId: String, requiredBalance: Int?, entityId: String?): FeatureCheckResult {
      return FeatureCheckResult(
        customerId = "sdk_user",
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

  private class RecordingPlayBillingClient : PlayBillingClient {
    data class QueryCall(
      val productType: PlayStoreProductType,
      val includeSuspendedSubscriptions: Boolean,
    )

    val queries = mutableListOf<QueryCall>()
    var startConnectionCalls = 0
    override var isReady: Boolean = false
      private set

    override fun setPurchasesUpdatedListener(
      listener: ((PlayBillingResult, List<PlayBillingPurchaseSnapshot>?) -> Unit)?,
    ) {
    }

    override fun startConnection(
      onSetupFinished: (PlayBillingResult) -> Unit,
      onDisconnected: () -> Unit,
    ) {
      startConnectionCalls += 1
      isReady = true
      onSetupFinished(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"))
    }

    override fun endConnection() {
      isReady = false
    }

    override fun queryPurchases(
      productType: PlayStoreProductType,
      includeSuspendedSubscriptions: Boolean,
      listener: (PlayBillingResult, List<PlayBillingPurchaseSnapshot>) -> Unit,
    ) {
      queries += QueryCall(productType, includeSuspendedSubscriptions)
      listener(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"), emptyList())
    }

    override fun queryProductDetails(
      productType: PlayStoreProductType,
      productIds: List<String>,
      listener: (PlayBillingResult, List<PlayBillingProductDetailsSnapshot>) -> Unit,
    ) {
      listener(PlayBillingResult(BillingClient.BillingResponseCode.OK, "OK"), emptyList())
    }
  }

  private data class Harness(
    val sdk: NuxieSDK,
    val scope: CoroutineScope,
    val identity: DefaultIdentityService,
    val api: FakeApi,
    val featureService: FeatureService,
    val broker: DefaultTriggerBroker,
    val journeyService: JourneyService,
    val closeActivity: () -> Unit,
  ) {
    suspend fun close() {
      closeActivity()
      sdk.shutdown()
      scope.cancel()
    }
  }

  @After
  fun tearDown() = runBlocking {
    NuxieSDK.shared().shutdown()
  }

  @Test
  fun version_matches_core() {
    assertEquals(NuxieVersion.current, NuxieSDK.shared().version)
  }

  @Test
  fun trigger_emitsGateDecisionAfterJourneyDecision() = runBlocking {
    val harness = newHarness()
    try {
      val updates = mutableListOf<TriggerUpdate>()

      harness.sdk.trigger("paywall_trigger") { update ->
        updates += update
      }

      fun sawJourneyAndGate(): Boolean {
        return updates.any { it.decisionOrNull() is TriggerDecision.JourneyStarted } &&
          updates.any { it.decisionOrNull() == TriggerDecision.AllowedImmediate }
      }

      var attempts = 0
      while (!sawJourneyAndGate() && attempts < 20) {
        shadowOf(Looper.getMainLooper()).idle()
        attempts += 1
        delay(50)
      }
      shadowOf(Looper.getMainLooper()).idle()

      assertTrue(updates.any { it.decisionOrNull() is TriggerDecision.JourneyStarted })
      assertTrue(updates.any { it.decisionOrNull() == TriggerDecision.AllowedImmediate })
    } finally {
      harness.close()
    }
  }

  @Test
  fun trigger_completesSuppressedShowFlowGateAfterFlowShown() = runBlocking {
    val harness = newHarness()
    try {
      harness.sdk.trigger("paywall_trigger")
      waitForTrigger { harness.api.trackedEvents.any { it.event == "\$journey_start" } }

      harness.api.gatePayload = FakeApi.showFlowGatePayload("standalone_flow")
      val updates = mutableListOf<TriggerUpdate>()
      harness.sdk.trigger("paywall_trigger") { update ->
        updates += update
      }

      waitForTrigger {
        updates.any { it.decisionOrNull() is TriggerDecision.Suppressed } &&
          updates.any { it.decisionOrNull() is TriggerDecision.FlowShown }
      }
      val eventId = harness.api.trackedEvents.last { it.event == "paywall_trigger" }.uuid
      harness.broker.emit(eventId, TriggerUpdate.Decision(TriggerDecision.NoMatch))
      shadowOf(Looper.getMainLooper()).idle()

      assertTrue(updates.any { it.decisionOrNull() == TriggerDecision.Suppressed(SuppressReason.AlreadyActive) })
      assertTrue(updates.any { it.decisionOrNull() is TriggerDecision.FlowShown })
      assertTrue(updates.none { it.decisionOrNull() == TriggerDecision.NoMatch })
    } finally {
      harness.close()
    }
  }

  @Test
  fun trigger_keepsStartedJourneyShowFlowGateOpenForJourneyUpdate() = runBlocking {
    val harness = newHarness()
    try {
      harness.api.gatePayload = FakeApi.showFlowGatePayload("standalone_flow")
      val updates = mutableListOf<TriggerUpdate>()

      harness.sdk.trigger("paywall_trigger") { update ->
        updates += update
      }

      waitForTrigger {
        updates.any { it.decisionOrNull() is TriggerDecision.JourneyStarted } &&
          updates.any { it.decisionOrNull() is TriggerDecision.FlowShown }
      }
      val started = updates
        .mapNotNull { it.decisionOrNull() as? TriggerDecision.JourneyStarted }
        .firstOrNull()
      assertNotNull(started)

      harness.journeyService.handleRuntimeDismiss(started!!.ref.journeyId, CloseReason.UserDismissed)
      waitForTrigger {
        updates.any { it is TriggerUpdate.Journey }
      }

      assertTrue(updates.any { it is TriggerUpdate.Journey })
    } finally {
      harness.close()
    }
  }

  @Test
  fun identify_refreshesPlayStorePurchasesForNewDistinctId() = runBlocking {
    val harness = newHarness()
    try {
      val billingClient = RecordingPlayBillingClient()
      val observer = GooglePlayBillingPurchaseObserver(
        scope = harness.scope,
        syncService = PurchaseSyncService(
          api = harness.api,
          identityService = harness.identity,
          featureService = harness.featureService,
        ),
        client = billingClient,
        distinctIdProvider = { harness.identity.getDistinctId() },
        consumableProductIds = { emptySet() },
      )
      observer.start()
      harness.sdk.setPrivateField("playBillingObserver", observer)

      waitForTrigger { billingClient.queries.size == 2 }

      harness.sdk.identify("identified_user")
      waitForTrigger { billingClient.queries.size >= 4 }

      assertEquals("identified_user", harness.identity.getDistinctId())
      assertEquals(1, billingClient.startConnectionCalls)
      assertEquals(
        listOf(
          RecordingPlayBillingClient.QueryCall(PlayStoreProductType.SUBSCRIPTION, true),
          RecordingPlayBillingClient.QueryCall(PlayStoreProductType.ONE_TIME, false),
          RecordingPlayBillingClient.QueryCall(PlayStoreProductType.SUBSCRIPTION, true),
          RecordingPlayBillingClient.QueryCall(PlayStoreProductType.ONE_TIME, false),
        ),
        billingClient.queries,
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun syncPlayStorePurchase_appliesConsumableConfigurationToDirectSync() = runBlocking {
    val harness = newHarness()
    try {
      harness.sdk.configuration!!.addConsumablePlayStoreProduct("coins_100")

      val response = harness.sdk.syncPlayStorePurchase(
        PlayStorePurchase(
          purchaseToken = "token_consumable_2026",
          productIds = listOf(" coins_100 "),
          packageName = "io.nuxie.example",
        )
      )

      assertTrue(response.success)
      val request = harness.api.purchaseRequests.single()
      assertEquals("token_consumable_2026", request.purchaseToken)
      assertEquals("coins_100", request.productId)
      assertEquals(PlayStoreProductType.ONE_TIME, request.productType)
      assertEquals(true, request.consumePurchase)
    } finally {
      harness.close()
    }
  }

  @Test
  fun syncPlayStorePurchase_doesNotConsumeMixedConsumableDirectSync() = runBlocking {
    val harness = newHarness()
    try {
      harness.sdk.configuration!!.addConsumablePlayStoreProduct("coins_100")

      val response = harness.sdk.syncPlayStorePurchase(
        PlayStorePurchase(
          purchaseToken = "token_mixed_cart_2026",
          productIds = listOf("coins_100", "lifetime_unlock"),
          packageName = "io.nuxie.example",
        )
      )

      assertTrue(response.success)
      val request = harness.api.purchaseRequests.single()
      assertEquals("token_mixed_cart_2026", request.purchaseToken)
      assertNull(request.productId)
      assertNull(request.productType)
      assertNull(request.consumePurchase)
    } finally {
      harness.close()
    }
  }

  private fun TriggerUpdate.decisionOrNull(): TriggerDecision? {
    return (this as? TriggerUpdate.Decision)?.decision
  }

  private suspend fun waitForTrigger(predicate: () -> Boolean) {
    var attempts = 0
    while (!predicate() && attempts < 20) {
      shadowOf(Looper.getMainLooper()).idle()
      attempts += 1
      delay(50)
    }
    shadowOf(Looper.getMainLooper()).idle()
  }

  private suspend fun newHarness(): Harness {
    NuxieSDK.shared().shutdown()

    val sdk = NuxieSDK.shared()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val identity = DefaultIdentityService(InMemoryKeyValueStore())
    identity.setDistinctId("sdk_user")

    val config = NuxieConfiguration("test_key")
    val campaign = Campaign(
      id = "sdk_camp",
      name = "Campaign",
      flowId = "sdk_flow",
      flowNumber = 1,
      flowName = null,
      reentry = CampaignReentry.EveryTime,
      publishedAt = "2026-01-01T00:00:00Z",
      trigger = CampaignTrigger.Event(EventTriggerConfig(eventName = "paywall_trigger")),
      goal = null,
      exitPolicy = null,
      conversionAnchor = null,
      campaignType = null,
    )
    val remoteFlow = buildFlow()
    val profile = ProfileResponse(
      campaigns = listOf(campaign),
      segments = emptyList(),
      flows = listOf(remoteFlow),
      journeys = listOf<ActiveJourney>(),
      experiments = emptyMap(),
    )
    val api = FakeApi(profile = profile, remoteFlow = remoteFlow)
    val queueStore = InMemoryEventQueueStore()
    val historyStore = InMemoryEventHistoryStore()
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
    val sessionService = DefaultSessionService()
    val eventService = EventService(
      identityService = identity,
      sessionService = sessionService,
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
    val featureService = FakeFeatureService()
    val cacheDir = Files.createTempDirectory("nuxie_sdk_trigger_test").toFile()
    val broker = DefaultTriggerBroker()
    val activityTracker = CurrentActivityTracker(ApplicationProvider.getApplicationContext<Application>())
    val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    val profileService = FakeProfileService(profile)
    val flowService = FlowService(
      api = api,
      configuration = config,
      scope = scope,
      cacheDirectory = cacheDir,
    )
    val journeyService = JourneyService(
      scope = scope,
      configuration = config,
      identityService = identity,
      eventService = eventService,
      profileService = profileService,
      segmentService = segmentService,
      featureService = featureService,
      flowService = flowService,
      journeyStore = FileJourneyStore(File(cacheDir, "journeys")),
      triggerBroker = broker,
      irRuntime = irRuntime,
      presentFlow = { _, _ -> true },
    )

    sdk.setPrivateField("configuration", config)
    sdk.setPrivateField("identityService", identity)
    sdk.setPrivateField("sessionService", sessionService)
    sdk.setPrivateField("api", api)
    sdk.setPrivateField("eventService", eventService)
    sdk.setPrivateField("profileService", profileService)
    sdk.setPrivateField("featureInfo", FeatureInfo())
    sdk.setPrivateField("featureService", featureService)
    sdk.setPrivateField(
      "purchaseSyncService",
      PurchaseSyncService(
        api = api,
        identityService = identity,
        featureService = featureService,
        eventService = eventService,
      )
    )
    sdk.setPrivateField("flowService", flowService)
    sdk.setPrivateField("segmentService", segmentService)
    sdk.setPrivateField("journeyService", journeyService)
    sdk.setPrivateField("triggerBroker", broker)
    sdk.setPrivateField("scope", scope)
    sdk.setPrivateField("activityTracker", activityTracker)

    return Harness(
      sdk = sdk,
      scope = scope,
      identity = identity,
      api = api,
      featureService = featureService,
      broker = broker,
      journeyService = journeyService,
      closeActivity = { activityController.pause().stop().destroy() },
    )
  }

  private fun NuxieSDK.setPrivateField(name: String, value: Any?) {
    val field = NuxieSDK::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
  }

  private fun buildFlow(): RemoteFlow {
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
      id = "sdk_flow",
      bundle = FlowBundleRef(
        url = "https://example.com/sdk_flow",
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
      interactions = emptyMap(),
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
