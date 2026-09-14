package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.core.CoreConstruction
import ai.nuxie.sdk.core.SdkLifecycle
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Entry point for event capture, Features, Experiences and purchases. */
object Nuxie {
    private const val LOG_TAG = "Nuxie"

    private val featureInfoInstance = FeatureInfo()
    private val lifecycle = SdkLifecycle<NuxieCore>(
        prepareStop = { listener = null; it.prepareForShutdown() },
        stop = { state ->
            try { state.stopAndAwait() } finally { featureInfoInstance.reset() }
        },
    )
    private val identityDecisionLock = Any()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val callbackLock = Any()
    private val callbackDepth = ThreadLocal<Int>()
    private val pendingCallbacks = mutableMapOf<Runnable, () -> Unit>()

    @Volatile
    private var listenerReference = WeakReference<NuxieListener>(null)

    /** Weak listener for Experience requests delivered to the host app. */
    var listener: NuxieListener?
        get() = listenerReference.get()
        set(value) {
            val withdrawn = synchronized(callbackLock) {
                listenerReference = WeakReference(value)
                if (value == null) pendingCallbacks.toMap().also { pendingCallbacks.clear() }
                else emptyMap()
            }
            // Clearing the listener withdraws queued deliveries. In particular,
            // shutdown must not wait for a main-thread callback after detaching it.
            withdrawn.forEach { (task, finish) ->
                mainHandler.removeCallbacks(task)
                finish()
            }
        }

    val isSetup: Boolean
        get() = lifecycle.graph != null

    val version: String
        get() = SdkVersion.VALUE

    /** Reactive Feature access for the current customer. */
    val features: FeatureInfo
        get() = core?.featureInfo ?: featureInfoInstance

    /** Repeated setup, including during teardown, is ignored. Await shutdown before setting up again. */
    fun setup(context: Context, configuration: NuxieConfiguration) {
        val installed = lifecycle.install(create = {
            require(configuration.apiKey.isNotBlank()) { "apiKey must not be blank." }
            featureInfoInstance.onFeatureChange = { featureId, oldAccess, newAccess, isCurrent ->
                deliverFeatureAccessChange(featureId, oldAccess, newAccess, isCurrent)
            }
            val core = CoreConstruction().build(beforeRollback = { listener = null }) { construction ->
                construction.onFailure { featureInfoInstance.reset() }
                NuxieCore(
                    context = context,
                    apiKey = configuration.apiKey,
                    environment = configuration.environment,
                    logLevel = configuration.logLevel,
                    beforeSend = configuration.beforeSend?.let { hook -> { event -> inCallback { hook(event) } } },
                    featureInfo = featureInfoInstance,
                    featureCacheTtlMillis = configuration.featureCacheTTL,
                    localeIdentifier = configuration.localeIdentifier,
                    purchaseDelegate = configuration.purchaseDelegate,
                    purchaseHandlingMode = configuration.purchaseHandlingMode,
                    testStoreEnabled = configuration.testStoreEnabled,
                    apiEndpointOverride = configuration.testingOverrides.apiEndpoint,
                    overrides = overridesForTesting ?: NuxieCore.Overrides(),
                    forwardingEnabled = { listener != null },
                    forwardActivity = ::deliverActivity,
                    construction = construction,
                )
            }
            core
        }, start = { it.start(context as? Activity) })
        if (!installed && configuration.logLevel >= LogLevel.WARN) {
            Log.w(LOG_TAG, "SDK setup ignored while a graph is active or changing.")
        }
    }

    /** Close admission and initiate teardown without blocking the calling thread. */
    fun shutdown() {
        lifecycle.requestShutdown()
        listener = null
    }

    /** Await teardown completion before setting up a new SDK graph. */
    suspend fun shutdownAndAwait() {
        check((callbackDepth.get() ?: 0) == 0) {
            "An SDK callback cannot await its own shutdown. Call shutdown() and await completion after returning."
        }
        lifecycle.shutdownAndAwait()
    }

    private inline fun <T> inCallback(block: () -> T): T {
        val depth = callbackDepth.get() ?: 0
        callbackDepth.set(depth + 1)
        return try { block() } finally {
            if (depth == 0) callbackDepth.remove() else callbackDepth.set(depth)
        }
    }

    // MARK: Trigger

