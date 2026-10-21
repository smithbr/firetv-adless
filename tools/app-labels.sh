#!/usr/bin/env bash
# Print "package<tab>name" for each package given, with the name the TV
# shows. Fire OS has no command for an app's name, so this asks the launcher's
# AppLabelsReceiver first. For apps it does not name (the launcher missing or too
# old), it copies the manifest and resource table out of the APK (the TV
# has unzip) and reads the label with the SDK's aapt2, when there is one.
# A name read from an APK is cached by package and versionCode, since it
# changes only when the app updates. The package stands in for a name none
# of these give. Expects the caller to have connected; takes no lock.
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

# "package<tab>name<tab>versionCode" lines, one per name read from an APK.
CACHE="${XDG_CACHE_HOME:-${HOME}/.cache}/firetv/labels.tsv"

# "package<tab>versionCode" for each installed package, or nothing on a
# Fire OS whose pm cannot show versions (before Android 9).
versions() {
    tv shell pm list packages --show-versioncode 2>/dev/null | tr -d '\r' \
        | awk '$1 ~ /^package:/ && $2 ~ /^versionCode:[0-9]+$/ { print substr($1, 9) "\t" substr($2, 13) }' || true
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
# The launcher's names are current; the cache stands in only for the rest.
vers=""
if (( ${#missing[@]} > 0 )); then
    vers="$(versions)"
    cached="$(cat "${CACHE}" 2>/dev/null || true)"
    left=()
    for pkg in "${missing[@]}"; do
        ver="$(awk -F '\t' -v p="${pkg}" '$1 == p { print $2; exit }' <<< "${vers}")"
        name=""
        [[ -z "${ver}" ]] || name="$(awk -F '\t' -v p="${pkg}" -v v="${ver}" '$1 == p && $3 == v { print $2; exit }' <<< "${cached}")"
        if [[ -n "${name}" ]]; then
            known+=$'\n'"${pkg}"$'\t'"${name}"
        else
            left+=("${pkg}")
        fi
    done
    missing=("${left[@]+"${left[@]}"}")
fi
aapt2=""
(( ${#missing[@]} == 0 )) || aapt2="$(find_aapt2)"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/launcher-labels.XXXXXX")"
trap 'rm -rf "${tmp}"' EXIT
[[ -z "${aapt2}" ]] || fetch "${missing[@]}"
fresh=""
for pkg in "$@"; do
    name="$(awk -F '\t' -v p="${pkg}" '$1 == p { print $2; exit }' <<< "${known}")"
    if [[ -z "${name}" && -n "${aapt2}" ]]; then
        name="$(label "${pkg}" || true)"
        ver="$(awk -F '\t' -v p="${pkg}" '$1 == p { print $2; exit }' <<< "${vers}")"
        [[ -z "${name}" || -z "${ver}" ]] || fresh+="${pkg}"$'\t'"${name}"$'\t'"${ver}"$'\n'
    fi
    printf '%s\t%s\n' "${pkg}" "${name:-${pkg}}"
done
# New names replace each package's old line; written whole, then moved
# into place, so a reader never sees half a file.
if [[ -n "${fresh}" ]]; then
    mkdir -p "$(dirname "${CACHE}")"
    { awk -F '\t' 'NR == FNR { drop[$1]; next } !($1 in drop)' <(printf '%s' "${fresh}") "${CACHE}" 2>/dev/null || true
      printf '%s' "${fresh}"; } > "${tmp}/labels.tsv"
    mv "${tmp}/labels.tsv" "${CACHE}"
fi
