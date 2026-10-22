# Fixture parity

Synced from nuxie-ios `5d2f592bf6a3f29a7cdd406ab5c7fd77e2fe9773`.

- `journeys/planes/presentation-readiness.json`: blocked pending a larger parity port ([UNIV-5341](https://universe.basis.dev/issue/UNIV-5341)); Android has run-owned native values but no shared verified-release preparation store, readiness classification, or show-lifetime preparation reservation, so the iOS readiness and shimmer tests cannot yet be ported faithfully.
- `purchases/app-account-token.json`: iOS-only; StoreKit uses this scoped UUID while Google Play uses `playAccountId(distinctId)`, the SHA-256 hex digest of the customer ID passed to `BillingFlowParams.setObfuscatedAccountId`, so these vectors do not apply.
- `runtime/authored-font-fallback/`: driven by `SystemFontFallbackDeviceTest.ordinaryPublishedTextDrawsFallbackScriptsAndColorEmoji`, with the copied scene and Latin font also used by the example's release qualification assets.
- `runtime/paywall-selection/`: driven by `NuxiePaywallSelectionDeviceTest`; the JVM `NuxiePaywallSelectionTest` continues to drive `journeys/planes/file-paywall-selection.json` with a simulated native tap.
- `runtime/tap-once/`: driven by `ExperienceTapOnceDeviceTest`, using production player selection, event-source validation, and Journey emission routing for split and combined pointer steps.

`events/catalog.json` has no pre-existing Android catalog conformance reader.
`PurchaseServiceTest.completedCheckoutCarriesTheLocalizedPlayPrice` now checks
that `store_environment` is optional in the catalog and absent from a Play
completion, since Sandbox StoreKit purchases on live keys are an iOS App Review case.

The readiness gap is not iOS-only. The brief requires stopping before a large
behavior port. Its fixture also requires retrying incomplete optional-object
acquisition and retaining dropped releases until show end; adding a standalone
shimmer switch would not implement that contract.
