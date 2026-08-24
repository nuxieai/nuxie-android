package ai.nuxie.sdk.core

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.NuxieEvent
import ai.nuxie.sdk.events.EventLog
import ai.nuxie.sdk.events.EventStore
import ai.nuxie.sdk.events.NuxieContextBuilder
import ai.nuxie.sdk.events.SQLiteEventStore
import ai.nuxie.sdk.identity.AnonymousIdentityStub
import ai.nuxie.sdk.identity.IdentityProvider
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        val identity: IdentityProvider? = null,
        val nowMillis: (() -> Long)? = null,
        val appVersion: (() -> String)? = null,
        val registerLifecycle: Boolean = true,
    )

    private val appContext = context.applicationContext ?: context

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val identity: IdentityProvider = overrides.identity ?: AnonymousIdentityStub(appContext)

    private val nowMillis: () -> Long = overrides.nowMillis ?: System::currentTimeMillis

    val store: EventStore = overrides.store ?: SQLiteEventStore(appContext)

    val contextBuilder = NuxieContextBuilder(appContext, environment, logLevel, identity)

    val eventLog = EventLog(
        store = store,
        contextBuilder = contextBuilder,
        identity = identity,
        beforeSend = beforeSend,
        scope = scope,
        nowMillis = nowMillis,
    )

    val lifecycleTracker = AppLifecycleTracker(
        preferences = appContext.getSharedPreferences(LIFECYCLE_PREFERENCES, Context.MODE_PRIVATE),
        appVersionProvider = overrides.appVersion ?: { defaultAppVersion() },
        nowMillis = nowMillis,
        emit = { name, properties -> eventLog.capture(name, properties) },
    )

    private val lifecycleCoordinator = NuxieLifecycleCoordinator(lifecycleTracker, scope)

    /** Called once from Nuxie.setup after construction. */
    fun start() {
        lifecycleTracker.trackAppLaunchEvents()
        if (registerLifecycle) {
            (appContext as? Application)?.registerActivityLifecycleCallbacks(lifecycleCoordinator)
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

    private companion object {
        const val LIFECYCLE_PREFERENCES = "nuxie_lifecycle"
    }
}
