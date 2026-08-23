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

## Setup

```kotlin
val configuration = NuxieConfiguration("YOUR_API_KEY").apply {
    environment = NuxieEnvironment.PRODUCTION
}
Nuxie.setup(applicationContext, configuration)
check(Nuxie.isSetup)
```

The legacy SDK is archived on branch `legacy/webview-sdk`.
