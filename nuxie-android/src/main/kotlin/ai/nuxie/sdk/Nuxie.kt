package ai.nuxie.sdk

import android.content.Context
import android.util.Log

/**
 * Entry point for the greenfield Nuxie Android SDK.
 *
 * This scaffold intentionally provides setup state only. Runtime and product
 * subsystems arrive in subsequent work.
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
            if (existingState.configuration.logLevel >= LogLevel.WARN) {
                Log.w(LOG_TAG, "Nuxie is already set up; ignoring the repeated setup call.")
            }
            return
        }

        require(configuration.apiKey.isNotBlank()) { "apiKey must not be blank." }

        setupState = SetupState(
            applicationContext = context.applicationContext ?: context,
            configuration = ConfigurationSnapshot(
                apiKey = configuration.apiKey,
                environment = configuration.environment,
                logLevel = configuration.logLevel,
            ),
        )
    }

    private data class SetupState(
        val applicationContext: Context,
        val configuration: ConfigurationSnapshot,
    )

    private data class ConfigurationSnapshot(
        val apiKey: String,
        val environment: NuxieEnvironment,
        val logLevel: LogLevel,
    )
}
