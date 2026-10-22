# receiver: the theme over adb, through launcher_theme as firetv theme and
# install_launcher use it. Reads the theme, switches it with a Light or Dark,
# takes names from before the themes were renamed, refuses an unknown theme
# without changing anything or stopping the app, and hides an answer whose
# Light or Dark the TV's shell would run.
# shellcheck shell=bash

# launcher_theme (tools/adb.sh), sent to the devicetest build.
receiver() { LAUNCHER_PKG="${PKG}" launcher_theme "$@"; }

# Is ROW's Light or Dark VARIANT in the answer?
#   has_variant ANSWER ROW VARIANT
has_variant() {
    awk -F'\t' -v n="$2" -v v="$3" 'NR > 1 && $1 == n && $3 == v { found = 1 } END { exit !found }' <<< "$1"
}

scenario_receiver() {
    local theme="$1" other answer pid
    answer="$(receiver)"
    if [[ "${answer%%$'\n'*}" != "${theme}" ]]; then
        record "${theme}" receiver FAIL read "expected ${theme} as the current theme, got \"${answer%%$'\n'*}\""
        return
    fi

    other="$(themes | awk -v t="${theme}" '$0 != t && o == "" { o = $0 } END { print o }')"
    answer="$(receiver --es theme "${other}" --es "variant.${other}" Dark)"
    if [[ "${answer%%$'\n'*}" != "${other}" ]] || ! has_variant "${answer}" "${other}" Dark; then
        record "${theme}" receiver FAIL switch "asked for ${other} in Dark, got: ${answer//$'\n'/ | }"
        return
    fi

    answer="$(receiver --es theme APPLE_TV --es variant.SWITCH Dark)"
    if [[ "${answer%%$'\n'*}" != DAPPLE_TV ]] || ! has_variant "${answer}" SNITCH Dark; then
        record "${theme}" receiver FAIL legacy "APPLE_TV and variant.SWITCH should set DAPPLE_TV and SNITCH, got: ${answer//$'\n'/ | }"
        return
    fi

    pid="$(tv shell pidof "${PKG}" | tr -d '\r')"
    answer="$(receiver --es theme NO_SUCH_THEME)"
    if [[ -n "${answer}" ]]; then
        record "${theme}" receiver FAIL unknown "an unknown theme was accepted: ${answer%%$'\n'*}"
        return
    fi
    answer="$(receiver)"
    if [[ "${answer%%$'\n'*}" != DAPPLE_TV ]]; then
        record "${theme}" receiver FAIL unknown-changed "an unknown theme changed it to \"${answer%%$'\n'*}\""
        return
    fi
    if [[ "$(tv shell pidof "${PKG}" | tr -d '\r')" != "${pid}" ]]; then
        record "${theme}" receiver FAIL unknown-restarted "the app's process changed after an unknown theme"
        return
    fi

    # The inner quotes reach the TV's shell, so the launcher saves Dark;x.
    answer="$(receiver --es variant.SNITCH "'Dark;x'")"
    if [[ -n "${answer}" ]]; then
        record "${theme}" receiver FAIL unsafe "a Light or Dark with ; in it was passed on: ${answer//$'\n'/ | }"
        return
    fi
    answer="$(receiver --es variant.SNITCH Light)"
    if ! has_variant "${answer}" SNITCH Light; then
        record "${theme}" receiver FAIL unsafe-reset "setting Light after Dark;x gave: ${answer//$'\n'/ | }"
        return
    fi
    record "${theme}" receiver PASS receiver "read, switched to ${other}, took legacy names, refused an unknown theme and an unsafe answer"
}
