#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
readonly REPO_ROOT
readonly FIXTURE_ROOT="${REPO_ROOT}/fixtures"

if ! command -v python3 >/dev/null 2>&1; then
  printf 'Required command not found: python3\n' >&2
  exit 1
fi

python3 - "${FIXTURE_ROOT}" <<'PY'
import hashlib
import json
from pathlib import Path
import re
import sys


fixture_root = Path(sys.argv[1])
manifest_path = fixture_root / "MANIFEST.json"


def fail(message):
    print(message, file=sys.stderr)
    raise SystemExit(1)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as fixture_file:
        for chunk in iter(lambda: fixture_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if not fixture_root.is_dir():
    fail(f"Fixture directory not found: {fixture_root}")
if not manifest_path.is_file():
    fail(f"Fixture manifest not found: {manifest_path}")

try:
    with manifest_path.open("r", encoding="utf-8") as manifest_file:
        manifest = json.load(manifest_file)
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    fail(f"Could not read fixture manifest: {error}")

if not isinstance(manifest, dict):
    fail("Fixture manifest must contain a JSON object.")
if manifest.get("sourceRepo") != "nuxieai/nuxie-ios":
    fail("Fixture manifest has an invalid sourceRepo.")

source_commit = manifest.get("sourceCommit")
if not isinstance(source_commit, str) or re.fullmatch(r"[0-9a-fA-F]{40,64}", source_commit) is None:
    fail("Fixture manifest has an invalid sourceCommit.")

expected_files = manifest.get("files")
if not isinstance(expected_files, dict):
    fail("Fixture manifest must contain a files object.")
if any(
    not isinstance(path, str) or not isinstance(checksum, str)
    for path, checksum in expected_files.items()
):
    fail("Fixture manifest file paths and checksums must be strings.")

invalid_checksums = sorted(
    path
    for path, checksum in expected_files.items()
    if re.fullmatch(r"[0-9a-f]{64}", checksum) is None
)
if invalid_checksums:
    for path in invalid_checksums:
        print(f"Invalid checksum in manifest: {path}", file=sys.stderr)
    raise SystemExit(1)

source_commit_overrides = manifest.get("sourceCommitOverrides", {})
if not isinstance(source_commit_overrides, dict):
    fail("Fixture manifest sourceCommitOverrides must be an object when present.")
invalid_source_overrides = sorted(
    path
    for path, commit in source_commit_overrides.items()
    if path not in expected_files
    or not isinstance(path, str)
    or not isinstance(commit, str)
    or re.fullmatch(r"[0-9a-fA-F]{40,64}", commit) is None
)
if invalid_source_overrides:
    for path in invalid_source_overrides:
        print(f"Invalid source commit override in manifest: {path}", file=sys.stderr)
    raise SystemExit(1)

actual_files = {}
for path in sorted(
    fixture_root.rglob("*"),
    key=lambda candidate: candidate.relative_to(fixture_root).as_posix(),
):
    relative_path = path.relative_to(fixture_root).as_posix()
    # purchase-wire is Android-authored and verified by its SDK unit tests.
    if relative_path == "MANIFEST.json" or relative_path.startswith("purchase-wire/"):
        continue
    if path.is_symlink():
        fail(f"Fixture symlinks are not supported: {relative_path}")
    if path.is_file():
        actual_files[relative_path] = sha256(path)
    elif not path.is_dir():
        fail(f"Unsupported fixture entry: {relative_path}")

expected_paths = set(expected_files)
actual_paths = set(actual_files)
missing_paths = sorted(expected_paths - actual_paths)
extra_paths = sorted(actual_paths - expected_paths)
mismatched_paths = sorted(
    path
    for path in expected_paths & actual_paths
    if expected_files[path] != actual_files[path]
)

for path in missing_paths:
    print(f"Missing fixture: {path}", file=sys.stderr)
for path in extra_paths:
    print(f"Extra fixture: {path}", file=sys.stderr)
for path in mismatched_paths:
    print(f"Checksum mismatch: {path}", file=sys.stderr)
    print(f"  expected: {expected_files[path]}", file=sys.stderr)
    print(f"  actual:   {actual_files[path]}", file=sys.stderr)

if missing_paths or extra_paths or mismatched_paths:
    raise SystemExit(1)

print(
    f"Verified {len(actual_files)} fixture files from "
    f"{manifest['sourceRepo']}@{source_commit} "
    f"with {len(source_commit_overrides)} source commit override(s)."
)
PY
