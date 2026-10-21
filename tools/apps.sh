#!/usr/bin/env bash
# The TV's apps: list them, get one from Amazon's Appstore, or uninstall.
#   apps.sh list [--tsv]     installed apps; --tsv: "package<tab>name" by name
#   apps.sh add APP          APP: a name from appstore.txt, a package, an
#                            ASIN (B0...) or an Amazon link to the app
#   apps.sh remove [APP...]  APP: an installed app's name or package; with
#                            none, a checklist of the installed apps
# add opens the app's Appstore page on the TV and presses OK only when the
# button is a free Get or Download; a price, a subscription or anything
# else is left on the TV for you.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./adb.sh

STORE="com.amazon.venezia"
INSTALL_WAIT="${FIRETV_INSTALL_WAIT:-600}"
UI_XML="/data/local/tmp/firetv-ui.xml"
# The TV's third-party packages just before add presses OK.
BEFORE_LIST="/data/local/tmp/firetv-packages.txt"

usage() {
    show_help <<'HELP'
# firetv apps

## Usage

    firetv apps list             list the installed apps
    firetv apps add APP          get APP from the Appstore
    firetv apps remove [APP...]  uninstall apps: those named, or a checklist

`add` takes a name from `tools/appstore.txt`, a package
(`tv.pluto.android`), an ASIN (`B00KDSGIPK`) or the app's Amazon link,
amzn.to and a.co short links too. It presses OK on the TV only for a free
**Get** or **Download**; for a price or a subscription it leaves the app's
page open on the TV.

`remove` takes names or packages, or with none shows a checklist, and asks
before uninstalling.
HELP
}

# The TV's third-party packages with their installers, as pm list
# packages -3 -i prints them: "package:NAME  installer=INSTALLER".
read_installed() {
    spin_out "Reading the TV's apps" adb -s "${TV}" shell pm list packages -3 -i | tr -d '\r'
}

