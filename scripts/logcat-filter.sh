#!/usr/bin/env bash
# Shortens a raw logcat dump to the lines that matter for the rollback scenario.
# Usage: scripts/logcat-filter.sh < raw-logcat.txt
grep -E 'ReactHost|ReactNativeJS|UpdatesErrorRecovery|ErrorRecovery|Updates state change|BrownfieldSurface|AndroidRuntime|handleHostException|Running "|ReactNative ' \
  | grep -vE 'adbd' \
  | sed -E 's/, context = \{.*$//; s/\{"timestamp":[0-9]+,"message":"//; s/","code":"[^"]*","level":"[^"]*"\}$//' \
  | cut -c1-260
