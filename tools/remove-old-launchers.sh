#!/usr/bin/env bash
# Uninstall the launcher builds in packages-old-launchers.txt that are on
# the TV: old names, debug and devicetest builds. The release
# (la.bsmith.launcher) stays. Lists them and asks first; --yes skips the
# question, --list only lists them, and --check prints nothing and exits 0
# when the TV has any (firetv install and clean use it).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./adb.sh

mode=ask
case "${1:-}" in
    "") ;;
    --yes|-y) mode=yes ;;
    --list) mode=list ;;
    --check) mode=check ;;
    -h|--help)
        printf 'Usage: %s [--yes|--list|--check]\n' "$(basename "$0")"
        exit 0
        ;;
    *) die "Unknown option: $1 (--yes, --list or --check)" ;;
esac

# --check runs right after a step that connected, so it skips the
# connecting line and only takes the lock.
if [[ "${mode}" == check ]]; then
    lock
    [[ "$(tv get-state 2>/dev/null)" == device ]] || exit 1
else
    connect
fi
installed="$(tv shell pm list packages | tr -d '\r')"
found=()
while read -r pkg; do
    grep -qxF "package:${pkg}" <<< "${installed}" && found+=("${pkg}")
done < <(grep -v -E '^[[:space:]]*(#|$)' packages-old-launchers.txt)

if [[ "${mode}" == check ]]; then
    (( ${#found[@]} > 0 ))
    exit
fi
if (( ${#found[@]} == 0 )); then
    _success "No old launcher builds on the TV."
    exit 0
fi

for pkg in "${found[@]}"; do
    ver="$(tv shell dumpsys package "${pkg}" | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1)"
    _item "${pkg} ${ver:+(${ver})}"
done
[[ "${mode}" != list ]] || exit 0
if [[ "${mode}" == ask ]]; then
    confirm "Uninstall the old launcher builds above from ${TV}? Their favorites and settings go too." || exit 0
fi

failed=0
for pkg in "${found[@]}"; do
    out="$(tv uninstall "${pkg}" 2>&1 | tr -d '\r' || true)"
    if [[ "${out}" == Success ]]; then
        _check "Uninstalled ${pkg}"
    else
        _cross "${pkg}: ${out}"
        failed=1
    fi
done

# Home on Fire's target is kept in its own settings, which adb cannot read.
if grep -qxF "package:${HOF}" <<< "${installed}"; then
    log_info "If Home now opens Amazon's home, Home on Fire was set to an old build: open Home on Fire on the TV, select the target app and pick Launcher."
fi
exit "${failed}"
