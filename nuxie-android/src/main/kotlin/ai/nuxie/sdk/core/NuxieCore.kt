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
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    overrides: Overrides = Overrides(),
) {
    private val registerLifecycle = overrides.registerLifecycle

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

    val api = NuxieApi(
        apiKey = apiKey,
        environment = environment,
        transport = overrides.transport ?: HttpUrlConnectionTransport(),
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

    val triggers by lazy {
        TriggerService(
        eventLog = eventLog,
        api = api,
        broker = TriggerBroker(),
        journeys = overrides.journeys ?: journeys,
        features = overrides.features ?: TriggerService.NoFeatureAuthority,
            presenter = overrides.presenter ?: TriggerService.PresentationUnavailable,
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
    )

    /** Called once from Nuxie.setup after construction. */
    fun start() {
        // Every committed capture nudges the delivery threshold check.
        eventLog.subscribeCommitted { delivery.kick() }
        userTransitions.addObserver(profile.transitionObserver)
        profile.requestRefresh()
        lifecycleTracker.trackAppLaunchEvents()
        if (registerLifecycle) {
            (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycleCoordinator)
        }
    }

    /** Cancel workers and release the store. Testing teardown only for now. */
    fun stop() {
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
        if (!NuxieRuntimeBridge.isAvailable) return null
        // The native-runtime metadata bridge is not consumed until presentation
        // lands. This conservative placeholder still lets the verifier reject
        // every requirement it cannot prove rather than guessing compatibility.
        return SupportedRuntime(
            currentSdkVersion = ai.nuxie.sdk.SdkVersion.VALUE,
            supportedRuntimeRevisions = emptySet(),
            supportedLuauRevisions = emptyMap(),
            sceneFormatMajor = 0,
            sceneFormatMinor = 0,
            timezoneDataRevision = "",
            timezoneDataSha256 = "",
            supportedCapabilities = emptySet(),
        )
    }

    private companion object {
        const val LIFECYCLE_PREFERENCES = "nuxie_lifecycle"
    }
}
