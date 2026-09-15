# RevenueCat purchase delegate example

This source example compiles against RevenueCat Android **10.21.1**. It is a
separate verification module with no publication plugin, not a dependency of the
Nuxie SDK or the base example app. Copy the delegate into a host that already
uses RevenueCat, or depend on this project locally while developing.

Configure RevenueCat first with your public provider key and the same customer
identity used for Nuxie. RevenueCat owns billing, acknowledgement/consumption and
its own entitlement state. Configure Nuxie before its first setup:

```kotlin
val configuration = NuxieConfiguration(nuxiePublicKey).apply {
  purchaseHandlingMode = PurchaseHandlingMode.APP_MANAGED
  purchaseDelegate = NuxieRevenueCatPurchaseDelegate(
    currentActivity = { resumedActivity },
  )
}
Nuxie.setup(application, configuration)
Nuxie.identify(customerId)
```

Import Nuxie types from `ai.nuxie.sdk` and purchase types from
`ai.nuxie.sdk.billing`; the example delegate is in
`ai.nuxie.example.revenuecat`. Maintain `resumedActivity` through the host's
Activity lifecycle; do not retain a destroyed Activity. Configure identity at
the application/session level, keep both SDKs synchronized on login/logout, and
prevent identity changes while checkout is active. Activity recreation must not
reconfigure RevenueCat or reset the Nuxie SDK.

Trigger an authored Journey through `Nuxie.trigger`. Its Purchase Product step
invokes the delegate with the resolved product. The adapter re-fetches provider
products and requires the exact Play product/base-plan/offer/token plus matching
subscription pricing phases. It forwards `isOfferPersonalized`. Missing,
ambiguous or changed terms fail so the host can refresh the Experience; they do
not fall back to a provider-selected offer. The reference APIs are
[PurchaseParams](https://github.com/RevenueCat/purchases-android/blob/10.21.1/purchases/src/main/kotlin/com/revenuecat/purchases/PurchaseParams.kt)
and [GoogleSubscriptionOption](https://github.com/RevenueCat/purchases-android/blob/10.21.1/purchases/src/main/kotlin/com/revenuecat/purchases/models/GoogleSubscriptionOption.kt).

For upgrades/downgrades, supply `configureReplacement` and explicitly set the
provider's old product and replacement mode. The example does not infer a
replacement policy. Do not present a replacement offer without supplying the
policy required by your product. Personalized-price forwarding is applied after
that callback so replacement configuration cannot accidentally remove it.

Successful checkout returns Purchased; cancellation and pending payments remain
distinct. Coroutine cancellation is rethrown. Restore returns Restored only
when RevenueCat reports active entitlements, following the iOS reference.
A checkout result is not a Nuxie Feature grant. Continue using RevenueCat access
until your Connector is configured, mapped, synchronized and explicitly cut over
to Nuxie Feature authority.

## Supported scope and outstanding qualification

The source supports ordinary Play subscriptions with exact offers, and legacy
one-time products without an explicit purchase option. RevenueCat 10.21.1 has no
explicit one-time purchase-option constructor in PurchaseParams; this adapter
rejects those Nuxie products and any one-time offer token rather than purchasing
different terms. The pinned provider's
[BillingWrapper](https://github.com/RevenueCat/purchases-android/blob/10.21.1/purchases/src/main/kotlin/com/revenuecat/purchases/google/BillingWrapper.kt)
builds one-time checkout without forwarding an offer token. Modern
one-time options, real Play checkout, pending-payment recovery across process
death, account switches during provider checkout, upgrade/downgrade orchestration,
and consumption/acknowledgement remain unqualified here. Test Store bypasses
delegates and therefore cannot qualify this adapter. This is not a completed
provider parity claim.

The module currently consumes the SDK project and the staged local runtime
candidate described in the root README. Published SDK consumption is pending.

```bash
NUXIE_RUNTIME_USE_LOCAL=1 ./gradlew :example-revenuecat:test :example-revenuecat:lint verifyProviderBoundary
```

SDK lint also runs these example checks. The boundary checks the SDK's resolved
debug and release runtime dependencies for RevenueCat/Superwall leakage; provider
dependencies remain confined to examples. Tests exercise selection and outcome
mapping using the pinned provider's real model classes, not a fake provider SDK.
