package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.features.FeatureAccess
import ai.nuxie.sdk.features.FeatureCheckPolicy
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.features.FeatureUsageResult
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import ai.nuxie.sdk.billing.NuxiePurchaseDelegate
import ai.nuxie.sdk.billing.PurchaseHandlingMode
import ai.nuxie.sdk.billing.PurchaseResult
import ai.nuxie.sdk.billing.RestoreResult
import ai.nuxie.sdk.billing.StoreProduct
import ai.nuxie.sdk.billing.SubscriptionReplacement
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Entry point for the greenfield Nuxie Android SDK.
 *
 * Setup constructs the internal composition root (event log, lifecycle
 * capture). The trigger, Features, presentation, and purchase surfaces arrive
 * in subsequent PRs on the locked contract.
 */
object Nuxie {
    private const val LOG_TAG = "Nuxie"

    @Volatile
    private var setupState: SetupState? = null

    private val featureInfoInstance = FeatureInfo()
    private val identityDecisionLock = Any()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var listenerReference = WeakReference<NuxieListener>(null)

    /** Weak listener for Experience requests delivered to the host app. */
    var listener: NuxieListener?
        get() = listenerReference.get()
        set(value) {
            listenerReference = WeakReference(value)
        }

    val isSetup: Boolean
        get() = setupState != null

    val version: String
        get() = SdkVersion.VALUE

    /** Reactive Feature access for the current customer. */
    val features: FeatureInfo
        get() = core?.featureInfo ?: featureInfoInstance

    @Synchronized
    fun setup(context: Context, configuration: NuxieConfiguration) {
        val existingState = setupState
        if (existingState != null) {
            if (existingState.logLevel >= LogLevel.WARN) {
                Log.w(LOG_TAG, "Nuxie is already set up; ignoring the repeated setup call.")
            }
            return
        }

        require(configuration.apiKey.isNotBlank()) { "apiKey must not be blank." }

        featureInfoInstance.onFeatureChange = { featureId, oldAccess, newAccess, isCurrent ->
            deliverFeatureAccessChange(featureId, oldAccess, newAccess, isCurrent)
        }
        val core = NuxieCore(
            context = context,
            apiKey = configuration.apiKey,
            environment = configuration.environment,
            logLevel = configuration.logLevel,
            beforeSend = configuration.beforeSend,
            featureInfo = featureInfoInstance,
            featureCacheTtlMillis = configuration.featureCacheTTL,
            localeIdentifier = configuration.localeIdentifier,
            purchaseDelegate = configuration.purchaseDelegate,
            purchaseHandlingMode = configuration.purchaseHandlingMode,
            apiEndpointOverride = configuration.testingOverrides.apiEndpoint,
            overrides = overridesForTesting ?: NuxieCore.Overrides(),
            forwardingEnabled = { listener != null },
            forwardActivity = ::deliverActivity,
        )
        setupState = SetupState(logLevel = configuration.logLevel, core = core)
        core.start()
    }

    // MARK: Trigger

    /**
     * Name the moment. The remotely published Experiences decide what
     * happens; [handler] receives progressive updates through the terminal
     * outcome. Fire-and-forget when [handler] is null.
     */
    fun trigger(
        event: String,
        properties: Map<String, Any?>? = null,
        handler: ((TriggerUpdate) -> Unit)? = null,
    ) {
        val core = core ?: run {
            handler?.invoke(
                TriggerUpdate.Error(
                    TriggerError(TriggerErrorCode.NOT_CONFIGURED, "Call Nuxie.setup first."),
                ),
            )
            return
        }
        core.scope.launch {
            core.triggers.trigger(event, properties, handler ?: {})
        }
    }

    /**
     * Trigger and await the terminal outcome. [progress] observes every
     * update on the way there.
     */
    suspend fun triggerAndWait(
        event: String,
        properties: Map<String, Any?>? = null,
        progress: ((TriggerUpdate) -> Unit)? = null,
    ): TriggerResult {
        val core = core
            ?: return TriggerResult.Error(
                TriggerError(TriggerErrorCode.NOT_CONFIGURED, "Call Nuxie.setup first."),
            )
        val done = kotlinx.coroutines.CompletableDeferred<TriggerResult>()
        core.scope.launch {
            core.triggers.trigger(event, properties) { update ->
                progress?.invoke(update)
                val terminal: TriggerResult? = when (update) {
                    is TriggerUpdate.Error -> TriggerResult.Error(update.error)
                    is TriggerUpdate.Decision -> when (update.decision) {
                        is TriggerDecision.AllowedImmediate -> TriggerResult.Allowed
                        is TriggerDecision.DeniedImmediate -> TriggerResult.Denied
                        is TriggerDecision.NoMatch -> TriggerResult.NoMatch
                        // Suppression and gate-terminal presentation resolve as
                        // the sequence fallback (iOS parity).
                        is TriggerDecision.Suppressed -> TriggerResult.NoMatch
                        is TriggerDecision.ExperienceShown -> TriggerResult.NoMatch
                        else -> null
                    }
                    is TriggerUpdate.FeatureAccess -> when (update.update) {
                        is FeatureAccessUpdate.Allowed -> TriggerResult.Allowed
                        is FeatureAccessUpdate.Denied -> TriggerResult.Denied
                        is FeatureAccessUpdate.Pending -> null
                    }
                    is TriggerUpdate.Journey -> TriggerResult.JourneyCompleted(update.update)
                }
                terminal?.let { done.complete(it) }
            }
        }
        return done.await()
    }

