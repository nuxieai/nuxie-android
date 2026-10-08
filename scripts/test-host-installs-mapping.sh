#!/usr/bin/env bash
# Compile only the JNI operand mapper; no runtime library is linked.
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <runtime-header-directory> <jdk-include-directory>" >&2
  exit 64
fi
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
HEADERS="$(cd -- "$1" && pwd -P)"
JDK_INCLUDE="$(cd -- "$2" && pwd -P)"
case "$(uname -s)" in
  Darwin) JNI_PLATFORM=darwin ;;
  Linux) JNI_PLATFORM=linux ;;
  *) echo "Host mapper test requires macOS or Linux" >&2; exit 65 ;;
esac
for file in "$HEADERS/nux_capi.generated.h" "$JDK_INCLUDE/jni.h" "$JDK_INCLUDE/$JNI_PLATFORM/jni_md.h"; do
  if [[ ! -f "$file" ]]; then echo "Missing header: $file" >&2; exit 66; fi
done
BUILD_ROOT="$REPO_ROOT/nuxie-android/build/host-installs-mapping"
mkdir -p "$BUILD_ROOT"
TEST_DIR="$(mktemp -d "$BUILD_ROOT/test.XXXXXX")"
trap 'rm -rf -- "$TEST_DIR"' EXIT
"${CC:-clang}" -std=c11 -Wall -Wextra -Werror -fsanitize=address \
  -I "$HEADERS" -I "$JDK_INCLUDE" -I "$JDK_INCLUDE/$JNI_PLATFORM" \
  -I "$REPO_ROOT/nuxie-android/src/main/cpp" \
  "$REPO_ROOT/nuxie-android/src/test/cpp/host_installs_mapping_test.c" \
  -o "$TEST_DIR/host-installs-mapping-test"
"$TEST_DIR/host-installs-mapping-test"
