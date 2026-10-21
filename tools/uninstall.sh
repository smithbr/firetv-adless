#!/usr/bin/env bash
# Remove the custom launcher from the TV. Run restore-home.sh first if it is Home.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./adb.sh

connect
if [[ "$(state com.amazon.tv.launcher)" == disabled ]]; then
    die "Amazon's launcher is disabled; run ./restore-home.sh first so the TV keeps a Home app"
fi
tv uninstall la.bsmith.launcher | tr -d '\r'
