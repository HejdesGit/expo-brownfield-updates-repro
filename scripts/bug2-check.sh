#!/usr/bin/env bash
# Bug 2: builds the AAR in four configurations, lists the .so files in the published
# expo-updates AAR and builds the host app against it. Writes logs/bug2-*.txt.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR="$HOME/.m2/repository/host/exp/exponent/expo.modules.updates/58.0.22/expo.modules.updates-58.0.22.aar"
SUMMARY="$ROOT/logs/bug2-summary.txt"
: > "$SUMMARY"
run() { # $1 = name, rest = env assignments
  local name="$1"; shift
  echo "== $name ($*)" | tee -a "$SUMMARY"
  env "$@" "$ROOT/scripts/build-aar.sh" > "/tmp/bug2-$name-aar.log" 2>&1 || { echo "AAR build failed" | tee -a "$SUMMARY"; return; }
  echo "expo-updates AAR native libs:" >> "$SUMMARY"
  unzip -l "$AAR" | awk '/\.so$/ {print "  " $4}' >> "$SUMMARY"
  if unzip -l "$AAR" jni/arm64-v8a/libc++_shared.so >/dev/null 2>&1; then
    echo "md5 of jni/arm64-v8a/libc++_shared.so in the AAR: $(unzip -p "$AAR" jni/arm64-v8a/libc++_shared.so | md5 -q)" >> "$SUMMARY"
  fi
  if (cd "$ROOT/host" && ./gradlew assembleRelease --rerun-tasks > "/tmp/bug2-$name-host.log" 2>&1); then
    echo "host assembleRelease: SUCCESS" | tee -a "$SUMMARY"
  else
    echo "host assembleRelease: FAILED" | tee -a "$SUMMARY"
    sed -n '/What went wrong/,/^\* Try/p' "/tmp/bug2-$name-host.log" | sed "s#$HOME#~#g" >> "$SUMMARY"
  fi
  echo >> "$SUMMARY"
}
run default-ndk-unpatched
run ndk27.0-unpatched NDK_VERSION=27.0.12077973
run ndk27.0-pickfirst-removed NDK_VERSION=27.0.12077973 SO_FIX=1
run ndk27.0-pickfirst-androidtest-only NDK_VERSION=27.0.12077973 SO_FIX=2
echo "react-android 0.88.1 libc++_shared.so md5: $(unzip -p "$(ls ~/.gradle/caches/modules-2/files-2.1/com.facebook.react/react-android/0.88.1/*/react-android-0.88.1-release.aar)" jni/arm64-v8a/libc++_shared.so | md5 -q)" >> "$SUMMARY"
