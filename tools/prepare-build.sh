#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WRAPPER_DIR="$ROOT/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
EXPECTED_WRAPPER_SHA256="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

mkdir -p "$WRAPPER_DIR"

fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --retry-delay 1 "$1" --output "$2"
  else
    wget --tries=3 --output-document="$2" "$1"
  fi
}

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Baixando Gradle Wrapper 8.13..."
  fetch "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar" "$WRAPPER_JAR"
fi

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL="$(sha256sum "$WRAPPER_JAR" | awk '{print $1}')"
else
  ACTUAL="$(shasum -a 256 "$WRAPPER_JAR" | awk '{print $1}')"
fi

if [ "$ACTUAL" != "$EXPECTED_WRAPPER_SHA256" ]; then
  rm -f "$WRAPPER_JAR"
  echo "Hash invalido do gradle-wrapper.jar; arquivo removido por seguranca." >&2
  exit 1
fi

echo "[OK] Gradle Wrapper 8.13 verificado por SHA-256."

[ -f "$ROOT/gradlew" ] || fetch "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradlew" "$ROOT/gradlew"
[ -f "$ROOT/gradlew.bat" ] || fetch "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradlew.bat" "$ROOT/gradlew.bat"
chmod +x "$ROOT/gradlew"

if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -d "$candidate" ]; then
      export ANDROID_SDK_ROOT="$candidate"
      break
    fi
  done
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] || [ ! -f "$SDK/platforms/android-36/android.jar" ]; then
  echo "Android SDK/API 36 nao encontrado." >&2
  exit 1
fi

printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"

cd "$ROOT"
node --check app/src/main/assets/web/app.js
node --check app/src/main/assets/web/reconnect.js
./gradlew --no-daemon --stacktrace assembleDebug
cp -f app/build/outputs/apk/debug/app-debug.apk RemoteLink-v0.5-alpha-debug.apk

echo "APK: $ROOT/RemoteLink-v0.5-alpha-debug.apk"
