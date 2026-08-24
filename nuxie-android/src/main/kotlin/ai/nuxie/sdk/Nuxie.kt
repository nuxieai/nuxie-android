package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
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
