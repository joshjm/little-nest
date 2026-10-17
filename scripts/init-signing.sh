#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
key="${NEST_KEYSTORE:-$HOME/.local/share/little-nest/signing/release.jks}"
password="${NEST_KEYSTORE_PASSWORD_FILE:-$HOME/.local/share/little-nest/signing/password}"
if [[ -f "$key" ]]; then
  [[ -f "$password" ]] || { echo "Signing key exists but its password file is missing. Restore it; do not generate a replacement key." >&2; exit 1; }
  echo "Keeping existing signing key: $key"
  exit 0
fi
if [[ -f "$password" ]]; then
  echo "Password file exists without its key. Restore the key before continuing." >&2
  exit 1
fi
command -v openssl >/dev/null || { echo "OpenSSL is required to generate a strong signing password." >&2; exit 1; }
umask 077
mkdir -p "$(dirname "$key")" "$(dirname "$password")"
openssl rand -hex 32 > "$password"
keytool -genkeypair -keystore "$key" -storetype PKCS12 -alias little-nest \
  -storepass:file "$password" -keypass:file "$password" -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=Little Nest Personal, OU=Family, O=Little Nest"
echo "Private signing material saved outside the project. Back up both files securely."
echo "Key: $key"
echo "Password file: $password"