    // MARK: Feature access

    /**
     * Check whether the current customer has access to a Feature.
     * Metered Features are evaluated against [requiredBalance].
     */
    suspend fun hasFeature(
        featureId: String,
        requiredBalance: Double = 1.0,
        entityId: String? = null,
        policy: FeatureCheckPolicy = FeatureCheckPolicy.CACHE_FIRST,
    ): FeatureAccess {
        val core = core ?: throw IllegalStateException("Call Nuxie.setup first.")
        return when (policy) {
            FeatureCheckPolicy.CACHE_FIRST -> core.features.checkWithCache(
                featureId = featureId,
                requiredBalance = requiredBalance,
                entityId = entityId,
            )
            FeatureCheckPolicy.REMOTE -> core.features.check(
                featureId = featureId,
                requiredBalance = requiredBalance,
                entityId = entityId,
            )
        }
    }

    // MARK: Feature use

    /** Report metered Feature use without waiting for server confirmation. */
    fun useFeature(
        featureId: String,
        amount: Double = 1.0,
        entityId: String? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        val core = core ?: run {
            Log.w(LOG_TAG, "useFeature called before SDK setup")
            return
        }
        core.featureUsage.useFeature(featureId, amount, entityId, metadata)
    }

    /** Report metered Feature use and await the server-authoritative result. */
    suspend fun useFeatureAndWait(
        featureId: String,
        amount: Double = 1.0,
        entityId: String? = null,
        setUsage: Boolean = false,
        metadata: Map<String, Any?>? = null,
    ): FeatureUsageResult {
        val core = core ?: throw IllegalStateException("Call Nuxie.setup first.")
        return core.featureUsage.useFeatureAndWait(featureId, amount, entityId, setUsage, metadata)
    }

    // MARK: Identity

    /**
     * Associate the device with a known user. The same id with no properties
     * is a full no-op; a different id migrates anonymous history (first
     * identify only), starts a new session, and captures `$identify`.
     */
    fun identify(
        distinctId: String,
        userProperties: Map<String, Any?>? = null,
        userPropertiesSetOnce: Map<String, Any?>? = null,
    ) {
        val core = core ?: return
        require(distinctId.isNotBlank()) { "distinctId must not be blank." }

        val copiedUserProperties = userProperties?.toMap()
        val copiedUserPropertiesSetOnce = userPropertiesSetOnce?.toMap()
        val publication = synchronized(identityDecisionLock) {
            val identity = core.identity
            val oldDistinctId = identity.distinctId()
            val wasIdentified = identity.isIdentified
            val hasDifferentDistinctId = distinctId != oldDistinctId

            identity.setDistinctId(distinctId)
            val currentDistinctId = identity.distinctId()
            if (hasDifferentDistinctId) {
                core.userTransitions.enqueue(
                    UserTransitionCoordinator.Transition(
                        kind = UserTransitionCoordinator.Kind.IDENTIFY,
                        from = oldDistinctId,
                        to = currentDistinctId,
                        migrateEvents = !wasIdentified,
                    ),
                )
                // Rotating on every same-id identify would fragment sessions.
                core.sessions.startSession()
            }

            val hasUserProperties =
                copiedUserProperties != null || copiedUserPropertiesSetOnce != null
            if (hasDifferentDistinctId || hasUserProperties) {
                copiedUserProperties?.let { identity.setUserProperties(it) }
                copiedUserPropertiesSetOnce?.let { identity.setOnceUserProperties(it) }

                val properties = linkedMapOf<String, Any?>(
                    "distinct_id" to currentDistinctId,
                )
                if (!wasIdentified && hasDifferentDistinctId) {
                    properties["\$anon_distinct_id"] = oldDistinctId
                }
                copiedUserProperties?.let { properties["\$set"] = it }
                copiedUserPropertiesSetOnce?.let { properties["\$set_once"] = it }
                core.eventLog.capture(
                    SystemEventNames.IDENTIFY,
                    properties,
                    distinctIdOverride = currentDistinctId,
                )
            }

            // Every durable consequence above belongs to this transition even
            // if the visible publication synchronously identifies again.
            if (hasDifferentDistinctId) {
                core.stageFeatureUserChange(oldDistinctId, currentDistinctId)
            } else {
                null
            }
        }

        // Observable publication is the only reentrant step and remains
        // outside the facade and FeatureService monitors. A nested transition
        // can supersede this view, but cannot erase the durable work above.
        publication?.let {
            kotlinx.coroutines.runBlocking { core.featureInfo.publish(it) }
        }
    }

