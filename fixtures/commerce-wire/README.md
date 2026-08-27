# Commerce wire fixtures

These fixtures export the exact request bytes produced by the Android SDK's
commerce encoders. Each file contains the named case, endpoint, parsed JSON
body, and the byte-for-byte UTF-8 body text sent through `HttpTransport`.

Regenerate the request fixtures from the repository root with:

```sh
NUXIE_GENERATE_COMMERCE_WIRE_FIXTURES=1 ./gradlew \
  :nuxie-android:testDebugUnitTest \
  --tests ai.nuxie.sdk.network.CommerceWireFixtureTest.generateCommerceWireFixtures \
  --rerun-tasks
```

Normal unit tests regenerate the same deterministic cases in memory and fail
if a committed file differs. `entitled-atomic-use-full` and
`entitled-atomic-replay` deliberately contain identical `bodyText`, including
the same `event_id`.

The Play request cases call `NuxieApi.postPurchase` or
`NuxieApi.useFeatureWithPurchase` with fixed report values and a capturing
transport. Android cannot emit App Store evidence, so
`entitled-appstore-untouched` is a test-only compatibility fixture built from
the locked iOS `/purchase` fields. It pins the existing `appstore` arm while
workers add the `playstore` arm to their purchase union.

This subtree is Android-authored. It is deliberately excluded from
`fixtures/MANIFEST.json`, `scripts/sync-fixtures.sh`, and
`scripts/verify-fixtures.sh`; those files cover only fixtures synced from
`nuxie-ios`.

See [responses/README.md](responses/README.md) for the worker response
handshake.
