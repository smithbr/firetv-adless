#!/usr/bin/env bash
# Check the TV in one pass: the connection, its model and Fire OS, the
# launcher and Home, that every core package is on, how much of each
# debloat group is off, and free storage. Changes nothing; ends with the
# next steps. Exits 1 when a check failed.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./adb.sh

# Warn below this much free storage, in KB.
LOW_STORAGE_KB=1048576

result() {
    add_result "$1" "$2"
    case "$1" in
        PASS) _check "$2" ;;
        WARN) _caution "$2" ;;
        FAIL) _cross "$2" ;;
    esac
}

# Package names in a list file: every line but comments and blanks.
names() {
    grep -v -E '^[[:space:]]*(#|$)' "$1"
}

check_core() {
    local installed="$1" disabled="$2" pkg
    local -a off_core=() missing=()
    while read -r pkg; do
        if ! grep -qxF "package:${pkg}" <<< "${installed}"; then
            missing+=("${pkg}")
        elif grep -qxF "package:${pkg}" <<< "${disabled}"; then
            off_core+=("${pkg}")
            add_next_step "adb -s ${TV} shell pm enable ${pkg}"
        fi
    done < <(names packages-core.txt)
    if (( ${#off_core[@]} > 0 )); then
        result FAIL "Core packages off: ${off_core[*]}"
    else
        result PASS "Core packages on ($(names packages-core.txt | wc -l | tr -d ' '))"
    fi
    (( ${#missing[@]} == 0 )) || result WARN "Core packages not on this TV: ${missing[*]}"
}

# Per group of packages-disabled.txt: how many of those on this TV are off.
check_debloat() {
    local installed="$1" disabled="$2" line group="" present=0 off=0
    local -a groups=() counts=()
    while read -r line; do
        [[ -n "${line}" ]] || continue
        # A group starts at a one-word comment: "# telemetry".
        if [[ "${line}" =~ ^#\ ([a-z]+)$ ]]; then
            [[ -z "${group}" ]] || { groups+=("${group}"); counts+=("${off} ${present}"); }
            group="${BASH_REMATCH[1]}" present=0 off=0
            continue
        fi
        [[ "${line}" != \#* ]] || continue
        grep -qxF "package:${line}" <<< "${installed}" || continue
        present=$(( present + 1 ))
        grep -qxF "package:${line}" <<< "${disabled}" && off=$(( off + 1 ))
    done < packages-disabled.txt
    [[ -z "${group}" ]] || { groups+=("${group}"); counts+=("${off} ${present}"); }

    local i
    for i in "${!groups[@]}"; do
        read -r off present <<< "${counts[${i}]}"
        if (( present == 0 )); then
            result PASS "${groups[${i}]}: none on this TV"
        elif (( off < present )); then
            result WARN "${groups[${i}]}: ${off} of ${present} off"
            add_next_step "firetv tune"
        else
            result PASS "${groups[${i}]}: ${off} of ${present} off"
        fi
    done
}

check_launcher() {
    local installed="$1" home
    if ! grep -qxF "package:${LAUNCHER_PKG}" <<< "${installed}"; then
        result WARN "Launcher not installed"
        add_next_step "firetv install"
        return 0
    fi
    result PASS "Launcher installed"
    check_old_launchers "${installed}"
    home="$(tv shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME \
        | tr -d '\r' | sed -n 's/^ *packageName=//p' | head -1)"
    if [[ "${home}" == "${LAUNCHER_PKG}" ]]; then
        result PASS "Launcher is Home"
    elif grep -qxF "package:${HOF}" <<< "${installed}"; then
        result PASS "Home on Fire installed (opens the launcher from Amazon's Home)"
    else
        result WARN "Launcher is not Home"
        add_next_step "firetv install"
    fi
}

# Old names and debug or devicetest builds beside the release.
check_old_launchers() {
    local installed="$1" pkg
    local -a old=()
    while read -r pkg; do
        grep -qxF "package:${pkg}" <<< "${installed}" && old+=("${pkg}")
    done < <(names packages-old-launchers.txt)
    if (( ${#old[@]} > 0 )); then
        result WARN "Old launcher builds installed: ${old[*]}"
        add_next_step "firetv clean"
    fi
    return 0
}

check_storage() {
    local free_kb
    free_kb="$(tv shell df -k /data | tr -d '\r' | awk 'NR == 2 && $4 ~ /^[0-9]+$/ { print $4 }')"
    if [[ -z "${free_kb}" ]]; then
        result WARN "Could not read the TV's free storage"
    elif (( free_kb < LOW_STORAGE_KB )); then
        result WARN "Storage low: $(( free_kb / 1024 )) MB free"
        add_next_step "firetv clean"
    else
        result PASS "Storage: $(( free_kb / 1024 )) MB free"
    fi
}

main() {
    local installed disabled
    _header "Doctor: TV ${TV}"
    connect
    result PASS "Connected and authorized"
    result PASS "$(tv shell getprop ro.product.model | tr -d '\r'), $(tv shell getprop ro.build.version.name | tr -d '\r'), Android $(tv shell getprop ro.build.version.release | tr -d '\r')"

    installed="$(tv shell pm list packages | tr -d '\r')"
    disabled="$(tv shell pm list packages -d | tr -d '\r')"
    [[ -n "${installed}" ]] || die "Could not list the TV's packages"

    check_launcher "${installed}"
    check_core "${installed}" "${disabled}"
    check_debloat "${installed}" "${disabled}"
    check_storage

    add_next_step "Turn ADB debugging off on the TV when you are done"
    print_summary
}

main "$@"
