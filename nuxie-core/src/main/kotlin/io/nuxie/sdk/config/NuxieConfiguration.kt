package io.nuxie.sdk.config

import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.plugins.NuxiePlugin
import io.nuxie.sdk.purchases.NuxiePurchaseDelegate

/**
 * Configuration object for initializing the Nuxie SDK.
 *
 * This intentionally mirrors the iOS `NuxieConfiguration` defaults and semantics.
 */
class NuxieConfiguration(
  val apiKey: String,
) {
  /**
   * API endpoint (defaults to production).
   *
   * On iOS, changing [environment] updates [apiEndpoint] only if the caller was
   * still using the previous environment's default endpoint. We mirror that.
   */
  var apiEndpoint: String = Environment.PRODUCTION.defaultEndpoint
    private set

  var environment: Environment = Environment.PRODUCTION
    set(value) {
      val old = field
      field = value
      if (apiEndpoint == old.defaultEndpoint) {
        apiEndpoint = value.defaultEndpoint
      }
    }

  fun setApiEndpoint(endpoint: String) {
    apiEndpoint = endpoint
  }

  // Logging
  var logLevel: LogLevel = LogLevel.WARNING
  var enableConsoleLogging: Boolean = true
  var enableFileLogging: Boolean = false
  var redactSensitiveData: Boolean = true

  // Network
  var requestTimeoutSeconds: Long = 30
  var retryCount: Int = 3
  var retryDelaySeconds: Long = 2
  var syncIntervalSeconds: Long = 3600
  var enableCompression: Boolean = true

  // Event batching
  var eventBatchSize: Int = 50
  var flushAt: Int = 20
  var flushIntervalSeconds: Long = 30
  var maxQueueSize: Int = 1000

  // Storage
  var maxCacheSizeBytes: Long = 100L * 1024L * 1024L
  var cacheExpirationSeconds: Long = 7L * 24L * 3600L
  var enableEncryption: Boolean = true
  var customStoragePath: String? = null

  // Feature caching
  var featureCacheTtlSeconds: Long = 5L * 60L

  // Behavior
  var defaultPaywallTimeoutSeconds: Long = 10
  var respectDoNotTrack: Boolean = true
  var eventLinkingPolicy: EventLinkingPolicy = EventLinkingPolicy.MIGRATE_ON_IDENTIFY

  // Locale
  var localeIdentifier: String? = null

  // Debug
  var isDebugMode: Boolean = false

  // Plugins
  var enablePlugins: Boolean = true
  var plugins: MutableList<NuxiePlugin> = mutableListOf()

  fun addPlugin(plugin: NuxiePlugin) {
    plugins.add(plugin)
  }

  fun removePlugin(pluginId: String) {
    plugins.removeAll { it.pluginId == pluginId }
  }

  // Purchases
  var purchaseDelegate: NuxiePurchaseDelegate? = null
  var enablePlayStorePurchaseSync: Boolean = true
  var consumablePlayStoreProductIds: MutableSet<String> = mutableSetOf()

  fun addConsumablePlayStoreProduct(productId: String) {
    val normalized = productId.trim()
    if (normalized.isNotEmpty()) {
      consumablePlayStoreProductIds.add(normalized)
    }
  }

  // Event system hooks
  var propertiesSanitizer: NuxiePropertiesSanitizer? = null
  var beforeSend: ((NuxieEvent) -> NuxieEvent?)? = null

  // Flow caching
  var maxFlowCacheSizeBytes: Long = 500L * 1024L * 1024L
  var flowCacheExpirationSeconds: Long = 7L * 24L * 3600L
  var maxConcurrentFlowDownloads: Int = 4
  var flowDownloadTimeoutSeconds: Long = 30
  var flowCacheDirectory: String? = null
}

fun interface NuxiePropertiesSanitizer {
  fun sanitize(properties: Map<String, Any?>): Map<String, Any?>
}
