#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

for tool in curl unzip npm; do
  command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }
done
if ! command -v java >/dev/null 2>&1; then
  command -v mise >/dev/null || { echo "Install a full JDK 17+ (or mise) and rerun this script." >&2; exit 1; }
  mise install java@temurin-17
  export JAVA_HOME
  JAVA_HOME="$(mise where java@temurin-17)"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v javac >/dev/null || { echo "A full JDK including javac is required." >&2; exit 1; }
downloads="$HOME/.local/share/baby-app-tools/downloads"
mkdir -p "$downloads"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$downloads/android-cli.zip"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q "$downloads/android-cli.zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
echo "Accepting Android SDK licenses for this user-local SDK."
set +o pipefail
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null
license_status="${PIPESTATUS[1]}"
set -o pipefail
if [[ "$license_status" != 0 ]]; then echo "Android license acceptance failed." >&2; exit 1; fi
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
npm --prefix tooling ci --no-audit --no-fund
mkdir -p "$(dirname "$REA_JADX_MCP_JAR")"
if [[ ! -f "$REA_JADX_MCP_JAR" ]]; then
  curl -fL --retry 3 https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar -o "$REA_JADX_MCP_JAR"
fi
printf '6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75  %s\n' "$REA_JADX_MCP_JAR" | sha256sum -c -
tooling/node_modules/.bin/rea doctor --provider jadx --format json
echo "Ready. Build with: bash scripts/build-apk.sh"
