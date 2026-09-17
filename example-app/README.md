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
provider builds omit the one-SDK anonymous reset. RevenueCat exposes **Sign out**
through the application-owned transition below. Superwall does not expose sign-out
because supported completion is unavailable. Starting a new customer after completed
logout requires a deliberate new-session flow, which remains unimplemented. Do not
copy a one-SDK reset into a two-SDK production session.

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
must remain unchanged while stopped. Recreating the Activity must preserve both
a switched customer and an anonymous reset, rather than replaying the original
launch customer. The test shuts down the SDK and closes its
local server; external provider selections skip it because they require their own
credentials. This qualifies Activity observation with controlled HTTP responses,
not a production backend, provider logout, process death or store checkout.

Launch configuration and customer extras initialize the app-scoped SDK only when
it is not already set up. Activity recreation attaches new observers without
reapplying those initial values. Process restart checks the durable operation and
logout journals before accepting launch extras. A completed session transition
and deliberate new-session initialization remain separate open work.

Provider selections install an application-owned `ProviderOperations` delegate.
Purchase and restore callbacks continue in that owner if the original caller is
cancelled. `closeAndAwait()` permanently closes admission and drains admitted
calls; concurrent drain callers may wait independently. This is the operation
ownership portion of [UNIV-3197](https://universe.basis.dev/issue/UNIV-3197). It
does not perform logout or prove that a provider-reported pending payment has
completed. Ordinary SDK shutdown alone does not drain this
external owner. The sign-out control uses the durable transition below. Interrupted-operation
reconciliation remains unimplemented; pending or ambiguous store outcomes keep
admission closed.

Selected-provider unit source sets exercise the real RevenueCat and Superwall
adapters with held provider purchase/restore callbacks. They cancel the original
waiter, verify drain remains pending and new operations are rejected, then release
the provider completion and verify the adapter's completion identity check runs.
Run these with `-PnuxieExamplePurchaseProvider=revenuecat` or `superwall` and
`:example-app:testDebugUnitTest :example-app:testReleaseUnitTest`. These are pinned
provider API tests, not real-store qualification.

`SessionLogout` owns one in-process logout transition. It closes provider
admission, persists intent, drains admitted callbacks, resets Nuxie, awaits SDK
teardown, then awaits supported provider logout. Each completed stage is
persisted; an in-process retry retains completed side effects even if their
following write failed. UI waiter cancellation does not cancel this work. A
provider-reported pending payment blocks identity mutation and requires recovery.
The RevenueCat selection prepares a session-bound logout operation using public
`awaitLogOut` completion. It requires the expected non-anonymous customer at
admission and confirms an anonymous identity at completion. A failed callback can
arrive after identity reset: RevenueCat 10.21.1
[resets identity before refreshing customer information](https://github.com/RevenueCat/purchases-android/blob/10.21.1/purchases/src/main/kotlin/com/revenuecat/purchases/PurchasesOrchestrator.kt),
and [rejects another logout when already anonymous](https://github.com/RevenueCat/purchases-android/blob/10.21.1/purchases/src/main/kotlin/com/revenuecat/purchases/identity/IdentityManager.kt).
The original attempt still fails. In-process explicit retry retains the exact
anonymous ID observed after that callback and awaits `awaitCustomerInfo` with
`FETCH_CURRENT`, checking identity again at completion. Refresh failures remain
retryable without another logout; changed identities are rejected. The session
coordinator owns provider identity changes exclusively while this operation is
active. This is not recovery of an arbitrary already-anonymous session or a
previous process. Superwall and managed Play do not advertise this capability.

`LogoutJournal` stores synchronous-commit progress. Before starting either SDK
or identifying the launch customer, the example checks for a previous logout
record. Completed logout remains signed out; interrupted or invalid records keep
startup closed. The controller deliberately does not resume a previous process's
ambiguous external side effects. The RevenueCat example exposes **Sign out** while
active, including during a held
purchase/restore. **Retry sign out** is the only enabled operation after an in-process
failure. Both controls invoke the same application-owned transition; recreating the
Activity cancels its UI waiter without cancelling logout. Signing out, recovery-required,
and completed states disable every operation. Sign-in after completion and recovery
of a previous process remain unimplemented. `ProviderOperationJournal` also commits
an opaque operation
marker before dispatching each wrapped purchase or restore. A successful or
cancelled result retires that marker; pending payments, returned failures and
unexpected exceptions retain it. Failed results cannot distinguish a rejection
before checkout from an ambiguous external outcome. A failed journal write blocks
dispatch or clean drain. On restart, unfinished markers block provider setup and
re-identification even when no logout intent exists. Markers contain no customer
identifiers or purchase tokens. They detect unfinished work; they do not reconcile
store outcomes or authorize replaying checkout.

After building and installing the default example and its test APK, qualify
Activity recreation during a coordinated transition in a fresh instrumentation
process:

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class ai.nuxie.example.SessionLogoutLifecycleTest \
  -e sessionLifecycle true \
  ai.nuxie.example.test/androidx.test.runner.AndroidJUnitRunner
```

Run this opt-in class alone: its completed application owner intentionally stays
closed until process exit. It requires no pre-existing logout or operation
recovery record and restores both records in `finally`. A controlled delegate is
installed through the public SDK configuration, and public `restorePurchases()`
enters the real application owner. The test holds restore completion and cancels the
Activity-owned restore and logout waiters by recreating the Activity. It invokes
Restore, Sign out and Retry through the visible buttons. It checks the
original identity, one retained owner/transition, durable pending ownership,
rejected new calls and disabled controls. It then recreates during held provider
logout, after a controlled failure, and after explicit retry completes. SDK
teardown precedes provider logout; old launch extras never restart the SDK. The
replacement Activity displays closing, retry and signed-out state. Created
Activities are finished, held callbacks released, transition work awaited and SDK
shutdown awaited during cleanup.

Add `-e sessionScreenshots true` to save active, retry and completed screens as
`logout-{active,retry,complete}.png` under the target app external files directory.

API 36 ARM64 qualification passes 1/1. Disabling the session-state collector
makes the test fail on the missing closing status after recreation; restoring
production makes it pass. This exercises real Activity lifecycle, public Nuxie
entry points and durable journals with controlled loopback HTTP and a fake
provider. It does not exercise real RevenueCat/Superwall/Play callbacks, a logout
button, physical devices, process-death reconciliation or new-session admission.

Qualify the startup guard from a fresh process at every checkpoint:


```bash
for stage in REQUESTED DRAINED SDK_RESET SDK_RETIRED COMPLETE; do
  adb -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class ai.nuxie.example.LogoutStartupTest#seed \
    -e logoutPhase seed -e logoutStage "$stage" \
    ai.nuxie.example.test/androidx.test.runner.AndroidJUnitRunner
  adb -s "$ANDROID_SERIAL" shell am force-stop ai.nuxie.example
  adb -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class ai.nuxie.example.LogoutStartupTest#verify \
    -e logoutPhase verify -e logoutStage "$stage" \
    ai.nuxie.example.test/androidx.test.runner.AndroidJUnitRunner
done
```

Require `OK (1 test)` for each seed and verify run; instrumentation can return
exit zero for a failed test. The verify test requires a different PID, proves the
SDK stayed unconfigured and controls stayed disabled, and restores the prior
journal record in `finally`. If execution stops between seed and verify, run the
matching verify invocation to restore the saved record. These are seeded durable
checkpoint tests, not process-kill tests of an actual store transaction or a
proof that an interrupted provider operation can be reconciled.


To qualify process loss **during** a wrapped restore, use two terminals with the
same test device. Start the hold phase in the first:

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class ai.nuxie.example.ProviderOperationStartupTest#hold \
  -e providerPhase hold \
  ai.nuxie.example.test/androidx.test.runner.AndroidJUnitRunner
```

Wait for `providerJournal=ready` and status code `71` while the instrumentation
process is still running. Within its two-minute timeout, use the second terminal:

```bash
adb -s "$ANDROID_SERIAL" shell am force-stop ai.nuxie.example
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class ai.nuxie.example.ProviderOperationStartupTest#verify \
  -e providerPhase verify \
  ai.nuxie.example.test/androidx.test.runner.AndroidJUnitRunner
```

The killed hold phase reports a crashed process, never a passed test. Require
`OK (1 test)` from verify. It checks a different PID, the persisted operation
without logout intent, blocked SDK initialization and disabled controls, then
restores the original operation journal in `finally`. Run verify to restore that
backup if the sequence is interrupted. This executes the real application owner
with a controlled, suspended restore delegate. Actual RevenueCat/Superwall held
callbacks are covered by the selected-provider unit suites; external store
process-death reconciliation remains unqualified.
