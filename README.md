# Nuxie Android SDK

The Nuxie Android SDK is the device half of Nuxie's new-user-experience
engine. Apps trigger moments; signed Experiences decide what happens.

## Status

This is a pre-release greenfield rebuild. The
[Nuxie iOS SDK](https://github.com/nuxieai/nuxie-ios) is the reference
implementation. The specification lives in the `nuxie-dev` repository at
`specs/android-sdk/overview.md`.

## Requirements

- Android minSdk 23
- Kotlin

## Runtime artifact

A fresh clone builds normally; Gradle fetches and SHA-256 verifies the pinned
runtime release when needed. For local runtime development, stage a checkout
with `scripts/stage-runtime.sh <path-to-nuxie-runtime-checkout>` and build with
`NUXIE_RUNTIME_USE_LOCAL=1`.

## Wrapper contract

The [`nuxie-android/api/nuxie-android.api`](nuxie-android/api/nuxie-android.api)
`apiCheck` dump is the Android binding wrapper contract, sibling to the
`nuxie-ios` `api/public-api.txt`. Wrappers may bind only symbols listed in that
allowlist.

## Setup

```kotlin
val configuration = NuxieConfiguration("YOUR_API_KEY").apply {
    environment = NuxieEnvironment.PRODUCTION
}
Nuxie.setup(applicationContext, configuration)
check(Nuxie.isSetup)
```

The legacy SDK is archived on branch `legacy/webview-sdk`.
