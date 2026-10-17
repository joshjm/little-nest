#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

check_only=false
if [[ "${1:-}" == "--check-only" ]]; then check_only=true; shift; fi
if [[ $# != 1 || ! "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: bash scripts/publish-release.sh [--check-only] VERSION" >&2
  exit 1
fi
version="$1"
tag="v$version"
apk="$PWD/artifacts/LittleNest.apk"
notes="releases/$tag.md"
tools="$ANDROID_HOME/build-tools/35.0.0"
[[ -s "$apk" && -s "$notes" ]] || { echo "Build artifacts/LittleNest.apk and write $notes first." >&2; exit 1; }

details="$("$tools/aapt" dump badging "$apk")"
name="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
code="$(sed -n 's/^[[:space:]]*versionCode \([0-9]*\).*/\1/p' app/build.gradle)"
[[ "$name" == "$version" && "$details" == *"name='app.littlenest' versionCode='$code' versionName='$version'"* ]] ||
  { echo "APK package/version does not match app/build.gradle and requested release." >&2; exit 1; }
certificate="$("$tools/apksigner" verify --print-certs "$apk")"
expected="f515ea3fc3b0bc907ed1cda72f5d982ece498f6b40a4fff730982f15bd4e7f5e"
grep -Fqx "Signer #1 certificate SHA-256 digest: $expected" <<< "$certificate" ||
  { echo "APK is not signed with Little Nest's original release certificate. Do not publish." >&2; exit 1; }

mkdir -p artifacts/release
cp "$apk" artifacts/release/LittleNest.apk
(cd artifacts/release && sha256sum LittleNest.apk > SHA256SUMS && sha256sum -c SHA256SUMS)
echo "Validated $tag (version code $code) with the original release certificate."
if [[ "$check_only" == true ]]; then exit 0; fi

[[ -z "$(git status --porcelain)" ]] || { echo "Commit source and documentation before publishing." >&2; exit 1; }
[[ "$(git rev-parse "$tag^{commit}")" == "$(git rev-parse HEAD)" ]] ||
  { echo "$tag must point to the current committed source." >&2; exit 1; }
repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
[[ "$(git ls-remote --tags origin "refs/tags/$tag" | cut -f1)" == "$(git rev-parse "$tag")" ]] ||
  { echo "Push $tag to origin before publishing." >&2; exit 1; }
gh release create "$tag" --repo "$repo" --verify-tag --latest \
  --title "Little Nest $version" --notes-file "$notes" \
  artifacts/release/LittleNest.apk artifacts/release/SHA256SUMS
