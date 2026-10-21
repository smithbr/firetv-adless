#!/usr/bin/env bash
# Re-enable every package in packages-disabled.txt (undoes the debloat).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./tv.sh

connect
while read -r pkg; do
    [[ -n "${pkg}" && "${pkg}" != \#* ]] || continue
    case "$(state "${pkg}")" in
        disabled) tv shell pm enable "${pkg}" | tr -d '\r' | sed "s/^/${pkg}: /" ;;
        enabled)  printf '%s: already enabled\n' "${pkg}" ;;
        absent)   printf '%s: not on this TV\n' "${pkg}" ;;
    esac
done < packages-disabled.txt
printf 'Done. Reboot the TV to be sure: adb -s %s reboot\n' "${TV}"
