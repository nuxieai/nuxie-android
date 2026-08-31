# Repository guidance

- The [Nuxie iOS SDK](https://github.com/nuxieai/nuxie-ios) is the reference
  implementation. Fixtures are the cross-SDK contract; never change SDK
  semantics without a fixture change.
- Use the locked vocabulary exclusively: Experience, Journey, trigger, and
  Feature. Never introduce legacy alternatives for these nouns or verbs.
- Runtime dependencies are limited to Kotlin coroutines,
  `kotlinx-serialization-json`, and `androidx.sqlite` (the grilled spec's
  storage decision; no Room, no OkHttp). One sanctioned exception:
  `com.android.billingclient:billing` for Play purchases, which cannot exist
  without it. It is `api`-scoped because the locked `StoreProduct` shape
  exposes `ProductDetails` publicly, and it is the plain artifact, never
  `billing-ktx` (the KTX granule ships Kotlin metadata newer than this
  repo's pinned compiler). Adding any further runtime dependency needs a
  spec decision first. No `java.time` anywhere in SDK
  sources — it would force host apps below minSdk 26 into core-library
  desugaring. Timestamps are epoch millis (`Long`).
- Kotlin sources use the `ai.nuxie.sdk` package root.
- The public surface is guarded by `./gradlew apiCheck`. Regenerate its
  baseline for an intentional API change with `./gradlew apiDump`.
- Sync fixtures with `scripts/sync-fixtures.sh <path-to-nuxie-ios-checkout>`.
  `fixtures/MANIFEST.json` pins the source `nuxie-ios` SHA; validate it with
  `scripts/verify-fixtures.sh`.
- Run `./gradlew :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint`
  and `./gradlew :example-app:assembleDebug` before opening a PR.
- Work lands through feature branches and PRs. `main` is protected.
