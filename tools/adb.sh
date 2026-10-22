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
# An empty or malformed answer is asked again under gum (Esc quits) and
# ends the run with plain reads.
ask_host() {
    local host="" problem header="TV IP (Settings > My Fire TV > About > Network)"
    [[ -t 0 || "$(prompt_mode)" == gum ]] || die "No TV set up: export FIRETV_HOST=<tv-ip>, or run firetv setup"
    # gum asks again after a bad answer; a plain question stops, so piped
    # input cannot loop.
    while true; do
        host="$(ask_text "${header}" "192.168.1.20" "${FIRETV_HOST:-}")" || exit 1
        host="${host//[[:space:]]/}"
        if [[ -z "${host}" ]]; then
            problem="No TV IP given"
        elif [[ "${host}" == -* || ! "${host}" =~ ^[A-Za-z0-9.:-]+$ ]]; then
            problem="Not an IP or IP:port: ${host}"
        else
            break
        fi
        [[ "$(prompt_mode)" == gum ]] || die "${problem}"
        log_warn "${problem}"
    done
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

# Packages from packages-disabled.txt that Fire OS refused to disable
# ("Cannot disable a protected package"), one per line. tune adds to it and
# no longer offers them; doctor does not count them against debloat.
PROTECTED="${XDG_STATE_HOME:-${HOME}/.local/state}/firetv/protected"
is_protected() { grep -qxF "$1" "${PROTECTED}" 2>/dev/null; }

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
    [[ "$(tv get-state 2>/dev/null)" == device ]] \
        || die "The TV at ${TV} is not connected and authorized (ADB debugging on? accept the prompt on the TV). If its IP changed: run firetv setup, set FIRETV_HOST, or delete ${FIRETV_HOST_FILE}"
}

# The release key's fingerprint as dumpsys package prints it under
# signatures: the hex of Arrays.hashCode over its certificate. A launcher
# showing another was signed with another key (the debug-key releases up to
# v0.2, or a build from another computer) and cannot be updated in place.
LAUNCHER_SIGNATURE="e0fe6ef5"

# The installed launcher's signing-key fingerprints, comma-separated, or
# nothing when it is not installed or dumpsys shows none. Android 9 and
# later print "PackageSignatures{a6762fc version:2, signatures:[74975648],
# past signatures:[]}": anchored on version: so the empty past signatures
# list never matches. Fire OS 6 and 7 print "PackageSignatures{a6762fc
# [74975648]}". Several signers print "[a, b]".
launcher_signature() {
    tv shell dumpsys package la.bsmith.launcher | tr -d '\r' \
        | sed -n -e 's/.* version:[0-9]*, signatures:\[\([0-9a-f, ]*\)\].*/\1/p' \
            -e 's/.*PackageSignatures{[0-9a-f]* \[\([0-9a-f, ]*\)\]}.*/\1/p' \
        | tr -d ' ' | head -1
}

# Stops unless every signer of a downloaded APK is the release key pinned
# in app/release-key.sha256. The Release workflow checks the same, but
# whoever can add a file to a release can skip the workflow, and a TV
# without the launcher, or one whose launcher is being replaced, takes an
# APK signed with any key. apk-signers.py reads the signers, since a
# computer without the Android SDK has no apksigner.
check_release_signer() {
    local apk="$1" tools pinned signers
    tools="$(dirname "${BASH_SOURCE[0]}")"
    pinned="$(tr -d '[:space:]' 2>/dev/null < "${tools}/../app/release-key.sha256" || true)"
    [[ -n "${pinned}" ]] || die "No app/release-key.sha256 to check $(basename "${apk}") against"
    command -v python3 >/dev/null 2>&1 \
        || die "Checking $(basename "${apk}") needs python3. On a Mac, install the command line developer tools: xcode-select --install"
    signers="$(python3 -I "${tools}/apk-signers.py" "${apk}")" \
        || die "Could not read the signer of $(basename "${apk}"), so it was not installed"
    [[ "$(printf '%s\n' "${signers}" | sort -u)" == "${pinned}" ]] \
        || die "$(basename "${apk}") is not signed with the release key in app/release-key.sha256, so it was not installed. Its signers: $(printf '%s\n' "${signers}" | sort -u | tr '\n' ' ')"
}

