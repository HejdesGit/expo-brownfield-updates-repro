#!/usr/bin/env bash
# Classifies one rollback run from its logcat. Usage: scripts/classify-rollback.sh <pid> <logcat file>
# See scripts/run-rollback.sh for the meaning of RECOVERED, BLANK and INVALID.
set -euo pipefail
PID="$1"
awk -v pid="$PID" '
  $3 != pid { next }
  /Intentional module-scope error in a broken update/ && !crash { crash = $2 }
  /falling back to older update/ { fallback = 1 }
  fallback && /Restarting previously running React Native Surfaces/ { restarted = 1 }
  restarted && /Running "main"/ && !main { main = $2 }
  function secs(t,  a) { split(t, a, ":"); return a[1] * 3600 + a[2] * 60 + a[3] }
  END {
    if (pid == "" || !crash || !fallback || !restarted) { print "INVALID pid=" pid " crash=" (crash ? 1 : 0) " fallback=" fallback " reload=" restarted; exit }
    if (main) printf "RECOVERED pid=%s crash=%s main=%s after=%.3fs\n", pid, crash, main, secs(main) - secs(crash)
    else print "BLANK pid=" pid " crash=" crash
  }' "$2"
