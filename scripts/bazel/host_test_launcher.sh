#!/usr/bin/env bash
set -euo pipefail
export RUNFILES_DIR="${RUNFILES_DIR:-$0.runfiles}"
"$RUNFILES_DIR/@native_launcher@" "$@"
exec "$RUNFILES_DIR/@verify_tests@" "${XML_OUTPUT_FILE:?Host tests require a JUnit XML report}"
