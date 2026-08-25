#!/usr/bin/env bash
# Stage the prebuilt Nuxie engine (nux_capi) from a built nuxie-runtime
# checkout into runtime/prebuilt/ for local development. Normal builds fetch
# the pinned release in runtime/artifact.json instead.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
if [[ $# -ne 1 ]]; then
  echo "Usage: $(basename -- "$0") <path-to-nuxie-runtime-checkout>" >&2
  exit 64
fi
DEST="${REPO_ROOT}/runtime/prebuilt"
RUNTIME_DIR="${REPO_ROOT}/runtime"

# Recover the previous complete tree before any input validation can exit.
# A live tree wins when publication completed before the process died.
shopt -s nullglob
BACKUPS=("${RUNTIME_DIR}"/.prebuilt-*.backup)
if (( ${#BACKUPS[@]} > 0 )); then
  if [[ -e "${DEST}" ]]; then
    for backup in "${BACKUPS[@]}"; do rm -rf -- "${backup}"; done
  else
    RECOVERY="${BACKUPS[${#BACKUPS[@]} - 1]}"
    mv -- "${RECOVERY}" "${DEST}"
    for backup in "${BACKUPS[@]}"; do
      if [[ "${backup}" != "${RECOVERY}" ]]; then rm -rf -- "${backup}"; fi
    done
    echo "Recovered runtime/prebuilt/ from an interrupted installation."
  fi
fi
for stale in "${RUNTIME_DIR}"/.prebuilt-*.tmp; do rm -rf -- "${stale}"; done

SRC="$(cd -- "$1" && pwd -P)"

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

SOURCE_ARTIFACTS=(
  "${SRC}/crates/nux-capi/include/nux_capi.generated.h"
  "${SRC}/target/aarch64-linux-android/release/libnux_capi.so"
  "${NDK_SYSROOT_LIB}/aarch64-linux-android/libc++_shared.so"
  "${SRC}/target/x86_64-linux-android/release/libnux_capi.so"
  "${NDK_SYSROOT_LIB}/x86_64-linux-android/libc++_shared.so"
)
for artifact in "${SOURCE_ARTIFACTS[@]}"; do
  if [[ ! -f "${artifact}" ]]; then
    echo "Runtime staging input is missing: ${artifact}" >&2
    exit 66
  fi
done

TOKEN="$(date +%s)-$$-${RANDOM}"
STAGED="${RUNTIME_DIR}/.prebuilt-${TOKEN}.tmp"
BACKUP="${RUNTIME_DIR}/.prebuilt-${TOKEN}.backup"
mkdir -p "${STAGED}/jniLibs/arm64-v8a" "${STAGED}/jniLibs/x86_64" "${STAGED}/include"

recover_on_exit() {
  status=$?
  rm -rf -- "${STAGED}"
  if [[ -d "${BACKUP}" && ! -e "${DEST}" ]]; then
    mv -- "${BACKUP}" "${DEST}" || true
  fi
  return "${status}"
}
trap recover_on_exit EXIT

cp "${SOURCE_ARTIFACTS[0]}" "${STAGED}/include/nux_capi.generated.h"
cp "${SOURCE_ARTIFACTS[1]}" "${STAGED}/jniLibs/arm64-v8a/libnux_capi.so"
cp "${SOURCE_ARTIFACTS[2]}" "${STAGED}/jniLibs/arm64-v8a/libc++_shared.so"
cp "${SOURCE_ARTIFACTS[3]}" "${STAGED}/jniLibs/x86_64/libnux_capi.so"
cp "${SOURCE_ARTIFACTS[4]}" "${STAGED}/jniLibs/x86_64/libc++_shared.so"

REQUIRED_ARTIFACTS=(
  "include/nux_capi.generated.h"
  "jniLibs/arm64-v8a/libc++_shared.so"
  "jniLibs/arm64-v8a/libnux_capi.so"
  "jniLibs/x86_64/libc++_shared.so"
  "jniLibs/x86_64/libnux_capi.so"
)
for relative_path in "${REQUIRED_ARTIFACTS[@]}"; do
  if [[ ! -f "${STAGED}/${relative_path}" ]]; then
    echo "Staged runtime artifact set is missing ${relative_path}." >&2
    exit 67
  fi
done
ACTUAL_FILE_COUNT="$(find "${STAGED}" -type f | wc -l | tr -d '[:space:]')"
if [[ "${ACTUAL_FILE_COUNT}" != "${#REQUIRED_ARTIFACTS[@]}" ]]; then
  echo "Staged runtime artifact set contains unexpected files." >&2
  exit 67
fi

# Publication is a pair of same-filesystem atomic renames: preserve the old
# complete tree until the new complete tree is live, then discard the backup.
if [[ -e "${DEST}" ]]; then mv -- "${DEST}" "${BACKUP}"; fi
mv -- "${STAGED}" "${DEST}"
rm -rf -- "${BACKUP}"
trap - EXIT

echo "Staged nux_capi prebuilts (with libc++_shared.so) from ${SRC}."
