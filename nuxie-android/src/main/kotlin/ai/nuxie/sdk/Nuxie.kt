package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.events.SystemEventNames
import ai.nuxie.sdk.identity.UserTransitionCoordinator
import android.content.Context
import android.util.Log

/**
 * Entry point for the greenfield Nuxie Android SDK.
 *
 * Setup constructs the internal composition root (event log, lifecycle
 * capture). The trigger, Features, presentation, and commerce surfaces arrive
 * in subsequent PRs on the locked contract.
 */
object Nuxie {
    private const val LOG_TAG = "Nuxie"

    @Volatile
    private var setupState: SetupState? = null

    val isSetup: Boolean
        get() = setupState != null

    val version: String
        get() = SdkVersion.VALUE

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

        val core = NuxieCore(
            context = context,
            apiKey = configuration.apiKey,
            environment = configuration.environment,
            logLevel = configuration.logLevel,
            beforeSend = configuration.beforeSend,
        )
        setupState = SetupState(logLevel = configuration.logLevel, core = core)
        core.start()
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

        val identity = core.identity
        val oldDistinctId = identity.distinctId()
        val wasIdentified = identity.isIdentified
        val hasDifferentDistinctId = distinctId != oldDistinctId

        identity.setDistinctId(distinctId)
        val currentDistinctId = identity.distinctId()

        // Serialized, uncancellable transition across per-user state
        // (anonymous-event migration included). A rapid second identify() or
        // reset() queues behind this one instead of cancelling it mid-fan-out.
        if (hasDifferentDistinctId) {
            core.userTransitions.enqueue(
                UserTransitionCoordinator.Transition(
                    kind = UserTransitionCoordinator.Kind.IDENTIFY,
                    from = oldDistinctId,
                    to = currentDistinctId,
                    migrateEvents = !wasIdentified,
                ),
            )
            // Rotating the session on every same-id identify would fragment
            // session analytics; rotate only when the user actually changed.
            core.sessions.startSession()
        }

        val hasUserProperties = userProperties != null || userPropertiesSetOnce != null
        if (hasDifferentDistinctId || hasUserProperties) {
            userProperties?.let { identity.setUserProperties(it) }
            userPropertiesSetOnce?.let { identity.setOnceUserProperties(it) }

            val properties = linkedMapOf<String, Any?>("distinct_id" to currentDistinctId)
            if (!wasIdentified && hasDifferentDistinctId) {
                properties["\$anon_distinct_id"] = oldDistinctId
            }
            userProperties?.let { properties["\$set"] = it }
            userPropertiesSetOnce?.let { properties["\$set_once"] = it }
            core.eventLog.capture(SystemEventNames.IDENTIFY, properties)
        }
    }

    /** End the identified session and return to a (new or kept) anonymous id. */
    fun reset(keepAnonymousId: Boolean = false) {
        val core = core ?: return
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
    }

    val distinctId: String
        get() = core?.identity?.distinctId().orEmpty()

    val anonymousId: String
        get() = core?.identity?.anonymousId().orEmpty()

    val isIdentified: Boolean
        get() = core?.identity?.isIdentified ?: false

    internal val core: NuxieCore?
        get() = setupState?.core

    /** Testing seam: tear down the singleton between tests. Not public API. */
    internal fun resetForTesting() {
        setupState = null
    }

    private class SetupState(
        val logLevel: LogLevel,
        val core: NuxieCore,
    )
}
