# Android integration example

This app demonstrates public SDK setup, optional identification, an authored
trigger, reactive Feature access, restore, identity reset and activity forwarding to a
bounded local analytics sink. It does not show an Experience directly: the
configured Journey decides whether a trigger presents anything.

Gradle fetches the published native runtime pinned by the SDK automatically;
see the [runtime guide](../README.md#runtime-artifact). This sample consumes the
SDK project; installation from a published SDK artifact is a separate qualification.

## Run with the development Test Store

Run from the SDK repository:

```bash
./gradlew :example-app:installDebug
adb shell am start -S -n ai.nuxie.example/.MainActivity \
  --es nuxie_api_key pk_test_YOUR_KEY \
  --es nuxie_distinct_id example-customer \
  --es nuxie_trigger_event example_opened \
  --es nuxie_feature_id exports \
  --ez nuxie_test_store true
```

Use your project's public test key and Feature identifier. Author and publish a
Journey whose event condition matches `example_opened` before expecting the
trigger to show an Experience. Internal `$` events are emitted by the SDK;
application events should use ordinary names.

Test Store requires a debug build and is off unless explicitly requested. Its
flag is gated by the app's `BuildConfig.DEBUG`, including for release lint. It
simulates purchase/restore outcomes without Play charges; it does not qualify
real Google Play behavior. Omit the Test Store extra to use normal Nuxie-managed
Play handling. That requires the app/product/store setup described by your Play
integration; a local debug install alone does not provide it.

`-S` restarts only this example process before changing setup configuration.
The SDK captures configuration once; Activity recreation keeps the SDK alive.
The example cancels its screen's coroutine work and detaches its listener when
the Activity is destroyed. It does not shut down the app-scoped SDK there.
For an intentional SDK restart, await `Nuxie.shutdownAndAwait()` before setup.

An optional `nuxie_api_endpoint` string overrides the test endpoint. Leave it
unset for ordinary project integration. Never embed a private server credential
in this app.

## What the controls prove

- **Send event** submits a trigger; its return does not acknowledge durable
  capture or guarantee a matching Journey.
- **Current Feature access** observes `Nuxie.features.snapshot` while the Activity
  is started. It reads readiness and access from the same publication: Unknown
  waits for hydration, Reconciling shows pending server confirmation, and Ready
  displays denied, allowed, metered balance or unlimited access. The collector
  stops with the Activity and receives the latest snapshot when it starts again;
  identity reset does not retain a separate copy of the old customer's access.
  This display does not authorize an action or reserve metered quota.
- **Check access** calls suspend `Nuxie.hasFeature` from an Activity-owned
  coroutine and displays the access/balance decision. It does not consume usage.
  A cached access check is not an atomic quota reservation for a protected action.
- **Restore purchases** uses the configured purchase system and displays restored,
  no-purchases or failed outcomes.
- **Reset identity** switches to an anonymous identity for logout. The initial
  identified customer is supplied through `nuxie_distinct_id`.
- **Local analytics sink** receives committed `NuxieActivityInfo` values through
  `NuxieListener.onActivityEmitted`. The example retains at most 20 stable IDs and
  names in memory, and sends nothing externally. Preserve the SDK activity ID as
  the deduplication key when adapting this to your analytics provider.

App Actions are displayed by name. A real host should dispatch only the actions
it supports through `onAppActionRequested`, using the typed payload.

## Remaining integration examples

### Run with provider-owned billing

Choose one provider at build time. The default `nuxie` build contains neither
provider; `revenuecat` and `superwall` include only their selected adapter and
provider dependency. Both set `PurchaseHandlingMode.APP_MANAGED` and configure
the provider before Nuxie setup.

```bash
./gradlew -PnuxieExamplePurchaseProvider=revenuecat :example-app:installDebug
# Or replace revenuecat with superwall.
adb shell am start -S -n ai.nuxie.example/.MainActivity \
  --es nuxie_api_key pk_test_YOUR_NUXIE_KEY \
  --es nuxie_provider_key YOUR_PROVIDER_PUBLIC_ANDROID_KEY \
  --es nuxie_distinct_id example-customer \
  --es nuxie_trigger_event example_opened \
  --es nuxie_feature_id exports
```

The provider key and an explicit customer ID are required; missing values
disable the sample controls before provider setup. Do not enable Test Store in
a provider build: it would bypass the delegate, so the sample rejects that
combination. Configure the provider project and Google Play for this app's
package and products before expecting real checkout. Provider-specific product
limitations still apply. The sample checks Nuxie Features; only use those
decisions for provider-owned access after Connector cutover.

Provider configuration is retained across Activity recreation. The application
tracks its current resumed Activity with a weak reference, including Nuxie's
Experience Activity, for RevenueCat checkout. Both provider delegates require the expected customer to be visible in their
identity state before checkout or restore, and recheck identity before returning
successful completion. The
provider builds deliberately omit anonymous reset: coordinated logout and
in-flight identity changes are not implemented by this sample. Restart with a
different explicit customer to exercise a new session, after all checkout work
has finished. Do not copy a one-SDK reset into a two-SDK production session.

Build and check every selection separately, returning to the default build last:

```bash
./gradlew -PnuxieExamplePurchaseProvider=revenuecat :example-app:assembleDebug :example-app:lintDebug :example-app:lintRelease :example-app:testDebugUnitTest
./gradlew -PnuxieExamplePurchaseProvider=superwall :example-app:assembleDebug :example-app:lintDebug :example-app:lintRelease :example-app:testDebugUnitTest
./gradlew :example-app:assembleDebug :example-app:lintDebug :example-app:lintRelease :example-app:testDebugUnitTest
```

All selections use the same package and APK output path; installing another
selection replaces the sample app. Selection changes do not alter the SDK's
runtime dependency graph. Published-artifact consumption, real-store provider
qualification and coordinated logout remain outstanding.

### Remaining work

This sample demonstrates Nuxie-managed purchase handling. The separate
[RevenueCat delegate](../example-revenuecat/README.md) provides compiled adapter
source with explicit product limitations. A separate
[Superwall delegate](../example-superwall/README.md) supports validated selection
for non-personalized purchases. Provider session/logout and real-store
qualification, and exact-release Companion preview
remain tracked in [UNIV-2601](https://universe.basis.dev/issue/UNIV-2601). Test Store
is not a substitute for either provider integration or real-store qualification.

When implementing `NuxiePurchaseDelegate`, preserve the resolved
`storeProductId`, `basePlanId`, `purchaseOptionId`, `offerId` and
`isOfferPersonalized`. Do not let a provider select its default offer after Nuxie
has displayed a different one. For example, RevenueCat's
[PurchaseParams](https://github.com/RevenueCat/purchases-android/blob/main/purchases/src/main/kotlin/com/revenuecat/purchases/PurchaseParams.kt)
supports an explicit subscription option and personalized-price flag; passing
only its StoreProduct instead uses its default-option selection. The provider
examples compile against pinned versions and check selection/outcome mapping;
actual provider checkout and orchestration remain unqualified.

Validate the app and its Test Store release gate with:

```bash
./gradlew :example-app:assembleDebug :example-app:lintDebug :example-app:lintRelease
```

The default managed selection also has a device lifecycle test:

```bash
./gradlew :example-app:assembleDebug :example-app:assembleDebugAndroidTest
adb -s "$ANDROID_SERIAL" install -r example-app/build/outputs/apk/debug/example-app-debug.apk
adb -s "$ANDROID_SERIAL" install -r example-app/build/outputs/apk/androidTest/debug/example-app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w -e class ai.nuxie.example.FeatureObservationLifecycleTest ai.nuxie.example.test/androidx.test.runner.AndroidJUnitRunner
```

`FeatureObservationLifecycleTest` uses loopback HTTP and public SDK APIs with
unique test identities. It sends the real Activity Home, updates access while
stopped, and returns to the same Activity to verify the latest balance. A second
Home/return switches customer while profile requests fail and verifies that the
panel shows Unknown instead of the previous customer's balance. The hidden view
must remain unchanged while stopped. The test shuts down the SDK and closes its
local server; external provider selections skip it because they require their own
credentials. This qualifies Activity observation with controlled HTTP responses,
not a production backend, provider logout, process death or store checkout.
