# Commerce wire fixtures

These fixtures pin commerce requests replayed by the parent repository's
workers. The Play cases export exact request bytes produced by the Android
SDK. Each file contains the named case, endpoint, parsed JSON body, and the
byte-for-byte UTF-8 body text sent through `HttpTransport`.

Regenerate the request fixtures from the repository root with:

```sh
NUXIE_GENERATE_COMMERCE_WIRE_FIXTURES=1 ./gradlew \
  :nuxie-android:testDebugUnitTest \
  --tests ai.nuxie.sdk.network.CommerceWireFixtureTest.generateCommerceWireFixtures \
  --rerun-tasks
```

Normal unit tests regenerate the same deterministic cases in memory and fail
if a committed file differs. `entitled-atomic-use-full` and
`entitled-atomic-replay` come from a failed call followed by a retry through
`PurchaseService.useFeatureWithPendingPurchase`. The test captures both real
`/entitled` requests and requires byte-identical `bodyText`, including the
stable `event_id` owned by `PurchaseService`.

The remaining Play request cases call `NuxieApi.postPurchase` or
`NuxieApi.useFeatureWithPurchase` with fixed report values and a capturing
transport.

`entitled-appstore-untouched` is different: it is a server-contract vector
pinning the `/entitled` App Store arm shape defined by the iOS `CodingKeys`
canon for the parent repository's worker replay. Android cannot emit App Store
evidence, so this vector is deliberately not an Android encoder product or
Android encoder coverage. It is maintained locally; generating these fixtures
does not import from or modify the iOS repository.

This subtree is Android-authored. It is deliberately excluded from
`fixtures/MANIFEST.json`, `scripts/sync-fixtures.sh`, and
`scripts/verify-fixtures.sh`; those files cover only fixtures synced from
`nuxie-ios`.

See [responses/README.md](responses/README.md) for the worker response
handshake.
