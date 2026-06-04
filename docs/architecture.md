# Architecture Overview

This SDK is split into core runtime and Android platform integration.

## Modules

- `nuxie-core`: platform-agnostic logic
  - identity/session
  - network client and models
  - event queueing and batching
  - profile + segment + feature services
  - journey/IR runtime
  - trigger models
- `nuxie-android`: Android platform layer
  - `NuxieSDK` public API
  - Room-backed queue/history stores
  - flow rendering (`FlowView`, `FlowWebView`, `NuxieFlowActivity`)
  - activity/lifecycle bridge
  - plugin manager

## Runtime Flow

1. `NuxieSDK.setup(...)` creates services and starts queue processing.
2. `trigger(...)` records event and receives gate response.
3. Journey runtime evaluates local campaign + interaction logic.
4. Flow UI is shown if required (`showFlow` / journey presentation).
5. Delegate callbacks are emitted for flow actions.
6. Profile refresh syncs features, segments, journeys, and flows.
7. Play Billing purchase tokens are synced through `/purchase`; the backend verifies,
   acknowledges or consumes, and returns feature updates before the SDK confirms purchases.

Android mirrors iOS transaction-observation behavior at the entitlement boundary, but not
by copying StoreKit's API shape. The SDK listens for live `PurchasesUpdatedListener`
callbacks while BillingClient is connected, queries owned purchases after connection, and
re-queries when the app becomes active so purchases completed while the app was stopped,
disconnected, or pending are still synced. Successful restore actions also trigger the
same owned-purchase query so the restore path converges entitlements through Google
Play's current-purchase API.

The Android SDK depends on Google Play Billing Library 8.0.0. Billing 8.0 preserves the
SDK's minSdk 21 baseline while adding automatic service reconnection and modern
one-time product support. Billing 8.1+ adds suspended-subscription query flags but raises
minSdk to 23, so the adapter uses that query flag reflectively only when a host app
overrides Billing to a newer compatible version.

## Flow Interaction Contract

- Flow-level/global interactions are hosted under `interactions["__global__"]`.
- Flow entry executes from enabled global interactions with `trigger.type == "start"`.
- Runtime no longer uses legacy `interactions["start"]` or `$flow_entered` for entry.
- Generic trigger dispatch (event/manual/did_set) evaluates global interactions alongside screen/component interactions.

## Key Services

- `IdentityService`: manages distinct/anonymous IDs and user properties.
- `SessionService`: controls session lifecycle and ID rotation.
- `EventService` + `NuxieNetworkQueue`: local queue + flush/retry logic.
- `ProfileService`: cache-first profile fetch with background refresh.
- `FeatureService`: cached/remote entitlement and balance checks.
- `SegmentService`: segment membership and change stream.
- `JourneyService`: campaign trigger handling and flow journey execution.
- `FlowService`: remote flow fetch/cache and `FlowView` creation.
- `PluginService` (Android layer): plugin install/start/stop/lifecycle fanout.

## Persistence

- Room DB (`nuxie-android`): event queue + event history.
- File stores (`nuxie-core`): profile cache, journey state, segment membership, flow bundles/fonts.

## Threading Model

- Internal services run on a background `CoroutineScope`.
- SDK delegate callbacks are marshaled to the main thread.
- Queue/network and file I/O run off the main thread.

## Android Lifecycle Integration

`CurrentActivityTracker` monitors app activity transitions and drives:

- session foreground/background hooks
- profile/feature refresh on app active
- plugin lifecycle callbacks
- current host activity lookup for flow presentation
