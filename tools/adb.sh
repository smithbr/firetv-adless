# Shared by the tools: the TV's address, an adb wrapper, the lock that lets
# one script drive the TV at a time, and the connection check. The look of
# the output is ui.sh's.
# shellcheck shell=bash

# shellcheck source=ui.sh
source "$(dirname "${BASH_SOURCE[0]}")/ui.sh"

# The TV: FIRETV_HOST (IP, or IP:port), else the one ask_host saved, else
# asked for now.
FIRETV_HOST_FILE="${XDG_CONFIG_HOME:-${HOME}/.config}/firetv/host"

# Sets TV, the address adb uses, from a host; adb's port is 5555.
set_tv() {
    TV="$1"
    [[ "${TV}" == *:* ]] || TV="${TV}:5555"
}

# Asks for the TV's IP, offering the current one, and saves it so later
# runs need not ask. FIRETV_HOST_ASKED tells the caller it was just typed.
ask_host() {
    local host="" header="TV IP (Settings > My Fire TV > About > Network)"
    [[ -t 0 || "$(prompt_mode)" == gum ]] || die "No TV set up: export FIRETV_HOST=<tv-ip>, or run firetv setup"
    host="$(ask_text "${header}" "192.168.1.20" "${FIRETV_HOST:-}")" || exit 1
    host="${host//[[:space:]]/}"
    [[ -n "${host}" ]] || die "No TV IP given"
    mkdir -p "$(dirname "${FIRETV_HOST_FILE}")"
    printf '%s\n' "${host}" > "${FIRETV_HOST_FILE}"
    export FIRETV_HOST="${host}"
    # shellcheck disable=SC2034  # read by firetv
    FIRETV_HOST_ASKED=true
    set_tv "${host}"
}

[[ -n "${FIRETV_HOST:-}" ]] || FIRETV_HOST="$(cat "${FIRETV_HOST_FILE}" 2>/dev/null || true)"
if [[ -n "${FIRETV_HOST}" ]]; then
    # Exported, so the scripts this one runs use the same TV without asking.
    export FIRETV_HOST
    set_tv "${FIRETV_HOST}"
else
    ask_host
fi

tv() { adb -s "${TV}" "$@" < /dev/null; }

# One script drives the TV at a time. The lock is a directory (mkdir is
# atomic) holding the owner's PID; a lock whose owner has exited is cleared.
# /tmp/claude, not $TMPDIR, so that every shell and every Claude session
# (whose sandbox can write only there) sees the same lock.
LOCK="${FIRETV_LOCK:-/tmp/claude/firetv.lock}"
LOCK_WAIT="${FIRETV_LOCK_WAIT:-600}"

# True unless the PID is gone. A sandboxed shell may not signal another
# process at all, so "not permitted" counts as alive.
alive() {
    local err
    err="$(kill -0 "$1" 2>&1)" && return 0
    [[ "${err}" != *"No such process"* ]]
}

lock() {
    # A script started by the holder (run.sh calling install.sh) shares its lock.
    [[ -n "${FIRETV_LOCKED:-}" && "$(cat "${LOCK}/pid" 2>/dev/null)" == "${FIRETV_LOCKED}" ]] && return 0
    mkdir -p "$(dirname "${LOCK}")"
    local waited=0 holder
    until mkdir "${LOCK}" 2>/dev/null; do
        holder="$(cat "${LOCK}/pid" 2>/dev/null || true)"
        # No PID a minute after the mkdir: the owner died before writing it.
        if { [[ -n "${holder}" ]] && ! alive "${holder}"; } \
            || { [[ -z "${holder}" ]] && [[ -n "$(find "${LOCK}" -maxdepth 0 -mmin +1 2>/dev/null)" ]]; }; then
            rm -rf "${LOCK}"
            continue
        fi
        (( waited < LOCK_WAIT )) || die "The TV is in use by $(cat "${LOCK}/owner" 2>/dev/null || echo "PID ${holder:-?}"); gave up after ${LOCK_WAIT}s (lock: ${LOCK})"
        (( waited > 0 )) || log_info "Waiting for the TV: in use by $(cat "${LOCK}/owner" 2>/dev/null || echo "PID ${holder:-?}")" >&2
        sleep 2
        waited=$(( waited + 2 ))
    done
    printf '%s\n' "$$" > "${LOCK}/pid"
    printf '%s (PID %s, since %s)\n' "$(basename "$0")" "$$" "$(date '+%H:%M:%S')" > "${LOCK}/owner"
    export FIRETV_LOCKED="$$"
    trap unlock EXIT
}

