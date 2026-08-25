package ai.nuxie.sdk.core

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.events.EventDeliveryWorker
import ai.nuxie.sdk.events.TriggerBroker
import ai.nuxie.sdk.events.TriggerService
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.features.FeatureService
import ai.nuxie.sdk.features.FeatureType
import ai.nuxie.sdk.commerce.FilePurchaseEvidenceStore
import ai.nuxie.sdk.commerce.GooglePlayBillingClientAdapter
import ai.nuxie.sdk.commerce.NuxiePurchaseDelegate
import ai.nuxie.sdk.commerce.NuxieApiPurchaseSynchronizer
import ai.nuxie.sdk.commerce.PlayBillingConnection
import ai.nuxie.sdk.commerce.PurchaseEvidenceStore
import ai.nuxie.sdk.commerce.PurchaseHandlingMode
import ai.nuxie.sdk.commerce.PurchaseService
import ai.nuxie.sdk.commerce.PurchaseSettings
import ai.nuxie.sdk.commerce.purchaseEvidenceDirectory
import ai.nuxie.sdk.experiences.ExperienceTrustRoots
import ai.nuxie.sdk.experiences.ReleaseHighWaterStore
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.journey.JourneyLedger
import ai.nuxie.sdk.journey.JourneyReleaseCatalog
import ai.nuxie.sdk.journey.JourneyService
import ai.nuxie.sdk.journey.JourneyStore
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.network.HttpUrlConnectionTransport
import ai.nuxie.sdk.network.NuxieApi
import ai.nuxie.sdk.profile.ProfileService
import ai.nuxie.sdk.segments.SegmentService
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import ai.nuxie.sdk.session.SessionService
import ai.nuxie.sdk.runtime.NuxieRuntimeBridge
import ai.nuxie.sdk.experiences.SupportedRuntime
import ai.nuxie.sdk.experiences.ReleaseArtifactAcquirer
import ai.nuxie.sdk.presentation.ExperiencePresentationService
import ai.nuxie.sdk.presentation.AndroidRenderCapability
import ai.nuxie.sdk.presentation.PresentationOutcome
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    purchaseDelegate: NuxiePurchaseDelegate? = null,
    purchaseHandlingMode: PurchaseHandlingMode = PurchaseHandlingMode.NUXIE_MANAGED,
    overrides: Overrides = Overrides(),
) {
    private val registerLifecycle = overrides.registerLifecycle

    internal fun interface PresentationFactory {
        fun create(
            reportOutcome: suspend (PresentationOutcome) -> Unit,
        ): ExperiencePresentationService
    }

    internal class Overrides(
        val store: EventStore? = null,
        val identity: IdentityService? = null,
        val nowMillis: (() -> Long)? = null,
        val appVersion: (() -> String)? = null,
        val registerLifecycle: Boolean = true,
        val transport: HttpTransport? = null,
        val journeys: TriggerService.JourneyRouter? = null,
        val features: TriggerService.FeatureGate? = null,
        val presenter: TriggerService.ExperiencePresenter? = null,
        val presentationFactory: PresentationFactory? = null,
        val purchaseEvidenceStore: PurchaseEvidenceStore? = null,
    )

    private val appContext = context.applicationContext ?: context

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val identity: IdentityService = overrides.identity ?: IdentityService(appContext)

    private val nowMillis: () -> Long = overrides.nowMillis ?: System::currentTimeMillis

    val sessions = SessionService(nowMillis)

    val store: EventStore = overrides.store ?: SQLiteEventStore(appContext)

    val userTransitions: UserTransitionCoordinator by lazy {
        UserTransitionCoordinator(store, scope)
    }

    private val transport = overrides.transport ?: HttpUrlConnectionTransport()

    val api = NuxieApi(
        apiKey = apiKey,
        environment = environment,
        transport = transport,
    )

    val delivery = EventDeliveryWorker(store, api, scope, nowMillis = nowMillis)

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

    private val journeyCatalog = JourneyReleaseCatalog(
        trustedKeys = ExperienceTrustRoots.keys(environment),
        highWater = ReleaseHighWaterStore(appContext),
        supportedRuntime = ::journeySupportedRuntime,
    )

    val journeys = JourneyService(
        store = JourneyStore(appContext.filesDir),
        ledger = JourneyLedger(eventLog),
        releases = journeyCatalog,
        nowMillis = nowMillis,
        initialDistinctId = identity.distinctId(),
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
        factory = GooglePlayBillingClientAdapter.factory(appContext),
        scope = scope,
        onPurchasesUpdated = { update -> scope.launch { purchaseService.onPurchasesUpdated(update) } },
        onConnected = { purchaseService.recover() },
    )

    val purchases: PurchaseService = PurchaseService(
        billing = billing,
        evidenceStore = overrides.purchaseEvidenceStore
            ?: FilePurchaseEvidenceStore(
                purchaseEvidenceDirectory(appContext.filesDir, apiKey, environment),
            ),
        synchronizer = NuxieApiPurchaseSynchronizer(api),
        features = features,
        distinctId = identity::distinctId,
        emit = eventLog::capture,
        settings = purchaseSettings,
        scope = scope,
        nowMillis = nowMillis,
    ).also { purchaseService = it }

    private val reportPresentationOutcome: suspend (PresentationOutcome) -> Unit = { outcome ->
        outcome.ref.journeyId?.let { journeyId ->
            journeys.presentationEnded(identity.distinctId(), journeyId, outcome.reason)
        }
    }

    val presentations = overrides.presentationFactory?.create(reportPresentationOutcome)
        ?: ExperiencePresentationService(
            context = appContext,
            releases = journeyCatalog,
            acquirer = ReleaseArtifactAcquirer(appContext, transport),
            emit = eventLog::capture,
            scope = scope,
            runtimeAvailable = AndroidRenderCapability::isAvailable,
            reportOutcome = reportPresentationOutcome,
        )

    val triggers by lazy {
        TriggerService(
        eventLog = eventLog,
        api = api,
        broker = TriggerBroker(),
        journeys = overrides.journeys ?: journeys,
        features = overrides.features ?: object : TriggerService.FeatureGate {
            override suspend fun cachedAccess(
                featureId: String,
                requiredBalance: Double?,
                entityId: String?,
            ): Boolean? = features.getCached(featureId, requiredBalance, entityId)
                // getCached already resolved requiredBalance semantics for
                // every entry kind (forRequiredBalance for regular entries,
                // exact-match serving for opaque snapshots whose balance is
                // intentionally null); re-deriving from balance here would
                // deny allowed opaque grants.
                ?.allowed

            override suspend fun checkAccess(
                featureId: String,
                requiredBalance: Double?,
                entityId: String?,
            ): Boolean = features.checkWithCache(featureId, requiredBalance, entityId).allowed
        },
            presenter = overrides.presenter ?: TriggerService.ExperiencePresenter { experienceVersionId, journeyId ->
                presentations.present(experienceVersionId, journeyId)
            },
            nowMillis = nowMillis,
        )
    }

    val profile = ProfileService(
        context = appContext,
        api = api,
        identity = identity,
        segments = segments,
        applyUserProperties = { properties -> identity.setUserProperties(properties) },
        applyJourneyProfile = { distinctId, body ->
            journeyCatalog.applyProfile(distinctId, body)
            scope.launch { journeys.applyDownFacts(body, distinctId) }
        },
        applyFeatureProfile = { distinctId, body, purchaseRevision ->
            features.hydrateProfile(distinctId, body, purchaseRevision)
        },
        captureFeaturePurchaseRevision = features::capturePurchaseRevision,
        scope = scope,
        localeProvider = { null },  // locale override arrives with setLocaleIdentifier
        nowMillis = nowMillis,
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
        onBackground = { delivery.flushAll() },
        onForeground = {
            // Profile reconciliation is the server-authoritative refund and
            // revocation lane; purchase recovery handles still-active Play evidence.
            profile.requestRefresh()
            purchases.recover()
        },
    )

    /** Called once from Nuxie.setup after construction. */
    fun start() {
        // Every committed capture nudges the delivery threshold check.
        eventLog.subscribeCommitted { delivery.kick() }
        userTransitions.addObserver(UserTransitionCoordinator.Observer { _, from, to ->
            features.handleUserChange(from, to)
            purchases.recover()
        })
        userTransitions.addObserver(profile.transitionObserver)
        profile.requestRefresh()
        lifecycleTracker.trackAppLaunchEvents()
        billing.connect()
        if (registerLifecycle) {
            (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycleCoordinator)
        }
    }

    /** Cancel workers and release the store. Testing teardown only for now. */
    fun stop() {
        billing.close()
        presentations.close()
        kotlinx.coroutines.runBlocking {
            runCatching { profile.close() }
            runCatching { delivery.close() }
            runCatching { eventLog.close() }
        }
        scope.cancel()
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
    private fun journeySupportedRuntime(): SupportedRuntime? {
        if (!AndroidRenderCapability.isAvailable()) return null
        val sourceRevision = runCatching {
            Json.parseToJsonElement(NuxieRuntimeBridge.nativeRuntimeInfo()).jsonObject
                .getValue("sourceRevision").jsonPrimitive.content
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return SupportedRuntime(
            currentSdkVersion = ai.nuxie.sdk.SdkVersion.VALUE,
            supportedRuntimeRevisions = setOf(sourceRevision),
            supportedLuauRevisions = mapOf("rive_0_36" to setOf(3, 6)),
            sceneFormatMajor = 7,
            sceneFormatMinor = 0,
            timezoneDataRevision = "2026c",
            timezoneDataSha256 = "d4ad5c12a6be491076f333c9b4f96f60cb8ab552495bbfae0d8cdc9730ecb198",
            supportedCapabilities = setOf("rive", "text-input"),
        )
    }

    private companion object {
        const val LIFECYCLE_PREFERENCES = "nuxie_lifecycle"
    }
}
