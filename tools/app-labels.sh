#!/usr/bin/env bash
# Print "package<tab>name" for each package given, with the name the TV
# shows. Fire OS has no command for an app's name, so this asks the launcher's
# AppLabelsReceiver first. For apps it does not name (the launcher missing or too
# old), it copies the manifest and resource table out of the APK (the TV
# has unzip) and reads the label with the SDK's aapt2, when there is one.
# The package stands in for a name neither can give. Expects the caller to
# have connected; takes no lock.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
# Which launcher build to ask; tests point it at the devicetest build.
# Read before adb.sh, which sets LAUNCHER_PKG to the release.
labels_from="${LAUNCHER_PKG:-}"
source ./adb.sh
LAUNCHER_PKG="${labels_from:-${LAUNCHER_PKG}}"

# "package<tab>name" lines from the launcher, or nothing. am prints the receiver's
# answer as: Broadcast completed: result=0, data="<lines>"
from_launcher() {
    local out
    out="$(tv shell am broadcast -n "${LAUNCHER_PKG}/la.bsmith.launcher.AppLabelsReceiver" 2>/dev/null | tr -d '\r' || true)"
    [[ "${out}" == *'data="'* ]] || return 0
    out="${out#*data=\"}"
    printf '%s\n' "${out%\"}"
}

# Newest aapt2 in the SDK (ANDROID_HOME, or local.properties' sdk.dir), else
# one on PATH.
find_aapt2() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" found
    [[ -n "${sdk}" ]] || sdk="$(sed -n 's/^sdk\.dir=//p' ../local.properties 2>/dev/null || true)"
    found="$(find "${sdk}/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -1 || true)"
    [[ -n "${found}" ]] || found="$(command -v aapt2 || true)"
    printf '%s' "${found}"
}

# Unzips each app's manifest and resource table into a folder per package
# on the TV, then copies them all over in one tar: three adb calls in all.
fetch() {
    local remote="/data/local/tmp/launcher-labels" script pkg
    script="rm -rf ${remote}; mkdir -p ${remote}"
    for pkg in "$@"; do
        # pm path's first line is the base APK, which holds the label.
        script+="; a=\$(pm path ${pkg} | head -1); unzip -q -o \"\${a#package:}\" AndroidManifest.xml resources.arsc -d ${remote}/${pkg} 2>/dev/null"
    done
    tv shell "${script}" >/dev/null || true
    # Compressed: resource tables run to tens of MB and shrink about 25 times.
    # A gzip pipe, not toybox's tar -z, which can end the stream early.
    tv exec-out "tar -c -C ${remote} . | gzip -1" | tar -xz -C "${tmp}" || true
    tv shell rm -rf "${remote}" >/dev/null || true
}

label() {
    local dir="${tmp}/$1"
    [[ -f "${dir}/AndroidManifest.xml" && -f "${dir}/resources.arsc" ]] || return 0
    (cd "${dir}" && zip -q -0 app.apk AndroidManifest.xml resources.arsc) || return 0
    "${aapt2}" dump badging "${dir}/app.apk" 2>/dev/null \
        | sed -n "s/^application-label:'\(.*\)'$/\1/p" | sed "s/\\\\'/'/g" | head -1
}

known="$(from_launcher)"
missing=()
for pkg in "$@"; do
    awk -F '\t' -v p="${pkg}" '$1 == p { found = 1 } END { exit !found }' <<< "${known}" || missing+=("${pkg}")
done
aapt2=""
(( ${#missing[@]} == 0 )) || aapt2="$(find_aapt2)"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/launcher-labels.XXXXXX")"
trap 'rm -rf "${tmp}"' EXIT
[[ -z "${aapt2}" ]] || fetch "${missing[@]}"
for pkg in "$@"; do
    name="$(awk -F '\t' -v p="${pkg}" '$1 == p { print $2; exit }' <<< "${known}")"
    [[ -n "${name}" || -z "${aapt2}" ]] || name="$(label "${pkg}" || true)"
    printf '%s\t%s\n' "${pkg}" "${name:-${pkg}}"
done
