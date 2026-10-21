#!/usr/bin/env bash
# Make the custom launcher Home on Fire OS 8.1.6.9 and later, where Amazon's
# launcher cannot be disabled: install Home on Fire (GPL-3.0,
# https://github.com/toolicious/home-on-fire), switch on its accessibility
# service and open it. Its target app can only be picked on the TV.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./lib.sh

LAUNCHER="${LAUNCHER:-la.bsmith.launcher/.MainActivity}"
HOF="io.github.toolicious.homeonfire"
SERVICE="${HOF}/.HijackService"
# Pinned: a new release means a new version and checksum here.
HOF_VERSION="v1.5.2"
HOF_SHA256="87403c961264cc2e36e53cf571777ec20705eb87f46794cb0ac667135a98d9fe"
HOF_URL="https://github.com/toolicious/home-on-fire/releases/download/${HOF_VERSION}/home-on-fire.apk"

connect
tv shell pm list packages | tr -d '\r' | grep -qx "package:${LAUNCHER%%/*}" \
    || die "${LAUNCHER%%/*} is not installed; run ./install.sh first"

if [[ "$(state "${HOF}")" == absent ]]; then
    tmp="$(mktemp -d)"
    trap 'rm -rf "${tmp}"; unlock' EXIT
    printf 'Downloading Home on Fire %s\n' "${HOF_VERSION}"
    curl -fsSL -o "${tmp}/home-on-fire.apk" "${HOF_URL}" || die "Could not download ${HOF_URL}"
    [[ "$(shasum -a 256 "${tmp}/home-on-fire.apk" | cut -d' ' -f1)" == "${HOF_SHA256}" ]] \
        || die "Home on Fire download does not match its pinned checksum; not installing it"
    tv install -r "${tmp}/home-on-fire.apk" | tr -d '\r'
else
    printf 'Home on Fire: already installed\n'
fi

# Lets its in-app toggle switch the service on; Fire OS's own Accessibility
# page is unreliable.
tv shell pm grant "${HOF}" android.permission.WRITE_SECURE_SETTINGS

# Switch the service on here too, keeping any other accessibility services.
services="$(tv shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ ":${services}:" != *":${SERVICE}:"* ]]; then
    if [[ -z "${services}" || "${services}" == null ]]; then services="${SERVICE}"; else services="${services}:${SERVICE}"; fi
    tv shell settings put secure enabled_accessibility_services "${services}"
    tv shell settings put secure accessibility_enabled 1
fi
printf 'Home on Fire service: on\n'

tv shell am start -n "${HOF}/.MainActivity" >/dev/null 2>&1
cat <<EOF

Home on Fire is open on the TV. Finish there with the remote:
  1. Select "Choose target app..." and pick Launcher.
  2. Press Home. The launcher opens after about 2.5 s, with Amazon's home showing meanwhile.
Long-press and double-press Home open Amazon's home; turn each off in Home on Fire's settings to have them open the launcher too.
EOF
