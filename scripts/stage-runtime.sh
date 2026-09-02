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
TOOLCHAIN_FILE="${RUNTIME_DIR}/android-toolchain.properties"
if [[ ! -f "${TOOLCHAIN_FILE}" ]]; then
  echo "Android toolchain pin is missing: ${TOOLCHAIN_FILE}" >&2
  exit 65
fi
# The committed file contains shell-safe key=value pairs so Gradle and this
# staging boundary consume one exact toolchain pin.
# shellcheck disable=SC1090
source "${TOOLCHAIN_FILE}"
if [[ -z "${ndkVersion:-}" ]]; then
  echo "Android toolchain pin has no ndkVersion." >&2
  exit 65
fi
PINNED_NDK_VERSION="${ndkVersion}"

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

# Consume the runtime builder's verified publication tree rather than
# reconstructing one from arbitrary Cargo and locally installed NDK outputs.
# Its captured build inputs make the NDK source of libc++_shared.so explicit.
RUNTIME_BUILD="${SRC}/target/nux-capi-android"
RUNTIME_PREBUILT="${RUNTIME_BUILD}/build/prebuilt"
RUNTIME_BUILD_INPUTS="${RUNTIME_BUILD}/NuxieRuntimeAndroid-BUILD_INPUTS.json"
if [[ ! -f "${RUNTIME_BUILD_INPUTS}" ]]; then
  echo "Runtime build provenance is missing: ${RUNTIME_BUILD_INPUTS}" >&2
  exit 66
fi
BUILD_NDK_VERSION="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["configuration"]["androidNdk"])' "${RUNTIME_BUILD_INPUTS}")"
BUILD_SOURCE_REVISION="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["sourceRevision"])' "${RUNTIME_BUILD_INPUTS}")"
SOURCE_REVISION="$(git -C "${SRC}" rev-parse HEAD)"
if [[ "${BUILD_NDK_VERSION}" != "${PINNED_NDK_VERSION}" ]]; then
  echo "Runtime provenance mismatch: expected NDK ${PINNED_NDK_VERSION}, found ${BUILD_NDK_VERSION:-unknown}." >&2
  exit 65
fi
if [[ "${BUILD_SOURCE_REVISION}" != "${SOURCE_REVISION}" ]]; then
  echo "Runtime provenance mismatch: the staged build was not produced from the checkout's current HEAD." >&2
  exit 65
fi

SOURCE_ARTIFACTS=(
  "${RUNTIME_PREBUILT}/include/nux_capi.generated.h"
  "${RUNTIME_PREBUILT}/jniLibs/arm64-v8a/libnux_capi.so"
  "${RUNTIME_PREBUILT}/jniLibs/arm64-v8a/libc++_shared.so"
  "${RUNTIME_PREBUILT}/jniLibs/x86_64/libnux_capi.so"
  "${RUNTIME_PREBUILT}/jniLibs/x86_64/libc++_shared.so"
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
