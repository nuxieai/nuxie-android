#!/usr/bin/env bash
# Exercise the production helper with a controlled JVM table and real pthreads.
set -euo pipefail
if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <jdk-include-directory>" >&2
  exit 64
fi
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
JDK_INCLUDE="$(cd -- "$1" && pwd -P)"
case "$(uname -s)" in
  Darwin) JNI_PLATFORM=darwin ;;
  Linux) JNI_PLATFORM=linux ;;
  *) echo "JNI attachment tests require macOS or Linux" >&2; exit 65 ;;
esac
BUILD_ROOT="$REPO_ROOT/nuxie-android/build/jni-attachment"
mkdir -p "$BUILD_ROOT"
TEST_DIR="$(mktemp -d "$BUILD_ROOT/test.XXXXXX")"
trap 'rm -rf -- "$TEST_DIR"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined \
  -I "$JDK_INCLUDE" -I "$JDK_INCLUDE/$JNI_PLATFORM" \
  "$REPO_ROOT/nuxie-android/src/test/cpp/jni_attachment_test.c" \
  -o "$TEST_DIR/jni-attachment-test"
# Each process starts with a fresh pthread_once, including key-creation failure.
for scenario in concurrent owned get-env-error attach-error key-error tls-error; do
  "$TEST_DIR/jni-attachment-test" "$scenario"
done
