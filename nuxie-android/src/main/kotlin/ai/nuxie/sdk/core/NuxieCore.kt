package ai.nuxie.sdk.core

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.NuxieActivityInfo
import ai.nuxie.sdk.Nuxie
import ai.nuxie.sdk.events.ActivityForwarder
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.EventDeliveryWorker
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.features.FeatureService
import ai.nuxie.sdk.features.FeatureUsageService
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.billing.BillingClientAdapterFactory
import ai.nuxie.sdk.billing.FilePurchaseEvidenceStore
import ai.nuxie.sdk.billing.GooglePlayBillingClientAdapter
import ai.nuxie.sdk.billing.JourneyCommercePreparer
import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.NuxieApiPurchaseSynchronizer
import ai.nuxie.sdk.billing.PlayBillingConnection
import ai.nuxie.sdk.billing.PurchaseEvidenceStore
import ai.nuxie.sdk.billing.PurchaseHandlingMode
import ai.nuxie.sdk.billing.PurchaseService
import ai.nuxie.sdk.billing.PurchaseSettings
import ai.nuxie.sdk.billing.ProductResolver
import ai.nuxie.sdk.billing.registerJourneyProductMappings
import ai.nuxie.sdk.billing.purchaseEvidenceDirectory
import ai.nuxie.sdk.billing.purchaseAuthorityScope
import ai.nuxie.sdk.experiences.JourneyProfileCatalog
import ai.nuxie.sdk.experiences.JourneyTrustRoots
import ai.nuxie.sdk.experiences.JourneyReleaseHighWaterStore
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.journey.JourneyService
import ai.nuxie.sdk.journey.SignedTimezoneBundle
import ai.nuxie.sdk.journey.JourneyEffectDispatcher
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.HttpUrlConnectionTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.profile.ProfileService
import ai.nuxie.sdk.profile.ProfileLocaleSettings
import ai.nuxie.sdk.profile.ProfileStorageScope
import ai.nuxie.sdk.segments.SegmentService
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import ai.nuxie.sdk.session.SessionService
import ai.nuxie.sdk.runtime.NuxieEmbeddedRuntimeCompatibility
import ai.nuxie.sdk.runtime.nuxieRuntimeSourceRevision
import ai.nuxie.sdk.experiences.JourneyReleaseSupportedRuntime
import ai.nuxie.sdk.experiences.JourneyReleaseArtifactAcquirer
import ai.nuxie.sdk.presentation.ExperiencePresentationService
import ai.nuxie.sdk.presentation.ExperiencePresentationException
import ai.nuxie.sdk.presentation.AndroidRenderCapability
import ai.nuxie.sdk.presentation.JourneyPresentationRequest
import ai.nuxie.sdk.presentation.JourneyPresentationActionResult
import ai.nuxie.sdk.presentation.JourneyPresentationOwner
import ai.nuxie.sdk.presentation.JourneyPresentationResult
import ai.nuxie.sdk.presentation.JourneyPresenting
import android.app.Application
import android.content.Context
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Native provenance proves the runtime is present; compatibility is a separate contract. */
internal fun supportedRuntimeForEmbeddedRuntime(nativeSourceRevision: String?): JourneyReleaseSupportedRuntime? {
    if (nativeSourceRevision.isNullOrBlank()) return null
    return JourneyReleaseSupportedRuntime(
        currentSdkVersion = ai.nuxie.sdk.SdkVersion.VALUE,
        supportedRuntimeRevisions = setOf(NuxieEmbeddedRuntimeCompatibility.SOURCE_REVISION),
        supportedLuauRevisions = mapOf(
            NuxieEmbeddedRuntimeCompatibility.LUAU_REVISION to
                NuxieEmbeddedRuntimeCompatibility.LUAU_BYTECODE_VERSIONS,
        ),
        sceneFormatMajor = NuxieEmbeddedRuntimeCompatibility.SCENE_FORMAT_MAJOR,
        sceneFormatMinor = NuxieEmbeddedRuntimeCompatibility.SCENE_FORMAT_MINOR,
        timezoneDataRevision = SignedTimezoneBundle.REVISION,
        timezoneDataSha256 = SignedTimezoneBundle.SHA256,
        supportedCapabilities = NuxieEmbeddedRuntimeCompatibility.CAPABILITIES,
    )
}