# "package<tab>name" for each app in $1 (read_installed's lines) with a
# launcher icon, TV or phone (Tubi has only the phone one), sorted by name.
# That leaves out Amazon's overlay packages, the launcher, Home on Fire and
# old launcher builds (firetv's Remove old launcher builds names those).
installed_tsv() {
    local installed launchable="" category pkg old
    local -a pkgs=()
    installed="$(sed -e 's/^package://' -e 's/ .*//' <<< "$1")"
    old="$(grep -v -E '^[[:space:]]*(#|$)' packages-old-launchers.txt)"
    for category in LEANBACK_LAUNCHER LAUNCHER; do
        launchable+="$(tv shell cmd package query-activities --brief \
            -a android.intent.action.MAIN -c "android.intent.category.${category}" \
            | tr -d '\r' | sed -n 's|^ *\([^ /]*\)/.*|\1|p')"$'\n'
    done
    while read -r pkg; do
        [[ -n "${pkg}" && "${pkg}" != "${LAUNCHER_PKG}" && "${pkg}" != "${HOF}" ]] || continue
        ! grep -qxF "${pkg}" <<< "${old}" || continue
        grep -qxF "${pkg}" <<< "${installed}" && pkgs+=("${pkg}")
    done <<< "$(sort -u <<< "${launchable}")"
    (( ${#pkgs[@]} > 0 )) || return 0
    spin_out "Reading app names" ./app-labels.sh "${pkgs[@]}" | sort -t $'\t' -k 2 -f
}

do_list() {
    local installed apps pkg name from
    connect
    installed="$(read_installed)"
    apps="$(installed_tsv "${installed}")"
    if [[ "${1:-}" == --tsv ]]; then
        [[ -z "${apps}" ]] || printf '%s\n' "${apps}"
        return 0
    fi
    [[ -n "${apps}" ]] || { log_info "No installed apps."; return 0; }
    while IFS=$'\t' read -r pkg name; do
        from="$(grep -F "package:${pkg} " <<< "${installed}" | sed -n 's/.*installer=//p' | head -1)"
        case "${from}" in
            "${STORE}") from="Appstore" ;;
            null|"") from="sideloaded" ;;
        esac
        printf '%-26s %-40s %s\n' "${name}" "${pkg}" "${from}"
    done <<< "${apps}"
}

# Lowercase letters and digits only, + spelled out, so "disney plus" and
# "disney" both find "Disney+".
squash() {
    tr '[:upper:]' '[:lower:]' <<< "$1" | sed 's/+/plus/g' | tr -cd '[:alnum:]'
}

# "package<tab>name" lines whose name or package matches $1: an exact name
# or package wins over a partial name.
match() {
    local query="$1" want pkg name exact="" partial=""
    want="$(squash "${query}")"
    [[ -n "${want}" ]] || return 0
    while IFS=$'\t' read -r pkg name; do
        [[ -n "${pkg}" ]] || continue
        if [[ "${pkg}" == "${query}" || "$(squash "${name}")" == "${want}" ]]; then
            exact+="${pkg}"$'\t'"${name}"$'\n'
        elif [[ "$(squash "${name}")" == *"${want}"* ]]; then
            partial+="${pkg}"$'\t'"${name}"$'\n'
        fi
    done
    printf '%s' "${exact:-${partial}}"
}

# One "package<tab>name" from several, asking which when there is a choice;
# nothing when the choice is cancelled.
pick_one() {
    local header="$1" lines="$2" pkg name picked
    local -a opts=()
    if [[ "$(wc -l <<< "${lines}")" -eq 1 ]]; then
        printf '%s\n' "${lines}"
        return 0
    fi
    [[ -t 0 ]] || die "$(printf '%s matches several apps; name one:\n%s' "${header}" "$(cut -f 2 <<< "${lines}" | sed 's/^/  /')")"
    while IFS=$'\t' read -r pkg name; do
        opts+=("$(printf '%-24s %s' "${name}" "${pkg}")"$'\t'"${pkg}")
    done <<< "${lines}"
    picked="$(choose_one "${header} matches several apps; which one?" "" "${opts[@]}")"
    [[ -n "${picked}" ]] || return 0
    awk -F '\t' -v p="${picked}" '$1 == p { print; exit }' <<< "${lines}"
}

# The ASIN in an Amazon link: after /dp/, /d/ (the mobile /gp/aw/d/),
# /product/ or asin=. Nothing when it has none.
asin_in() {
    sed -nE 's#.*(/dp/|/d/|/product/|[?&]asin=)([A-Z0-9]{10})([^A-Za-z0-9].*)?$#\2#p' <<< "$1" | head -1
}

# The ASIN in an Amazon link, following a short link (amzn.to, a.co) to
# the page it redirects to. Nothing when there is none.
link_asin() {
    local link="$1" asin
    asin="$(asin_in "${link}")"
    if [[ -z "${asin}" && "${link}" =~ ^(https?://)?(www\.)?(amzn\.to|a\.co|amzn\.com)/ ]]; then
        asin="$(curl -sIL --max-time 10 --max-redirs 5 "${link}" 2>/dev/null | tr -d '\r' \
            | sed -n 's/^[Ll]ocation: //p' \
            | while IFS= read -r location; do asin_in "${location}"; done | head -1 || true)"
    fi
    printf '%s' "${asin}"
}

# Prints the Appstore link for APP and, when known, its package:
# "uri<tab>package".
resolve() {
    local app="$1" asin="" found
    if [[ "${app}" =~ ^B[0-9A-Z]{9}$ ]]; then
        asin="${app}"
    elif [[ "${app}" == *[/:]* ]]; then
        asin="$(link_asin "${app}")"
        [[ -n "${asin}" ]] || die "No app ASIN in ${app}. Give the app's amazon.com page (its link has /dp/B0...), its ASIN or its package"
    fi
    if [[ -n "${asin}" ]]; then
        printf 'amzn://apps/android?asin=%s\t\n' "${asin}"
    elif [[ "${app}" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$ ]]; then
        printf 'amzn://apps/android?p=%s\t%s\n' "${app}" "${app}"
    else
        found="$(grep -v '^#' appstore.txt | awk -F '\t' '{ print $2 "\t" $1 }' | match "${app}")"
        [[ -n "${found}" ]] || die "${app} is not in tools/appstore.txt. Give its package, or its ASIN or amazon.com link (search amazon.com > Apps & Games; the ASIN is the B0... in the link)"
        # || exit: a die inside $(...) ends only the substitution.
        found="$(pick_one "${app}" "${found}")" || exit 1
        [[ -n "${found}" ]] || return 0
        printf 'amzn://apps/android?p=%s\t%s\n' "${found%%$'\t'*}" "${found%%$'\t'*}"
    fi
}

# The buy button in uiautomator's nodes, one per line, as "label<tab>
# focused": its own text or else the first text inside it ("Get",
# "Download", "Open app", a price, "Subscribe & download app", "Not
# available on this device"...), and yes when it or a view inside it has
# focus. Inside means deeper in the tree, counted from each line's opening
# tag, a self-closing "/>" and its "</node>"s.
buy_button() {
    awk '
        {
            tag = $0
            sub(/>.*/, ">", tag)
            start = depth
            depth += 1 - (tag ~ /\/>$/) - gsub(/<\/node>/, "&")
        }
        inside && start <= top { exit }
        !inside && /id\/buy_button"/ { inside = 1; top = start }
        inside {
            if (label == "" && match($0, / text="[^"]+"/)) label = substr($0, RSTART + 7, RLENGTH - 8)
            if ($0 ~ / focused="true"/) focused = "yes"
        }
        END { if (label != "") printf "%s\t%s\n", label, (focused == "yes" ? "yes" : "no") }
    '
}

# The open Appstore page as "title<tab>button label<tab>focused" (see
# buy_button). Empty until the page has loaded.
store_page() {
    local nodes title button
    nodes="$(tv shell "uiautomator dump ${UI_XML} >/dev/null 2>&1 && cat ${UI_XML}; rm -f ${UI_XML}" \
        | tr -d '\r' | sed 's/<node /\n<node /g')"
    title="$(grep 'id/asin_title_text"' <<< "${nodes}" | sed -n 's/.* text="\([^"]*\)".*/\1/p' | head -1)"
    button="$(buy_button <<< "${nodes}")"
    [[ -n "${title}" && -n "${button}" ]] || return 0
    printf '%s\t%s\t%s\n' "$(unescape "${title}")" "$(unescape "${button%%$'\t'*}")" "${button#*$'\t'}"
}

unescape() {
    sed -e 's/&#10;/ /g' -e 's/&quot;/"/g' -e "s/&#39;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' \
        -e 's/&amp;/\&/g' -e 's/  */ /g' -e 's/ $//' <<< "$1"
}

# Waits up to 20 s for the app's page to load; prints store_page's line.
wait_for_page() {
    local i page=""
    for (( i = 0; i < 10; i++ )); do
        sleep 2
        if tv shell dumpsys window | tr -d '\r' | grep 'mCurrentFocus' | grep -q "${STORE}/.*AppDetailsActivity"; then
            page="$(store_page)"
            [[ -z "${page}" ]] || break
        fi
    done
    printf '%s' "${page}"
}

do_add() {
    local app="$1" target uri pkg page title label focused added="" name check
    target="$(resolve "${app}")" || exit 1
    [[ -n "${target}" ]] || return 0
    uri="${target%%$'\t'*}"
    pkg="${target#*$'\t'}"
    connect
    if [[ -n "${pkg}" ]]; then
        case "$(state "${pkg}")" in
            enabled)
                _success "$(./app-labels.sh "${pkg}" | cut -f 2) (${pkg}) is already installed."
                return 0
                ;;
            disabled)
                tv shell pm enable "${pkg}" | tr -d '\r' | sed "s/^/${pkg}: /"
                _success "$(./app-labels.sh "${pkg}" | cut -f 2) (${pkg}) was installed but disabled; it is enabled now."
                return 0
                ;;
        esac
    fi
    tv shell input keyevent KEYCODE_WAKEUP
    # Home first, so an Appstore page already open (another app's) is gone
    # and never the one read and pressed.
    tv shell input keyevent KEYCODE_HOME
    sleep 2
    tv shell am start -a android.intent.action.VIEW -d "'${uri}'" >/dev/null 2>&1
    page="$(spin_out "Opening the Appstore page on ${TV}" wait_for_page)"
    [[ -n "${page}" ]] || die "The Appstore on the TV has no page for ${app} (or it did not load)"
    IFS=$'\t' read -r title label focused <<< "${page}"
    case "${label}" in
        Get|Download) ;;
        Open*) _success "${title} is already installed."; return 0 ;;
        Update*) log_info "${title} is installed; its Appstore page on the TV offers an update."; return 0 ;;
        *) die "The Appstore shows \"${label}\" for ${title}. firetv presses only a free Get or Download; the page is open on the TV if you want it" ;;
    esac
    [[ "${focused}" == yes ]] || die "The ${label} button for ${title} is not focused on the TV; press it there"

    # Waits for the package when it is known, else for any package that was
    # not there before OK, so an app removed meanwhile changes nothing.
    # Polls on the TV, so the spinner is one adb call.
    if [[ -n "${pkg}" ]]; then
        check="pm list packages -3 | grep -qxF 'package:${pkg}'"
    else
        tv shell "pm list packages -3 > ${BEFORE_LIST}"
        check="pm list packages -3 | grep -vqxF -f ${BEFORE_LIST}"
    fi
    tv shell input keyevent KEYCODE_DPAD_CENTER
    spin "Installing ${title} (up to ${INSTALL_WAIT}s)" adb -s "${TV}" shell \
        "i=0; until ${check} || [ \$i -ge $(( INSTALL_WAIT / 2 )) ]; do sleep 2; i=\$((i+1)); done" \
        < /dev/null
    if [[ -z "${pkg}" ]]; then
        added="$(tv shell "pm list packages -3 | grep -vxF -f ${BEFORE_LIST}; rm -f ${BEFORE_LIST}" | tr -d '\r' | sed 's/^package://')"
    elif [[ "$(state "${pkg}")" != absent ]]; then
        added="${pkg}"
    fi
    [[ -n "${added}" ]] || die "${title} is not installed after ${INSTALL_WAIT}s; it may still be downloading on the TV (firetv apps list shows what is installed)"
    while read -r pkg; do
        name="$(./app-labels.sh "${pkg}" | cut -f 2)"
        _success "Installed ${name} (${pkg})."
    done <<< "${added}"
}

