# Nuxie Android SDK

The Nuxie Android SDK is the device half of Nuxie's new-user-experience
engine. Apps trigger moments; signed Experiences decide what happens.

## Installation

In `settings.gradle.kts`, enable the standard Android and Maven Central repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Add the SDK to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ai.nuxie:nuxie-android:1.0.0")
}
```

Maintainers can use the [publication guide](docs/publishing.md) to prepare and
publish subsequent releases.

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

Bazel and Gradle default to the local runtime in `runtime/prebuilt`, verifying
its header and both ABI libraries against `runtime/local-artifact.json`.
First build the Android distribution in a `nuxie-runtime` checkout and stage it:

```bash
scripts/stage-runtime.sh /absolute/path/to/nuxie-runtime
```

Then build and test normally:

```bash
scripts/bazel/sdk.sh build
scripts/bazel/sdk.sh test
scripts/bazel/sdk.sh example
./gradlew :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint :example-app:assembleDebug
```

The staging script verifies the build's source revision and pinned NDK.
Unset or `NUXIE_RUNTIME_USE_LOCAL=1` uses this staged runtime; missing or invalid
staging stops the build without downloading. `NUXIE_RUNTIME_USE_LOCAL=0` restores
the pinned release in `runtime/artifact.json` and is required for release publishing.
Restage after using the pinned release to resume local runtime development.

See the [Bazel build guide](docs/bazel-build.md) for Maven preparation, direct JVM
tests, instrumentation, and the retained API/lint/release qualification drivers.

## Integration example

The [example app guide](example-app/README.md) covers setup, identity, authored
triggers, Feature access, restore, a local analytics sink and the development
Test Store using the selected runtime artifact.

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
RGBA8 premultiplied-sRGB files plus `manifest.json`. Bazel compiles its Kotlin
and host JNI adapter directly; harness sources are not shipped in the AAR.

The input directory must contain:

```text
release/
├── release-descriptor.json
├── experience.nux
└── assets/
    └── ... files at their descriptor keys, relative to release/
```

Build the host runtime from a `nuxie-runtime` checkout:

```bash
python3 tools/bazel/runtime.py build -p nux-capi --features android-authored-wgsl,android-vulkan,scripting
export NUXIE_HOST_CAPI_LIB=/absolute/path/to/nuxie-runtime/target/debug/libnux_capi.dylib
```

The host adapter requires the same Vulkan and scripting capabilities as the
shipped Android runtime. It probes scripting support when loading and fails
with the required build command instead of rendering a degraded blank frame.

On Linux, use `libnux_capi.so`; a conformant Vulkan ICD such as lavapipe may
be selected with `VK_ICD_FILENAMES` when the machine has more than one ICD.

On Apple Silicon macOS with Homebrew MoltenVK, use the runtime's explicit
loader path. This is reliable through JVM processes; setting only
`DYLD_LIBRARY_PATH` is not.

```bash
export NUXIE_MOLTENVK_LIBRARY=/opt/homebrew/lib/libMoltenVK.dylib
export VK_ICD_FILENAMES=/opt/homebrew/share/vulkan/icd.d/MoltenVK_icd.json
```

Run the harness:

```bash
scripts/bazel/sdk.sh host-render --input /absolute/path/to/release \
  --output /absolute/path/to/frames --frames 2 --size 390x844 --step-ms 16
