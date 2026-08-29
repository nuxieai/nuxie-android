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

## Host JVM render harness

The host-only harness drives a release descriptor through the SDK's configured
Experience asset import, artboard/player ownership, fixed-timestep stepping,
and headless Android Vulkan renderer. It writes tightly packed, top-row-first
RGBA8 premultiplied-sRGB files plus `manifest.json`. Harness sources compile
only into the JVM test artifact and are not shipped in the AAR.

The input directory must contain:

```text
release/
├── release-descriptor.json
├── experience.riv
└── assets/
    └── ... files at their descriptor keys, relative to release/
```

Build the host runtime from a `nuxie-runtime` checkout:

```bash
cargo build -p nux-capi --features android-authored-wgsl,android-vulkan,scripting
export NUXIE_HOST_CAPI_LIB=/absolute/path/to/nuxie-runtime/target/debug/libnux_capi.dylib
```

The host adapter requires the same Vulkan and scripting capabilities as the
shipped Android runtime. It probes scripting support when loading and fails
with the required build command instead of rendering a degraded blank frame.

On Linux, use `libnux_capi.so`; a conformant Vulkan ICD such as lavapipe may
be selected with `VK_ICD_FILENAMES` when the machine has more than one ICD.

On Apple Silicon macOS with Homebrew MoltenVK, use the runtime's explicit
loader path. This is reliable through Gradle worker processes; setting only
`DYLD_LIBRARY_PATH` is not.

```bash
export NUXIE_MOLTENVK_LIBRARY=/opt/homebrew/lib/libMoltenVK.dylib
export VK_ICD_FILENAMES=/opt/homebrew/share/vulkan/icd.d/MoltenVK_icd.json
```

Run the harness:

```bash
./gradlew :nuxie-android:hostRenderHarness \
  --args='--input /absolute/path/to/release --output /absolute/path/to/frames --frames 2 --size 390x844 --step-ms 16'
```

`--frames` defaults to `1`, `--step-ms` to `16`, and `--size` to the first
screen's authored width and height in the release descriptor. Omitting
`--size` is an error when that extent is absent. Each frame is written as
`frame-<index>.rgba`; `manifest.json` records its SHA-256 and dimensions plus
the string returned by `NuxieRuntime.info()`.

Verify the live harness separately from the default unit test suite:

```bash
./gradlew :nuxie-android:hostRenderSmoke
```

This dedicated task runs only the three host render smoke tests in a fresh
worker JVM. The default `:nuxie-android:test` task excludes them and is
insensitive to host harness environment variables. When `NUXIE_HOST_CAPI_LIB`
is unset, the dedicated task skips them with the named assumption
`NUXIE_HOST_CAPI_LIB must name a host-built nux_capi library`.

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
