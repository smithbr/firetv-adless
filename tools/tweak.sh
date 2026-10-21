#!/usr/bin/env bash
# Android settings tweaks from tweaks.txt, for firetv tune.
#   tweak.sh              list each tweak: on, off, or partly on
#   tweak.sh --tsv        the same as "NAME<tab>STATE<tab>DESCRIPTION" lines
#   tweak.sh NAME on|off  apply one; on saves the values it replaces, off
#                         puts them back
# The saved values live in ~/.local/state/firetv/tweaks-saved, as
# "NAMESPACE<tab>KEY<tab>VALUE" lines.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./adb.sh

SAVED="${XDG_STATE_HOME:-${HOME}/.local/state}/firetv/tweaks-saved"

# Values go into a shell command on the TV; only plain ones are written.
plain_value() { [[ "$1" =~ ^[A-Za-z0-9_.,:/+-]*$ ]]; }

rows() {
    grep -v -E '^[[:space:]]*(#|$)' tweaks.txt
}

setting_get() {
    tv shell settings get "$1" "$2" | tr -d '\r'
}

saved_value() {
    awk -F'\t' -v ns="$1" -v k="$2" '$1 == ns && $2 == k { v = $3; found = 1 } END { if (found) print v; exit !found }' \
        "${SAVED}" 2>/dev/null
}

list() {
    local tsv="$1" name ns key value about desc on total state
    local -a tweak_names=()
    while IFS= read -r name; do tweak_names+=("${name}"); done < <(rows | cut -f1 | awk '!seen[$0]++')
    for name in "${tweak_names[@]}"; do
        on=0 total=0 desc=""
        while IFS=$'\t' read -r _ ns key value about; do
            desc="${about}"
            total=$(( total + 1 ))
            [[ "$(setting_get "${ns}" "${key}")" != "${value}" ]] || on=$(( on + 1 ))
        done < <(rows | awk -F'\t' -v n="${name}" '$1 == n')
        if (( on == total )); then state=on; elif (( on == 0 )); then state=off; else state=partly; fi
        if [[ "${tsv}" == true ]]; then
            printf '%s\t%s\t%s\n' "${name}" "${state}" "${desc}"
        else
            printf '  %-18s %-7s %s\n' "${name}" "${state}" "${desc}"
        fi
    done
}

turn_on() {
    local ns="$1" key="$2" value="$3" current
    current="$(setting_get "${ns}" "${key}")"
    if ! saved_value "${ns}" "${key}" >/dev/null; then
        # A value that cannot be written back safely cannot be restored.
        if ! plain_value "${current}"; then
            _cross "${ns} ${key}: current value cannot be saved safely; left as is"
            return 0
        fi
        printf '%s\t%s\t%s\n' "${ns}" "${key}" "${current}" >> "${SAVED}"
    fi
    tv shell settings put "${ns}" "${key}" "${value}"
    _check "${ns} ${key}: ${current} -> ${value}"
}

turn_off() {
    local ns="$1" key="$2" current saved
    current="$(setting_get "${ns}" "${key}")"
    if ! saved="$(saved_value "${ns}" "${key}")"; then
        _skip "${ns} ${key}: no saved value; left at ${current}"
        return 0
    fi
    if [[ "${saved}" == null ]]; then
        tv shell settings delete "${ns}" "${key}" >/dev/null
    else
        tv shell settings put "${ns}" "${key}" "${saved}"
    fi
    awk -F'\t' -v ns="${ns}" -v k="${key}" '!($1 == ns && $2 == k)' "${SAVED}" > "${SAVED}.tmp"
    mv "${SAVED}.tmp" "${SAVED}"
    _check "${ns} ${key}: ${current} -> ${saved} (restored)"
}

apply() {
    local name="$1" action="$2" matched ns key value
    matched="$(rows | awk -F'\t' -v n="${name}" '$1 == n')"
    [[ -n "${matched}" ]] || die "Unknown tweak: ${name} (see firetv tune)"
    mkdir -p "$(dirname "${SAVED}")"
    while IFS=$'\t' read -r _ ns key value _; do
        if [[ "${action}" == on ]]; then
            turn_on "${ns}" "${key}" "${value}"
        else
            turn_off "${ns}" "${key}"
        fi
    done <<< "${matched}"
    _success "${name} ${action}."
}

main() {
    case "${1:-}" in
        "") connect; list false ;;
        --tsv) connect; list true ;;
        *)
            [[ "${2:-}" =~ ^(on|off)$ && $# -eq 2 ]] || die "Usage: firetv tune NAME on|off (firetv tune lists them)"
            connect
            apply "$1" "$2"
            ;;
    esac
}

main "$@"