# Runs a command for at most SECONDS (macOS has no timeout), then stops
# it: for a command that can hang on the network. Fails when it fails or
# runs out of time. Stops only the command's own process, so it must not
# start children that outlive it.
#   out="$(within SECONDS COMMAND...)"
within() {
    local tenths=$(( $1 * 10 )) pid
    shift
    "$@" &
    pid=$!
    while kill -0 "${pid}" 2>/dev/null; do
        if (( tenths-- <= 0 )); then
            kill "${pid}" 2>/dev/null || true
            wait "${pid}" 2>/dev/null || true
            return 1
        fi
        sleep 0.1
    done
    wait "${pid}"
}

# True when the TV's adb port takes a TCP connection within 2 seconds. adb
# connect waits over a minute on an IP where nothing answers.
tv_answers() {
    # shellcheck disable=SC2016  # $1 and $2 expand in the inner bash
    within 2 "${BASH}" -c ': <> "/dev/tcp/$1/$2"' _ "${TV%:*}" "${TV##*:}" >/dev/null 2>&1
}

# versionName of an installed package, or empty.
version() {
    tv shell dumpsys package "$1" | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1
}

# The latest release's version (0.3), or nothing when GitHub cannot be
# reached. gh reaches a private repo; once it is public, the latest-release
# page's redirect names the tag. gh has no timeout of its own.
latest_version() {
    local tag=""
    if command -v gh >/dev/null 2>&1; then
        tag="$(within 5 gh release view --repo "${REPO}" --json tagName --jq .tagName 2>/dev/null || true)"
    fi
    if [[ -z "${tag}" ]]; then
        tag="$(curl -fsSI --max-time 5 "https://github.com/${REPO}/releases/latest" 2>/dev/null \
            | tr -d '\r' | sed -n 's|^[Ll]ocation: .*/releases/tag/||p' | head -1 || true)"
    fi
    printf '%s\n' "${tag#v}"
}

