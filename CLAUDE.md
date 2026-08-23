# Repository guidance

- The [Nuxie iOS SDK](https://github.com/nuxieai/nuxie-ios) is the reference
  implementation. Fixtures are the cross-SDK contract; never change SDK
  semantics without a fixture change.
- Use the locked vocabulary exclusively: Experience, Journey, trigger, and
  Feature. Never introduce legacy alternatives for these nouns or verbs.
- Runtime dependencies are limited to Kotlin coroutines and
  `kotlinx-serialization-json`. No `java.time` anywhere in SDK sources —
  it would force host apps below minSdk 26 into core-library desugaring.
  Timestamps are epoch millis (`Long`).
- Kotlin sources use the `ai.nuxie.sdk` package root.
- The public surface is guarded by `./gradlew apiCheck`. Regenerate its
  baseline for an intentional API change with `./gradlew apiDump`.
- Sync fixtures with `scripts/sync-fixtures.sh <path-to-nuxie-ios-checkout>`.
  `fixtures/MANIFEST.json` pins the source `nuxie-ios` SHA; validate it with
  `scripts/verify-fixtures.sh`.
- Run `./gradlew :nuxie-android:test :nuxie-android:apiCheck :nuxie-android:lint`
  and `./gradlew :example-app:assembleDebug` before opening a PR.
- Work lands through feature branches and PRs. `main` is protected.
