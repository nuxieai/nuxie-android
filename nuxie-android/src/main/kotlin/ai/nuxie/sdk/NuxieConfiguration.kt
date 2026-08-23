package ai.nuxie.sdk

/** Configuration captured when [Nuxie.setup] initializes the SDK. */
class NuxieConfiguration(val apiKey: String) {
    var environment: NuxieEnvironment = NuxieEnvironment.PRODUCTION
    var logLevel: LogLevel = LogLevel.WARN

    /**
     * Optionally transforms an event, or returns `null` to drop it.
     *
     * This hook is configuration-only in the current SDK slice and is not
     * invoked until the capture pipeline is introduced.
     */
    var beforeSend: ((NuxieEvent) -> NuxieEvent?)? = null
}

enum class NuxieEnvironment {
    PRODUCTION,
    DEVELOPMENT,
}

enum class LogLevel {
    NONE,
    ERROR,
    WARN,
    INFO,
    DEBUG,
}
