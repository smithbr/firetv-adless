# Shared by the tools: the TV's address, an adb wrapper, the lock that lets
# one script drive the TV at a time, and the connection check. The look of
# the output is ui.sh's.
# shellcheck shell=bash

# shellcheck source=ui.sh
source "$(dirname "${BASH_SOURCE[0]}")/ui.sh"

[[ -n "${FIRETV_HOST:-}" ]] || die "Set FIRETV_HOST to the TV's IP, e.g. export FIRETV_HOST=192.168.1.20"
TV="${FIRETV_HOST}"
[[ "${TV}" == *:* ]] || TV="${TV}:5555"

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

# "enabled", "disabled", or "absent".
state() {
    if ! tv shell pm list packages | tr -d '\r' | grep -qx "package:$1"; then printf 'absent'
    elif tv shell pm list packages -d | tr -d '\r' | grep -qx "package:$1"; then printf 'disabled'
    else printf 'enabled'; fi
}
