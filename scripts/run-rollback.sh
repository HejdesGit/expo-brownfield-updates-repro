#!/usr/bin/env bash
# Runs the rollback scenario once and classifies the result from logcat.
# Usage: scripts/run-rollback.sh <apk> <agent-device command> <adb serial> [deep link]
#
# Prints one line: RECOVERED, BLANK or INVALID, plus the pid and (for RECOVERED) the time from the
# crash to the next `Running "main"` in the same process.
#   RECOVERED  B threw, expo-updates fell back, the host reloaded, and `Running "main"` followed in
#              the same pid, from the fallback bundle (not B's failing run).
#   BLANK      the same sequence, but no `Running "main"` after the reload in that pid.
#   INVALID    the sequence did not happen as expected (B not launched, no fallback, process died).
# Logcat of the app's processes goes to $LOGCAT_OUT (default /tmp/rollback-raw.txt); a screenshot of the final screen
# goes to $SHOT_OUT if set.
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
# Keep only lines from the app's own processes; system lines can name other installed apps.
adb -s "$S" logcat -d | awk '
  { lines[NR] = $0; pid[NR] = $3 }
  /BridgelessReact|ReactNativeJS|dev\.expo\.updates|BrownfieldSurface/ { app[$3] = 1 }
  END { for (i = 1; i <= NR; i++) if (pid[i] in app) print lines[i] }' > "$OUT"
if [ -n "${SHOT_OUT:-}" ]; then "$AD" screenshot "$SHOT_OUT" >/dev/null || true; fi

"$ROOT/scripts/classify-rollback.sh" "$PID" "$OUT"
