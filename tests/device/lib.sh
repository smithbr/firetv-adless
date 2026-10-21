# Shared by tests/device: the devicetest app, the UI dump, keys, checks and
# results. Every function works on the TV named by tools/tv.sh.
# shellcheck shell=bash

DEVICE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${DEVICE_DIR}/../.." && pwd)"
# shellcheck source=../../tools/tv.sh
source "${ROOT}/tools/tv.sh"

# The throwaway build. Never the installed launcher, la.bsmith.launcher.
PKG="la.bsmith.launcher.devicetest"
ACTIVITY="${PKG}/la.bsmith.launcher.MainActivity"
# shellcheck disable=SC2034  # used by run.sh
APK="${ROOT}/app/build/outputs/apk/devicetest/app-devicetest.apk"

OUT="${DEVICE_OUT:-${DEVICE_DIR}/out}"
RESULTS="${OUT}/results.tsv"
DUMP="${OUT}/.ui.xml"
# Seconds to let focus and animations settle after a key, before a dump.
SETTLE="${SETTLE:-0.5}"
# How far navigate goes: moves along a row, and rows down.
MAX_COLS="${MAX_COLS:-6}"
MAX_ROWS="${MAX_ROWS:-4}"
FAVORITES=8

# The themes, from the enum, so a new theme is tested without editing this.
themes() {
    sed -nE 's/^    ([A-Z_]+)\(".*/\1/p' "${ROOT}/app/src/main/java/la/bsmith/launcher/Themes.kt"
}

# Width and height of the screen in pixels, as "W H".
screen_size() {
    tv shell wm size | tr -d '\r' | sed -nE 's/.*size: ([0-9]+)x([0-9]+).*/\1 \2/p' | tail -1
}

# Starts the theme from nothing: cleared data, FAVORITES favorites, the clock
# at 10:09 and no backdrop photo. Waits until something has focus.
launch() {
    local theme="$1"
    [[ "${PKG}" == *.devicetest ]] || die "refusing to clear ${PKG}"
    tv shell input keyevent KEYCODE_WAKEUP >/dev/null
    tv shell am force-stop "${PKG}"
    tv shell pm clear "${PKG}" >/dev/null
    restart --es theme "${theme}" --ei favorites "${FAVORITES}"
}

# Starts the app again on its saved data, with the clock and backdrop frozen.
restart() {
    tv shell am force-stop "${PKG}"
    tv shell am start -W -n "${ACTIVITY}" --ez freezeClock true --ez freezeBackdrop true "$@" >/dev/null
    local _
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        sleep 1
        dump
        [[ -n "$(focused)" ]] && return 0
    done
    return 1
}

# Presses keys by name (RIGHT, DOWN, CENTER, BACK...) in one adb call, then settles.
press() {
    local keys=() k
    for k in "$@"; do
        case "${k}" in
            UP|DOWN|LEFT|RIGHT|CENTER) keys+=("KEYCODE_DPAD_${k}") ;;
            *) keys+=("KEYCODE_${k}") ;;
        esac
    done
    tv shell input keyevent "${keys[@]}"
    sleep "${SETTLE}"
}

# Runs Monkey events (one per line) from one process on the TV.
monkey_run() {
    local script
    script="$(mktemp)"
    { printf 'type= raw events\ncount= 1\nspeed= 1.0\nstart data >>\n'; cat; } > "${script}"
    tv push "${script}" /sdcard/devicetest-monkey.txt >/dev/null 2>&1
    rm -f "${script}"
    tv shell monkey -f /sdcard/devicetest-monkey.txt 1 >/dev/null 2>&1
}

# Holds OK the way a remote does: a press, auto-repeats every 50 ms after
# the first 500 ms, and the release after $1 ms (default 1200).
hold_ok() {
    local hold="${1:-1200}" t=500
    {
        printf 'DispatchKey(0,0,0,23,0,0,-1,0)\nUserWait(500)\n'
        local repeat=1
        while (( t < hold )); do
            printf 'DispatchKey(0,0,0,23,%d,0,-1,0)\nUserWait(50)\n' "${repeat}"
            repeat=$(( repeat + 1 ))
            t=$(( t + 50 ))
        done
        printf 'DispatchKey(0,0,1,23,0,0,-1,0)\n'
    } | monkey_run
    sleep "${SETTLE}"
}

