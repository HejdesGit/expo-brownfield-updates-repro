#!/usr/bin/env bash
# Usage: ./publish.sh <label> [--broken]
# Exports the rn/ project for Android and stores it as the newest update for runtime version 1.
set -euo pipefail
LABEL="${1:?label required, e.g. A}"
BREAK=0
[[ "${2:-}" == "--broken" ]] && BREAK=1
HERE="$(cd "$(dirname "$0")" && pwd)"
RN="$HERE/../rn"
RUNTIME_VERSION=1
OUT="$HERE/updates/$RUNTIME_VERSION/$(date +%s)-$LABEL"
mkdir -p "$OUT"
(cd "$RN" && EXPO_PUBLIC_LABEL="$LABEL" EXPO_PUBLIC_BREAK="$BREAK" npx expo export --platform android --output-dir "$OUT" --clear >/dev/null)
(cd "$RN" && npx expo config --json --type public > "$OUT/expoConfig.json")
printf '{"createdAt":"%s","label":"%s","broken":%s}\n' "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" "$LABEL" "$([[ $BREAK == 1 ]] && echo true || echo false)" > "$OUT/published.json"
echo "Published update '$LABEL' (broken=$BREAK) to $OUT"
