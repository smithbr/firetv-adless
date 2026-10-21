#!/usr/bin/env bash
# Build the launcher (release: the debug variant is twice as slow to draw on
# the TV) and install it. Pass --launch to open it too.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./tv.sh

APK="../app/build/outputs/apk/release/app-release.apk"

spin "Building the launcher" sh -c 'cd .. && exec ./gradlew --quiet --console=plain :app:assembleRelease'
connect
spin "Installing the launcher on ${TV}" adb -s "${TV}" install -r "${APK}"
if [[ "${1:-}" == --launch ]]; then
    tv shell am start -n la.bsmith.launcher/.MainActivity >/dev/null
    _success "Installed the launcher and opened it on the TV."
else
    _success "Installed the launcher on the TV."
fi
