#!/usr/bin/env bash
# Builds the tiny test APKs used by the emulator tests (src/test/resources/apks/test-trash-v1.apk and -v2.apk).
# The APKs are committed, so this only needs to run when scripts/test-apk changes.
# Requires Docker; it uses the Android SDK inside the same image the emulator tests run.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
IMAGE=halimqarroum/docker-android:api-33
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -r "$ROOT/scripts/test-apk" "$WORK/src"

docker run --rm -v "$WORK:/work" --entrypoint bash "$IMAGE" -c "
  set -euo pipefail
  (yes || true) | sdkmanager --install 'build-tools;34.0.0' > /dev/null
  BT=/opt/android/build-tools/34.0.0
  cd /work
  keytool -genkeypair -keystore test.jks -storepass android -keypass android -alias test \
    -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Post-Update Analyzer test' > /dev/null 2>&1
  for V in 1 2; do
    rm -rf build && mkdir build
    \$BT/aapt2 compile --dir src/res -o build/res.zip
    \$BT/aapt2 link -o build/unaligned.apk -I /opt/android/platforms/android-33/android.jar \
      --manifest src/AndroidManifest.xml --version-code \$V --version-name \$V.0 \
      --min-sdk-version 21 --target-sdk-version 33 build/res.zip
    \$BT/zipalign -f 4 build/unaligned.apk build/aligned.apk
    \$BT/apksigner sign --ks test.jks --ks-pass pass:android --out test-trash-v\$V.apk build/aligned.apk
    \$BT/aapt2 dump badging test-trash-v\$V.apk | head -3
  done
  chown -R $(id -u):$(id -g) /work
"

mkdir -p "$ROOT/src/test/resources/apks"
cp "$WORK"/test-trash-v1.apk "$WORK"/test-trash-v2.apk "$ROOT/src/test/resources/apks/"
ls -la "$ROOT/src/test/resources/apks"
