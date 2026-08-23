package ai.nuxie.sdk

/** Configuration captured when [Nuxie.setup] initializes the SDK. */
class NuxieConfiguration(val apiKey: String) {
    var environment: NuxieEnvironment = NuxieEnvironment.PRODUCTION
    var logLevel: LogLevel = LogLevel.WARN
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
