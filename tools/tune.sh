#!/usr/bin/env bash
# Debloat and tweaks in one checklist: the packages in packages-disabled.txt
# and the tweaks in tweaks.txt (tweak.sh). A tick applies it: a ticked
# package is disabled, a ticked tweak is on. Lists every change and asks
# before applying them.
#   tune.sh               the checklist in a terminal, else lists the tweaks
#   tune.sh NAME on|off   apply one tweak (tweak.sh)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

(( $# == 0 )) || exec ./tweak.sh "$@"
[[ -t 0 && -t 1 ]] || exec ./tweak.sh

source ./adb.sh

connect

# Package rows, as firetv debloat built them. Two adb calls for the whole
# list; state() would take two per package.
all="$(spin_out "Reading the TV's packages" adb -s "${TV}" shell pm list packages | tr -d '\r')"
off="$(tv shell pm list packages -d | tr -d '\r')"
group="" missing="" width=0 gwidth=0
pkgs=() groups=() states=()
while read -r line; do
    [[ -n "${line}" ]] || continue
    if [[ "${line}" == \#* ]]; then
        group="${line#\# }"
        continue
    fi
    pkg="${line}"
    if grep -qxF "${pkg}" packages-core.txt; then
        log_warn "${pkg} is a core package (packages-core.txt); never disabled"
        continue
    fi
    if ! grep -qxF "package:${pkg}" <<< "${all}"; then
        missing="${missing} ${pkg}"
        continue
    fi
    st=enabled
    grep -qxF "package:${pkg}" <<< "${off}" && st=disabled
    pkgs+=("${pkg}")
    groups+=("${group}")
    states+=("${st}")
    (( ${#pkg} <= width )) || width=${#pkg}
    (( ${#group} <= gwidth )) || gwidth=${#group}
done < packages-disabled.txt
[[ -z "${missing}" ]] || log_info "Not on this TV:${missing}"
(( ${#pkgs[@]} > 0 )) || log_warn "None of packages-disabled.txt is on this TV"

# Tweak rows: NAME<tab>STATE<tab>DESCRIPTION.
tweaks="$(spin_out "Reading the TV's settings" ./tweak.sh --tsv)"

# Columns wide enough for the tweaks too.
while IFS=$'\t' read -r name st _; do
    (( ${#name} <= width )) || width=${#name}
    (( ${#st} <= gwidth )) || gwidth=${#st}
done <<< "${tweaks}"

# Every package starts ticked; a tweak starts ticked when it is on. gum
# cannot relabel a row as it is toggled, so a package row says what Enter
# does either way.
opts=()
selected=""
for index in "${!pkgs[@]}"; do
    what="off → on if unticked"
    [[ "${states[${index}]}" == enabled ]] && what="on → off if ticked"
    opts+=("$(printf '%-7s  %-*s  %-*s  %s' package "${width}" "${pkgs[${index}]}" "${gwidth}" "${groups[${index}]}" "${what}")"$'\t'"pkg:${pkgs[${index}]}")
    selected="${selected} pkg:${pkgs[${index}]}"
done
while IFS=$'\t' read -r name st desc; do
    [[ -n "${name}" ]] || continue
    opts+=("$(printf '%-7s  %-*s  %-*s  %s' tweak "${width}" "${name}" "${gwidth}" "${st}" "${desc}")"$'\t'"tweak:${name}")
    [[ "${st}" != on ]] || selected="${selected} tweak:${name}"
done <<< "${tweaks}"
(( ${#opts[@]} > 0 )) || die "Nothing to tune on this TV"

picked="$(choose_many --selected "${selected}" "A tick applies it (package disabled, tweak on). Packages start ticked." "${opts[@]}")" || exit 0

# Ticked packages that are enabled get disabled, unticked ones that are
# disabled get enabled. A ticked tweak not fully on is turned on, an
# unticked one not fully off is turned off.
disable="" enable="" tweak_on="" tweak_off=""
for index in "${!pkgs[@]}"; do
    pkg="${pkgs[${index}]}"
    if grep -qxF "pkg:${pkg}" <<< "${picked}"; then
        [[ "${states[${index}]}" == enabled ]] && disable="${disable} ${pkg}"
    else
        [[ "${states[${index}]}" == disabled ]] && enable="${enable} ${pkg}"
    fi
done
while IFS=$'\t' read -r name st _; do
    [[ -n "${name}" ]] || continue
    if grep -qxF "tweak:${name}" <<< "${picked}"; then
        [[ "${st}" == on ]] || tweak_on="${tweak_on} ${name}"
    else
        [[ "${st}" == off ]] || tweak_off="${tweak_off} ${name}"
    fi
done <<< "${tweaks}"
if [[ -z "${disable}${enable}${tweak_on}${tweak_off}" ]]; then
    log_info "Nothing to change."
    exit 0
fi

_box "$(
    for pkg in ${disable}; do printf 'disable   %s\n' "${pkg}"; done
    for pkg in ${enable}; do printf 'enable    %s\n' "${pkg}"; done
    for name in ${tweak_on}; do printf 'turn on   %s\n' "${name}"; done
    for name in ${tweak_off}; do printf 'turn off  %s\n' "${name}"; done
)"
confirm "Apply these changes on ${TV}?" || exit 0
for pkg in ${disable}; do
    tv shell pm disable-user --user 0 "${pkg}" | tr -d '\r' | sed "s/^/${pkg}: /"
done
for pkg in ${enable}; do
    tv shell pm enable "${pkg}" | tr -d '\r' | sed "s/^/${pkg}: /"
done
for name in ${tweak_on}; do ./tweak.sh "${name}" on; done
for name in ${tweak_off}; do ./tweak.sh "${name}" off; done
