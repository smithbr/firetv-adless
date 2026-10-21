# What the tools add to lib.sh: output helpers and the repo the launcher's
# releases come from. lib.sh is a verbatim copy of
# scripts/common.sh in the author's dotfiles: never edit it here, copy it
# over again. A helper here that a dotfiles script comes to need moves there.
# shellcheck shell=bash

# Sourced once, by adb.sh or by a script that also sources adb.sh.
[[ -z "${_UI_SH:-}" ]] || return 0
_UI_SH=1

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# shellcheck disable=SC2034  # used by the scripts that source this
REPO="smithbr/firetv-adless"
# The launcher's package, and Home on Fire's, which hands Home to it.
# shellcheck disable=SC2034  # used by the scripts that source this
LAUNCHER_PKG="la.bsmith.launcher"
# shellcheck disable=SC2034  # used by the scripts that source this
HOF="io.github.toolicious.homeonfire"

# Like spin, but the command's stdout passes through, for capturing it:
#   packages="$(spin_out "Reading the TV's packages" adb shell pm list packages)"
# The title goes to stderr, so it never lands in the capture. Once the
# command ends, the title stays as a ✓ or ✗ line with the output below it,
# indented on screen.
spin_out() {
    local title="$1"
    shift

    if command -v gum >/dev/null 2>&1 && [[ -t 2 && "$(type -t "$1" 2>/dev/null)" != "function" ]]; then
        local out status=0
        out="$(gum spin --spinner dot --show-output --title "${title}" --padding="0 1" -- "$@" < /dev/null)" || status=$?
        if ((status == 0)); then
            _paint "${HAS_COLOR_ERR}" '32' "✓ ${title}" "ok: ${title}" >&2
        else
            _paint "${HAS_COLOR_ERR}" '31' "✗ ${title}" "failed: ${title}" >&2
        fi
        # Indented under its step on screen; as is when captured.
        if [[ -z "${out}" ]]; then
            :
        elif [[ -t 1 ]]; then
            printf '%s\n' "${out}" | sed 's/^/  /'
        else
            printf '%s\n' "${out}"
        fi
        return "${status}"
    else
        log_info "${title}" >&2
        "$@" < /dev/null
    fi
}
