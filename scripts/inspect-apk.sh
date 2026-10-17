#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
apk="${1:-$PWD/artifacts/LittleNest.apk}"
[[ -f "$apk" ]] || { echo "APK not found: $apk. Build it first." >&2; exit 1; }
mkdir -p artifacts
tooling/node_modules/.bin/rea inspect-android-package "$apk" --format json > artifacts/rea-package.json
tooling/node_modules/.bin/rea inspect-android-class "$apk" app.littlenest.OneDriveApi --format json > artifacts/rea-drive-class.json
tooling/node_modules/.bin/rea inspect-android-class "$apk" app.littlenest.NaraImport --format json > artifacts/rea-import-class.json
tooling/node_modules/.bin/rea inspect-android-class "$apk" app.littlenest.CarePredictor --format json > artifacts/rea-predictor-class.json
tooling/node_modules/.bin/rea inspect-android-class "$apk" app.littlenest.CareComparison --format json > artifacts/rea-comparison-class.json
tooling/node_modules/.bin/rea inspect-android-class "$apk" app.littlenest.CareReminders --format json > artifacts/rea-care-reminders-class.json
echo "REA/JADX artifact-bound evidence saved in artifacts/."