# Uninstalls the apps named, or with none, the ones ticked on a checklist.
do_remove() {
    local installed apps app found pkg name picked="" out
    local -a opts=()
    connect
    installed="$(read_installed)"
    apps="$(installed_tsv "${installed}")"
    if (( $# == 0 )); then
        [[ -n "${apps}" ]] || { log_info "No installed apps to remove."; return 0; }
        [[ -t 0 ]] || die "Name the apps to remove: firetv apps remove APP..."
        while IFS=$'\t' read -r pkg name; do
            opts+=("$(printf '%-24s %s' "${name}" "${pkg}")"$'\t'"${pkg}")
        done <<< "${apps}"
        picked="$(choose_many "Ticked apps get uninstalled (the launcher and Home on Fire: use Reset)" "${opts[@]}")" || return 0
        [[ -n "${picked}" ]] || { log_info "Nothing to remove."; return 0; }
        # The ticked packages' "package<tab>name" lines.
        picked="$(awk -F '\t' 'NR == FNR { want[$0]; next } $1 in want' <(printf '%s\n' "${picked}") - <<< "${apps}")"
    fi
    for app in "$@"; do
        if [[ "${app}" == "${LAUNCHER_PKG}" || "${app}" == "${HOF}" ]]; then
            die "${app}: remove the launcher and Home on Fire with Reset in firetv"
        fi
        found="$(match "${app}" <<< "${apps}")"
        [[ -n "${found}" ]] || die "No installed app matches ${app} (firetv apps list shows them)"
        # || exit: a die inside $(...) ends only the substitution.
        found="$(pick_one "${app}" "${found}")" || exit 1
        [[ -n "${found}" ]] || return 0
        picked+="${found}"$'\n'
    done
    picked="$(sort -u <<< "${picked}" | sed '/^$/d')"
    _box "$(cut -f 2 <<< "${picked}")"
    confirm "Uninstall these from ${TV}? Their data goes too." || return 0
    while IFS=$'\t' read -r pkg name; do
        out="$(tv uninstall "${pkg}" 2>&1 | tr -d '\r' || true)"
        if [[ "${out}" == Success ]]; then
            _success "Uninstalled ${name}."
        else
            log_warn "${name}: ${out}"
        fi
    done <<< "${picked}"
}

case "${1:-}" in
    list)
        [[ "${2:-}" != -h && "${2:-}" != --help ]] || { usage; exit 0; }
        shift
        do_list "$@"
        ;;
    add)
        shift
        # The words joined, so YouTube TV needs no quotes.
        [[ $# -gt 0 && -n "$*" ]] || die "Usage: firetv apps add APP (see firetv apps --help)"
        do_add "$*"
        ;;
    remove)
        shift
        do_remove "$@"
        ;;
    -h|--help|help|"") usage ;;
    *) die "Unknown apps command: $1 (see firetv apps --help)" ;;
esac
