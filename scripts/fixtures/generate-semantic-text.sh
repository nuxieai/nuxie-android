#!/usr/bin/env bash
set -euo pipefail
sdk_dir="$(cd "$(dirname "$0")/../.." && pwd)"
runtime_dir="${1:-$sdk_dir/../../third_party/nuxie-runtime}"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuxie-semantic-fixture.XXXXXX")"
trap 'rm -r "$build_dir"' EXIT
rustc --edition=2021 --crate-name nuxie_schema --crate-type rlib \
  "$runtime_dir/crates/nuxie-schema/src/lib.rs" -o "$build_dir/libnuxie_schema.rlib"
rustc --edition=2021 "$sdk_dir/scripts/fixtures/semantic-text.rs" \
  --extern "nuxie_schema=$build_dir/libnuxie_schema.rlib" -o "$build_dir/generate"
"$build_dir/generate" "$sdk_dir/nuxie-android/src/androidTest/assets/semantic_text.riv"
