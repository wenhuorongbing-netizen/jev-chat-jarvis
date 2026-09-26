#!/usr/bin/env bash
# =============================================================================
# perm_churn.sh - overlay-permission churn regression script
#
# Purpose:
#   Hammer the SYSTEM_ALERT_WINDOW (overlay) permission of com.jev.probe:
#   revoke -> wait -> restore -> wait, N rounds. After each round, inspect
#   logcat (tag JEVASSIST) for "canDrawOverlays=false" lines and recovery
#   signs, and finally check whether the bubble window is rebuilt after the
#   permission comes back (dumpsys window windows | grep com.jev.probe).
#
# Usage (Git Bash on Windows):
#   bash tools/perm_churn.sh [rounds]
#     rounds: optional, default 10
#
# Prerequisites:
#   - adb reachable device with USB debugging on (adb devices shows it)
#   - com.jev.probe installed, overlay permission granted, bubble shown
#   - run from the repo root (paths are relative)
#
# Output: per-round PASS/FAIL plus a final summary and window check.
# =============================================================================

export MSYS_NO_PATHCONV=1

ADB="${ADB:-/c/Users/Jack/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="com.jev.probe"
TAG="JEVASSIST"
ROUNDS="${1:-10}"
SETTLE=3

pass=0
fail=0

if [ ! -x "$ADB" ] && ! command -v "$ADB" >/dev/null 2>&1; then
    echo "FATAL: adb not found at $ADB (set ADB env var to override)"
    exit 1
fi

echo "== perm_churn: $ROUNDS rounds on $PKG =="
"$ADB" devices | grep -q "device$" || { echo "FATAL: no adb device"; exit 1; }

for i in $(seq 1 "$ROUNDS"); do
    "$ADB" logcat -c   # clear buffer so counts are per-round

    # revoke overlay permission
    "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW ignore >/dev/null 2>&1
    sleep "$SETTLE"

    # restore overlay permission
    "$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
    sleep "$SETTLE"

    denied=$("$ADB" logcat -d -s "$TAG" 2>/dev/null | grep -c "canDrawOverlays=false")
    recovered=$("$ADB" logcat -d -s "$TAG" 2>/dev/null | grep -cE "canDrawOverlays=true|bubble|rebuild|reattach|show")

    if [ "$denied" -ge 1 ] && [ "$recovered" -ge 1 ]; then
        verdict="PASS"
        pass=$((pass + 1))
    else
        verdict="FAIL"
        fail=$((fail + 1))
    fi
    echo "round $i: denied_lines=$denied recovery_lines=$recovered -> $verdict"
done

echo
echo "== summary =="
echo "PASS=$pass FAIL=$fail (of $ROUNDS)"

# after final restore, is our window still there?
win_count=$("$ADB" shell dumpsys window windows 2>/dev/null | grep -c "$PKG")
if [ "$win_count" -ge 1 ]; then
    echo "window check: PASS ($win_count window line(s) for $PKG after restore)"
else
    echo "window check: FAIL (no $PKG window found after restore)"
fi
