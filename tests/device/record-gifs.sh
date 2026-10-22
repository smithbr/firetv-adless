#!/usr/bin/env bash
# Records the README's GIFs on the TV with the devicetest build (run.sh
# builds it): docs/gifs/tour.gif, a few moves in every theme, and
# docs/gifs/menu.gif, holding OK on a favorite. Also shoots the Light look of
# each theme that starts Dark as docs/screenshots/<slug>-light.png, which
# publish-screenshots.sh adds to the README's table. Holds the TV lock
# (tools/adb.sh) throughout; needs ffmpeg.
#
#   record-gifs.sh [--width 960] [--fps 12]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WIDTH=960
FPS=12
while (( $# )); do
    case "$1" in
        --width) WIDTH="${2:?--width needs pixels}"; shift ;;
        --fps) FPS="${2:?--fps needs a number}"; shift ;;
        -h|--help) sed -n '2,9s/^# \{0,1\}//p' "$0"; exit 0 ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
    shift
done
command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg not found: brew install ffmpeg"
[[ -f "${APK}" ]] || die "no ${APK}: run tests/device/run.sh first"

# Themes that start Dark and have a Light look.
LIGHT_THEMES=(SNITCH GAME_QUEEN EDITORIAL)

GIFS="${ROOT}/docs/gifs"
SHOTS="${ROOT}/docs/screenshots"
CLIPS="${OUT}/gifs"
REMOTE=/sdcard/devicetest.mp4

# Presses keys PACE ms apart from one Monkey run, since an adb keyevent
# takes a second or more on a Fire TV.
PACE=650
moves() {
    local k code
    for k in "$@"; do
        case "${k}" in
            UP) code=19 ;; DOWN) code=20 ;; LEFT) code=21 ;; RIGHT) code=22 ;; BACK) code=4 ;;
            *) die "no key named ${k}" ;;
        esac
        printf 'DispatchKey(0,0,0,%d,0,0,-1,0)\nDispatchKey(0,0,1,%d,0,0,-1,0)\nUserWait(%d)\n' "${code}" "${code}" "${PACE}"
    done | monkey_run
}

# The moves for each theme in the tour: along its row, then down to what is
# under it. Game Queen switches to Light halfway.
tour_DAPPLE_TV() { moves RIGHT RIGHT UP RIGHT DOWN RIGHT; }
tour_TERMINAL() { moves DOWN DOWN DOWN DOWN DOWN; }
tour_SNITCH() { moves RIGHT RIGHT RIGHT DOWN RIGHT RIGHT; }
tour_GAME_QUEEN() {
    moves RIGHT RIGHT DOWN
    LAUNCHER_PKG="${PKG}" launcher_theme --es variant.GAME_QUEEN Light >/dev/null
    sleep 0.5
    moves RIGHT DOWN
}
tour_AMBIENT() { sleep 1; moves RIGHT RIGHT RIGHT DOWN; }
tour_EDITORIAL() { moves RIGHT RIGHT RIGHT RIGHT DOWN; }

# Holds OK on the first favorite, moves through the menu, and closes it.
menu() { hold_ok; sleep 0.3; moves DOWN UP BACK; }

# Records the screen while a command runs, to CLIPS/NAME.mp4. screenrecord
# writes a frame only when the screen changes, so the GIF's fps filter fills
# the still parts.
#   clip NAME COMMAND...
clip() {
    local name="$1" pid
    shift
    tv shell rm -f "${REMOTE}"
    tv shell screenrecord --size 1280x720 --bit-rate 8000000 "${REMOTE}" &
    pid=$!
    sleep 1
    "$@"
    sleep 1
    tv shell pkill -INT screenrecord || true
    wait "${pid}" || true
    # The MP4 is finished after screenrecord exits.
    sleep 1
    tv pull "${REMOTE}" "${CLIPS}/${name}.mp4" >/dev/null 2>&1 || die "could not pull the ${name} recording"
    tv shell rm -f "${REMOTE}"
}

# Writes a GIF from MP4s, played one after another, with a palette made for
# it. Each clip holds its last frame a second, since a recording ends on the
# last change.
#   gif OUT.gif IN.mp4...
gif() {
    local out="$1" inputs=() chains="" labels="" i=0 f
    shift
    for f in "$@"; do
        inputs+=(-i "${f}")
        chains+="[${i}:v]fps=${FPS},scale=${WIDTH}:-2:flags=lanczos,setsar=1,tpad=stop_mode=clone:stop_duration=1[v${i}];"
        labels+="[v${i}]"
        i=$(( i + 1 ))
    done
    ffmpeg -v error -y "${inputs[@]}" -filter_complex \
        "${chains}${labels}concat=n=${i}:v=1[v];[v]split[a][b];[a]palettegen=stats_mode=full[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
        "${out}"
}

connect
mkdir -p "${CLIPS}" "${GIFS}" "${SHOTS}"
rm -f "${CLIPS}"/*.mp4
tv install -r "${APK}" | tr -d '\r' | grep -v '^Performing' || true

tour=()
while read -r theme; do
    printf 'Recording %s\n' "${theme}" >&2
    launch "${theme}" || die "${theme}: nothing had focus 10 s after starting"
    clip "${theme}" "tour_${theme}"
    tour+=("${CLIPS}/${theme}.mp4")
done < <(themes)

printf 'Recording the menu\n' >&2
launch DAPPLE_TV || die "DAPPLE_TV: nothing had focus 10 s after starting"
clip menu menu

for theme in "${LIGHT_THEMES[@]}"; do
    printf 'Shooting %s in Light\n' "${theme}" >&2
    launch "${theme}" || die "${theme}: nothing had focus 10 s after starting"
    [[ -n "$(LAUNCHER_PKG="${PKG}" launcher_theme --es "variant.${theme}" Light)" ]] || die "${theme}: the launcher refused Light"
    restart || die "${theme}: nothing had focus 10 s after restarting in Light"
    shot "${theme}" light
    slug="$(tr '[:upper:]_' '[:lower:]-' <<< "${theme}")"
    sips --resampleWidth 1280 "${OUT}/${theme}/light.png" --out "${SHOTS}/${slug}-light.png" >/dev/null
done

# Leave the TV on its own home screen.
tv shell am force-stop "${PKG}"
tv shell input keyevent KEYCODE_HOME >/dev/null

gif "${GIFS}/tour.gif" "${tour[@]}"
gif "${GIFS}/menu.gif" "${CLIPS}/menu.mp4"
printf 'Updated %s and %s\n' "${GIFS#"${ROOT}"/}/" "${SHOTS#"${ROOT}"/}/" >&2
