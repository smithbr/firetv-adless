#!/usr/bin/env bash
# Build the launcher (release: the debug variant is twice as slow to draw on
# the TV) and install it. Pass --launch to open it too.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./lib.sh

APK="../app/build/outputs/apk/release/app-release.apk"

(cd .. && ./gradlew --console=plain :app:assembleRelease)
connect
tv install -r "${APK}" | tr -d '\r'
if [[ "${1:-}" == --launch ]]; then
    tv shell am start -n la.bsmith.launcher/.MainActivity >/dev/null
fi