# Saves the UI tree of the focused window to DUMP. Retries: uiautomator gives
# up when the screen never goes idle.
dump() {
    local _
    for _ in 1 2 3; do
        if tv shell uiautomator dump /sdcard/devicetest-ui.xml 2>/dev/null | grep -q 'dumped to'; then
            tv exec-out cat /sdcard/devicetest-ui.xml > "${DUMP}"
            return 0
        fi
        sleep 0.5
    done
    : > "${DUMP}"
}

# One line per node in DUMP: resource-id, focused, text, content-desc, x1,
# y1, x2, y2 and label, tab-separated. The label is the first description or
# text (a description wins, as for a screen reader) in the node or in a later
# node centred inside it, since Compose puts a tile's text on a child; lower
# case, without Terminal's cursor marks.
nodes() {
    tr '<' '\n' < "${DUMP}" | grep '^node ' \
        | sed -nE 's/.* text="([^"]*)" resource-id="([^"]*)" .* content-desc="([^"]*)" .* focused="([^"]*)" .* bounds="\[(-?[0-9]+),(-?[0-9]+)\]\[(-?[0-9]+),(-?[0-9]+)\]".*/\2	\4	\1	\3	\5	\6	\7	\8/p' \
        | awk -F'\t' '
            function norm(s) {
                gsub(/&gt;/, ">", s); gsub(/&lt;/, "<", s); gsub(/&amp;/, "\\&", s); gsub(/&quot;/, "\"", s); gsub(/&apos;/, "'"'"'", s)
                gsub(/^[ >_$]+|[ _]+$/, "", s)
                return tolower(s)
            }
            { line[NR] = $0; for (k = 1; k <= 8; k++) f[NR, k] = $k; n = NR }
            END {
                for (i = 1; i <= n; i++) {
                    label = ""
                    for (j = i; j <= n && label == ""; j++) {
                        # By centre: a focused tile scales its content past its own bounds.
                        cx = (f[j, 5] + f[j, 7]) / 2; cy = (f[j, 6] + f[j, 8]) / 2
                        if (cx < f[i, 5] || cx > f[i, 7] || cy < f[i, 6] || cy > f[i, 8]) continue
                        label = norm(f[j, 4] != "" ? f[j, 4] : f[j, 3])
                    }
                    print line[i] "\t" label
                }
            }'
}

# The focused node: the smallest of the focused ones, since the view that
# hosts Compose reports focus too. Empty when nothing has focus.
focused() {
    nodes | awk -F'\t' '$2 == "true" { a = ($7 - $5) * ($8 - $6); if (best == "" || a < min) { min = a; best = $0 } } END { if (best != "") print best }'
}

# A node's label (see nodes).
label_of() {
    cut -f9 <<< "$1"
}

# The first node matching an awk condition on the fields of nodes(), such as
# '$1 == "tile"' or '$9 == "hidden apps"'.
find_node() {
    nodes | awk -F'\t' "$1 { print; exit }"
}

# Is there a tile with this label (any case)?
has_tile() {
    nodes | awk -F'\t' -v want="$(tr '[:upper:]' '[:lower:]' <<< "$1")" \
        '$1 == "tile" && $9 == want { found = 1 } END { exit !found }'
}

has_id() {
    nodes | awk -F'\t' -v id="$1" '$1 == id { found = 1 } END { exit !found }'
}

# Is the activity in front ours? (An OK that leaks through opens an app.)
in_front() {
    tv shell dumpsys activity activities | tr -d '\r' | grep -E 'mResumedActivity|topResumedActivity' | head -1 | grep -q "${PKG}/"
}

# Checks the focused node after a move: there is one, it lies inside the
# screen, and a tile is not clipped (narrower than the widest tile of its
# height, which happens when a row leaves it partly scrolled out of view).
# Prints nothing and succeeds, or prints the failed check and the details.
focus_problem() {
    local node size
    node="$(focused)"
    [[ -n "${node}" ]] || { printf 'focus-lost\tnothing has focus'; return; }
    size="$(screen_size_cached)"
    nodes | awk -F'\t' -v node="${node}" -v size="${size}" '
        BEGIN {
            split(node, f, "\t"); split(size, s, " ")
            fw = f[7] - f[5]; fh = f[8] - f[6]
        }
        $1 == "tile" && fh > 0 && ($8 - $6) > fh * 0.8 && ($8 - $6) < fh * 1.25 && ($7 - $5) > widest { widest = $7 - $5 }
        END {
            where = sprintf("%s \"%s\" at [%d,%d][%d,%d]", f[1] == "" ? "node" : f[1], f[9], f[5], f[6], f[7], f[8])
            if (fw <= 0 || fh <= 0) printf "focus-lost\t%s has no size", where
            else if (f[5] < 0 || f[6] < 0 || f[7] > s[1] || f[8] > s[2]) printf "off-screen\t%s on a %dx%d screen", where, s[1], s[2]
            else if (f[1] == "tile" && widest > 0 && fw < widest * 0.8) printf "clipped\t%s is %d wide, its row-mates %d", where, fw, widest
        }'
}

SCREEN=""
screen_size_cached() {
    [[ -n "${SCREEN}" ]] || SCREEN="$(screen_size)"
    printf '%s' "${SCREEN}"
}

# Moves focus to the first node matching an awk condition (see find_node),
# pressing the arrow that most closes the distance. When the node is not on
# screen yet, presses DOWN (three at a time) until it is. Fails after 30 presses.
goto() {
    local cond="$1" step target here dir last="" stuck=0
    for (( step = 0; step < 30; step++ )); do
        dump
        target="$(find_node "${cond}")"
        if [[ -z "${target}" ]]; then
            press DOWN DOWN DOWN
            continue
        fi
        here="$(focused)"
        [[ -n "${here}" ]] || { press RIGHT; continue; }
        awk -F'\t' "${cond} { found = 1 } END { exit !found }" <<< "${here}" && return 0
        dir="$(awk -F'\t' -v here="${here}" '
            BEGIN { split(here, h, "\t") }
            {
                tx = ($5 + $7) / 2; ty = ($6 + $8) / 2
                fx = (h[5] + h[7]) / 2; fy = (h[6] + h[8]) / 2
                if ((tx >= h[5] && tx <= h[7] && ty >= h[6] && ty <= h[8]) || (fx >= $5 && fx <= $7 && fy >= $6 && fy <= $8)) { print "HERE"; exit }
                dx = tx - fx; dy = ty - fy
                ady = dy < 0 ? -dy : dy
                if (ady > (h[8] - h[6]) / 2) print (dy > 0 ? "DOWN" : "UP"); else print (dx > 0 ? "RIGHT" : "LEFT")
            }' <<< "${target}")"
        [[ "${dir}" == HERE ]] && return 0
        # The same press twice with no move: try the other axis once.
        if [[ "${here}" == "${last}" ]]; then
            stuck=$(( stuck + 1 ))
            (( stuck < 3 )) || return 1
            case "${dir}" in UP|DOWN) dir=RIGHT ;; *) dir=DOWN ;; esac
        else
            stuck=0
        fi
        last="${here}"
        press "${dir}"
    done
    return 1
}

# Saves a screenshot to OUT/<theme>/<name>.png.
shot() {
    mkdir -p "${OUT}/$1"
    tv exec-out screencap -p > "${OUT}/$1/$2.png"
}

# Appends a result: theme, scenario, PASS/FAIL/SKIP, check, detail. A
# failure's signature, theme/scenario/check, is what the fix loop tracks.
record() {
    printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "${5:-}" >> "${RESULTS}"
}
