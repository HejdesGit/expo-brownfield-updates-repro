#!/usr/bin/env bash
# Puts the emulator in the starting state for the recorded scenario:
# fresh install of the host APK, update A published, downloaded and running.
# Usage: scripts/scenario-setup.sh <apk> <agent-device command> [adb serial] [deep link]
# Pass repro://docs as the deep link to use DocsExampleActivity instead of MainActivity.
set -euo pipefail
APK="$1"; AD="$2"; SERIAL="${3:-emulator-5554}"; URL="${4:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
adb -s "$SERIAL" uninstall dev.repro.host >/dev/null 2>&1 || true
adb -s "$SERIAL" install "$APK" >/dev/null
"$ROOT/server/reset.sh"
"$ROOT/server/publish.sh" A
cold_start() { adb -s "$SERIAL" shell am force-stop dev.repro.host; "$AD" open dev.repro.host $URL >/dev/null; }
cold_start   # embedded launch, downloads A
sleep 6
cold_start   # launches A
sleep 4
