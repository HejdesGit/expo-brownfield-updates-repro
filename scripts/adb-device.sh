#!/usr/bin/env bash
# Minimal stand-in for the device command the scenario scripts take as their <agent-device command>
# argument. It implements the two subcommands they use:
#   adb-device.sh open <package> [deep link]   start the app (or open the deep link in it)
#   adb-device.sh screenshot <file.png>        save a screenshot
# Uses $ANDROID_SERIAL if set.
set -euo pipefail
cmd="$1"; shift
case "$cmd" in
  open)
    pkg="$1"; url="${2:-}"
    if [ -n "$url" ]; then
      adb shell am start -W -a android.intent.action.VIEW -d "$url" "$pkg" >/dev/null
    else
      adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    fi
    ;;
  screenshot)
    adb exec-out screencap -p > "$1"
    ;;
  *)
    echo "unsupported: $cmd" >&2; exit 2
    ;;
esac
