#!/usr/bin/env bash
# Frame statistics while Monkey replays 24 moves across the favorites row
# (3 right, 3 left, four times, 450 ms apart) from one process, so the key
# injection itself costs the TV almost nothing. Prints dumpsys gfxinfo.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./lib.sh

LABEL="${1:-bench}"
SCRIPT="$(mktemp)"
{
    printf 'type= raw events\ncount= 1\nspeed= 1.0\nstart data >>\n'
    for _ in 1 2 3 4; do
        for _ in 1 2 3; do printf 'DispatchPress(KEYCODE_DPAD_RIGHT)\nUserWait(450)\n'; done
        for _ in 1 2 3; do printf 'DispatchPress(KEYCODE_DPAD_LEFT)\nUserWait(450)\n'; done
    done
} > "${SCRIPT}"
connect
tv push "${SCRIPT}" /sdcard/launcher-bench.txt >/dev/null
rm -f "${SCRIPT}"
tv shell input keyevent KEYCODE_WAKEUP >/dev/null
tv shell am force-stop la.bsmith.launcher
tv shell am start -n la.bsmith.launcher/.MainActivity >/dev/null 2>&1
sleep 6
tv shell dumpsys gfxinfo la.bsmith.launcher reset >/dev/null
tv shell monkey -f /sdcard/launcher-bench.txt 1 >/dev/null 2>&1
sleep 1.5
tv shell dumpsys gfxinfo la.bsmith.launcher | tr -d '\r' \
    | grep -E 'Total frames|Janky frames|50th percentile|90th percentile|99th percentile|Number Slow UI' \
    | sed "s/^/[${LABEL}] /"
