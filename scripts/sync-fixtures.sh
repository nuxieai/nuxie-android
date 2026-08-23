#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly REPO_ROOT
readonly DESTINATION="${REPO_ROOT}/fixtures"

usage() {
  printf 'Usage: %s <path-to-nuxie-ios-checkout>\n' "$(basename -- "$0")" >&2
}

if [[ $# -ne 1 ]]; then
  usage
  exit 64
fi

for required_command in git python3 rsync; do
  if ! command -v "${required_command}" >/dev/null 2>&1; then
    printf 'Required command not found: %s\n' "${required_command}" >&2
    exit 1
  fi
done

if ! SOURCE_CHECKOUT="$(cd -- "$1" 2>/dev/null && pwd -P)"; then
  printf 'iOS checkout does not exist or is not a directory: %s\n' "$1" >&2
  exit 1
fi
readonly SOURCE_CHECKOUT
readonly SOURCE_FIXTURES="${SOURCE_CHECKOUT}/fixtures"

if [[ ! -d "${SOURCE_FIXTURES}" ]]; then
  printf 'Fixture directory not found: %s\n' "${SOURCE_FIXTURES}" >&2
  exit 1
fi

if ! SOURCE_COMMIT="$(git -C "${SOURCE_CHECKOUT}" rev-parse HEAD 2>/dev/null)"; then
  printf 'Could not resolve the source checkout HEAD: %s\n' "${SOURCE_CHECKOUT}" >&2
  exit 1
fi
readonly SOURCE_COMMIT

if [[ ! "${SOURCE_COMMIT}" =~ ^[0-9a-fA-F]{40,64}$ ]]; then
  printf 'Source checkout HEAD is not a full commit hash: %s\n' "${SOURCE_COMMIT}" >&2
  exit 1
fi

# Export fixtures from the resolved commit, never the working tree, so the
# manifest's sourceCommit can always reproduce the copied bytes even when the
# source checkout is dirty.
STAGING="$(mktemp -d)"
trap 'rm -rf -- "${STAGING}"' EXIT
if ! git -C "${SOURCE_CHECKOUT}" archive "${SOURCE_COMMIT}" -- fixtures | tar -x -C "${STAGING}"; then
  printf 'Failed to export fixtures/ from commit %s\n' "${SOURCE_COMMIT}" >&2
  exit 1
fi

mkdir -p -- "${DESTINATION}"
rsync --archive --delete --exclude /MANIFEST.json -- "${STAGING}/fixtures/" "${DESTINATION}/"

python3 - "${DESTINATION}" "${SOURCE_COMMIT}" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile


fixture_root = Path(sys.argv[1])
source_commit = sys.argv[2]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as fixture_file:
        for chunk in iter(lambda: fixture_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


fixture_files = {}
for path in sorted(
    fixture_root.rglob("*"),
    key=lambda candidate: candidate.relative_to(fixture_root).as_posix(),
):
    relative_path = path.relative_to(fixture_root).as_posix()
    if relative_path == "MANIFEST.json":
        continue
    if path.is_symlink():
        raise SystemExit(f"Fixture symlinks are not supported: {relative_path}")
    if path.is_file():
        fixture_files[relative_path] = sha256(path)
    elif not path.is_dir():
        raise SystemExit(f"Unsupported fixture entry: {relative_path}")

manifest = {
    "sourceRepo": "nuxieai/nuxie-ios",
    "sourceCommit": source_commit,
    "files": fixture_files,
}

temporary_path = None
try:
    with tempfile.NamedTemporaryFile(
        "w",
        encoding="utf-8",
        dir=fixture_root,
        prefix=".MANIFEST.",
        suffix=".tmp",
        delete=False,
    ) as temporary_file:
        temporary_path = temporary_file.name
        json.dump(manifest, temporary_file, ensure_ascii=False, indent=2)
        temporary_file.write("\n")
    os.chmod(temporary_path, 0o644)
    os.replace(temporary_path, fixture_root / "MANIFEST.json")
finally:
    if temporary_path is not None and os.path.exists(temporary_path):
        os.unlink(temporary_path)

print(f"Synced {len(fixture_files)} fixture files from {source_commit}.")
PY
