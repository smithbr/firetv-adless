# sound: the Sound entry opens Fire TV's sound page. Fire OS shows it only
# where the launcher may start that page, so this runs on a TV that has it.
# shellcheck shell=bash disable=SC2016  # the awk conditions are single-quoted on purpose

scenario_sound() {
    local theme="$1" top
    [[ "${theme}" != DAPPLE_TV ]] || goto '$1 == "tab_settings"' || true
    if ! goto '$9 == "sound" || $9 == "snd"'; then
        record "${theme}" sound FAIL no-entry "could not move focus to Sound"
        shot "${theme}" sound
        return
    fi
    press CENTER
    sleep 2
    top="$(tv shell dumpsys activity activities | tr -d '\r' | grep -E 'mResumedActivity|topResumedActivity' | head -1)"
    tv shell input keyevent KEYCODE_BACK
    if [[ "${top}" == *SoundbarActivity* ]]; then
        record "${theme}" sound PASS opened "OK on Sound opened the sound page"
    else
        record "${theme}" sound FAIL not-opened "OK on Sound left ${top:-nothing} in front"
    fi
}
