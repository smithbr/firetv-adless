#!/usr/bin/env bash
# The launcher's release signing key: creates it outside the repo unless it
# exists, and points Gradle at it in ~/.gradle/gradle.properties. Its
# certificate's SHA-256 is pinned in app/release-key.sha256, which the
# Release workflow checks the APK against. Keep one key for good: a release
# signed with a new key cannot update an install signed with the old one.
# So this never replaces the key on its own: with the keystore missing while
# a pin exists, or a keystore that does not match the pin, it stops. --rotate
# makes a new key when the keystore is missing, or pins the keystore there
# now; to replace a keystore that is there, move it away first. --github
# also stores the key in the repo's Actions secrets, which the Release
# workflow signs with.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./ui.sh

KEYSTORE="${HOME}/.android/firetv-adless-release.p12"
PROPS="${GRADLE_USER_HOME:-${HOME}/.gradle}/gradle.properties"
PIN="$(cd .. && pwd)/app/release-key.sha256"

# The keystore password Gradle reads, or empty. Every read of it goes
# through here.
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

# The pinned certificate SHA-256, or empty when there is no pin yet.
pinned_fingerprint() {
    [[ -f "${PIN}" ]] || return 0
    tr -d '[:space:]' < "${PIN}"
}

# The SHA-256 of the keystore's certificate, lowercase hex without colons.
# keytool reads the password from the environment, not its arguments.
key_fingerprint() {
    local listing fingerprint
    # With -v an error comes with a stack trace: keep keytool's message line.
    listing="$(LAUNCHER_KEYSTORE_PASSWORD="$(saved_password)" keytool -list -v \
        -keystore "${KEYSTORE}" -storetype PKCS12 -alias launcher \
        -storepass:env LAUNCHER_KEYSTORE_PASSWORD 2>&1)" \
        || die "keytool cannot read ${KEYSTORE}: $(printf '%s\n' "${listing}" | grep -m1 'keytool error' || printf '%s\n' "${listing}" | head -1)"
    fingerprint="$(printf '%s\n' "${listing}" | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' | tr -d ':[:space:]' | tr 'A-F' 'a-f')"
    [[ -n "${fingerprint}" ]] || die "keytool printed no SHA256 fingerprint for ${KEYSTORE}"
    printf '%s\n' "${fingerprint}"
}

# Writes the keystore's fingerprint to the pin file and says what else a
# new key needs.
pin_key() {
    local fingerprint="$1"
    printf '%s\n' "${fingerprint}" > "${PIN}"
    _check "Pinned ${fingerprint} in app/release-key.sha256"
    log_warn "Commit app/release-key.sha256. LAUNCHER_SIGNATURE in tools/adb.sh must change too: it is the hex of Java's Arrays.hashCode over the new certificate's bytes, as dumpsys package prints it."
}

create_key() {
    local rotate="$1" password pin fingerprint
    command -v keytool >/dev/null 2>&1 || die "keytool not found: tools/setup.sh --build installs Temurin"
    pin="$(pinned_fingerprint)"
    if [[ -f "${KEYSTORE}" ]]; then
        [[ -n "$(saved_password)" ]] || die "${KEYSTORE} exists but ${PROPS} has no launcher.keystorePassword; restore it from your backup"
        fingerprint="$(key_fingerprint)"
        if [[ "${fingerprint}" == "${pin}" ]]; then
            _check "Release key: ${KEYSTORE} matches app/release-key.sha256"
        elif [[ "${rotate}" == true ]]; then
            _check "Release key: ${KEYSTORE}"
            pin_key "${fingerprint}"
        elif [[ -z "${pin}" ]]; then
            die "No app/release-key.sha256 to check ${KEYSTORE} against; run this with --rotate to pin it"
        else
            die "${KEYSTORE} has certificate SHA-256 ${fingerprint}, not the pinned ${pin}. Restore the release keystore and its password from your backup; --rotate pins this one instead, and its releases cannot update installed launchers."
        fi
    elif [[ -n "${pin}" && "${rotate}" == false ]]; then
        die "${KEYSTORE} is missing. Restore it and the launcher.keystorePassword line of ${PROPS} from your backup: a new key cannot update installed launchers. --rotate makes a new one anyway."
    else
        password="$(openssl rand -base64 48 | tr -d '/+=\n')"
        # umask 077, so the keystore is never readable by others, even briefly.
        (
            umask 077
            mkdir -p "$(dirname "${KEYSTORE}")"
            export LAUNCHER_KEYSTORE_PASSWORD="${password}"
            spin "Creating the release key" \
                keytool -genkeypair -keystore "${KEYSTORE}" -storetype PKCS12 \
                -storepass:env LAUNCHER_KEYSTORE_PASSWORD -alias launcher \
                -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Launcher, O=firetv-adless"
        )
        set_prop keystorePassword "${password}"
        _check "Created ${KEYSTORE}"
        fingerprint="$(key_fingerprint)"
        pin_key "${fingerprint}"
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
    local github=false rotate=false arg
    for arg in "$@"; do
        case "${arg}" in
            --github) github=true ;;
            --rotate) rotate=true ;;
            *) die "Usage: tools/release-key.sh [--rotate] [--github]" ;;
        esac
    done
    _header "Release signing key"
    create_key "${rotate}"
    [[ "${github}" == false ]] || upload_key
    log_info "Back up ${KEYSTORE} and the launcher.keystorePassword line of ${PROPS} in your password manager: without them, no later release can update an installed one."
}

main "$@"
