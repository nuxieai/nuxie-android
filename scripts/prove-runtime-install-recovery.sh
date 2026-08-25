#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
CASE="${1:-all}"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/nuxie-runtime-recovery.XXXXXX")"
WORKSPACE="${TEST_ROOT}/repo"
LOG_FILE="${TEST_ROOT}/gradle.log"
PIN_CHECKSUM="b3e08fc270162fcc27322766e45df8ffbe36cb56db01c9749db321993a7a4997"

cleanup() {
  rm -rf -- "${TEST_ROOT}"
}
trap cleanup EXIT
shopt -s nullglob

rsync -a \
  --exclude '.git' \
  --exclude '.gradle' \
  --exclude '.kotlin' \
  --exclude 'build' \
  --exclude 'runtime/prebuilt' \
  "${REPO_ROOT}/" "${WORKSPACE}/"

write_pin() {
  local checksum="${1}"
  local url="${2:-file:///does-not-exist}"
  printf '{\n  "release": "proof-release",\n  "url": "%s",\n  "checksum": "%s"\n}\n' \
    "${url}" "${checksum}" > "${WORKSPACE}/runtime/artifact.json"
}

write_artifact_set() {
  local directory="${1}"
  local marker="${2}"
  local archive_checksum="${3:-${PIN_CHECKSUM}}"
  local relative_path
  local digest

  local artifacts=(
    'include/nux_capi.generated.h'
    'jniLibs/arm64-v8a/libc++_shared.so'
    'jniLibs/arm64-v8a/libnux_capi.so'
    'jniLibs/x86_64/libc++_shared.so'
    'jniLibs/x86_64/libnux_capi.so'
  )

  for relative_path in "${artifacts[@]}"; do
    mkdir -p -- "${directory}/$(dirname -- "${relative_path}")"
    printf '%s:%s\n' "${marker}" "${relative_path}" > "${directory}/${relative_path}"
  done

  {
    printf '{\n  "archiveChecksum": "%s",\n  "files": {\n' "${archive_checksum}"
    for relative_path in "${artifacts[@]}"; do
      digest="$(shasum -a 256 "${directory}/${relative_path}" | awk '{print $1}')"
      if [[ "${relative_path}" == "${artifacts[${#artifacts[@]} - 1]}" ]]; then
        printf '    "%s": "%s"\n' "${relative_path}" "${digest}"
      else
        printf '    "%s": "%s",\n' "${relative_path}" "${digest}"
      fi
    done
    printf '  }\n}\n'
  } > "${directory}/.artifact-checksum"
}

run_fetch() {
  (
    cd -- "${WORKSPACE}"
    env "$@" ./gradlew :runtime:fetch --offline --no-daemon
  ) > "${LOG_FILE}" 2>&1
}

reset_install_state() {
  local stale

  rm -rf -- "${WORKSPACE}/runtime/prebuilt"
  for stale in "${WORKSPACE}"/runtime/.prebuilt-*.backup "${WORKSPACE}"/runtime/.prebuilt-*.tmp; do
    rm -rf -- "${stale}"
  done
}

prove_valid_older_backup_beats_corrupt_newest() {
  local runtime="${WORKSPACE}/runtime"
  local older="${runtime}/.prebuilt-older.backup"
  local newer="${runtime}/.prebuilt-newer.backup"

  reset_install_state
  write_pin "${PIN_CHECKSUM}"
  write_artifact_set "${older}" 'older-valid'
  write_artifact_set "${newer}" 'newer-corrupt'
  printf 'tampered\n' > "${newer}/jniLibs/arm64-v8a/libnux_capi.so"
  touch -t 202608250101 "${older}"
  touch -t 202608250102 "${newer}"

  if ! run_fetch; then
    printf 'Expected recovery to succeed; Gradle output:\n' >&2
    sed -n '1,240p' "${LOG_FILE}" >&2
    return 1
  fi

  grep -q '^older-valid:' "${runtime}/prebuilt/jniLibs/arm64-v8a/libnux_capi.so"
  [[ ! -e "${older}" ]]
  [[ ! -e "${newer}" ]]
}

