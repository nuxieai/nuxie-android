package ai.nuxie.sdk

import ai.nuxie.sdk.commerce.NuxiePurchaseDelegate
import ai.nuxie.sdk.commerce.PurchaseHandlingMode

/** Configuration captured when [Nuxie.setup] initializes the SDK. */
class NuxieConfiguration(val apiKey: String) {
    var environment: NuxieEnvironment = NuxieEnvironment.PRODUCTION
    var logLevel: LogLevel = LogLevel.WARN

    /** TTL in milliseconds for real-time Feature check results (five minutes by default). */
    var featureCacheTTL: Long = 5L * 60L * 1000L

    /**
     * Locale for profile requests. When null, the SDK follows the device locale.
     * Change it after setup with [Nuxie.setLocaleIdentifier].
     */
    var localeIdentifier: String? = null

    /**
     * Optionally transforms an event, or returns `null` to drop it.
     *
     * This hook is configuration-only in the current SDK slice and is not
     * invoked until the capture pipeline is introduced.
     */
    var beforeSend: ((NuxieEvent) -> NuxieEvent?)? = null

    var purchaseHandlingMode: PurchaseHandlingMode = PurchaseHandlingMode.NUXIE_MANAGED

    var purchaseDelegate: NuxiePurchaseDelegate? = null
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
