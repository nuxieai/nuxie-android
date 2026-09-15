# Android integration example

This app demonstrates public SDK setup, optional identification, an authored
trigger, Feature access, restore, identity reset and activity forwarding to a
bounded local analytics sink. It does not show an Experience directly: the
configured Journey decides whether a trigger presents anything.

The current development branch requires the locally built runtime candidate
described in the [SDK README](../README.md#runtime-artifact). The published runtime
pin is not yet sufficient for this branch. This sample currently consumes the
SDK project; it is not proof of installation from a published SDK artifact.

## Run with the development Test Store

After building and staging the runtime candidate, run from the SDK repository:

```bash
NUXIE_RUNTIME_USE_LOCAL=1 ./gradlew :example-app:installDebug
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

This sample demonstrates Nuxie-managed purchase handling. Provider delegate
adapters, app-managed purchase completion, and exact-release Companion preview
remain tracked in [UNIV-2601](https://universe.basis.dev/issue/UNIV-2601). Test Store
is not a substitute for either provider integration or real-store qualification.

When implementing `NuxiePurchaseDelegate`, preserve the resolved
`storeProductId`, `basePlanId`, `purchaseOptionId`, `offerId` and
`isOfferPersonalized`. Do not let a provider select its default offer after Nuxie
has displayed a different one. For example, RevenueCat's
[PurchaseParams](https://github.com/RevenueCat/purchases-android/blob/main/purchases/src/main/kotlin/com/revenuecat/purchases/PurchaseParams.kt)
supports an explicit subscription option and personalized-price flag; passing
only its StoreProduct instead uses its default-option selection. The provider
examples still need compilation and orchestration qualification against pinned
provider versions.

Validate the app and its Test Store release gate with:

```bash
NUXIE_RUNTIME_USE_LOCAL=1 ./gradlew :example-app:assembleDebug :example-app:lintDebug :example-app:lintRelease
```