```

`--frames` defaults to `1`, `--step-ms` to `16`, and `--size` to the first
screen's authored width and height in the release descriptor. Omitting
`--size` is an error when that extent is absent. Each frame is written as
`frame-<index>.rgba`; `manifest.json` records its SHA-256 and dimensions plus
the string returned by `NuxieRuntime.info()`.

Verify the live harness separately from the default unit test suite:

```bash
scripts/bazel/sdk.sh host-render-smoke
```

This dedicated target runs only the four host render smoke tests in a fresh
JVM against the selected scripting-enabled runtime. The frontend requires
`NUXIE_HOST_CAPI_LIB` and checks its scripting exports before building. Ordinary
SDK tests exclude these live tests and clear host runtime selection.

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
allowlist. `NuxieActivity.LinkOpened` is part of this wrapper contract and forwards
`$link_opened` with the URL, actual `destination` (`in_app` or `external`), authored target, screen/source attribution and Journey leg.

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
A locale change invalidates the old profile request and fetches the new
locale's profile at once; events sent meanwhile wait for it, then reach
Journeys against it. `null` follows the device locale. Purchase settings
apply to future purchase/restore work, and a null delegate restores native billing.

Android retains `featureCacheTTL` on configuration, in milliseconds (five minutes
by default). iOS exposes its equivalent through testing overrides in seconds;
bindings must convert units. `testingOverrides.apiEndpoint` is available for
attended test hosts on both platforms. Production integrations should use the
configured environment.

The legacy SDK is archived on branch `legacy/webview-sdk`.

## Development Test Store

Use Test Store to exercise purchase and restore outcomes, and Feature access,
without charging a customer or contacting Google Play:

```kotlin
val configuration = NuxieConfiguration("pk_test_YOUR_KEY").apply {
    testStoreEnabled = BuildConfig.DEBUG
}
Nuxie.setup(applicationContext, configuration)
```

`BuildConfig` above belongs to your app. Test Store is off by default. Enabling it
requires a `pk_test_` key and a debuggable host, on any service, like
RevenueCat's Test Store; setup rejects an enabled Test Store with a live key or in
a non-debuggable application. Configuration is captured at setup; await shutdown
before changing modes.

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
no Play transaction. Purchased asks Nuxie to grant the Product's Features to the
customer, so access unlocks at once and stays on the server like a real purchase;
if Nuxie cannot grant it, the purchase fails. The grant records no transaction and
never counts as revenue, and it has no end: test again with a new customer. The
local purchase history that Restored returns is in memory and scoped to each
customer.

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

### Magic Move step timing

In a debuggable app, each Magic Move step between two Screens of a page logs
when it starts and when it lands, under the `NuxieMagicMove` tag. The start and
land lines are `DEBUG`; one `VERBOSE` line follows each player step in between.
A release (non-debuggable) app logs nothing on this path at any `logLevel`.

```bash
adb logcat -v monotonic -s NuxieMagicMove:V
```

```text
step start id=<n> from=<screen> to=<screen> back=<0|1> frameNanos=<ns>
frame id=<n> frameNanos=<ns> elapsedMs=<ms>
step land id=<n> frameNanos=<ns> lengthMs=<ms>
```

- `id` counts Magic Move steps in the process and pairs a step's lines.
- `frameNanos` is the Choreographer frame time the page's player step used. It
  is on the `System.nanoTime()` clock, the monotonic clock that
  `logcat -v monotonic` prints in seconds, so the lines line up with a screen
  recording.
- `step start` is the frame whose player step began the move. `step land` is
  the frame whose player step reported the step's last completion event. When
  the step's watchdog ends the wait first, it is the last frame that stepped
  the move. `lengthMs` is the land frame time minus the start frame time.
- Each `frame` line is one player step from the start until the step lands.
  `elapsedMs` is the time that step advanced the player by: `0.0` on the start
  frame, which applies the move. Milliseconds are rounded to the microsecond.
- `back` is `1` for a Back step and `0` otherwise.
- A step that ends without landing (its page closes, or the presentation
  fails) logs no `step land` line and no further `frame` lines. A step that
  plays no move in the scene logs nothing: reduced motion, a cut, a page that
  changes under a sheet, or a page that isn't drawing. Other transition styles
  are not logged.
- `logLevel` must be `DEBUG` for the start and land lines and `VERBOSE` for
  the frame lines. `from` and `to` are Screen ids in sensitive fields, so with
  `redactSensitiveData = true` they read `<redacted hmac-sha256:...>`. Every
  other field is a number and stays readable.

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

The same test also supports an isolated local invoice fixture. Its `/config`
sets `localInvoiceFixture: true` and supplies the local AppPlatform's `pk_live_`
key, because automatic invoice enforcement operates in the live namespace.
The controller must use an isolated test database and local Worker bindings;
SDK traffic remains restricted to the forwarded loopback controller. `/policy`
changes fixture invoice debt and invokes the real automatic reconciler instead
of setting policy directly. `POST /settle-one` pays one of two overdue invoices;
`POST /settle-partial` leaves a positive balance on the second. Neither may restore
delivery. Clearing all overdue debt must restore the same release and native
presentation. These local invoice records do not represent provider payments.

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

Links use the shared `events/link-open-states.json` state table. A settled, owned
Experience opens web links in-app for an omitted target, `_self`, `_parent`, `_top`,
or `in_app`; `_blank` and `external` use the browser. Closing, closed, and screenless
Experiences fall back to the browser or system. A started app with no resumed Activity
opens externally from the application context; only a resumed Activity can host an in-app link. With no started Activity nothing
opens or records. Non-web schemes use an available system handler. Broken Journey
link steps advance without opening, recording, or dismissing. `$link_opened` and
public `LinkOpened` include the actual `destination` (`in_app` or `external`) and the
original optional `target`. Step records use the step identity before completion.
A link already in flight still opens externally after identity change or current-customer profile clear, but its revoked run records no event.

### Reuse native products in wrapper CI

Set `NUXIE_SDK_PREPARE_PRODUCTS=1` on a policy-v2 Buildkite build to export the
existing native artifact contract after the SDK checks pass. Download the
`output/pipeline/native-products/android-products.tar.gz` artifact for wrapper builds.
The archive preserves the original `sdk-artifacts.json`, file hashes and symlinks;
wrapper native pins must match its source revision.
The archive uses the wrappers’ existing source-addressed Maven coordinate.

## Build pool

With the homelab client configured, the existing SDK build/test frontends and
readiness gate submit to the shared pool automatically. Portable work can use
Linux, WSL or macOS; Apple work selects a Mac, and engine checks require the
pinned installed editor. Missing tools cause a refusal before execution.
Running builds are not restarted when the client is updated.

Products return to the checkout's declared artifact paths. Logs, test diagnostics
and request timings are retained under `.nuxie/homelab/` and
`.nuxie/homelab-timings/`. A failed remote test remains a failure and does not
fall back to local compilation. See the [SDK pool operations guide](https://github.com/nuxieai/nuxie-dev/blob/main/docs/operations/sdk-bazel-build.md#shared-pool-execution-for-every-sdk).

Gradle `--offline`, Maven-local publication and cleanup explicitly address the
caller's own cache/output directories and retain local execution. Normal builds
and tests use automatic pool placement.
