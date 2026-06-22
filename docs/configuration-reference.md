# Configuration Reference

`NuxieConfiguration` defines SDK behavior at setup time.

Source: `nuxie-core/src/main/kotlin/io/nuxie/sdk/config/NuxieConfiguration.kt`.

## Required

```kotlin
val config = NuxieConfiguration(apiKey = "YOUR_API_KEY")
```

## Endpoint and Environment

| Field | Default | Notes |
| --- | --- | --- |
| `apiKey` | required | API key for backend auth. |
| `environment` | `Environment.PRODUCTION` | Production/staging/development/custom endpoint family. |
| `apiEndpoint` | `https://i.nuxie.ai` | Use `setApiEndpoint(...)` to override directly. |

`Environment` defaults:

- `PRODUCTION`: `https://i.nuxie.ai`
- `STAGING`: `https://staging-i.nuxie.ai`
- `DEVELOPMENT`: `https://dev-i.nuxie.ai`
- `CUSTOM`: `https://i.nuxie.ai` (override with `setApiEndpoint(...)`)

## Logging

| Field | Default |
| --- | --- |
| `logLevel` | `LogLevel.WARNING` |
| `enableConsoleLogging` | `true` |
| `enableFileLogging` | `false` |
| `redactSensitiveData` | `true` |

`LogLevel` values: `VERBOSE`, `DEBUG`, `INFO`, `WARNING`, `ERROR`, `NONE`.

## Networking and Queueing

| Field | Default |
| --- | --- |
| `requestTimeoutSeconds` | `30` |
| `retryCount` | `3` |
| `retryDelaySeconds` | `2` |
| `syncIntervalSeconds` | `3600` |
| `enableCompression` | `true` |
| `eventBatchSize` | `50` |
| `flushAt` | `20` |
| `flushIntervalSeconds` | `30` |
| `maxQueueSize` | `1000` |

## Storage and Cache

| Field | Default |
| --- | --- |
| `maxCacheSizeBytes` | `100 MB` |
| `cacheExpirationSeconds` | `7 days` |
| `enableEncryption` | `true` |
| `customStoragePath` | `null` |
| `featureCacheTtlSeconds` | `5 minutes` |
| `maxFlowCacheSizeBytes` | `500 MB` |
| `flowCacheExpirationSeconds` | `7 days` |
| `maxConcurrentFlowDownloads` | `4` |
| `flowDownloadTimeoutSeconds` | `30` |
| `flowCacheDirectory` | `null` |

## Runtime Behavior

| Field | Default |
| --- | --- |
| `defaultPaywallTimeoutSeconds` | `10` |
| `respectDoNotTrack` | `true` |
| `eventLinkingPolicy` | `MIGRATE_ON_IDENTIFY` |
| `localeIdentifier` | `null` |
| `isDebugMode` | `false` |

Embedded `FlowView` hosts use `ComponentActivity` and provide a stable embedded `viewId`. See `getFlowView(...)` in the API reference for the host-side contract.

`eventLinkingPolicy`:

- `MIGRATE_ON_IDENTIFY`: migrate anonymous events to identified user on first identify
- `KEEP_SEPARATE`: do not migrate

## Plugins and Purchases

| Field | Default | Notes |
| --- | --- | --- |
| `enablePlugins` | `true` | If `false`, config-provided plugins are not auto-installed. |
| `plugins` | empty list | Add with `addPlugin(plugin)` / remove with `removePlugin(pluginId)`. |
| `purchaseDelegate` | `null` | Required for flow purchase/restore actions to succeed. |
| `enablePlayStorePurchaseSync` | `true` | Starts the Play Billing observer and syncs completed purchase tokens. |
| `consumablePlayStoreProductIds` | empty set | Product IDs that should be consumed by the backend after validation. Prefer `addConsumablePlayStoreProduct(productId)`. |

## Event Hooks

| Field | Type | Notes |
| --- | --- | --- |
| `propertiesSanitizer` | `(Map<String, Any?>) -> Map<String, Any?>` | Last-mile event properties sanitizer before enqueue. |
| `beforeSend` | `(NuxieEvent) -> NuxieEvent?` | Mutate or drop events (`null` drops). |

## Example

```kotlin
val config = NuxieConfiguration("YOUR_API_KEY").apply {
  environment = Environment.STAGING
  logLevel = LogLevel.DEBUG
  flushAt = 10
  flushIntervalSeconds = 15
  eventLinkingPolicy = EventLinkingPolicy.MIGRATE_ON_IDENTIFY
  localeIdentifier = "en_US"
  purchaseDelegate = myPurchaseDelegate
  addConsumablePlayStoreProduct("coins_100")
  addPlugin(myPlugin)
}
```
