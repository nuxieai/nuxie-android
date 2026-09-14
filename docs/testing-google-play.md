# Test Store and Google Play qualification

Use Nuxie Test Store for deterministic checkout choices during development. Use
Google Play license testing for actual BillingClient, purchase-token, backend,
acknowledgement, and restore qualification. Passing one does not qualify the other.

## Choose the test mode

| Mode | Configuration | What it proves |
| --- | --- | --- |
| Nuxie Test Store | `testStoreEnabled = BuildConfig.DEBUG`, development environment, public test key | Signed preview display, native choice UI, purchase/restore outcomes and Journey routing; no Play transaction |
| Google Play license test | `testStoreEnabled = false`, development environment, public test key, Nuxie-managed purchases | Real Play catalog and sheet, verified test purchase, durable recovery and store finalization |
| Production candidate | `testStoreEnabled = false`, production configuration | Must be qualified separately with release configuration and approved purchase procedures |

See [Test Store setup](../README.md#development-test-store) for all seven choices
and the shipped release lint guard. Await `Nuxie.shutdownAndAwait()` before changing
modes. Test Store history is in memory, customer-scoped and separate from native
commerce evidence. A simulated purchase cannot stand in for a Play token.

## Prepare a Play license test

1. In Play Console, add the billing account under **Settings > License testing**.
   Publish the products/subscriptions to be tested. License eligibility and
   internal-track eligibility are separate settings. Follow Google's
   [license testing setup](https://support.google.com/googleplay/android-developer/answer/6062777?hl=en).
2. Publish an internal-test build, have the tester accept the opt-in link, and
   install it through Play. Check the account shown in the purchase dialog.
   Track membership alone does not prevent charges: use a license tester and
   confirm Play's test-purchase notice and test payment instrument before buying.
   For development, license testers can also sideload a matching-package debug
   build. See [Google's integration testing guide](https://developer.android.com/google/play/billing/test).
3. Connect the exact package to a Nuxie test AppPlatform with working Play
   verification credentials and RTDN delivery. Sync the catalog, select the exact
   Product/base plan/offer or purchase option, attach the intended Feature grants,
   and publish the Experience. Keep the immutable publication identity with the
   test results. A successful catalog sync alone does not prove purchase verification.
4. Initialize the ordinary SDK. Use the same stable Nuxie customer identity on
   purchase and restore; the Google billing account and Nuxie customer ID are
   different identities.

```kotlin
val configuration = NuxieConfiguration("pk_test_YOUR_KEY").apply {
    environment = NuxieEnvironment.DEVELOPMENT
    testStoreEnabled = false
    purchaseHandlingMode = PurchaseHandlingMode.NUXIE_MANAGED
}
Nuxie.setup(applicationContext, configuration)
Nuxie.identify("your-stable-test-customer-id")
```

Import `Nuxie`, `NuxieConfiguration` and `NuxieEnvironment` from `ai.nuxie.sdk`,
and `PurchaseHandlingMode` from `ai.nuxie.sdk.billing`. Exercise the published
Experience's purchase and restore actions. For direct integrations, use a
resolved `StoreProduct` from that catalog; do not manufacture a product with a
missing Play offer token or substitute the Test Store preview.

## Record the outcomes

These are acceptance targets, not a statement that a particular app has passed.
Test each applicable product type and exact offer selection.

| Scenario | Nuxie acceptance evidence |
| --- | --- |
| Approved checkout | Play terms match the signed selection; durable token evidence precedes backend work; backend verification yields the expected Feature state; Nuxie-managed acknowledgement/consumption completes after backend acceptance |
| User cancellation or declined payment | Expected terminal Journey outlet; no new verified purchase or Feature grant |
| Pending payment, then approval or cancellation | Pending creates no terminal checkout outcome or access grant; restart while pending; approval reconciles through the normal native recovery path, cancellation leaves access unchanged |
| Backend unavailable after Play success | Evidence survives process death; recovery later converges without a second purchase or duplicate grant; inspect both immediate SDK state and authoritative Feature state |
| Restore with a stable customer | Same eligible native purchase converges to its existing lineage; no duplicate grant; an empty restore and an unavailable/rejected backend remain distinguishable |
| Customer changes during checkout | Outcome remains bound to the initiating customer; another customer cannot claim already-owned native evidence |
| Consumable repeat purchase | Consumption follows acceptance and permits a later independent purchase; each grant is counted once |
| Subscription lifecycle | Renewals, cancellation, expiry, grace/hold and recovery produce the expected verified Feature state without relying on accelerated test timing |
| SDK-first and RTDN-first delivery | Both orders and duplicates converge on one purchase lineage and original commercial terms |

Use Play's approved/declined and slow payment instruments for payment cases.
Billing Lab can accelerate subscription state changes and repeat introductory
offer tests; use the same license account. Test renewals are accelerated, so
verify state rather than relying on wall-clock delays. Google's current testing
guide describes these [test instruments and lifecycle controls](https://developer.android.com/google/play/billing/test).

For response-code fault injection, follow Google's
[Billing Lab Response Simulator instructions](https://developer.android.com/google/play/billing/test-response-codes).
It requires Billing 7.1.1 or newer. Put its manifest metadata only in a dedicated
nonproduction variant, including Google's `NONPRODUCTION` marker, and exclude
both metadata entries from production. Response simulation is additional error
coverage; it does not prove backend verification or real store finalization.

## Troubleshoot the boundary that failed

- **No test payment instruments:** check the billing account, license eligibility,
  package, and install provenance before attempting a purchase.
- **No Product or wrong offer:** compare the signed catalog identity with the
  active Play catalog and the tester's eligibility. Nuxie intentionally fails
  unresolved signed terms instead of guessing another offer.
- **Play succeeded but access did not converge:** inspect native recovery and
  backend verification independently. Retain the customer, publication and
  operation IDs; don't delete the app's evidence while diagnosing recovery.
- **Restore differs from Test Store:** native restore consults actual purchases
  and backend acceptance. Choosing simulated Restored is not evidence of either.

Retain SDK/runtime revisions, app version code and artifact digest, device/API,
package, Nuxie environment/customer, signed publication digest, scenario, expected
and observed outcomes, and redacted SDK/backend/RTDN evidence. Keep raw purchase
tokens and account credentials out of reports. Mark unavailable provider checks
as unverified, with the specific missing prerequisite. Run the app's
`lintRelease` gate before shipping; never treat a green instrumentation fixture
as a successful Play purchase.
