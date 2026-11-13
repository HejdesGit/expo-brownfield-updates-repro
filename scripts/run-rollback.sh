#!/usr/bin/env bash
# Runs the rollback scenario once and reports whether the embedded surface came back in the
# same process. Usage: scripts/run-rollback.sh <apk> <agent-device command> <adb serial> [deep link]
# Prints: RECOVERED or BLANK, plus the pid. Raw logcat goes to $LOGCAT_OUT (default /tmp/rollback-raw.txt).
set -euo pipefail
APK="$1"; AD="$2"; S="$3"; URL="${4:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${LOGCAT_OUT:-/tmp/rollback-raw.txt}"
"$ROOT/scripts/scenario-setup.sh" "$APK" "$AD" "$S" "$URL" >/dev/null 2>&1
cs() { adb -s "$S" shell am force-stop dev.repro.host; "$AD" open dev.repro.host $URL >/dev/null; }
"$ROOT/server/publish.sh" B --broken >/dev/null
cs; sleep 6                       # A runs, B downloads
adb -s "$S" logcat -c
cs; sleep 12                      # B throws, error recovery falls back to A
PID="$(adb -s "$S" shell pidof dev.repro.host || true)"
adb -s "$S" logcat -d > "$OUT"
# Did "Running \"main\"" happen in this pid after the reload restarted surfaces?
if awk -v pid="$PID" '$3==pid && /Restarting previously running React Native Surfaces/ {r=1} r && $3==pid && /Running "main"/ {f=1} END {exit !f}' "$OUT"; then
  echo "RECOVERED pid=$PID"
else
  echo "BLANK pid=$PID"
fi
