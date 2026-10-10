#!/usr/bin/env bash
set -euo pipefail
sdk_dir="$(cd "$(dirname "$0")/../.." && pwd)"
exec python3 "$sdk_dir/scripts/bazel/semantic_fixture.py" "$@"
