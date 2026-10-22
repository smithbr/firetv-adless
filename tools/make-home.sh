#!/usr/bin/env bash
# Make the custom launcher the Home app: disable Amazon's launcher and the
# home-starter that re-opens it on the Home button, then set the home
# activity. restore-home.sh undoes all of it. Says what it changes and asks
# first; --yes skips the question (install_launcher, whose user already
# agreed).
#   make-home.sh [--yes]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./ui.sh

# Options first: adb.sh asks for the TV, which --help does not need.
ask=true
case "${1:-}" in
    "") ;;
    --yes|-y) ask=false ;;
    -h|--help)
        printf 'Usage: %s [--yes]\n' "$(basename "$0")"
        exit 0
        ;;
    *) die "Unknown option: $1 (--yes)" ;;
esac

source ./adb.sh

LAUNCHER="${LAUNCHER:-la.bsmith.launcher/.MainActivity}"
AMAZON="com.amazon.tv.launcher com.amazon.firehomestarter"

connect
tv shell pm list packages | tr -d '\r' | grep -qx "package:${LAUNCHER%%/*}" \
    || die "${LAUNCHER%%/*} is not installed; run ./install.sh first"

printf 'This disables %s on the TV at %s and makes %s the Home app.\n' "${AMAZON}" "${TV}" "${LAUNCHER}"
printf 'Fire TV Settings is part of the stock launcher; the custom launcher opens the settings sections directly.\n'
[[ "${ask}" == false ]] || confirm "Continue?" || exit 1

# Packages this run disabled, re-enabled if a later one is refused so the TV
# is never left without a Home app.
disabled_now=""
for pkg in ${AMAZON}; do
    case "$(state "${pkg}")" in
        enabled)
            out="$(tv shell pm disable-user --user 0 "${pkg}" 2>&1 | tr -d '\r' || true)"
            if [[ "${out}" != *"new state: disabled"* ]]; then
                for done_pkg in ${disabled_now}; do
                    tv shell pm enable "${done_pkg}" | tr -d '\r' | sed "s/^/${done_pkg}: /"
                done
                [[ "${out}" == *"protected package"* ]] \
                    || die "Could not disable ${pkg}, so nothing was changed: ${out}"
                # Fire OS 8.1.6.9 and later protect Amazon's launcher.
                printf '\nThis Fire OS version protects Amazon'"'"'s launcher, so Home cannot be switched directly. Nothing was changed.\n'
                printf 'Home on Fire does it instead: it opens the launcher whenever Amazon'"'"'s home appears.\n'
                confirm "Set up Home on Fire now?" \
                    || die "Not set up. To do it later, run: $(pwd)/home-on-fire.sh"
                # Not exec: this script keeps the TV lock and releases it on exit.
                ./home-on-fire.sh
                exit 0
            fi
            disabled_now="${disabled_now} ${pkg}"
            printf '%s\n' "${out}" | sed "s/^/${pkg}: /"
            ;;
        disabled) printf '%s: already disabled\n' "${pkg}" ;;
        absent) printf '%s: not on this TV\n' "${pkg}" ;;
    esac
done
tv shell cmd package set-home-activity "${LAUNCHER}" | tr -d '\r' || true
tv shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
printf 'Home now resolves to: '
tv shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | sed -n 's/^ *packageName=//p' | head -1
