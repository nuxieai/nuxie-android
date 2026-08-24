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

# The Vulkan engine links the NDK C++ runtime, so libc++_shared.so must ride
# along in jniLibs or System.loadLibrary fails at dlopen on device.
NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [[ -z "${NDK_ROOT}" && -n "${ANDROID_HOME:-}" ]]; then
  NDK_ROOT="$(find "${ANDROID_HOME}/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
fi
if [[ -z "${NDK_ROOT}" || ! -d "${NDK_ROOT}" ]]; then
  echo "Set ANDROID_NDK_HOME (or ANDROID_HOME with an installed ndk) to stage libc++_shared.so." >&2
  exit 65
fi
NDK_SYSROOT_LIB="${NDK_ROOT}/toolchains/llvm/prebuilt"
NDK_SYSROOT_LIB="$(find "${NDK_SYSROOT_LIB}" -maxdepth 1 -mindepth 1 -type d | head -1)/sysroot/usr/lib"
cp "${NDK_SYSROOT_LIB}/aarch64-linux-android/libc++_shared.so" "${DEST}/jniLibs/arm64-v8a/"
cp "${NDK_SYSROOT_LIB}/x86_64-linux-android/libc++_shared.so" "${DEST}/jniLibs/x86_64/"

echo "Staged nux_capi prebuilts (with libc++_shared.so) from ${SRC}."