    /** End the identified session and return to a (new or kept) anonymous id. */
    fun reset(keepAnonymousId: Boolean = false) {
        val core = core ?: return
        val publication = synchronized(identityDecisionLock) {
            val identity = core.identity
            val previousDistinctId = identity.distinctId()
            identity.reset(keepAnonymousId)
            val newDistinctId = identity.distinctId()
            core.userTransitions.enqueue(
                UserTransitionCoordinator.Transition(
                    kind = UserTransitionCoordinator.Kind.RESET,
                    from = previousDistinctId,
                    to = newDistinctId,
                    migrateEvents = false,
                ),
            )
            core.sessions.resetSession()

            // Publication is staged after every durable reset consequence so
            // reentrant identify cannot cause this RESET transition to vanish.
            core.stageFeatureUserChange(previousDistinctId, newDistinctId)
        }

        kotlinx.coroutines.runBlocking { core.featureInfo.publish(publication) }
    }

    val distinctId: String
        get() = core?.identity?.distinctId().orEmpty()

    val anonymousId: String
        get() = core?.identity?.anonymousId().orEmpty()

    val isIdentified: Boolean
        get() = core?.identity?.isIdentified ?: false

    // MARK: Profile

    /** Change the profile locale and await a refresh for the new locale. */
    suspend fun setLocaleIdentifier(localeIdentifier: String?) {
        val core = core ?: return
        core.profile.setLocaleIdentifier(localeIdentifier)
        core.profile.refreshAndWait()
    }

    // MARK: Presentation

    /** Dismiss the active engine-owned Experience and await local semantic completion. */
    suspend fun dismiss() {
        val core = core ?: return
        core.presentations.dismissFromHost(core.identity.distinctId())
    }

    /**
     * Last-mile App Action delivery. Journey execution calls this only after
     * its liveness fences pass; the listener is resolved on the main thread
     * so changing or clearing it before delivery takes effect immediately.
     */
    internal suspend fun deliverAppAction(action: AppAction) {
        deliverOnMain("App Action") { it.onAppActionRequested(this, action) }
    }

    /** Last-mile typed activity delivery through the weak listener seam. */
    internal suspend fun deliverActivity(info: NuxieActivityInfo) {
        deliverOnMain("activity") { it.onActivityEmitted(this, info) }
    }

    /** Last-mile Feature transition delivery through the weak listener seam. */
    internal suspend fun deliverFeatureAccessChange(
        featureId: String,
        oldAccess: FeatureAccess?,
        newAccess: FeatureAccess,
        isCurrent: () -> Boolean,
    ) {
        deliverOnMain("Feature access change", isCurrent) {
            it.featureAccessDidChange(featureId, oldAccess, newAccess)
        }
    }

    private suspend fun deliverOnMain(
        label: String,
        isCurrent: () -> Boolean = { true },
        callback: (NuxieListener) -> Unit,
    ) {
        if (!isCurrent() || listener == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener?.let { resolved ->
                if (isCurrent()) callback(resolved)
            }
            return
        }
        suspendCancellableCoroutine { continuation ->
            val posted = mainHandler.post {
                runCatching {
                    listener?.let { resolved ->
                        if (isCurrent()) callback(resolved)
                    }
                }
                    .onSuccess { continuation.resume(Unit) }
                    .onFailure(continuation::resumeWithException)
            }
            if (!posted) {
                continuation.resumeWithException(
                    IllegalStateException("Could not dispatch $label to the main thread."),
                )
            }
        }
    }

    // MARK: Purchases

    /** Launch checkout for the exact StoreProduct that was shown. */
    suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        replacement: SubscriptionReplacement? = null,
    ): PurchaseResult = core?.purchases?.purchase(activity, product, replacement)
        ?: PurchaseResult.Failed(IllegalStateException("Call Nuxie.setup first."))

    /** Restore Play's currently active subscriptions and one-time purchases. */
    suspend fun restorePurchases(): RestoreResult = core?.purchases?.restorePurchases()
        ?: RestoreResult.Failed(IllegalStateException("Call Nuxie.setup first."))

    fun setPurchaseDelegate(delegate: NuxiePurchaseDelegate?) {
        core?.purchaseSettings?.delegate = delegate
    }

    fun setPurchaseHandlingMode(mode: PurchaseHandlingMode) {
        core?.purchaseSettings?.handlingMode = mode
    }

    internal val core: NuxieCore?
        get() = setupState?.core

    /** Testing seam: inject core overrides for the next setup. Not public API. */
    internal var overridesForTesting: NuxieCore.Overrides? = null

    /** Testing seam: tear down the singleton between tests. Not public API. */
    internal fun resetForTesting() {
        setupState?.core?.let { runCatching { it.stop() } }
        setupState = null
        listener = null
        kotlinx.coroutines.runBlocking { featureInfoInstance.reset() }
    }

    private class SetupState(
        val logLevel: LogLevel,
        val core: NuxieCore,
    )

}