# True when version $1 is older than $2 (0.9 is older than 0.10).
older() {
    [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$1" ]]
}

# Reads the TV's launcher against the latest release, for firetv and
# doctor.sh: INSTALLED (empty when not installed), LATEST (empty when GitHub
# cannot be reached), and OLD_KEY, true only when dumpsys shows its key and
# that key is not the release key. A key it cannot read is not an old key.
# shellcheck disable=SC2034  # read by firetv and doctor.sh
read_launcher() {
    local signature
    INSTALLED="$(version la.bsmith.launcher)"
    LATEST="$(latest_version)"
    signature="$(launcher_signature)" || signature=""
    OLD_KEY=false
    if [[ -n "${signature}" && ",${signature}," != *",${LAUNCHER_SIGNATURE},"* ]]; then
        OLD_KEY=true
    fi
}

# The launcher's theme, as its ThemeReceiver answers: the current theme's
# name on the first line, then "NAME<tab>label<tab>variant" per theme.
# EXTRAs set it first (--es theme NAME, --es variant.NAME Dark). Nothing
# when the launcher is absent or older than the receiver, when the receiver
# fails (an unknown theme, say), or when a name or Light or Dark is not
# plain letters and underscores: callers send those back through adb shell,
# whose shell on the TV would run anything else in them, and a launcher
# with another key may be any app. Flag 32 includes a launcher not yet
# opened since it was installed.
#   launcher_theme [EXTRA...]
launcher_theme() {
    local out line row=$'^[A-Z_]+\t[^\t]*\t[A-Za-z]*$'
    out="$(tv shell am broadcast -f 32 -n "${LAUNCHER_PKG}/la.bsmith.launcher.ThemeReceiver" "$@" 2>/dev/null | tr -d '\r' || true)"
    [[ "${out}" == *'result=0, data="'* ]] || return 0
    out="${out#*result=0, data=\"}"
    out="${out%\"}"
    [[ "${out%%$'\n'*}" =~ ^[A-Z_]+$ ]] || return 0
    while IFS= read -r line; do
        [[ "${line}" =~ ${row} ]] || return 0
    done < <(tail -n +2 <<< "${out}")
    printf '%s\n' "${out}"
}

# Gives the launcher back a theme read by launcher_theme before it was
# replaced: the theme, and each theme's Light or Dark. When none was read
# (the old launcher was too old to answer), asks which theme to use. Warns
# when the launcher refuses it: the receiver fails on a theme it has no
# name for, such as one a newer build removed.
#   restore_theme SAVED
restore_theme() {
    local saved="$1" theme name label variant
    local -a extras=() themes=()
    if [[ -n "${saved}" ]]; then
        extras=(--es theme "${saved%%$'\n'*}")
        while IFS=$'\t' read -r name label variant; do
            [[ -z "${variant}" ]] || extras+=(--es "variant.${name}" "${variant}")
        done < <(tail -n +2 <<< "${saved}")
    else
        saved="$(launcher_theme)"
        [[ -n "${saved}" ]] || return 0
        while IFS=$'\t' read -r name label variant; do
            themes+=("${label}"$'\t'"${name}")
        done < <(tail -n +2 <<< "${saved}")
        theme="$(choose_one "The old launcher's theme could not be read. Which theme?" "${saved%%$'\n'*}" "${themes[@]}")"
        extras=(--es theme "${theme}")
    fi
    [[ -n "$(launcher_theme "${extras[@]}")" ]] || log_warn "Could not set the launcher's theme; pick it in the launcher's Settings."
}

# Installs the launcher's APK, updating the TV's launcher in place when it
# can. When the TV refuses for the signing key: if the TV's launcher has the
# release key, the APK does not, so nothing changes. Otherwise the TV's
# launcher has another key (an older release, or a build from another
# computer) and is replaced, asking first: that clears its favorites and
# hidden apps, and the theme is put back. --replace says the user already
# agreed, so it is replaced without asking. When it is Home through
# make-home.sh, Amazon's home stands in while it is replaced, and it is made
# Home again after.
#   install_launcher [--replace] APK
install_launcher() {
    local apk replace=false out was_home=false tools saved
    if [[ "${1:-}" == --replace ]]; then
        replace=true
        shift
    fi
    apk="$1"
    tools="$(dirname "${BASH_SOURCE[0]}")"
    # shellcheck disable=SC2016  # $1 and $2 expand in the inner sh
    out="$(spin_out "Installing the launcher on ${TV}" sh -c 'adb -s "$1" install -r "$2" 2>&1' _ "${TV}" "${apk}")" && return 0
    if [[ "${out}" != *INSTALL_FAILED_UPDATE_INCOMPATIBLE* && "${out}" != *INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES* ]]; then
        printf '%s\n' "${out}" >&2
        die "Could not install the launcher"
    fi
    [[ ",$(launcher_signature)," != *",${LAUNCHER_SIGNATURE},"* ]] \
        || die "$(basename "${apk}") is not signed with the release key and the launcher on the TV is, so it was left unchanged. To install this build anyway (a local build without the release key), remove the launcher first, which clears its settings: firetv reset, or adb -s ${TV} uninstall la.bsmith.launcher"
    if [[ "${replace}" == false ]]; then
        log_warn "The launcher on the TV is signed with a different key (an older release, or a build from another computer), so it cannot be updated in place."
        confirm "Replace it? Its favorites and hidden apps are cleared." || die "Not installed; the old launcher is still on the TV"
    fi
    saved="$(launcher_theme)"
    if [[ "$(state com.amazon.tv.launcher)" == disabled ]]; then
        was_home=true
        "${tools}/restore-home.sh"
    fi
    tv uninstall la.bsmith.launcher | tr -d '\r'
    # shellcheck disable=SC2016  # $1 and $2 expand in the inner sh
    out="$(spin_out "Installing the launcher on ${TV}" sh -c 'adb -s "$1" install "$2" 2>&1' _ "${TV}" "${apk}")" \
        || { printf '%s\n' "${out}" >&2; die "Could not install the launcher"; }
    restore_theme "${saved}"
    # The user agreed to all of it, so make-home.sh need not ask again.
    [[ "${was_home}" == false ]] || "${tools}/make-home.sh" --yes
}

# "enabled", "disabled", or "absent".
state() {
    if ! tv shell pm list packages | tr -d '\r' | grep -qx "package:$1"; then printf 'absent'
    elif tv shell pm list packages -d | tr -d '\r' | grep -qx "package:$1"; then printf 'disabled'
    else printf 'enabled'; fi
}