/**
 * Constructor-injected composition root (iOS `NuxieCore` parity): concrete
 * services are created in dependency order and consumers receive
 * role-specific seams. [Overrides] is the ONLY injection point — tests fill
 * it; production passes none. No service locator.
 */
internal class NuxieCore(
    context: Context,
    val apiKey: String,
    val environment: NuxieEnvironment,
    val logLevel: LogLevel,
    beforeSend: ((NuxieEvent) -> NuxieEvent?)?,
    val featureInfo: FeatureInfo = FeatureInfo(),
    featureCacheTtlMillis: Long = 5L * 60L * 1000L,
    localeIdentifier: String? = null,
    purchaseDelegate: NuxiePurchaseDelegate? = null,
    purchaseHandlingMode: PurchaseHandlingMode = PurchaseHandlingMode.NUXIE_MANAGED,
    apiEndpointOverride: URL? = null,
    overrides: Overrides = Overrides(),
    private val forwardingEnabled: () -> Boolean = { false },
    private val forwardActivity: suspend (NuxieActivityInfo) -> Unit = {},
) {
    private val registerLifecycle = overrides.registerLifecycle
    private val stopped = AtomicBoolean(false)
    private val requestInitialProfileRefresh = overrides.requestInitialProfileRefresh

    internal fun interface PresentationFactory {
        fun create(): ExperiencePresentationService
    }

    internal class Overrides(
        val store: EventStore? = null,
        val identity: IdentityService? = null,
        val nowMillis: (() -> Long)? = null,
        val appVersion: (() -> String)? = null,
        val registerLifecycle: Boolean = true,
        val transport: HttpTransport? = null,
        val presentationFactory: PresentationFactory? = null,
        val purchaseEvidenceStore: PurchaseEvidenceStore? = null,
        val eventDatabaseFile: File? = null,
        val profileCacheDirectory: File? = null,
        val journeySupportedRuntime: (() -> JourneyReleaseSupportedRuntime?)? = null,
        val deviceLocaleIdentifier: (() -> String)? = null,
        /** Unit suites that hydrate profiles explicitly opt out so the
         *  startup refresh cannot race their hydrations through the
         *  authoritative revision fences. */
        val requestInitialProfileRefresh: Boolean = true,
        /** Unit suites inject an inert factory so a Robolectric billing
         *  connection can never fire connection-driven recovery, whose
         *  evidence-derived projection would clear a test's display overlay
         *  mid-assertion. */
        val billingClientFactory: BillingClientAdapterFactory? = null,
    )

    private val appContext = context.applicationContext ?: context

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val identity: IdentityService = overrides.identity ?: IdentityService(appContext)

    private val nowMillis: () -> Long = overrides.nowMillis ?: System::currentTimeMillis

    private val profileLocale = ProfileLocaleSettings(
        localeIdentifier = localeIdentifier,
        deviceLocaleIdentifier = overrides.deviceLocaleIdentifier ?: { Locale.getDefault().toString() },
    )

    val sessions = SessionService(nowMillis)

    val store: EventStore = overrides.store ?: SQLiteEventStore(
        appContext,
        nowMillis = nowMillis,
        databaseFile = overrides.eventDatabaseFile ?: File(appContext.filesDir, "nuxie/events.db"),
    )

    val userTransitions: UserTransitionCoordinator by lazy {
        UserTransitionCoordinator(store, scope)
    }

    private val transport = overrides.transport ?: HttpUrlConnectionTransport()

    val api = NuxieApi(
        apiKey = apiKey,
        environment = environment,
        transport = transport,
        baseUrlOverride = apiEndpointOverride,
    )

    val segments = SegmentService(appContext)

    val contextBuilder = NuxieContextBuilder(appContext, environment, logLevel, identity)

    val eventLog = EventLog(
        store = store,
        contextBuilder = contextBuilder,
        identity = identity,
        beforeSend = beforeSend,
        scope = scope,
        nowMillis = nowMillis,
        sessionIdProvider = { sessions.getSessionId() },
    )

    private val purchaseEvidenceStore = overrides.purchaseEvidenceStore
        ?: FilePurchaseEvidenceStore(
            purchaseEvidenceDirectory(appContext.filesDir, apiKey, environment),
        )

    private val releaseTrustRoots = JourneyTrustRoots.keys(environment)
    private val releaseHighWater = JourneyReleaseHighWaterStore(appContext)

    val journeyProfiles = JourneyProfileCatalog(
        trustedKeys = releaseTrustRoots,
        highWater = releaseHighWater,
        supportedRuntime = overrides.journeySupportedRuntime ?: ::journeySupportedRuntime,
        onReleaseAdmitted = purchaseEvidenceStore::registerJourneyProductMappings,
    )

    val delivery = EventDeliveryWorker(
        store = store,
        api = api,
        scope = scope,
        nowMillis = nowMillis,
    )

    val features = FeatureService(
        api = api,
        identity = identity,
        featureInfo = featureInfo,
        cacheTtlMillis = featureCacheTtlMillis,
        nowMillis = nowMillis,
    )

    val purchaseSettings = PurchaseSettings(purchaseDelegate, purchaseHandlingMode)

    private lateinit var purchaseService: PurchaseService

    private val billing = PlayBillingConnection(
        factory = overrides.billingClientFactory
            ?: GooglePlayBillingClientAdapter.factory(appContext),
        scope = scope,
        onPurchasesUpdated = { update -> scope.launch { purchaseService.onPurchasesUpdated(update) } },
        onConnected = { purchaseService.recover() },
    )

    val purchases: PurchaseService = PurchaseService(
        billing = billing,
        evidenceStore = purchaseEvidenceStore,
        synchronizer = NuxieApiPurchaseSynchronizer(api),
        features = features,
        distinctId = identity::distinctId,
        emit = eventLog::capture,
        settings = purchaseSettings,
        scope = scope,
        nowMillis = nowMillis,
        api = api,
        purchaseStorageScope = purchaseAuthorityScope(apiKey, environment),
        capturePurchaseSynced = eventLog::captureIdempotently,
        capturePurchaseEvent = ::capturePurchaseEvent,
    ).also { purchaseService = it }

    private val journeyCommerce = JourneyCommercePreparer(
        resolver = ProductResolver(billing, purchaseEvidenceStore),
        purchases = purchases,
    )

    /** Durable purchase capture; committed events enter the Journey route once. */
    internal suspend fun capturePurchaseEvent(
        name: String,
        properties: Map<String, Any?>,
        eventId: String,
        distinctId: String,
    ): Boolean {
        return eventLog.captureIdempotentlyWithResult(
            name,
            properties,
            eventId,
            distinctId,
        ).succeeded
    }

    /**
     * Decide the complete destination Feature projection synchronously. The
     * facade publishes the returned mutation only after releasing its identity
     * monitor, so inline collectors cannot observe a half-switched customer.
     */
    fun stageFeatureUserChange(from: String, to: String): FeatureInfo.Mutation =
        kotlinx.coroutines.runBlocking {
            purchases.withOptimisticProjectionSnapshot(to) { destinationProjection ->
                features.handleUserChange(from, to, destinationProjection)
            }
        }

    val featureUsage = FeatureUsageService(
        api = api,
        purchases = purchases,
        identity = identity,
        features = features,
        eventLog = eventLog,
        scope = scope,
    )

    private val releaseArtifactAcquirer = JourneyReleaseArtifactAcquirer(appContext, transport)

    val presentations = overrides.presentationFactory?.create()
        ?: ExperiencePresentationService(
            context = appContext,
            emit = eventLog::capture,
            scope = scope,
            runtimeAvailable = AndroidRenderCapability::isAvailable,
            commerce = journeyCommerce,
        )

    private val journeyPresenter = object : JourneyPresenting {
        override fun reserve(ownerDistinctId: String) =
            presentations.reserveJourney(ownerDistinctId)

        override suspend fun present(
            request: JourneyPresentationRequest,
        ): JourneyPresentationResult = try {
            presentations.presentJourney(
                release = request.release,
                screenId = request.screenId,
                journeyId = request.journeyId,
                ownerDistinctId = request.ownerDistinctId,
                reservation = request.reservation,
                canPresent = request.canPresent,
                acquire = {
                    releaseArtifactAcquirer.acquire(request.release, request.delivery)
                },
                nextBatchSequence = request.nextBatchSequence,
                nextEmissionSequence = request.nextEmissionSequence,
                onScreenChanged = request.onScreenChanged,
                onScreenDismissed = request.onScreenDismissed,
                onEmissionBatch = request.onEmissionBatch,
                onPresentationRevealed = request.onPresentationRevealed,
                onOutcome = request.onOutcome,
            )
            JourneyPresentationResult.Shown
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ExperiencePresentationException) {
            when (error.reason) {
                ExperiencePresentationException.Reason.DECLINED ->
                    JourneyPresentationResult.Declined
                ExperiencePresentationException.Reason.JOURNEY_COMPLETED ->
                    JourneyPresentationResult.Completed
                ExperiencePresentationException.Reason.PRODUCTS_UNAVAILABLE ->
                    JourneyPresentationResult.ProductsUnavailable
                else -> JourneyPresentationResult.Failed
            }
        } catch (_: Exception) {
            JourneyPresentationResult.Failed
        }

        override fun owns(owner: JourneyPresentationOwner): Boolean =
            presentations.ownsJourney(owner)

        override fun screenId(owner: JourneyPresentationOwner): String? =
            presentations.journeyScreenId(owner)

        override fun resolveAction(
            owner: JourneyPresentationOwner,
            action: kotlinx.serialization.json.JsonObject,
            source: ai.nuxie.sdk.presentation.JourneyScreenEmissionSource?,
        ): kotlinx.serialization.json.JsonObject? =
            presentations.resolveJourneyAction(owner, action, source)

        override suspend fun dispatchAction(
            owner: JourneyPresentationOwner,
            action: kotlinx.serialization.json.JsonObject,
            effectId: String,
        ): JourneyPresentationActionResult =
            presentations.dispatchJourneyAction(owner, action, effectId)

        override fun cancelBackNavigation(owner: JourneyPresentationOwner) {
            presentations.cancelBackNavigation(owner)
        }

        override suspend fun shutdownOwnedBy(ownerDistinctId: String) {
            presentations.shutdownOwnedBy(ownerDistinctId)
        }

        override suspend fun shutdownPresentation(
            ownerDistinctId: String,
            journeyId: String,
        ) {
            presentations.shutdownJourney(ownerDistinctId, journeyId)
        }
    }

    private val journeyDispatcher = JourneyEffectDispatcher(
        identity = identity,
        capture = eventLog::captureIdempotentlyIfCurrent,
        deliverAppAction = Nuxie::deliverAppAction,
    )

    val journeys = JourneyService(
        identity = identity,
        events = store,
        catalog = journeyProfiles,
        journalDirectory = File(appContext.filesDir, "nuxie"),
        scope = scope,
        capture = eventLog::captureIdempotently,
        captureScreenEvent = eventLog::captureScreenEvent,
        capturePresentationEvent = eventLog::captureRoutedSystemEvent,
        featureAccess = { featureId ->
            features.getCached(featureId, requiredBalance = null, entityId = null)
        },
        dispatcher = journeyDispatcher,
        presenter = journeyPresenter,
        nowMillis = nowMillis,
        replayPendingLocalRoutes = eventLog::replayPendingLocalRoutes,
        artifactManager = releaseArtifactAcquirer,
    )

    val profile = ProfileService(
        context = appContext,
        storageScope = ProfileStorageScope(apiKey, environment),
        api = api,
        identity = identity,
        journeyProfiles = journeyProfiles,
        journeys = journeys,
        journeyArtifacts = releaseArtifactAcquirer,
        stageFeatureProfile = { distinctId, body, purchaseRevision, authoritativeRevision, isCurrent ->
            features.stageProfile(
                distinctId,
                body,
                purchaseRevision,
                authoritativeRevision,
                isCurrent,
            )
        },
        publishFeatureProfile = features::publishStaged,
        captureFeaturePurchaseRevision = features::capturePurchaseRevision,
        reserveFeatureAuthoritativeRevision = features::reserveAuthoritativeRevision,
        scope = scope,
        localeSettings = profileLocale,
        nowMillis = nowMillis,
        cacheDirectory = overrides.profileCacheDirectory,
    )

    val lifecycleTracker = AppLifecycleTracker(
        preferences = appContext.getSharedPreferences(LIFECYCLE_PREFERENCES, Context.MODE_PRIVATE),
        appVersionProvider = overrides.appVersion ?: { defaultAppVersion() },
        nowMillis = nowMillis,
        emit = { name, properties -> eventLog.capture(name, properties) },
    )

    private val lifecycleCoordinator = NuxieLifecycleCoordinator(
        lifecycleTracker,
        sessions,
        scope,
        onBackground = {
            journeys.onAppDidEnterBackground()
        },
        afterBackground = {
            delivery.flushAll()
        },
        onForeground = {
            // Profile reconciliation is the server-authoritative refund and
            // revocation lane. A failed revalidation leaves the authenticated
            // cached authority in place for offline execution.
            profile.refreshAndWait()
            journeys.onAppWillEnterForeground()
            // Purchase recovery handles still-active Play evidence.
            purchases.recover()
            delivery.flushAll()
        },
    )

    /** Called once from Nuxie.setup after construction. */
    fun start() {
        val activityForwarder = ActivityForwarder(deliver = forwardActivity)
        eventLog.subscribeCommittedWithAdmission(
            sampleGeneration = journeys::eventAdmissionGeneration,
        ) { event, admittedGeneration ->
            journeys.handleEvent(event, admittedGeneration)
        }
        eventLog.subscribeForwarding(
            isEnabled = forwardingEnabled,
            handler = activityForwarder::onCommitted,
        )
        // Every committed capture nudges the delivery threshold check.
        eventLog.subscribeCommitted { delivery.kick() }
        userTransitions.addObserver(UserTransitionCoordinator.Observer { _, from, _ ->
            presentations.shutdownOwnedBy(from)
        })
        userTransitions.addObserver(UserTransitionCoordinator.Observer { _, from, to ->
            journeys.handleUserChange(from, to)
        })
        userTransitions.addObserver(UserTransitionCoordinator.Observer { _, _, _ ->
            // The retained-evidence projection already switched synchronously
            // through stageFeatureUserChange. This queued lane reconciles Play
            // and backend state without becoming the source of local readiness.
            purchases.recover()
        })
        userTransitions.addObserver(profile.transitionObserver)
        // Subscriber registration precedes recovery, while the synchronous
        // enqueue keeps every later capture behind initialization in its FIFO.
        journeys.enqueueInitialization()
        if (requestInitialProfileRefresh) {
            profile.requestRefresh()
        }
        scope.launch {
            delivery.flushAll()
        }
        lifecycleTracker.trackAppLaunchEvents()
        billing.connect()
        if (registerLifecycle) {
            (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycleCoordinator)
        }
    }

    /** Stop every owned coroutine before releasing the shared event store. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        if (registerLifecycle) {
            (appContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycleCoordinator)
        }
        billing.close()
        presentations.close()
        kotlinx.coroutines.runBlocking {
            runCatching { journeys.profileDidClearAll() }
            runCatching { profile.close() }
            runCatching { delivery.close() }
            runCatching { eventLog.closeWorkers() }
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.join()
            runCatching { store.close() }
        }
    }

    private fun defaultAppVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val name = info.versionName ?: "unknown"
        "$name (${androidVersionCode(info)})"
    }.getOrDefault("unknown")

    private fun androidVersionCode(info: android.content.pm.PackageInfo): Long =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /** Runtime absence closes the Journey enrollment front door. */
    private fun journeySupportedRuntime(): JourneyReleaseSupportedRuntime? {
        if (!AndroidRenderCapability.isAvailable()) return null
        return supportedRuntimeForEmbeddedRuntime(nuxieRuntimeSourceRevision())
    }

    private companion object {
        const val LOG_TAG = "Nuxie"
        const val LIFECYCLE_PREFERENCES = "nuxie_lifecycle"
    }
}
