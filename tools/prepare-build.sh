#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.."&&pwd)"; WD="$ROOT/gradle/wrapper"; JAR="$WD/gradle-wrapper.jar"
EXPECTED="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"; mkdir -p "$WD"
fetch(){ if command -v curl >/dev/null;then curl -fL "$1" -o "$2";else wget -O "$2" "$1";fi; }
[ -f "$JAR" ]||fetch "https://services.gradle.org/distributions/gradle-8.13-wrapper.jar" "$JAR"
if command -v sha256sum >/dev/null;then ACTUAL="$(sha256sum "$JAR"|awk '{print $1}')";else ACTUAL="$(shasum -a 256 "$JAR"|awk '{print $1}')";fi
[ "$ACTUAL" = "$EXPECTED" ]||{ rm -f "$JAR";echo "Hash invalido do wrapper" >&2;exit 1; }
[ -f "$ROOT/gradlew" ]||fetch "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradlew" "$ROOT/gradlew"
[ -f "$ROOT/gradlew.bat" ]||fetch "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradlew.bat" "$ROOT/gradlew.bat";chmod +x "$ROOT/gradlew"
if [ -z "${ANDROID_SDK_ROOT:-}" ]&&[ -z "${ANDROID_HOME:-}" ];then for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk";do [ -d "$c" ]&&export ANDROID_SDK_ROOT="$c"&&break;done;fi
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}";[ -n "$SDK" ]&&[ -f "$SDK/platforms/android-36/android.jar" ]||{ echo "Android SDK/API 36 nao encontrado." >&2;exit 1; }
printf 'sdk.dir=%s\n' "$SDK">"$ROOT/local.properties";cd "$ROOT";./gradlew --no-daemon --stacktrace assembleDebug;cp -f app/build/outputs/apk/debug/app-debug.apk RemoteLink-v0.2-alpha-debug.apk
echo "APK: $ROOT/RemoteLink-v0.2-alpha-debug.apk"
