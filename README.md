# Nuxie Android SDK

The Nuxie Android SDK is the device half of Nuxie's new-user-experience
engine. Apps trigger moments; signed Experiences decide what happens.

## Status

This is a pre-release greenfield rebuild. The
[Nuxie iOS SDK](https://github.com/nuxieai/nuxie-ios) is the reference
implementation. The specification lives in the `nuxie-dev` repository at
`specs/android-sdk/overview.md`.

Maintainers can stage and qualify the Maven artifact using the
[publication guide](docs/publishing.md). Maven Central availability is still
pending; local publication does not establish a public release.

## Requirements

- Android minSdk 23
- Kotlin

SDK setup, event capture, Features, and commerce remain available on devices
without a compatible Vulkan renderer. Experiences require that renderer: the
SDK rejects presentation with `RUNTIME_UNAVAILABLE` before downloading artifacts
or opening an Experience Activity. Android API 23 uses this graceful degradation
contract; it does not render Experiences. Later API levels also require a
compatible device driver.

The API 23 device test
`PublishedTextInputDeviceTest#unavailableRendererRejectsPresentationWithoutDisablingTheSdk`
checks the real renderer probe, repeated signed-release rejection, durable event
capture, remote Feature checks, delegated restore, and shutdown. Its transport
and purchase delegate are test doubles; real Play behavior requires separate
store qualification. Run it on an API 23 device after building and installing
`:nuxie-android:assembleDebugAndroidTest`:

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class ai.nuxie.sdk.presentation.PublishedTextInputDeviceTest#unavailableRendererRejectsPresentationWithoutDisablingTheSdk \
  ai.nuxie.sdk.test/androidx.test.runner.AndroidJUnitRunner
```

## Runtime artifact

Gradle fetches and SHA-256 verifies the published Android runtime pinned
in `runtime/artifact.json`. It contains the native surface-presentation APIs used
by this SDK. Ordinary builds and tests use that artifact automatically:

```bash
./gradlew :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint :example-app:assembleDebug
```

For development of runtime changes, build the Android distribution in a
`nuxie-runtime` checkout and optionally stage its verified outputs:

```bash
scripts/stage-runtime.sh /absolute/path/to/nuxie-runtime
NUXIE_RUNTIME_USE_LOCAL=1 ./gradlew :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint :example-app:assembleDebug
```

The staging script verifies the build's source revision and pinned NDK.
Without `NUXIE_RUNTIME_USE_LOCAL=1`, Gradle restores the public pinned artifact;
restage your runtime build before resuming local runtime development if needed.

## Integration example

The [example app guide](example-app/README.md) covers setup, identity, authored
triggers, Feature access, restore, a local analytics sink and the development
Test Store using the pinned runtime artifact.

The separate [RevenueCat adapter example](example-revenuecat/README.md) compiles
against a pinned provider SDK and preserves exact subscription checkout terms.
Its supported product scope and remaining real-store qualification are explicit
in that guide. Provider dependencies are checked to remain outside the SDK.
The [Superwall adapter example](example-superwall/README.md) similarly compiles
against a pinned provider and validates exact selection, with an explicit
personalized-price limitation.

## Host JVM render harness

The host-only harness drives a release descriptor through the SDK's configured
Experience asset import, artboard/player ownership, fixed-timestep stepping,
and headless Android Vulkan renderer. It writes tightly packed, top-row-first
RGBA8 premultiplied-sRGB files plus `manifest.json`. Harness sources compile
only into the JVM test artifact and are not shipped in the AAR.

The input directory must contain:

```text
release/
├── release-descriptor.json
├── experience.riv
└── assets/
    └── ... files at their descriptor keys, relative to release/
```

Build the host runtime from a `nuxie-runtime` checkout:

```bash
cargo build -p nux-capi --features android-authored-wgsl,android-vulkan,scripting
export NUXIE_HOST_CAPI_LIB=/absolute/path/to/nuxie-runtime/target/debug/libnux_capi.dylib
```

The host adapter requires the same Vulkan and scripting capabilities as the
shipped Android runtime. It probes scripting support when loading and fails
with the required build command instead of rendering a degraded blank frame.

On Linux, use `libnux_capi.so`; a conformant Vulkan ICD such as lavapipe may
be selected with `VK_ICD_FILENAMES` when the machine has more than one ICD.

On Apple Silicon macOS with Homebrew MoltenVK, use the runtime's explicit
loader path. This is reliable through Gradle worker processes; setting only
`DYLD_LIBRARY_PATH` is not.

```bash
export NUXIE_MOLTENVK_LIBRARY=/opt/homebrew/lib/libMoltenVK.dylib
export VK_ICD_FILENAMES=/opt/homebrew/share/vulkan/icd.d/MoltenVK_icd.json
```

Run the harness:

```bash
./gradlew :nuxie-android:hostRenderHarness \
  --args='--input /absolute/path/to/release --output /absolute/path/to/frames --frames 2 --size 390x844 --step-ms 16'
```

`--frames` defaults to `1`, `--step-ms` to `16`, and `--size` to the first
screen's authored width and height in the release descriptor. Omitting
`--size` is an error when that extent is absent. Each frame is written as
`frame-<index>.rgba`; `manifest.json` records its SHA-256 and dimensions plus
the string returned by `NuxieRuntime.info()`.

Verify the live harness separately from the default unit test suite:

```bash
./gradlew :nuxie-android:hostRenderSmoke
```

This dedicated task runs only the four host render smoke tests in a fresh
worker JVM. The default `:nuxie-android:test` task excludes them and is
insensitive to host harness environment variables. When `NUXIE_HOST_CAPI_LIB`
is unset, the dedicated task skips them with the named assumption
`NUXIE_HOST_CAPI_LIB must name a host-built nux_capi library`.

## Wrapper contract

Journey reporting adds `JourneyStarted` and `JourneyCompleted` activities, with
flat names `journey_started` and `journey_completed`. They carry the
experience/version, journey, leg ID, and generation; completion also carries
the outcome. A leg completion does not imply that the entire journey ended.
Activity timestamps preserve occurrence time; `receivedAtMillis` records local
capture. Buffered answers stay in the ordinary report and are not copied into
the flat activity view. Host `beforeSend` privacy policy applies to both reports.

The [`nuxie-android/api/nuxie-android.api`](nuxie-android/api/nuxie-android.api)
`apiCheck` dump is the Android binding wrapper contract, sibling to the
`nuxie-ios` `api/public-api.txt`. Wrappers may bind only symbols listed in that
allowlist.

## Setup

```kotlin
val configuration = NuxieConfiguration("YOUR_API_KEY").apply {
    environment = NuxieEnvironment.PRODUCTION
}
Nuxie.setup(applicationContext, configuration)
check(Nuxie.isSetup)
```

Runtime updates through `setLocaleIdentifier`, `setPurchaseDelegate`, and
`setPurchaseHandlingMode` require a running SDK. They throw
`IllegalStateException` before setup and once shutdown starts; rejected changes
are not saved for the next setup. Set initial values on `NuxieConfiguration`.
Locale changes invalidate the old profile request and take effect at the next
profile synchronization; `null` follows the device locale. Purchase settings
apply to future purchase/restore work, and a null delegate restores native billing.

Android retains `featureCacheTTL` on configuration, in milliseconds (five minutes
by default). iOS exposes its equivalent through testing overrides in seconds;
bindings must convert units. `testingOverrides.apiEndpoint` is available for
attended test hosts on both platforms. Production integrations should use the
configured environment.

The legacy SDK is archived on branch `legacy/webview-sdk`.

## Development Test Store

Use Test Store to exercise purchase and restore outcomes without charging a
customer or contacting Google Play:

```kotlin
val configuration = NuxieConfiguration("pk_test_YOUR_KEY").apply {
    environment = NuxieEnvironment.DEVELOPMENT
    testStoreEnabled = BuildConfig.DEBUG
}
Nuxie.setup(applicationContext, configuration)
```

`BuildConfig` above belongs to your app. Test Store is off by default. Enabling it
requires the development environment, a `pk_test_` key, and a debuggable host;
setup rejects an enabled Test Store in a non-debuggable application. Configuration
is captured at setup; await shutdown before changing modes.

The AAR includes the fatal `NuxieTestStoreRelease` Android lint rule (qualified
with AGP 8.10.1 / lint 31.10.1). Run your app's `lintRelease` task in its release
gate. The rule accepts literal `false`, your module's `BuildConfig.DEBUG`, and
`&&` expressions containing that gate; it rejects unconditional `true`, runtime
flags alone, negated debug flags, and `||` expressions. Kotlin property writes
and Java setters are checked. Test sources are excluded. Disabling lint or
suppressing this rule removes the build-time protection; setup still enforces
the runtime admission checks above.

SDK maintainers can verify the shipped rule with
`./gradlew :nuxie-android:assembleRelease` followed by
`python3 scripts/test-test-store-lint.py` (Python 3.11+ and Android SDK required).
The fixture consumes the resulting AAR directly and checks that release lint
rejects `true` and accepts `BuildConfig.DEBUG` in Java and Kotlin. Detector tests also run through
`:nuxie-android:test` and `:nuxie-android:check`.

Published Experiences display their signed product previews with TEST labels.
Checkout offers Purchased, Pending, Cancelled, and Failed choices. Restore offers
Restored, No Purchases, and Failed; choosing Restored succeeds even with no local
purchase history. Test Store takes precedence over a purchase delegate and creates
no Play transaction. Its purchase history is in memory and scoped to each customer.

Initialize in your Application before Activities start so restore can find a
visible host, or initialize with an already visible Activity. Direct purchase
calls use the Activity supplied by the caller. These simulated choices do not
qualify real Play billing, acknowledgement, consumption, or store recovery.
Follow [Google Play qualification](docs/testing-google-play.md) for license
testers, test tracks, Billing Lab, and the required SDK/backend evidence.

## Shutdown and repeated setup

`Nuxie.shutdown()` closes public operation admission and starts teardown without
blocking the calling thread. Use `Nuxie.shutdownAndAwait()` from a coroutine when
you need completion, especially before calling `setup` again. Setup calls during
construction or teardown are ignored. Cancelling a coroutine waiting for shutdown
does not cancel teardown.

```kotlin
Nuxie.shutdownAndAwait()
Nuxie.setup(applicationContext, configuration)
```

Shutdown stops external intake and background producers, drains accepted operations,
and then closes consumers and storage. A pending Play checkout may be cancelled as
an SDK lifetime operation; this does not report a store cancellation or erase retained
purchase evidence. SDK callbacks may call `shutdown()` and return. They must await
completion independently after returning, since awaiting their own teardown would
prevent the operation being drained from finishing.

Compatibility note: `shutdown()` previously blocked until cleanup returned. Code
that immediately sets up another graph must now await `shutdownAndAwait()` first.

### Logging

Configure logging before `Nuxie.setup`:

```kotlin
val configuration = NuxieConfiguration("pk_live_...").apply {
    logLevel = LogLevel.WARN
    redactSensitiveData = true
}
Nuxie.setup(applicationContext, configuration)
```

`WARN` is the default. `NONE` disables SDK output; `ERROR`, `WARN`, `INFO`,
`DEBUG`, and `VERBOSE` include progressively more diagnostics in logcat.
Sensitive fields and exception details are redacted by default with summaries
that correlate repeated values within the process. Static diagnostic text and
explicit status/count fields remain readable. Kotlin and native bridge warnings
use the same policy; the SDK leaves the application's stderr alone.

Set `redactSensitiveData = false` only for attended diagnostics that need raw
values. Setup captures both controls; mutating the configuration or repeating
setup does not reconfigure an active SDK. Await shutdown before setting up with
a different policy.

SDK development enforces the logging boundary with `NuxieLoggingPolicy` lint.
It rejects direct platform/console output outside the sink and nonconstant tags,
message structure or field names; use sensitive fields for dynamic values.
The rule applies to the SDK module, not consumer applications. Native builds
reject common direct-output and stream-redirection APIs at compilation, with
positive/negative probes included in `runtimeBoundary`.

After building the release AAR, qualify its JNI logging callback under R8 on an
available Android emulator/device:

```bash
ANDROID_HOME=/path/to/android-sdk python3 scripts/test-minified-native-logging.py emulator-5558
```

This isolated, minified file-AAR consumer checks disabled/redacted/raw native
warnings and unchanged stderr. It is a logging qualification fixture, not an
integration example or a complete release/device qualification. Build logs,
R8 mapping and device evidence remain under `build/minified-native-logging-consumer`.

## Startup process-death qualification

Build and install the debug instrumentation APK on a dedicated test device, then
run the external driver (pass `--adb /path/to/adb` when adb is not on PATH):

```sh
./gradlew :nuxie-android:assembleDebugAndroidTest
adb -s emulator-5558 install -r nuxie-android/build/outputs/apk/androidTest/debug/nuxie-android-debug-androidTest.apk
python3 scripts/test-startup-process-death.py --device emulator-5558
```

The driver captures an ordinary public trigger while the
initial profile response is held, verifies the test package owns the recorded PID, and
kills that process without SDK shutdown. A second instrumentation process reads
the existing identity and SQLite event, admits its signed Journey, exercises the
native compiled control, and verifies durable response/event state after shutdown.
The original trigger ID must survive unchanged and appear exactly once.

To kill after a compiled control has committed a response on an active screen,
add `--boundary active-screen`. Recovery follows the shared iOS/Android contract:
interrupted active effects are abandoned rather than replayed. The driver checks
that the original response and event IDs survive, the preallocated completion ID
reports abandonment once, and repeated profile refresh does not reopen the screen.

Use `--boundary parked` to kill after a signed `wait_until` action has parked.
The restarted SDK must retain that run, wake deadline and artifact references,
ignore an unrelated event, then resume on the authored event without another
enrollment. The resumed native control must commit its response and event once.

Logs and the result are retained under `build/process-death/<run-id>/`. The
instrumentation method is opt-in and skips in an ordinary suite; its seed phase
must be terminated by this driver. These cases use controlled HTTP and real
native rendering/storage. They do not qualify death during store checkout,
OS task restoration, or a live server release.

To qualify delivery-cache withdrawal and recovery on a connected device:

```sh
ANDROID_SERIAL=emulator-5558 ./gradlew :nuxie-android:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.nuxie.sdk.NuxiePublicStartupDeviceTest#emptyDeliverySurvivesOfflineReconstructionAndRestoresTheSameRelease
```

This uses controlled HTTP responses with the real SDK, signed release, disk
cache and publication replay store. Failed refreshes retain the last received
delivery. A successful empty profile removes its arms and releases, remains empty
after SDK reconstruction while offline, and accepts the same signed release on
recovery without clearing replay protection. It does not kill the Android process,
render a screen, or exercise a backend billing policy.
To qualify native presentation after delivery restoration, run the same command
with `#restoredDeliveryPresentsAndCommitsACompiledControlEvent`. An empty profile
must launch no Experience and download no release artifacts. Restoring the signed
release then renders the Experience; a real touch must commit its compiled control
event and Journey response. This also uses controlled HTTP responses, not backend
invoice enforcement.

To exercise a real local backend policy, use the opt-in
`ai.nuxie.sdk.BackendDeliveryRecoveryDeviceTest` instrumentation class and pass
`-Pandroid.testInstrumentationRunnerArguments.nuxie_delivery_probe_url=http://127.0.0.1:<forwarded-port>/<run-token>`.
Forward that loopback port with `adb reverse`. The controller contract is:

- `GET /config`: test `apiKey`, `appPlatformId`, and allowed `artifactKeys`.
- `POST /policy` with `{ "suspended": boolean }`: publish the real test delivery policy.
- `POST /profile`: forward the SDK request and conditional headers to the backend.
- `GET /<artifact-key>`: serve hash-verified published release bytes and content type.

The test checks active → offline retained → online empty → restored delivery,
then triggers `mar_delivery_recovered` and requires native Experience presentation.
It restores delivery in cleanup and retains SDK telemetry locally. The controller
must also restore policy and remove its ADB forward if instrumentation fails.
Without the controller argument this operator test is skipped. Cleartext networking
is enabled only in the instrumentation test manifest; production configuration is
unchanged. This proves policy-to-device delivery, not invoice aging or Play purchases.

### Activity identity

`NuxieActivityInfo.customerId` identifies the customer who produced the durable
activity. Use it when attributing the activity in an external analytics system;
the SDK's current customer may already have changed by the time a listener runs.

For UI or gameplay tied to the current customer, check `info.isCurrentIdentity`
before acting. This property checks the original capture's identity session when
read. Switching A → B → A does not reactivate activity from the first A session,
and shutting down the SDK invalidates activities from that SDK session. Stale
activities are still delivered for analytics, with their original customer ID.
Activity identity metadata does not change the flat activity name or properties.
