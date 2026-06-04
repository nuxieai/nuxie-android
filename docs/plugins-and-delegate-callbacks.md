# Plugins and Delegate Callbacks

This page documents extension points for integrating app-specific behavior.

## Plugin Contract

Source: `nuxie-core/src/main/kotlin/io/nuxie/sdk/plugins/NuxiePlugin.kt`.

```kotlin
interface NuxiePlugin {
  val pluginId: String

  fun install(host: NuxiePluginHost)
  fun uninstall()
  fun start()
  fun stop()

  fun onAppBecameActive() {}
  fun onAppDidEnterBackground() {}
  fun onAppWillEnterForeground() {}
}
```

`NuxiePluginHost` gives plugins access to:

- `trigger(event, properties, userProperties, userPropertiesSetOnce)`
- `getDistinctId()`
- `getAnonymousId()`
- `isIdentified()`

## Plugin Installation Modes

### Configuration-time

```kotlin
val config = NuxieConfiguration("YOUR_API_KEY").apply {
  addPlugin(MyPlugin())
}
NuxieSDK.shared().setup(context, config)
```

### Runtime

```kotlin
val sdk = NuxieSDK.shared()
sdk.installPlugin(MyPlugin())
sdk.startPlugin("my-plugin")
```

Runtime install/uninstall can throw `PluginError`:

- `PluginError.PluginNotFound`
- `PluginError.PluginAlreadyInstalled`
- `PluginError.PluginInstallationFailed`
- `PluginError.PluginUninstallationFailed`

## Lifecycle Event Order

Plugin lifecycle hooks are called from Android app lifecycle transitions:

- `onAppWillEnterForeground()` when app enters foreground
- `onAppBecameActive()` when first activity is resumed
- `onAppDidEnterBackground()` when app moves to background

## Example Plugin

```kotlin
class LifecycleTelemetryPlugin : NuxiePlugin {
  override val pluginId: String = "lifecycle-telemetry"
  private var host: NuxiePluginHost? = null

  override fun install(host: NuxiePluginHost) {
    this.host = host
  }

  override fun uninstall() {
    host = null
  }

  override fun start() {
    host?.trigger("$plugin_started", properties = mapOf("identified" to host?.isIdentified()))
  }

  override fun stop() = Unit

  override fun onAppBecameActive() {
    host?.trigger("$app_active")
  }
}
```

## Delegate Callbacks

Source: `nuxie-android/src/main/kotlin/io/nuxie/sdk/NuxieDelegate.kt`.

Set `NuxieSDK.shared().delegate` to receive runtime callbacks from journey/flow actions.

```kotlin
NuxieSDK.shared().delegate = object : NuxieDelegate {
  override fun flowDelegateCalled(message: String, payload: Any?, journeyId: String, campaignId: String?) {}

  override fun flowPurchaseRequested(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    productId: String,
    placementIndex: Any?,
  ) {}

  override fun flowRestoreRequested(journeyId: String, campaignId: String?, screenId: String?) {}

  override fun flowOpenLinkRequested(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    url: String,
    target: String?,
  ) {}

  override fun flowDismissed(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    reason: String,
    error: String?,
  ) {}

  override fun flowBackRequested(journeyId: String, campaignId: String?, screenId: String?, steps: Int) {}
}
```

### Dismiss Reason Values

`flowDismissed(..., reason, error)` currently emits:

- `user_dismissed`
- `purchase_completed`
- `timeout`
- `error` (`error` string populated when available)

## Purchase Delegate and Flow Purchase Actions

Flow actions `purchase` / `restore` are routed through `NuxiePurchaseDelegate`:

```kotlin
interface NuxiePurchaseDelegate {
  suspend fun purchase(productId: String): PurchaseResult
  suspend fun purchaseOutcome(productId: String): PurchaseOutcome
  suspend fun restore(): RestoreResult
}
```

Attach with `NuxieConfiguration.purchaseDelegate`.

When `purchaseOutcome` returns a successful `PurchaseOutcome` with `playStorePurchase`,
Nuxie syncs the Google Play purchase token with the backend before sending
`purchase_confirmed` back to the flow runtime. This lets the backend verify the token,
acknowledge or consume the purchase, and return updated feature access.

The Android SDK also starts a Play Billing observer by default. It listens for purchase
updates and queries owned purchases on startup, then syncs only completed purchases.
Pending purchases are ignored until Google Play reports them as purchased. Set
`enablePlayStorePurchaseSync = false` if the host app needs to own the BillingClient
connection exclusively.

When purchase delegate is not configured, flow runtime sends purchase/restore error messages back to flow UI.
