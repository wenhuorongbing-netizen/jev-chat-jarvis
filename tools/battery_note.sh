#!/usr/bin/env bash
# =============================================================================
# battery_note.sh - battery-drain comparison snapshot for com.jev.probe
#
# Purpose:
#   Reset batterystats, let the app run normally for a while, then dump the
#   batterystats section for com.jev.probe so drain can be compared across
#   builds / configurations.
#
# Usage (Git Bash on Windows):
#   bash tools/battery_note.sh            # interactive: asks you to use the
#                                         # phone ~10 min, then press Enter
#   bash tools/battery_note.sh 600        # non-interactive: sleeps 600 s
#
# Prerequisites:
#   - adb reachable device with USB debugging on
#   - com.jev.probe installed and in use during the measurement window
#   - run from the repo root (paths are relative)
#
# Note: batterystats numbers are per-device estimates; compare only against
# runs on the same device with a similar usage pattern.
# =============================================================================

export MSYS_NO_PATHCONV=1

ADB="${ADB:-/c/Users/Jack/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="com.jev.probe"

if [ ! -x "$ADB" ] && ! command -v "$ADB" >/dev/null 2>&1; then
    echo "FATAL: adb not found at $ADB (set ADB env var to override)"
    exit 1
fi

"$ADB" devices | grep -q "device$" || { echo "FATAL: no adb device"; exit 1; }

echo "== resetting batterystats =="
"$ADB" shell dumpsys batterystats --reset

if [ -n "$1" ]; then
    echo "sleeping ${1}s while you use the phone normally..."
    sleep "$1"
else
    echo "Now use the phone normally for about 10 minutes (chat with the bubble active)."
    read -r -p "Press Enter when done... "
fi

echo "== batterystats section for $PKG =="
"$ADB" shell dumpsys batterystats 2>/dev/null | grep -i -A 6 "$PKG" | head -60

echo
echo "Done. Re-run on the same device with a similar usage pattern to compare."
