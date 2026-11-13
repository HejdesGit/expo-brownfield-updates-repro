#!/usr/bin/env bash
# Removes all published updates.
rm -rf "$(cd "$(dirname "$0")" && pwd)/updates"
