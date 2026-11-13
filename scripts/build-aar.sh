#!/usr/bin/env bash
# Builds the release brownfield AAR from rn/ and publishes it to ~/.m2 (dev.repro:brownfield:1.0.0).
#
# Optional fixes (all off by default, so a plain run uses unpatched packages):
#   PR_50943=1         apply expo/expo#50943 to node_modules/expo-updates
#   VIEWFACTORY_FIX=1  replace the generated ReactNativeViewFactory.kt with fixes/viewfactory/
#   SO_FIX=1           remove the two pickFirst lines from expo-updates' build.gradle
#   SO_FIX=2           move those pickFirst lines to the androidTest component (proposed upstream fix)
#   PR_50943_DIAG=1    with PR_50943=1, also add the log lines from fixes/pr-50943/diagnostic-logging.patch
#
# NDK_VERSION=<version> builds the native code with another installed NDK than React Native's
# default (27.1.12297006), e.g. NDK_VERSION=27.0.12077973.
#
# Each run restores the original expo-updates files first (copies kept in
# rn/node_modules/.repro-pristine/), so earlier runs do not leak into later ones.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RN="$ROOT/rn"
UPDATES="$RN/node_modules/expo-updates"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# One ABI keeps the build short; the emulator is arm64. Override for x86_64 emulators.
export ORG_GRADLE_PROJECT_reactNativeArchitectures="${ARCHS:-arm64-v8a}"
if [[ -n "${NDK_VERSION:-}" ]]; then export ORG_GRADLE_PROJECT_ndkVersion="$NDK_VERSION"; fi

# The patches touch two files in node_modules/expo-updates. Keep a pristine copy of each and
# restore it before every build, then apply only the patches that were asked for.
PRISTINE="$RN/node_modules/.repro-pristine/expo-updates"
PATCHED_FILES=(
  android/build.gradle
  android/src/main/java/expo/modules/updates/procedures/RestartReactAppExtensions.kt
)
for f in "${PATCHED_FILES[@]}"; do
  if [[ ! -f "$PRISTINE/$f" ]]; then
    mkdir -p "$(dirname "$PRISTINE/$f")"; cp "$UPDATES/$f" "$PRISTINE/$f"
  fi
  cp "$PRISTINE/$f" "$UPDATES/$f"
done
apply_patch() { (cd "$UPDATES" && patch -p3 -s --forward < "$1"); echo "applied $(basename "$1")"; }

[[ "${PR_50943:-0}" == 1 ]] && apply_patch "$ROOT/fixes/pr-50943/expo-updates-restart.patch"
[[ "${PR_50943:-0}" == 1 && "${PR_50943_DIAG:-0}" == 1 ]] && apply_patch "$ROOT/fixes/pr-50943/diagnostic-logging.patch"
[[ "${SO_FIX:-0}" == 1 ]] && apply_patch "$ROOT/fixes/so-pickfirst/expo-updates-build-gradle.patch"
[[ "${SO_FIX:-0}" == 2 ]] && apply_patch "$ROOT/fixes/so-pickfirst/expo-updates-build-gradle-androidtest-only.patch"
true

cd "$RN"
npx expo prebuild --platform android --clean --no-install >/dev/null
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
if [[ "${VIEWFACTORY_FIX:-0}" == 1 ]]; then
  cp "$ROOT/fixes/viewfactory/ReactNativeViewFactory.kt" android/brownfield/src/main/java/dev/repro/brownfield/
  echo "using fixes/viewfactory/ReactNativeViewFactory.kt"
fi

npx expo-brownfield build:android --release --verbose
echo "Published dev.repro:brownfield:1.0.0 (PR_50943=${PR_50943:-0} VIEWFACTORY_FIX=${VIEWFACTORY_FIX:-0} SO_FIX=${SO_FIX:-0} PR_50943_DIAG=${PR_50943_DIAG:-0} NDK_VERSION=${NDK_VERSION:-default})"