prove_local_live_tree_survives_interrupted_stage() {
  local runtime="${WORKSPACE}/runtime"
  local live="${runtime}/prebuilt"
  local backup="${runtime}/.prebuilt-interrupted.backup"

  reset_install_state
  write_pin "${PIN_CHECKSUM}"
  write_artifact_set "${live}" 'new-local-live'
  write_artifact_set "${backup}" 'old-local-backup'
  rm -- "${live}/.artifact-checksum" "${backup}/.artifact-checksum"

  if ! run_fetch NUXIE_RUNTIME_USE_LOCAL=1; then
    printf 'Expected local recovery to succeed; Gradle output:\n' >&2
    sed -n '1,240p' "${LOG_FILE}" >&2
    return 1
  fi

  grep -q '^new-local-live:' "${live}/jniLibs/arm64-v8a/libnux_capi.so"
  [[ ! -e "${backup}" ]]
}

assert_malformed_pin_preserved_state() {
  local live="${1}"
  local backup="${2}"
  local temporary="${3}"
  local expected_message="${4}"

  grep -q "${expected_message}" "${LOG_FILE}"
  grep -q '^intact-live:' "${live}/jniLibs/arm64-v8a/libnux_capi.so"
  grep -q '^intact-backup:' "${backup}/jniLibs/arm64-v8a/libnux_capi.so"
  grep -q '^intact temporary tree$' "${temporary}/sentinel"
}

prove_malformed_pin_changes_nothing() {
  local runtime="${WORKSPACE}/runtime"
  local live="${runtime}/prebuilt"
  local backup="${runtime}/.prebuilt-malformed-pin.backup"
  local temporary="${runtime}/.prebuilt-malformed-pin.tmp"
  local malformed_checksum='not-a-lowercase-sha256'
  local malformed_url='not a valid absolute URL'

  # Case 1: malformed checksum fails before any destructive step.
  reset_install_state
  write_pin "${malformed_checksum}"
  write_artifact_set "${live}" 'intact-live'
  write_artifact_set "${backup}" 'intact-backup' "${malformed_checksum}"
  mkdir -p -- "${temporary}"
  printf 'intact temporary tree\n' > "${temporary}/sentinel"

  if run_fetch; then
    printf 'Expected the malformed checksum pin to fail.\n' >&2
    return 1
  fi
  assert_malformed_pin_preserved_state "${live}" "${backup}" "${temporary}" \
    'checksum must be a lowercase SHA-256 digest'

  # Case 2: a valid checksum with a malformed URL also fails before any
  # destructive step; a live tree and backup that fail pin validation must
  # both survive untouched, and no recovery/pruning may run.
  reset_install_state
  write_pin "${PIN_CHECKSUM}" "${malformed_url}"
  write_artifact_set "${live}" 'intact-live' 'f000000000000000000000000000000000000000000000000000000000000000'
  write_artifact_set "${backup}" 'intact-backup' 'f000000000000000000000000000000000000000000000000000000000000000'
  mkdir -p -- "${temporary}"
  printf 'intact temporary tree\n' > "${temporary}/sentinel"

  if run_fetch; then
    printf 'Expected the malformed URL pin to fail.\n' >&2
    return 1
  fi
  assert_malformed_pin_preserved_state "${live}" "${backup}" "${temporary}" \
    'url must be a valid absolute URL'
}

case "${CASE}" in
  valid-older-backup)
    prove_valid_older_backup_beats_corrupt_newest
    ;;
  local-live-survives)
    prove_local_live_tree_survives_interrupted_stage
    ;;
  malformed-pin-preserves-install)
    prove_malformed_pin_changes_nothing
    ;;
  all)
    prove_valid_older_backup_beats_corrupt_newest
    prove_local_live_tree_survives_interrupted_stage
    prove_malformed_pin_changes_nothing
    ;;
  *)
    printf 'Unknown proof case: %s\n' "${CASE}" >&2
    exit 64
    ;;
esac

printf 'Runtime install recovery proof passed: %s\n' "${CASE}"
