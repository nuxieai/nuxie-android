#!/usr/bin/env bash
set -euo pipefail
export RUNFILES_DIR="${RUNFILES_DIR:-$0.runfiles}"
if [[ ! -d "$RUNFILES_DIR" ]]; then
  echo "Host renderer requires its declared Bazel runfiles" >&2
  exit 1
fi
export NUXIE_HOST_CAPI_LIB="$RUNFILES_DIR/@capi@"
exec "$RUNFILES_DIR/@binary@" \
  "--jvm_flag=-Dnuxie.host.jni.lib=$RUNFILES_DIR/@jni@" \
  "--jvm_flag=-Dnuxie.repo.root=$RUNFILES_DIR/@workspace@" "$@"