    /** Capture an event for delivery and ordered Journey evaluation. */
    fun trigger(
        event: String,
        properties: Map<String, Any?>? = null,
    ) {
        val operation = lifecycle.admit() ?: return
        val core = operation.graph
        try {
            core.eventLog.capture(event, properties)
        } finally { operation.finish() }
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
        return lifecycle.withOperation { state ->
            val core = state
            when (policy) {
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
    }

    // MARK: Feature use

    /** Report metered Feature use without waiting for server confirmation. */
    fun useFeature(
        featureId: String,
        amount: Double = 1.0,
        entityId: String? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        val copiedMetadata = metadata?.toMap()
        lifecycle.launchOperation({ it.scope }) {
            runCatching { it.featureUsage.useFeatureAndWait(featureId, amount, entityId, false, copiedMetadata) }
                .onFailure { failure -> Log.w(LOG_TAG, "Feature use failed", failure) }
        }
    }

    /** Report metered Feature use and await the server-authoritative result. */
    suspend fun useFeatureAndWait(
        featureId: String,
        amount: Double = 1.0,
        entityId: String? = null,
        setUsage: Boolean = false,
        metadata: Map<String, Any?>? = null,
    ): FeatureUsageResult {
        return lifecycle.withOperation { state ->
            val core = state
            core.featureUsage.useFeatureAndWait(featureId, amount, entityId, setUsage, metadata)
        }
    }

    /** Consume an exact quantity with an operation ID retained across caller retries. */
    suspend fun consumeFeature(
        featureId: String,
        quantity: Double = 1.0,
        operationId: String,
        entityId: String? = null,
    ): ai.nuxie.sdk.features.FeatureConsumptionResult {
        return lifecycle.withOperation { state ->
            val core = state
            core.featureUsage.consumeFeature(featureId, quantity, operationId, entityId)
        }
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
        val operation = lifecycle.admit() ?: return
        val core = operation.graph
        try {
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
        } finally { operation.finish() }
    }

    /** End the identified session and return to a (new or kept) anonymous id. */
    fun reset(keepAnonymousId: Boolean = false) {
        val operation = lifecycle.admit() ?: return
        val core = operation.graph
        try {
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
        } finally { operation.finish() }
    }

    val distinctId: String
        get() = core?.identity?.distinctId().orEmpty()

    val anonymousId: String
        get() = core?.identity?.anonymousId().orEmpty()

    val isIdentified: Boolean
        get() = core?.identity?.isIdentified ?: false

    // MARK: Profile

    /** Use this locale at the next launch/foreground profile synchronization. */
    suspend fun setLocaleIdentifier(localeIdentifier: String?) {
        val operation = lifecycle.admit() ?: return
        lifecycle.execute(operation) { state ->
            val core = state
            core.profile.setLocaleIdentifier(localeIdentifier)
        }
    }

    // MARK: Presentation

    /** Dismiss the active engine-owned Experience and await local semantic completion. */
    suspend fun dismiss() {
        val operation = lifecycle.admit() ?: return
        lifecycle.execute(operation) { state ->
            val core = state
            core.presentations.dismissFromHost(core.identity.distinctId())
        }
    }

    /**
     * Last-mile App Action delivery. Journey execution calls this only after
     * its liveness fences pass; the listener is resolved on the main thread
     * so changing or clearing it before delivery takes effect immediately.
     */
    internal suspend fun deliverAppAction(action: AppAction) {
        deliverOnMain("App Action") { it.onAppActionRequested(this, action) }
    }

    /** Journey publication admitted under its execution and identity fences. */
    internal suspend fun deliverAppAction(
        action: AppAction,
        publishIfCurrent: ((() -> Unit) -> Boolean),
    ): Boolean = deliverOnMainIfAdmitted("App Action", publishIfCurrent) {
        it.onAppActionRequested(this, action)
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
                if (isCurrent()) inCallback { callback(resolved) }
            }
            return
        }
        suspendCancellableCoroutine { continuation ->
            lateinit var task: Runnable
            task = Runnable {
                val claimed = synchronized(callbackLock) { pendingCallbacks.remove(task) != null }
                if (!claimed) return@Runnable
                runCatching {
                    listener?.let { resolved ->
                        if (isCurrent()) inCallback { callback(resolved) }
                    }
                }
                    .onSuccess { continuation.resume(Unit) }
                    .onFailure(continuation::resumeWithException)
            }
            var withdrawn = false
            val posted = synchronized(callbackLock) {
                if (listener == null || !isCurrent()) {
                    withdrawn = true
                    true
                } else {
                    pendingCallbacks[task] = { continuation.resume(Unit) }
                    mainHandler.post(task).also { if (!it) pendingCallbacks.remove(task) }
                }
            }
            continuation.invokeOnCancellation {
                synchronized(callbackLock) { pendingCallbacks.remove(task) }
                mainHandler.removeCallbacks(task)
            }
            if (withdrawn) continuation.resume(Unit)
            else if (!posted) {
                continuation.resumeWithException(
                    IllegalStateException("Could not dispatch $label to the main thread."),
                )
            }
        }
    }

