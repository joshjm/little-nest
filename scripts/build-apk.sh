#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
bash scripts/init-signing.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --console=plain
mkdir -p artifacts
cp app/build/outputs/apk/release/app-release.apk artifacts/LittleNest.apk
apksigner="$ANDROID_HOME/build-tools/35.0.0/apksigner"
"$apksigner" verify --verbose --print-certs artifacts/LittleNest.apk > artifacts/signing-certificate.txt
sha256sum artifacts/LittleNest.apk > artifacts/SHA256SUMS
echo "Installable APK: $PWD/artifacts/LittleNest.apk"
