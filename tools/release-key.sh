#!/usr/bin/env bash
# The launcher's release signing key: creates it outside the repo unless it
# exists, and points Gradle at it in ~/.gradle/gradle.properties. --github
# also stores it in the repo's Actions secrets, which the Release workflow
# signs with. Keep one key for good: a release signed with a new key cannot
# update an install signed with the old one.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./ui.sh

KEYSTORE="${HOME}/.android/firetv-adless-release.p12"
PROPS="${GRADLE_USER_HOME:-${HOME}/.gradle}/gradle.properties"

# The keystore password Gradle reads, or empty.
saved_password() {
    sed -n 's/^launcher\.keystorePassword=//p' "${PROPS}" 2>/dev/null | tail -1
}

# Sets launcher.KEY=VALUE in gradle.properties, keeping its other lines.
set_prop() {
    local key="$1" value="$2" tmp
    mkdir -p "$(dirname "${PROPS}")"
    tmp="$(mktemp "${TMPDIR:-/tmp}/gradle.properties.XXXXXX")"
    { grep -v "^launcher\.${key}=" "${PROPS}" 2>/dev/null || true; printf 'launcher.%s=%s\n' "${key}" "${value}"; } > "${tmp}"
    chmod 600 "${tmp}"
    mv "${tmp}" "${PROPS}"
}

create_key() {
    local password
    if [[ -f "${KEYSTORE}" ]]; then
        [[ -n "$(saved_password)" ]] || die "${KEYSTORE} exists but ${PROPS} has no launcher.keystorePassword; restore it from your backup"
        _check "Release key: ${KEYSTORE}"
    else
        command -v keytool >/dev/null 2>&1 || die "keytool not found: tools/setup.sh --build installs Temurin"
        password="$(openssl rand -base64 48 | tr -d '/+=\n')"
        mkdir -p "$(dirname "${KEYSTORE}")"
        # keytool reads the password from the environment, not its arguments.
        (
            export LAUNCHER_KEYSTORE_PASSWORD="${password}"
            spin "Creating the release key" \
                keytool -genkeypair -keystore "${KEYSTORE}" -storetype PKCS12 \
                -storepass:env LAUNCHER_KEYSTORE_PASSWORD -alias launcher \
                -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Launcher, O=firetv-adless"
        )
        chmod 600 "${KEYSTORE}"
        set_prop keystorePassword "${password}"
        _check "Created ${KEYSTORE}"
    fi
    set_prop keystore "${KEYSTORE}"
    _check "${PROPS} points Gradle at it"
}

# Stores the keystore and its password as the repo's Actions secrets. Both go
# through stdin, never the command line.
upload_key() {
    command -v gh >/dev/null 2>&1 || die "gh not found: brew install gh"
    confirm "Store the release key in ${REPO}'s Actions secrets?" || return 0
    base64 < "${KEYSTORE}" | tr -d '\n' | gh secret set LAUNCHER_KEYSTORE_BASE64 --repo "${REPO}"
    _check "Stored LAUNCHER_KEYSTORE_BASE64"
    saved_password | tr -d '\n' | gh secret set LAUNCHER_KEYSTORE_PASSWORD --repo "${REPO}"
    _check "Stored LAUNCHER_KEYSTORE_PASSWORD"
}

main() {
    local github=false
    case "${1:-}" in
        --github) github=true ;;
        "") ;;
        *) die "Usage: tools/release-key.sh [--github]" ;;
    esac
    _header "Release signing key"
    create_key
    [[ "${github}" == false ]] || upload_key
    log_info "Back up ${KEYSTORE} and the launcher.keystorePassword line of ${PROPS} in your password manager: without them, no later release can update an installed one."
}

main "$@"
