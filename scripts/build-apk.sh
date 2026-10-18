#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x .local/tools/node_modules/node/bin/node ]]; then export PATH="$PWD/.local/tools/node_modules/node/bin:$PATH"; fi
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export NODE_ENV=production
if [[ "${1:-}" == "--api-url" ]]; then
  [[ "${2:-}" == https://*/api/v1 ]] || { echo 'HTTPS API ünvanı tələb olunur: https://domen/api/v1'; exit 1; }
  export EXPO_PUBLIC_API_URL="$2"
  export KAMPUS_HTTPS_ONLY=true EXPO_PUBLIC_DEPLOYED=true
fi
mkdir -p .local/signing
chmod 700 .local/signing
if [[ ! -f .local/signing/password ]]; then
  python3 - <<'SIGN'
import os,secrets
fd=os.open('.local/signing/password',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as f:f.write(secrets.token_hex(32))
SIGN
fi
export KAMPUS_KEYSTORE_FILE="$PWD/.local/signing/kampus-release.p12"
export KAMPUS_KEYSTORE_PASSWORD="$(cat .local/signing/password)"
if [[ ! -f "$KAMPUS_KEYSTORE_FILE" ]]; then
  keytool -genkeypair -keystore "$KAMPUS_KEYSTORE_FILE" -storetype PKCS12 -alias kampus -keyalg RSA -keysize 3072 -validity 10000 -storepass:env KAMPUS_KEYSTORE_PASSWORD -dname 'CN=Kampus Demo, O=Kampus, C=AZ' >/dev/null 2>&1
  chmod 600 "$KAMPUS_KEYSTORE_FILE"
fi
mkdir -p artifacts
cd apps/mobile
npx expo prebuild --platform android --no-install
cd android
./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a --no-daemon
cp app/build/outputs/apk/release/app-release.apk ../../../artifacts/kampus-demo.apk
cd ../../..
"$ANDROID_HOME/build-tools/37.0.0/apksigner" sign --ks "$KAMPUS_KEYSTORE_FILE" --ks-key-alias kampus --ks-pass env:KAMPUS_KEYSTORE_PASSWORD artifacts/kampus-demo.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify artifacts/kampus-demo.apk
shasum -a 256 artifacts/kampus-demo.apk > artifacts/kampus-demo.apk.sha256
echo "APK: artifacts/kampus-demo.apk"
echo "SHA256: artifacts/kampus-demo.apk.sha256"
