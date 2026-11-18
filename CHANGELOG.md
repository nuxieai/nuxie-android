# Changelog

## 1.1.0

Updates the embedded runtime to [android-runtime-v1.2.0](https://github.com/nuxieai/nuxie-runtime/releases/tag/android-runtime-v1.2.0).

Nuxie Android runtime 1.2.0

Source: `826d2cc24d4ee7ec0900ab32b00af8ef8e27f17b`
C ABI runtime: `1.2.0@826d2cc24d4ee7ec0900ab32b00af8ef8e27f17b`

Pinned release cut:

- Rust 1.94.1
- Bazel 9.3.0 (`//crates/nux-capi:nux-capi__android__cdylib`)
- Android NDK 29.0.14206865
- Android API 23
- ABIs: arm64-v8a, x86_64
- Features: android-authored-wgsl, android-vulkan, scripting

Archive bytes: 27181074
Expanded bytes: 77587703
Archive SHA-256: `75389537f1e30046615661f784b7efab92f651a7b9e4db90091aa2bcfd828985`

## 1.0.0

First public 1.0.0 release of the Nuxie Android SDK.

Uses [Android runtime 1.0.0](https://github.com/nuxieai/nuxie-runtime/releases/tag/android-runtime-v1.0.0) with a versioned, SHA-256 verified native artifact.
