#!/usr/bin/env bash
# Put Amazon's launcher back as Home (undoes make-home.sh). The custom
# launcher stays installed; uninstall.sh removes it.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./lib.sh

connect
for pkg in com.amazon.tv.launcher com.amazon.firehomestarter; do
    case "$(state "${pkg}")" in
        disabled) tv shell pm enable "${pkg}" | tr -d '\r' | sed "s/^/${pkg}: /" ;;
        enabled) printf '%s: already enabled\n' "${pkg}" ;;
        absent) printf '%s: not on this TV\n' "${pkg}" ;;
    esac
done
tv shell cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext | tr -d '\r' || true
tv shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
printf 'Home now resolves to: '
tv shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | sed -n 's/^ *packageName=//p' | head -1
