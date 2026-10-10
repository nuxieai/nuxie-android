#!/usr/bin/env bash
set -euo pipefail
root="$TEST_SRCDIR/$TEST_WORKSPACE"
# Gradle's fixture tests run from the SDK module. Robolectric's generated
# manifest/resource paths run from the Bazel package. Give both their existing
# relative paths using links to declared runfiles in an isolated test directory.
work="$TEST_TMPDIR/sdk-cwd"
cwd="$work/nuxie-android"
mkdir -p "$cwd"
for entry in "$TEST_SRCDIR"/*; do
  ln -s "$entry" "$work/$(basename "$entry")"
done
for entry in "$root"/*; do
  name="$(basename "$entry")"
  if [[ "$name" != nuxie-android ]]; then
    # FixtureRunner walks to repository fixtures and derives its source root
    # from their parent; retain Gradle's parent-level fixture location.
    [[ "$name" == fixtures ]] || ln -s "$entry" "$cwd/$name"
    [[ -e "$work/$name" ]] || ln -s "$entry" "$work/$name"
  fi
done
if [[ -d "$root/nuxie-android" ]]; then
  for entry in "$root/nuxie-android"/*; do
    name="$(basename "$entry")"
    [[ -e "$cwd/$name" ]] || ln -s "$entry" "$cwd/$name"
  done
fi
cd "$cwd"
android_jar="@android_jar@"
if [[ -n "$android_jar" ]]; then
  # Lint needs genuine Android API classes even when allowMissingSdk is set.
  # Keep discovery and cache private, with the jar a declared input.
  sdk="$TEST_TMPDIR/lint-sdk"
  mkdir -p "$sdk/platforms/android-36" "$TEST_TMPDIR/lint-cache"
  ln -s "$TEST_SRCDIR/$android_jar" "$sdk/platforms/android-36/android.jar"
  cat > "$sdk/platforms/android-36/source.properties" <<'metadata'
Platform.Version=16
AndroidVersion.ApiLevel=36
Pkg.Revision=2
metadata
  export ANDROID_SDK_ROOT="$sdk" ANDROID_HOME="$sdk"
  export ANDROID_SDK_CACHE_DIR="$TEST_TMPDIR/lint-cache"
fi
exec "$TEST_SRCDIR/@binary@" "$@"