    private suspend fun deliverOnMainIfAdmitted(
        label: String,
        publishIfCurrent: ((() -> Unit) -> Boolean),
        callback: (NuxieListener) -> Unit,
    ): Boolean {
        if (listener == null) return publishIfCurrent {}
        val publication = {
            publishIfCurrent {
                listener?.let { resolved -> inCallback { callback(resolved) } }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return publication()
        return suspendCancellableCoroutine { continuation ->
            val finishWithoutListener = {
                if (continuation.isActive) {
                    runCatching { publishIfCurrent {} }
                        .onSuccess { continuation.resume(it) }
                        .onFailure(continuation::resumeWithException)
                }
                Unit
            }
            lateinit var task: Runnable
            task = Runnable {
                val claimed = synchronized(callbackLock) { pendingCallbacks.remove(task) != null }
                if (!claimed || !continuation.isActive) return@Runnable
                runCatching(publication)
                    .onSuccess { continuation.resume(it) }
                    .onFailure(continuation::resumeWithException)
            }
            var withdrawn = false
            val posted = synchronized(callbackLock) {
                if (listener == null) {
                    withdrawn = true
                    true
                } else {
                    pendingCallbacks[task] = finishWithoutListener
                    mainHandler.post(task).also { if (!it) pendingCallbacks.remove(task) }
                }
            }
            continuation.invokeOnCancellation {
                synchronized(callbackLock) { pendingCallbacks.remove(task) }
                mainHandler.removeCallbacks(task)
            }
            if (withdrawn) finishWithoutListener()
            else if (!posted) {
                continuation.resumeWithException(
                    IllegalStateException("Could not dispatch $label to the main thread."),
                )
            }
        }
    }

    // MARK: Commerce

    /** Launch checkout for the exact StoreProduct that was shown. */
    suspend fun purchase(
        activity: Activity,
        product: StoreProduct,
        replacement: SubscriptionReplacement? = null,
    ): PurchaseResult {
        val operation = lifecycle.admit() ?: return PurchaseResult.Failed(IllegalStateException("Call Nuxie.setup first."))
        return lifecycle.executeOwned(operation, { it.producerScope }) {
            it.purchases.purchase(activity, product, replacement)
        }
    }

    /** Restore Play's currently active subscriptions and one-time purchases. */
    suspend fun restorePurchases(): RestoreResult {
        val operation = lifecycle.admit() ?: return RestoreResult.Failed(IllegalStateException("Call Nuxie.setup first."))
        return lifecycle.executeOwned(operation, { it.producerScope }) { it.purchases.restorePurchases() }
    }

    fun setPurchaseDelegate(delegate: NuxiePurchaseDelegate?) {
        val operation = lifecycle.admit() ?: return
        try { operation.graph.purchaseSettings.delegate = delegate } finally { operation.finish() }
    }

    fun setPurchaseHandlingMode(mode: PurchaseHandlingMode) {
        val operation = lifecycle.admit() ?: return
        try { operation.graph.purchaseSettings.handlingMode = mode } finally { operation.finish() }
    }

    internal val core: NuxieCore?
        get() = lifecycle.graph

    /** Testing seam: inject core overrides for the next setup. Not public API. */
    internal var overridesForTesting: NuxieCore.Overrides? = null

    /** Testing seam: tear down the singleton between tests. Not public API. */
    internal fun resetForTesting() {
        kotlinx.coroutines.runBlocking { shutdownAndAwait() }
    }


}
