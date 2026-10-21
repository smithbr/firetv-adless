#!/usr/bin/env bash
# Uninstall the launcher builds in packages-old-launchers.txt that are on
# the TV: old names, debug and devicetest builds. The release
# (la.bsmith.launcher) stays. Lists them and asks first; --yes skips the
# question, --list only lists them.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./adb.sh

HOF="io.github.toolicious.homeonfire"

mode=ask
case "${1:-}" in
    "") ;;
    --yes|-y) mode=yes ;;
    --list) mode=list ;;
    -h|--help)
        printf 'Usage: %s [--yes|--list]\n' "$(basename "$0")"
        exit 0
        ;;
    *) die "Unknown option: $1 (--yes or --list)" ;;
esac

connect
installed="$(tv shell pm list packages | tr -d '\r')"
found=()
while read -r pkg; do
    grep -qxF "package:${pkg}" <<< "${installed}" && found+=("${pkg}")
done < <(grep -v -E '^[[:space:]]*(#|$)' packages-old-launchers.txt)

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