unlock() {
    [[ "$(cat "${LOCK}/pid" 2>/dev/null)" == "$$" ]] && rm -rf "${LOCK}"
    return 0
}

# adb connect with its stderr folded into stdout, so the spinner keeps the
# terminal's stderr.
adb_connect() {
    # shellcheck disable=SC2016  # $1 expands in the inner sh
    spin_out "Connecting to ${TV}" sh -c 'adb connect "$1" 2>&1' _ "${TV}" || true
}

# Takes the lock, then checks the TV is reachable. Every tool calls this
# before its first adb command.
connect() {
    lock
    command -v adb >/dev/null 2>&1 || die "adb not found: brew install --cask android-platform-tools"
    local out
    out="$(adb_connect)"
    # macOS answers "No route to host" when the process that started the adb
    # server has no Local Network permission (a sandbox, an app, launchd).
    # A server started from this shell inherits this shell's permission.
    if [[ "${out}" == *"No route to host"* ]]; then
        adb kill-server >/dev/null 2>&1 || true
        out="$(adb_connect)"
        [[ "${out}" != *"No route to host"* ]] \
            || die "No route to the TV at ${TV}. If it answers ping, this terminal app needs Local Network access: System Settings > Privacy & Security > Local Network, then reopen the terminal"
    fi
    [[ "$(tv get-state 2>/dev/null)" == device ]] || die "The TV at ${TV} is not connected and authorized (ADB debugging on? accept the prompt on the TV)"
}

# The release key's fingerprint as dumpsys package prints it under
# signatures: the hex of Arrays.hashCode over its certificate. A launcher
# showing another was signed with another key (the debug-key releases up to
# v0.2, or a build from another computer) and cannot be updated in place.
# shellcheck disable=SC2034  # read by firetv
LAUNCHER_SIGNATURE="e0fe6ef5"

# The installed launcher's signing-key fingerprints, comma-separated, or
# nothing when it is not installed. Anchored on version: so the empty past
# signatures list that follows never matches.
launcher_signature() {
    tv shell dumpsys package la.bsmith.launcher | tr -d '\r' \
        | sed -n 's/.* version:[0-9]*, signatures:\[\([0-9a-f,]*\)\].*/\1/p' | head -1
}

# Installs the launcher's APK. A launcher on the TV signed with another key
# cannot be updated in place, so it is replaced, asking first: that clears
# its favorites, hidden apps and theme. --replace says the caller already
# found the other key and the user agreed, so it is replaced straight away.
# When it is Home through make-home.sh, Amazon's home stands in while it is
# replaced.
#   install_launcher [--replace] APK
install_launcher() {
    local apk replace=false out was_home=false tools
    if [[ "${1:-}" == --replace ]]; then
        replace=true
        shift
    fi
    apk="$1"
    tools="$(dirname "${BASH_SOURCE[0]}")"
    if [[ "${replace}" == false ]]; then
        # shellcheck disable=SC2016  # $1 and $2 expand in the inner sh
        out="$(spin_out "Installing the launcher on ${TV}" sh -c 'adb -s "$1" install -r "$2" 2>&1' _ "${TV}" "${apk}")" && return 0
        if [[ "${out}" != *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
            printf '%s\n' "${out}" >&2
            die "Could not install the launcher"
        fi
        log_warn "The launcher on the TV is signed with a different key (an older release, or a build from another computer), so it cannot be updated in place."
        confirm "Replace it? Its favorites, hidden apps and theme are cleared." || die "Not installed; the old launcher is still on the TV"
    fi
    if [[ "$(state com.amazon.tv.launcher)" == disabled ]]; then
        was_home=true
        "${tools}/restore-home.sh"
    fi
    tv uninstall la.bsmith.launcher | tr -d '\r'
    # shellcheck disable=SC2016  # $1 and $2 expand in the inner sh
    out="$(spin_out "Installing the launcher on ${TV}" sh -c 'adb -s "$1" install "$2" 2>&1' _ "${TV}" "${apk}")" \
        || { printf '%s\n' "${out}" >&2; die "Could not install the launcher"; }
    [[ "${was_home}" == false ]] || "${tools}/make-home.sh"
}

# "enabled", "disabled", or "absent".
state() {
    if ! tv shell pm list packages | tr -d '\r' | grep -qx "package:$1"; then printf 'absent'
    elif tv shell pm list packages -d | tr -d '\r' | grep -qx "package:$1"; then printf 'disabled'
    else printf 'enabled'; fi
}
