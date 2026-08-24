#!/usr/bin/env bash
# Stage the prebuilt Nuxie engine (nux_capi) from a built nuxie-runtime
# checkout into runtime/prebuilt/ for local development. The release pipeline
# replaces this with a pinned artifact download (runtime/artifact.json).
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
if [[ $# -ne 1 ]]; then
  echo "Usage: $(basename -- "$0") <path-to-nuxie-runtime-checkout>" >&2
  exit 64
fi
SRC="$(cd -- "$1" && pwd -P)"
DEST="${REPO_ROOT}/runtime/prebuilt"
mkdir -p "${DEST}/jniLibs/arm64-v8a" "${DEST}/jniLibs/x86_64" "${DEST}/include"
cp "${SRC}/target/aarch64-linux-android/release/libnux_capi.so" "${DEST}/jniLibs/arm64-v8a/"
cp "${SRC}/target/x86_64-linux-android/release/libnux_capi.so" "${DEST}/jniLibs/x86_64/"
cp "${SRC}/crates/nux-capi/include/nux_capi.generated.h" "${DEST}/include/"
echo "Staged nux_capi prebuilts from ${SRC}."
