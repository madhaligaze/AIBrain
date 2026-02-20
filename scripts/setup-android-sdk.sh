#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/workspace/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"

mkdir -p "${SDK_ROOT}/cmdline-tools"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

curl -fsSL "${CMDLINE_TOOLS_URL}" -o "${TMP_DIR}/cmdline-tools.zip"
unzip -q -o "${TMP_DIR}/cmdline-tools.zip" -d "${TMP_DIR}"

rm -rf "${SDK_ROOT}/cmdline-tools/latest"
mv "${TMP_DIR}/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"

export ANDROID_SDK_ROOT="${SDK_ROOT}"
yes | "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "build-tools;35.0.0"

printf 'sdk.dir=%s\n' "${SDK_ROOT}" > local.properties

echo "Android SDK installed at ${SDK_ROOT}"
echo "local.properties updated with sdk.dir=${SDK_ROOT}"
