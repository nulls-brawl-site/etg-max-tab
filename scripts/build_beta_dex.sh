#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/dex_beta/build"
SRC="$ROOT/dex_beta/src"
CLASSES="$BUILD/classes"
D8_OUT="$BUILD/d8"
OUT="$BUILD/etg-max-bridge-beta.dex"

ANDROID_JAR="${ANDROID_JAR:-}"
D8_JAR="${D8_JAR:-$ROOT/build/r8.jar}"

mkdir -p "$CLASSES"
rm -rf "$CLASSES" "$D8_OUT" "$OUT"
mkdir -p "$CLASSES"

if [[ -z "$ANDROID_JAR" ]]; then
  for p in \
    "$ANDROID_HOME/platforms/android-35/android.jar" \
    "$ANDROID_HOME/platforms/android-34/android.jar" \
    "/usr/lib/android-sdk/platforms/android-35/android.jar" \
    "/usr/lib/android-sdk/platforms/android-34/android.jar" \
    "/usr/lib/android-sdk/platforms/android-23/android.jar"; do
    if [[ -f "$p" ]]; then
      ANDROID_JAR="$p"
      break
    fi
  done
fi

if [[ -z "$ANDROID_JAR" || ! -f "$ANDROID_JAR" ]]; then
  echo "ANDROID_JAR is required. Install Android SDK platform or set ANDROID_JAR=/path/android.jar" >&2
  exit 2
fi

if [[ ! -f "$D8_JAR" ]]; then
  echo "Downloading R8/D8..."
  curl -fsSL "https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.1.31/r8-9.1.31.jar" -o "$D8_JAR"
fi

javac --release 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$CLASSES" $(find "$SRC" -name '*.java' | sort)
mkdir -p "$D8_OUT"
java -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 23 --lib "$ANDROID_JAR" --output "$D8_OUT" $(find "$CLASSES" -name '*.class' | sort)
mv "$D8_OUT/classes.dex" "$OUT"
sha256sum "$OUT" | tee "$BUILD/etg-max-bridge-beta.dex.sha256"
