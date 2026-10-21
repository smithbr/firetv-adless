# What the tools' output adds to lib.sh. lib.sh is a verbatim copy of
# scripts/common.sh in the author's dotfiles: never edit it here, copy it
# over again. A helper here that a dotfiles script comes to need moves there.
# shellcheck shell=bash

# Sourced once, by tv.sh or by a script that also sources tv.sh.
[[ -z "${_UI_SH:-}" ]] || return 0
_UI_SH=1

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Like spin, but the command's stdout passes through, for capturing it:
#   packages="$(spin_out "Reading the TV's packages" adb shell pm list packages)"
# The title goes to stderr, so it never lands in the capture.
spin_out() {
    local title="$1"
    shift

    if command -v gum >/dev/null 2>&1 && [[ -t 2 && "$(type -t "$1" 2>/dev/null)" != "function" ]]; then
        gum spin --spinner dot --show-output --title "${title}" --padding="0 1" -- "$@" < /dev/null
    else
        log_info "${title}" >&2
        "$@" < /dev/null
    fi
}
