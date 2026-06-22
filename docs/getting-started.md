# Getting Started

This guide walks through integrating the Android SDK into an app and validating core functionality quickly.

## Prerequisites

- Android Studio with Android SDK 34
- JDK 17+
- `minSdk` 21 or higher in your app

## Add the SDK

Inside this repository, depend on the Android module:

```kotlin
implementation(project(":nuxie-android"))
```

If you are consuming from another repo, include this SDK project/modules in your Gradle settings and then add a project dependency on `:nuxie-android`.

## Add Network Permission

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Initialize Once

Initialize the singleton once at app startup.

```kotlin
import android.app.Application
import io.nuxie.sdk.NuxieSDK
import io.nuxie.sdk.config.LogLevel
import io.nuxie.sdk.config.NuxieConfiguration

class App : Application() {
  override fun onCreate() {
    super.onCreate()

    val configuration = NuxieConfiguration(apiKey = "YOUR_API_KEY").apply {
      logLevel = LogLevel.DEBUG
      // environment = Environment.STAGING
      // setApiEndpoint("https://staging-i.nuxie.ai")
    }

    NuxieSDK.shared().setup(this, configuration)
  }
}
```

## Set a Delegate

Callbacks are delivered on the main thread.

```kotlin
NuxieSDK.shared().delegate = object : NuxieDelegate {
  override fun flowDelegateCalled(message: String, payload: Any?, journeyId: String, campaignId: String?) {
    // observe call_delegate actions
  }
}
```

## Identify and Trigger

```kotlin
val sdk = NuxieSDK.shared()

sdk.identify(
  distinctId = "user_123",
  userProperties = mapOf("plan" to "pro"),
)

sdk.trigger("paywall_trigger") { update ->
  // Handle TriggerUpdate stream
}
```

## Show a Flow

### SDK-managed activity

```kotlin
sdk.showFlow("flow_123")
```

### Custom hosted `FlowView`

```kotlin
val flowView = sdk.getFlowView(
  activity = this,
  flowId = "flow_123",
  viewId = R.id.nuxie_flow_slot,
)
container.addView(flowView)
```

## Compose Hosting Example

`getFlowView(...)` needs a `ComponentActivity` and a stable `viewId`. In Compose, resolve the host activity from context, keep the id in `rememberSaveable`, and host with `AndroidView`.

```kotlin
@Composable
fun NuxieFlowContainer(flowId: String) {
  val context = LocalContext.current
  val activity = context as? ComponentActivity ?: return
  val flowViewId = rememberSaveable { View.generateViewId() }
  var flowView by remember(flowId) { mutableStateOf<FlowView?>(null) }

  LaunchedEffect(activity, flowId, flowViewId) {
    flowView = NuxieSDK.shared().getFlowView(activity, flowId, flowViewId)
  }

  flowView?.let { view ->
    AndroidView(factory = { view })
  }
}
```

## Quick Validation Checklist

1. App starts without SDK configuration errors.
2. `identify(...)` changes `getDistinctId()`.
3. `trigger(...)` emits at least one `TriggerUpdate`.
4. `showFlow(...)` opens `NuxieFlowActivity` when a host activity is foregrounded.
5. Delegate callbacks fire when running flow actions.

## Example App

Use the included `example-app` module for a working reference implementation:

```sh
./gradlew :example-app:installDebug
./gradlew :example-app:testDebugUnitTest
```
