package ai.nuxie.sdk.logging

import ai.nuxie.sdk.LogLevel

/** Process-wide Kotlin entry point. Its correlation key survives SDK setup/shutdown. */
internal object NuxieLog {
    private val logger = NuxieLogger()

    fun configure(level: LogLevel, redactSensitiveData: Boolean = true) =
        logger.configure(NuxieLogger.Policy(level, redactSensitiveData))

    fun sensitive(name: String, value: Any?) = NuxieLogger.Field.sensitive(name, value)
    fun status(name: String, value: Number) = NuxieLogger.Field.status(name, value)

    fun d(tag: String, message: String, error: Throwable? = null, vararg fields: NuxieLogger.Field) =
        logger.log(LogLevel.DEBUG, tag, message, error, *fields)
    fun i(tag: String, message: String, error: Throwable? = null, vararg fields: NuxieLogger.Field) =
        logger.log(LogLevel.INFO, tag, message, error, *fields)
    fun w(tag: String, message: String, error: Throwable? = null, vararg fields: NuxieLogger.Field) =
        logger.log(LogLevel.WARN, tag, message, error, *fields)
    fun e(tag: String, message: String, error: Throwable? = null, vararg fields: NuxieLogger.Field) =
        logger.log(LogLevel.ERROR, tag, message, error, *fields)
}
