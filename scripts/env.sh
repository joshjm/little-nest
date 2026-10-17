#!/usr/bin/env bash

export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/baby-app-tools/android-sdk}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
if [[ -z "${JAVA_HOME:-}" ]] && command -v mise >/dev/null 2>&1; then
  JAVA_HOME="$(mise where java@temurin-17 2>/dev/null || true)"
  export JAVA_HOME
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH"
export REA_JADX_MCP_JAR="${REA_JADX_MCP_JAR:-$HOME/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar}"
